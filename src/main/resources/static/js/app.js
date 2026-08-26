(function () {
  "use strict";

  const app = document.querySelector("#app");
  const userSelect = document.querySelector("#demo-user");
  const toastRegion = document.querySelector("#toast-region");
  const modalRoot = document.querySelector("#modal-root");
  const flights = window.UPLY_FLIGHTS || [];
  const previewMode = new URLSearchParams(window.location.search).get("preview") === "1";

  const campaignDescriptions = {
    JEJU: "푸른 제주로 떠나는 여행, 국내선 얼리버드 특가",
    TOKYO: "도쿄 도심과 근교를 가볍게 즐기는 일본 노선 특가",
    OSAKA: "미식과 쇼핑을 한 번에, 오사카 얼리버드 특가",
    BANGKOK: "따뜻한 휴양과 도심 여행을 위한 방콕 특가",
    PARIS: "유럽의 낭만을 만나는 파리 장거리 얼리버드",
    NEWYORK: "뉴욕으로 향하는 미주 노선 특별 프로모션",
  };

  const previewCampaigns = [
    {
      campaignId: 1,
      name: "제주 얼리버드 특가",
      openAt: "2026-08-21T01:00:00.000Z",
      expireAt: "2026-08-31T14:59:59.000Z",
      status: "OPEN",
    },
    {
      campaignId: 2,
      name: "방콕 늦여름 특가",
      openAt: "2026-08-25T01:00:00.000Z",
      expireAt: "2026-09-05T14:59:59.000Z",
      status: "SCHEDULED",
    },
    {
      campaignId: 3,
      name: "뉴욕 노선 특가",
      openAt: "2026-08-01T01:00:00.000Z",
      expireAt: "2026-08-20T14:59:59.000Z",
      status: "CLOSED",
    },
  ];

  const previewCampaignDetails = {
    1: {
      ...previewCampaigns[0],
      stocks: [
        { routeId: "JEJU", fareClass: "ECONOMY", totalStock: 8000, remainingStock: 1548 },
        { routeId: "JEJU", fareClass: "BUSINESS", totalStock: 2000, remainingStock: 312 },
      ],
    },
    2: {
      ...previewCampaigns[1],
      stocks: [
        { routeId: "BANGKOK", fareClass: "ECONOMY", totalStock: 1000, remainingStock: 1000 },
        { routeId: "BANGKOK", fareClass: "BUSINESS", totalStock: 200, remainingStock: 200 },
      ],
    },
    3: {
      ...previewCampaigns[2],
      stocks: [
        { routeId: "NEWYORK", fareClass: "ECONOMY", totalStock: 500, remainingStock: 0 },
        { routeId: "NEWYORK", fareClass: "BUSINESS", totalStock: 100, remainingStock: 0 },
      ],
    },
  };

  const state = {
    userId: Number(localStorage.getItem("uply.userId") || 10001),
    campaigns: null,
    campaign: null,
    selectedStock: null,
    issueResult: null,
    search: {
      origin: "GMP",
      destination: "JEJU",
      date: "2026-09-05",
      fareClass: "ECONOMY",
    },
    selectedFlight: null,
    selectedCouponId: null,
    currentCoupon: null,
    execution: null,
    verificationRun: null,
    pollTimer: null,
    stockStream: null,
    batchPollTimer: null,
    monitorTimer: null,
    monitorStream: null,
    monitorStockTimer: null,
    idempotencyKeys: Object.create(null),
    renderToken: 0,
  };

  userSelect.value = String(state.userId);

  class ApiError extends Error {
    constructor(status, errorCode, message, body) {
      super(message || "요청 처리 중 오류가 발생했습니다.");
      this.name = "ApiError";
      this.status = status;
      this.errorCode = errorCode || `HTTP_${status}`;
      this.body = body;
    }
  }

  async function request(path, options = {}) {
    const headers = { Accept: "application/json", ...(options.headers || {}) };
    if (options.body && !headers["Content-Type"]) headers["Content-Type"] = "application/json";

    let response;
    try {
      response = await fetch(path, { ...options, headers });
    } catch (error) {
      throw new ApiError(0, "NETWORK_ERROR", "서버에 연결할 수 없습니다.", error);
    }

    const contentType = response.headers.get("content-type") || "";
    const body = contentType.includes("application/json")
      ? await response.json().catch(() => ({}))
      : await response.text().catch(() => "");

    if (!response.ok) {
      throw new ApiError(
        response.status,
        body?.errorCode,
        body?.message || body?.error || `요청이 실패했습니다. (${response.status})`,
        body,
      );
    }
    return body;
  }

  function uuid() {
    if (window.crypto?.randomUUID) return window.crypto.randomUUID();
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (char) => {
      const random = (Math.random() * 16) | 0;
      const value = char === "x" ? random : (random & 0x3) | 0x8;
      return value.toString(16);
    });
  }

  function idempotencyKeyFor(action) {
    if (!state.idempotencyKeys[action]) state.idempotencyKeys[action] = uuid();
    return state.idempotencyKeys[action];
  }

  function finishIdempotentAction(action) {
    delete state.idempotencyKeys[action];
  }

  function releaseIdempotencyKeyAfterFailure(action, error) {
    const resultMayBeUnknown =
      !(error instanceof ApiError) ||
      error.status === 0 ||
      error.status >= 500 ||
      error.errorCode === "IDEMPOTENCY_REQUEST_IN_PROGRESS" ||
      error.errorCode === "SAVE_RESULT_UNKNOWN" ||
      error.errorCode === "KAFKA_PUBLISH_UNKNOWN";
    if (!resultMayBeUnknown) finishIdempotentAction(action);
  }

  function escapeHtml(value) {
    return String(value ?? "")
      .replaceAll("&", "&amp;")
      .replaceAll("<", "&lt;")
      .replaceAll(">", "&gt;")
      .replaceAll('"', "&quot;")
      .replaceAll("'", "&#039;");
  }

  function formatDate(value, withTime = true) {
    if (!value) return "-";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return escapeHtml(value);
    return new Intl.DateTimeFormat("ko-KR", {
      timeZone: "Asia/Seoul",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      ...(withTime ? { hour: "2-digit", minute: "2-digit", hour12: false } : {}),
    }).format(date);
  }

  function currency(value) {
    return `${new Intl.NumberFormat("ko-KR").format(Number(value || 0))}원`;
  }

  function campaignStatusLabel(status) {
    return { OPEN: "진행 중", SCHEDULED: "오픈 예정", CLOSED: "종료" }[status] || status;
  }

  function couponStatusLabel(status) {
    return {
      ISSUED: "사용 가능",
      USED: "사용 완료",
      CANCELLED: "취소됨",
      EXPIRED: "만료됨",
    }[status] || status;
  }

  function batchStatusLabel(status) {
    return {
      STARTING: "시작 중",
      STARTED: "진행 중",
      COMPLETED: "완료",
      FAILED: "실패",
      STOPPED: "중단",
      ABANDONED: "종료",
    }[status] || status || "-";
  }

  function ruleStatusLabel(status) {
    return {
      CHECKED: "검사 완료",
      NOT_APPLICABLE: "검사 대상 아님",
      SKIPPED: "검사 보류",
      FAILED: "위반 발견",
    }[status] || status || "-";
  }

  function verdictLabel(verdict) {
    return {
      PASSED: "정상",
      FAILED: "위반 발견",
      MISMATCH: "재고 불일치",
      INVALID: "판정 불가",
      INCOMPLETE: "검사 미완료",
      BASELINE: "기준 측정",
    }[verdict] || verdict || "-";
  }

  function routeLabel(routeId) {
    const flight = flights.find((item) => item.destination === routeId);
    return flight ? `${flight.originName} → ${flight.destinationName}` : routeId;
  }

  function syncSearchRoute(routeId, fareClass) {
    const matchingFlight = flights.find((flight) => flight.destination === routeId);
    if (matchingFlight) state.search.origin = matchingFlight.origin;
    state.search.destination = routeId;
    state.search.fareClass = fareClass;
  }

  function fareLabel(fareClass) {
    return fareClass === "BUSINESS" ? "비즈니스" : "이코노미";
  }

  function describeCampaign(campaign) {
    const knownRoute = Object.keys(campaignDescriptions).find((route) =>
      String(campaign.name || "").toUpperCase().includes(route),
    );
    if (knownRoute) return campaignDescriptions[knownRoute];
    return "노선별 한정 수량으로 제공되는 U-Ply 얼리버드 쿠폰 이벤트";
  }

  function setRoute(route) {
    const next = `#/${route.replace(/^\//, "")}`;
    if (window.location.hash === next) renderRoute();
    else window.location.hash = next;
  }

  function currentRoute() {
    return window.location.hash.replace(/^#\/?/, "") || "home";
  }

  function clearPoller() {
    if (state.pollTimer) window.clearInterval(state.pollTimer);
    state.pollTimer = null;
    if (state.stockStream) state.stockStream.close();
    state.stockStream = null;
    if (state.batchPollTimer) window.clearInterval(state.batchPollTimer);
    state.batchPollTimer = null;
    if (state.monitorTimer) window.clearInterval(state.monitorTimer);
    state.monitorTimer = null;
    if (state.monitorStockTimer) window.clearInterval(state.monitorStockTimer);
    state.monitorStockTimer = null;
    if (state.monitorStream) state.monitorStream.close();
    state.monitorStream = null;
  }

  function setActiveNav(route) {
    document.querySelectorAll("[data-route]").forEach((element) => element.classList.remove("active"));
    const navRoute = route.startsWith("campaign") ? "campaigns" : route.startsWith("booking") ? "bookings" : route.startsWith("coupon") ? "coupons" : route.startsWith("admin") ? "admin" : "home";
    document.querySelectorAll(`[data-route="${navRoute}"]`).forEach((element) => element.classList.add("active"));
  }

  function showLoading(message = "데이터를 불러오고 있습니다.") {
    app.innerHTML = `<div class="page-loading" role="status"><span class="spinner"></span><p>${escapeHtml(message)}</p></div>`;
  }

  function toast(title, message, type = "success") {
    const node = document.createElement("div");
    node.className = `toast ${type}`;
    node.innerHTML = `<div><strong>${escapeHtml(title)}</strong><span>${escapeHtml(message)}</span></div>`;
    toastRegion.append(node);
    window.setTimeout(() => node.remove(), 4200);
  }

  function errorMessage(error) {
    if (error instanceof ApiError) {
      const friendly = {
        NETWORK_ERROR: "서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.",
        USER_NOT_FOUND: "선택한 회원 정보를 찾을 수 없습니다.",
        CAMPAIGN_NOT_FOUND: "선택한 특가 이벤트를 찾을 수 없습니다.",
        CAMPAIGN_NOT_OPEN: "아직 쿠폰 발급이 시작되지 않았습니다.",
        CAMPAIGN_EXPIRED: "종료된 특가 이벤트입니다.",
        CAMPAIGN_NOT_CACHED: "특가 정보를 준비하고 있습니다. 잠시 후 다시 시도해 주세요.",
        OUT_OF_STOCK: "준비된 쿠폰이 모두 소진되었습니다.",
        ALREADY_ISSUED: "이미 이 이벤트의 쿠폰을 발급받았습니다.",
        COUPON_NOT_FOUND: "쿠폰 정보를 찾을 수 없습니다.",
        COUPON_NOT_READY: "쿠폰 정보를 저장하고 있습니다. 잠시 후 다시 확인해 주세요.",
        INVALID_STATE_TRANSITION: "현재 상태에서는 요청한 작업을 진행할 수 없습니다.",
        IDEMPOTENCY_KEY_REUSED: "이전 요청 정보를 확인할 수 없습니다. 다시 시도해 주세요.",
        IDEMPOTENCY_REQUEST_IN_PROGRESS: "같은 요청을 처리하고 있습니다. 잠시만 기다려 주세요.",
        CONNECTION_UNAVAILABLE: "요청이 많아 처리가 지연되고 있습니다. 잠시 후 다시 시도해 주세요.",
        SAVE_RESULT_UNKNOWN: "처리 결과를 확인하고 있습니다. 잠시 후 다시 확인해 주세요.",
      }[error.errorCode];
      return friendly || error.message || "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
    }
    return error?.message || "알 수 없는 오류가 발생했습니다.";
  }

  function errorView(title, error, retryRoute = currentRoute()) {
    return `
      <section class="page-shell narrow">
        <div class="error-state">
          <div class="error-icon">!</div>
          <h2>${escapeHtml(title)}</h2>
          <p>${escapeHtml(errorMessage(error))}</p>
          <button class="primary-button" type="button" data-route="${escapeHtml(retryRoute)}">다시 시도</button>
        </div>
      </section>`;
  }

  function openConfirm({ title, message, confirmText = "확인", danger = false }) {
    return new Promise((resolve) => {
      modalRoot.innerHTML = `
        <div class="modal-backdrop" role="presentation">
          <section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
            <h2 id="modal-title">${escapeHtml(title)}</h2>
            <p>${escapeHtml(message)}</p>
            <div class="button-row end">
              <button class="ghost-button" type="button" data-modal="cancel">돌아가기</button>
              <button class="${danger ? "danger-button" : "primary-button"}" type="button" data-modal="confirm">${escapeHtml(confirmText)}</button>
            </div>
          </section>
        </div>`;

      const finish = (result) => {
        modalRoot.innerHTML = "";
        resolve(result);
      };
      modalRoot.querySelector('[data-modal="cancel"]').addEventListener("click", () => finish(false));
      modalRoot.querySelector('[data-modal="confirm"]').addEventListener("click", () => finish(true));
      modalRoot.querySelector(".modal-backdrop").addEventListener("click", (event) => {
        if (event.target.classList.contains("modal-backdrop")) finish(false);
      });
    });
  }

  async function getCampaigns(force = false) {
    if (!force && state.campaigns) return state.campaigns;
    if (previewMode) {
      state.campaigns = previewCampaigns;
      return state.campaigns;
    }
    const response = await request("/api/campaigns");
    state.campaigns = response.campaigns || [];
    return state.campaigns;
  }

  async function getCampaign(id) {
    if (previewMode) return previewCampaignDetails[id] || previewCampaignDetails[1];
    return request(`/api/campaigns/${id}`);
  }

  async function getCampaignStatus(campaignId, stock) {
    if (previewMode) return { campaignId, ...stock };
    const params = new URLSearchParams({ routeId: stock.routeId, fareClass: stock.fareClass });
    return request(`/api/campaigns/${campaignId}/status?${params}`);
  }

  function campaignCard(campaign, compact = false) {
    const open = campaign.status === "OPEN";
    return `
      <article class="campaign-card ${escapeHtml(campaign.status)}">
        <span class="badge ${escapeHtml(campaign.status)}">${escapeHtml(campaignStatusLabel(campaign.status))}</span>
        <h3>${escapeHtml(campaign.name)}</h3>
        <p>${escapeHtml(describeCampaign(campaign))}</p>
        ${
          compact
            ? ""
            : `<dl class="campaign-meta">
                <div><dt>오픈</dt><dd>${formatDate(campaign.openAt)}</dd></div>
                <div><dt>만료</dt><dd>${formatDate(campaign.expireAt)}</dd></div>
              </dl>`
        }
        <button class="${open ? "primary-button" : "ghost-button"}" type="button" ${open ? `data-route="campaign/${campaign.campaignId}"` : "disabled"}>
          ${open ? "특가 확인 →" : campaignStatusLabel(campaign.status)}
        </button>
      </article>`;
  }

  async function renderHome(token) {
    let campaigns = [];
    let apiNotice = "";
    try {
      campaigns = await getCampaigns();
    } catch (error) {
      apiNotice = `<div class="notice">${escapeHtml(errorMessage(error))}</div>`;
    }
    if (token !== state.renderToken) return;

    const origins = [...new Map(flights.map((flight) => [flight.origin, flight.originName])).entries()];
    const destinations = [...new Map(flights.map((flight) => [flight.destination, flight.destinationName])).entries()];
    const openCampaigns = campaigns.filter((campaign) => campaign.status === "OPEN").slice(0, 3);

    app.innerHTML = `
      <section class="hero">
        <div class="hero-content">
          <p class="hero-eyebrow">Earlybird Special · U-Ply Air</p>
          <h1>더 멀리 떠나는 순간,<br />가격은 더 가볍게</h1>
          <p>선착순 얼리버드 쿠폰으로 국내선부터 미주·유럽까지 특별하게 예약하세요.</p>
          <button class="primary-button" type="button" data-route="campaigns">특가 이벤트 보기 →</button>
        </div>
      </section>
      <section class="search-panel" aria-label="항공편 검색">
        <div class="trip-tabs">
          <button type="button" disabled>왕복</button>
          <button class="active" type="button">편도</button>
          <button type="button" disabled>다구간</button>
        </div>
        <form id="home-search-form" class="search-grid">
          <div class="field">
            <label for="origin">출발지</label>
            <select id="origin" name="origin">${origins.map(([code, name]) => `<option value="${code}" ${code === state.search.origin ? "selected" : ""}>${escapeHtml(name)}</option>`).join("")}</select>
          </div>
          <div class="field">
            <label for="destination">도착지</label>
            <select id="destination" name="destination">${destinations.map(([code, name]) => `<option value="${code}" ${code === state.search.destination ? "selected" : ""}>${escapeHtml(name)}</option>`).join("")}</select>
          </div>
          <div class="field">
            <label for="depart-date">출발일</label>
            <input id="depart-date" name="date" type="date" value="${escapeHtml(state.search.date)}" />
          </div>
          <div class="field">
            <label for="fare-class">좌석 등급</label>
            <select id="fare-class" name="fareClass">
              <option value="ECONOMY" ${state.search.fareClass === "ECONOMY" ? "selected" : ""}>이코노미</option>
              <option value="BUSINESS" ${state.search.fareClass === "BUSINESS" ? "selected" : ""}>비즈니스</option>
            </select>
          </div>
          <button class="primary-button" type="submit">항공편 검색</button>
        </form>
      </section>
      <section class="home-events">
        ${apiNotice}
        <div class="section-heading">
          <h2>진행 중인 얼리버드 특가</h2>
          <button class="text-button" type="button" data-route="campaigns">전체 이벤트 보기 →</button>
        </div>
        ${
          openCampaigns.length
            ? `<div class="campaign-grid">${openCampaigns.map((campaign) => campaignCard(campaign, true)).join("")}</div>`
            : `<div class="empty-state"><div class="empty-icon">✈</div><h2>진행 중인 이벤트가 없습니다</h2><p>새로운 얼리버드 특가를 준비하고 있습니다.</p></div>`
        }
      </section>`;
  }

  async function renderCampaigns(token) {
    try {
      const campaigns = await getCampaigns(true);
      if (token !== state.renderToken) return;
      app.innerHTML = `
        <section class="page-shell narrow">
          <header class="page-header">
            <div><p class="page-kicker">Earlybird coupon</p><h1 class="page-title">특가 캠페인</h1><p class="page-description">얼리버드 쿠폰을 발급받아 특가 항공권을 예약하세요.</p></div>
          </header>
          ${campaigns.length ? `<div class="campaign-list">${campaigns.map((campaign) => campaignCard(campaign)).join("")}</div>` : '<div class="empty-state"><div class="empty-icon">✈</div><h2>현재 진행 중인 특가가 없습니다</h2><p>새로운 여행 혜택으로 다시 찾아뵙겠습니다.</p></div>'}
        </section>`;
    } catch (error) {
      if (token === state.renderToken) app.innerHTML = errorView("캠페인을 불러오지 못했습니다", error, "campaigns");
    }
  }

  async function renderCampaignDetail(id, token) {
    try {
      const campaign = await getCampaign(id);
      if (token !== state.renderToken) return;
      state.campaign = campaign;
      state.selectedStock = campaign.stocks?.[0] || null;
      drawCampaignDetail(campaign);
      if (campaign.status === "OPEN" && state.selectedStock) startStockUpdates(campaign.campaignId);
    } catch (error) {
      if (token === state.renderToken) app.innerHTML = errorView("캠페인 정보를 불러오지 못했습니다", error, "campaigns");
    }
  }

  function drawCampaignDetail(campaign) {
    const routes = [...new Map((campaign.stocks || []).map((stock) => [stock.routeId, routeLabel(stock.routeId)])).entries()];
    const active = state.selectedStock || campaign.stocks?.[0];
    const fares = (campaign.stocks || []).filter((stock) => stock.routeId === active?.routeId);
    const issuedRate = active?.totalStock ? Math.round(((active.totalStock - active.remainingStock) / active.totalStock) * 100) : 0;

    app.innerHTML = `
      <section class="page-shell narrow">
        <button class="back-button" type="button" data-route="campaigns">← 캠페인 목록</button>
        <article class="card campaign-detail-card">
          <header class="campaign-detail-head">
            <span class="badge ${escapeHtml(campaign.status)}">${escapeHtml(campaignStatusLabel(campaign.status))}</span>
            <h1>${escapeHtml(campaign.name)}</h1>
            <p>${formatDate(campaign.openAt)} ~ ${formatDate(campaign.expireAt)}</p>
          </header>
          <div class="campaign-detail-body">
            <div class="field">
              <label for="campaign-route">노선</label>
              <select id="campaign-route" ${campaign.status !== "OPEN" ? "disabled" : ""}>
                ${routes.map(([routeId, label]) => `<option value="${escapeHtml(routeId)}" ${routeId === active?.routeId ? "selected" : ""}>${escapeHtml(label)}</option>`).join("")}
              </select>
            </div>
            <div class="field" style="margin-top:18px">
              <label>좌석 등급</label>
              <div class="fare-tabs">
                ${fares.map((stock) => `<button class="${stock.fareClass === active?.fareClass ? "active" : ""}" type="button" data-action="select-fare" data-fare="${escapeHtml(stock.fareClass)}">${fareLabel(stock.fareClass)}</button>`).join("")}
              </div>
            </div>
            ${
              active
                ? `<div class="stock-board">
                    <div class="stock-board-head"><span>발급 현황</span><span class="live-dot" id="stock-live-label">실시간 갱신 중</span></div>
                    <div class="stock-numbers">
                      <div><span>총 쿠폰</span><strong>${active.totalStock.toLocaleString()}<small> 장</small></strong></div>
                      <div><span>남은 쿠폰</span><strong class="remaining" id="remaining-stock">${active.remainingStock.toLocaleString()}<small> 장</small></strong></div>
                    </div>
                    <div class="progress"><span id="stock-progress" style="width:${issuedRate}%"></span></div>
                    <div class="progress-caption" id="stock-caption">${issuedRate}% 발급 완료</div>
                  </div>`
                : '<div class="notice">등록된 재고 풀이 없습니다.</div>'
            }
            <button class="primary-button" style="width:100%" type="button" data-action="issue-coupon" ${campaign.status !== "OPEN" || !active || active.remainingStock <= 0 ? "disabled" : ""}>
              ${active?.remainingStock <= 0 ? "쿠폰 소진" : "얼리버드 쿠폰 받기"}
            </button>
          </div>
        </article>
      </section>`;
  }

  function updateStockView(status) {
    state.selectedStock = { ...state.selectedStock, ...status };
    const remaining = document.querySelector("#remaining-stock");
    const progress = document.querySelector("#stock-progress");
    const caption = document.querySelector("#stock-caption");
    const issueButton = document.querySelector('[data-action="issue-coupon"]');
    if (!remaining || !progress || !caption) return;

    const rate = status.totalStock
      ? Math.round(((status.totalStock - status.remainingStock) / status.totalStock) * 100)
      : 0;
    remaining.innerHTML = `${Number(status.remainingStock).toLocaleString()}<small> 장</small>`;
    progress.style.width = `${rate}%`;
    caption.textContent = `${rate}% 발급 완료`;
    if (issueButton) {
      const soldOut = Number(status.remainingStock) <= 0;
      issueButton.disabled = soldOut || state.campaign?.status !== "OPEN";
      issueButton.textContent = soldOut ? "쿠폰 소진" : "얼리버드 쿠폰 받기";
    }
  }

  function startStockUpdates(campaignId) {
    if (state.pollTimer) window.clearInterval(state.pollTimer);
    state.pollTimer = null;
    if (state.stockStream) state.stockStream.close();
    state.stockStream = null;
    if (!state.selectedStock || previewMode) return;

    const params = new URLSearchParams({
      routeId: state.selectedStock.routeId,
      fareClass: state.selectedStock.fareClass,
    });
    const stream = new EventSource(`/api/campaigns/${campaignId}/status/stream?${params}`);
    state.stockStream = stream;

    stream.addEventListener("stock-update", (event) => {
      try {
        const status = JSON.parse(event.data);
        if (currentRoute() === `campaign/${campaignId}`) updateStockView(status);
      } catch (_error) {
        // 다음 이벤트에서 다시 갱신한다.
      }
    });

    stream.addEventListener("open", () => {
      const label = document.querySelector("#stock-live-label");
      if (label) label.textContent = "실시간 갱신 중";
    });

    stream.addEventListener("error", () => {
      if (state.stockStream !== stream) return;
      stream.close();
      state.stockStream = null;
      const label = document.querySelector("#stock-live-label");
      if (label) label.textContent = "자동 갱신 중";
      if (!state.pollTimer) {
        state.pollTimer = window.setInterval(() => refreshStock(campaignId), 1000);
      }
    });
  }

  async function refreshStock(campaignId) {
    const route = currentRoute();
    if (route !== `campaign/${campaignId}` || !state.selectedStock) return clearPoller();
    try {
      const status = await getCampaignStatus(campaignId, state.selectedStock);
      updateStockView(status);
    } catch (error) {
      clearPoller();
      toast("재고 갱신 중단", errorMessage(error), "error");
    }
  }

  async function issueCoupon() {
    if (!state.campaign || !state.selectedStock) return;
    if (previewMode) {
      toast("현재 이용할 수 없습니다", "잠시 후 다시 시도해 주세요.", "error");
      return;
    }
    const confirmed = await openConfirm({
      title: "얼리버드 쿠폰을 발급할까요?",
      message: `${routeLabel(state.selectedStock.routeId)} · ${fareLabel(state.selectedStock.fareClass)} 쿠폰을 발급합니다.`,
      confirmText: "쿠폰 발급",
    });
    if (!confirmed) return;

    const action = `issue:${state.userId}:${state.campaign.campaignId}:${state.selectedStock.routeId}:${state.selectedStock.fareClass}`;
    try {
      state.issueResult = await request("/api/coupons/issue", {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKeyFor(action) },
        body: JSON.stringify({
          userId: state.userId,
          campaignId: state.campaign.campaignId,
          routeId: state.selectedStock.routeId,
          fareClass: state.selectedStock.fareClass,
        }),
      });
      syncSearchRoute(state.selectedStock.routeId, state.selectedStock.fareClass);
      finishIdempotentAction(action);
      setRoute("issue-result");
    } catch (error) {
      releaseIdempotencyKeyAfterFailure(action, error);
      toast("쿠폰 발급 실패", errorMessage(error), "error");
    }
  }

  function renderIssueResult() {
    const result = state.issueResult;
    if (!result) return setRoute("campaigns");
    app.innerHTML = `
      <section class="page-shell narrow">
        <div class="page-header"><div><p class="page-kicker">Coupon issued</p><h1 class="page-title">쿠폰 발급이 완료됐습니다</h1><p class="page-description">결제 단계에서 쿠폰을 선택하면 할인이 적용됩니다.</p></div></div>
        <article class="ticket">
          <div class="ticket-main">
            <span class="ticket-label">U-PLY EARLYBIRD</span>
            <h2>얼리버드 할인 쿠폰</h2>
            <div class="ticket-grid">
              <div><span>쿠폰 번호</span><strong>${escapeHtml(result.couponId)}</strong></div>
              <div><span>상태</span><strong>${couponStatusLabel(result.status)}</strong></div>
              <div><span>발급 시각</span><strong>${formatDate(result.issuedAt)}</strong></div>
              <div><span>유효기간</span><strong>${formatDate(result.expireAt)}</strong></div>
            </div>
          </div>
          <div class="ticket-side"><div class="barcode" aria-hidden="true"></div><small>USER ${state.userId}</small></div>
        </article>
        <div class="button-row end" style="margin-top:22px">
          <button class="secondary-button" type="button" data-route="coupons">내 쿠폰 보기</button>
          <button class="primary-button" type="button" data-route="flight-search">항공편 검색하기 →</button>
        </div>
      </section>`;
  }

  function matchingFlights() {
    return flights.filter(
      (flight) =>
        flight.origin === state.search.origin && flight.destination === state.search.destination,
    );
  }

  function renderFlightSearch() {
    const results = matchingFlights();
    const originName = flights.find((flight) => flight.origin === state.search.origin)?.originName || state.search.origin;
    const destinationName = flights.find((flight) => flight.destination === state.search.destination)?.destinationName || state.search.destination;
    app.innerHTML = `
      <section class="page-shell wide">
        <button class="back-button" type="button" data-route="home">← 검색 조건 변경</button>
        <header class="page-header"><div><p class="page-kicker">Flight search</p><h1 class="page-title">${escapeHtml(originName)} → ${escapeHtml(destinationName)}</h1><p class="page-description">${escapeHtml(state.search.date)} · ${fareLabel(state.search.fareClass)} · 성인 1명</p></div></header>
        ${
          results.length
            ? `<div class="flight-list">${results.map((flight) => flightCard(flight)).join("")}</div>`
            : `<div class="empty-state"><div class="empty-icon">✈</div><h2>조건에 맞는 항공편이 없습니다</h2><p>출발지와 목적지를 다시 선택해 주세요.</p><button class="primary-button" type="button" data-route="home">검색 조건 변경</button></div>`
        }
      </section>`;
  }

  function flightCard(flight) {
    const price = flight.prices[state.search.fareClass];
    return `
      <article class="flight-card">
        <div class="flight-number"><strong>${escapeHtml(flight.id)}</strong><span>${escapeHtml(flight.aircraft)} · ${escapeHtml(flight.country)}</span></div>
        <div class="flight-route">
          <div><strong>${escapeHtml(flight.departTime)}</strong><span class="flight-meta">${escapeHtml(flight.origin)}</span></div>
          <div><div class="flight-meta">${escapeHtml(flight.duration)}</div><div class="flight-line"></div><div class="flight-meta">직항</div></div>
          <div><strong>${escapeHtml(flight.arriveTime)}</strong><span class="flight-meta">${escapeHtml(flight.destination)}</span></div>
        </div>
        <div class="flight-price"><strong>${currency(price)}</strong><span>${fareLabel(state.search.fareClass)} · 편도 기준</span></div>
        <button class="primary-button" type="button" data-action="select-flight" data-flight="${escapeHtml(flight.id)}">선택</button>
      </article>`;
  }

  function renderBookingInfo() {
    const flight = state.selectedFlight;
    if (!flight) return setRoute("flight-search");
    app.innerHTML = `
      <section class="page-shell">
        <button class="back-button" type="button" data-route="flight-search">← 항공편 다시 선택</button>
        <header class="page-header"><div><p class="page-kicker">Booking information</p><h1 class="page-title">예약 정보를 확인해 주세요</h1><p class="page-description">쿠폰은 다음 결제 단계에서 선택합니다.</p></div></header>
        <div class="summary-layout">
          <div>
            <article class="card"><h2 class="card-title">선택 항공편</h2>${flightSummary(flight)}</article>
            <article class="card"><h2 class="card-title">탑승객 정보</h2><div class="detail-grid"><div class="detail-item"><span>회원 번호</span><strong>${state.userId}</strong></div><div class="detail-item"><span>탑승객 유형</span><strong>성인 1명</strong></div></div></article>
          </div>
          <aside class="card sticky-card"><h2 class="card-title">예약 요약</h2><dl class="summary-list"><div><dt>항공 운임</dt><dd>${currency(flight.prices[state.search.fareClass])}</dd></div><div><dt>세금 및 유류할증료</dt><dd>포함</dd></div><div class="total"><dt>예상 결제 금액</dt><dd>${currency(flight.prices[state.search.fareClass])}</dd></div></dl><button class="primary-button" style="width:100%;margin-top:22px" type="button" data-route="payment">결제 단계로 이동 →</button></aside>
        </div>
      </section>`;
  }

  function flightSummary(flight) {
    return `<div class="flight-route" style="grid-template-columns:1fr 150px 1fr">
      <div><strong>${escapeHtml(flight.departTime)}</strong><span class="flight-meta">${escapeHtml(flight.originName)} (${escapeHtml(flight.origin)})</span></div>
      <div><div class="flight-meta">${escapeHtml(flight.id)} · ${escapeHtml(flight.duration)}</div><div class="flight-line"></div><div class="flight-meta">${fareLabel(state.search.fareClass)}</div></div>
      <div><strong>${escapeHtml(flight.arriveTime)}</strong><span class="flight-meta">${escapeHtml(flight.destinationName)} (${escapeHtml(flight.destination)})</span></div>
    </div>`;
  }

  async function loadUsableCoupons() {
    if (previewMode) return [];
    const list = await request(`/api/users/${state.userId}/coupons`);
    const issued = (list.coupons || []).filter((coupon) => coupon.status === "ISSUED");
    const details = await Promise.all(issued.map((coupon) => request(`/api/coupons/${coupon.couponId}`)));
    return details
      .filter(
        (coupon) =>
          coupon.routeId === state.selectedFlight?.destination &&
          coupon.fareClass === state.search.fareClass &&
          new Date(coupon.expireAt).getTime() > Date.now(),
      );
  }

  async function renderPayment(token) {
    if (!state.selectedFlight) return setRoute("flight-search");
    let coupons = [];
    let couponError = "";
    try {
      coupons = await loadUsableCoupons();
    } catch (error) {
      couponError = errorMessage(error);
    }
    if (token !== state.renderToken) return;
    const flight = state.selectedFlight;
    const basePrice = flight.prices[state.search.fareClass];
    app.innerHTML = `
      <section class="page-shell">
        <button class="back-button" type="button" data-route="booking-info">← 예약 정보</button>
        <header class="page-header"><div><p class="page-kicker">Payment</p><h1 class="page-title">결제 및 쿠폰 선택</h1><p class="page-description">예약이 확정되면 선택한 쿠폰의 사용이 완료됩니다.</p></div></header>
        <div class="summary-layout">
          <div>
            <article class="card"><h2 class="card-title">할인 쿠폰</h2>
              ${couponError ? `<div class="notice">쿠폰을 불러오지 못했습니다. ${escapeHtml(couponError)}</div>` : ""}
              <label class="coupon-option"><input type="radio" name="coupon" value="" checked /><span><strong>쿠폰을 사용하지 않음</strong><span>일반 운임으로 예약합니다.</span></span></label>
              ${coupons.map((coupon) => `<label class="coupon-option"><input type="radio" name="coupon" value="${escapeHtml(coupon.couponId)}" /><span><strong>얼리버드 쿠폰 · ${routeLabel(coupon.routeId)}</strong><span>${fareLabel(coupon.fareClass)} · ${formatDate(coupon.expireAt)}까지</span></span></label>`).join("")}
              ${!coupons.length ? '<div class="notice info" style="margin-top:14px">현재 항공편에 사용할 수 있는 쿠폰이 없습니다. 쿠폰 없이 예약을 진행할 수 있습니다.</div>' : ""}
            </article>
            <article class="card"><h2 class="card-title">결제 수단</h2><label class="coupon-option"><input type="radio" checked /><span><strong>U-Ply 간편결제</strong><span>•••• •••• •••• 0829</span></span></label></article>
          </div>
          <aside class="card sticky-card"><h2 class="card-title">최종 결제 금액</h2><dl class="summary-list"><div><dt>항공 운임</dt><dd>${currency(basePrice)}</dd></div><div class="discount"><dt>얼리버드 할인</dt><dd id="discount-price">0원</dd></div><div class="total"><dt>총 결제 금액</dt><dd id="final-price">${currency(basePrice)}</dd></div></dl><button class="primary-button" style="width:100%;margin-top:22px" type="button" data-action="confirm-booking">예약 확정</button></aside>
        </div>
      </section>`;
    state.selectedCouponId = null;
  }

  function discountAmount() {
    if (!state.selectedCouponId || !state.selectedFlight) return 0;
    const price = state.selectedFlight.prices[state.search.fareClass];
    return Math.min(Math.round(price * 0.2), 100000);
  }

  async function confirmBooking() {
    if (!state.selectedFlight) return;
    const confirmed = await openConfirm({
      title: "예약을 확정할까요?",
      message: state.selectedCouponId
        ? "예약 확정과 동시에 선택한 쿠폰이 사용 완료 처리됩니다."
        : "쿠폰 없이 항공권 예약을 확정합니다.",
      confirmText: "예약 확정",
    });
    if (!confirmed) return;

    const action = state.selectedCouponId ? `use:${state.userId}:${state.selectedCouponId}` : null;
    try {
      if (state.selectedCouponId) {
        await request(`/api/coupons/${state.selectedCouponId}/use`, {
          method: "POST",
          headers: { "Idempotency-Key": idempotencyKeyFor(action) },
        });
      }
      const booking = {
        bookingId: `UP${Date.now().toString().slice(-9)}`,
        userId: state.userId,
        flight: state.selectedFlight,
        fareClass: state.search.fareClass,
        departureDate: state.search.date,
        couponId: state.selectedCouponId,
        discount: discountAmount(),
        totalPrice: state.selectedFlight.prices[state.search.fareClass] - discountAmount(),
        status: "CONFIRMED",
        bookedAt: new Date().toISOString(),
      };
      const bookings = getBookings();
      bookings.unshift(booking);
      saveBookings(bookings);
      if (action) finishIdempotentAction(action);
      state.bookingResult = booking;
      setRoute("booking-confirm");
    } catch (error) {
      if (action) releaseIdempotencyKeyAfterFailure(action, error);
      toast("예약 확정 실패", errorMessage(error), "error");
    }
  }

  function getBookings() {
    try {
      return JSON.parse(localStorage.getItem(`uply.bookings.${state.userId}`) || "[]");
    } catch (_error) {
      return [];
    }
  }

  function saveBookings(bookings) {
    localStorage.setItem(`uply.bookings.${state.userId}`, JSON.stringify(bookings));
  }

  function renderBookingConfirm() {
    const booking = state.bookingResult;
    if (!booking) return setRoute("bookings");
    app.innerHTML = `
      <section class="page-shell narrow">
        <div class="empty-state" style="border-style:solid;box-shadow:var(--shadow)">
          <div class="success-icon">✓</div><h2>예약이 완료됐습니다</h2><p>예약번호 <strong>${escapeHtml(booking.bookingId)}</strong> · ${escapeHtml(booking.flight.originName)} → ${escapeHtml(booking.flight.destinationName)}</p>
          <div class="button-row" style="justify-content:center"><button class="secondary-button" type="button" data-route="home">홈으로</button><button class="primary-button" type="button" data-route="booking/${escapeHtml(booking.bookingId)}">예약 상세 보기</button></div>
        </div>
      </section>`;
  }

  function renderBookings() {
    const bookings = getBookings();
    app.innerHTML = `
      <section class="page-shell">
        <header class="page-header"><div><p class="page-kicker">My bookings</p><h1 class="page-title">내 예약</h1><p class="page-description">예약 상세에서 항공편 예약을 취소할 수 있습니다.</p></div></header>
        ${bookings.length ? `<div class="booking-list">${bookings.map((booking) => bookingRow(booking)).join("")}</div>` : '<div class="empty-state"><div class="empty-icon">✈</div><h2>예약 내역이 없습니다</h2><p>특가 항공편을 검색하고 첫 여행을 예약해 보세요.</p><button class="primary-button" type="button" data-route="home">항공편 검색</button></div>'}
      </section>`;
  }

  function bookingRow(booking) {
    return `<article class="booking-row"><div><h3 class="row-title">${escapeHtml(booking.flight.originName)} → ${escapeHtml(booking.flight.destinationName)}</h3><span class="row-muted">${escapeHtml(booking.bookingId)} · ${escapeHtml(booking.departureDate)} · ${escapeHtml(booking.flight.id)}</span></div><div><span class="badge ${booking.status === "CONFIRMED" ? "OPEN" : "CANCELLED"}">${booking.status === "CONFIRMED" ? "예약 완료" : "예약 취소"}</span></div><button class="ghost-button" type="button" data-route="booking/${escapeHtml(booking.bookingId)}">상세 보기</button></article>`;
  }

  function renderBookingDetail(bookingId) {
    const booking = getBookings().find((item) => item.bookingId === bookingId);
    if (!booking) {
      app.innerHTML = errorView("예약을 찾을 수 없습니다", new Error("예약 정보를 확인할 수 없습니다."), "bookings");
      return;
    }
    app.innerHTML = `
      <section class="page-shell narrow"><button class="back-button" type="button" data-route="bookings">← 내 예약</button><article class="card"><div class="page-header"><div><span class="badge ${booking.status === "CONFIRMED" ? "OPEN" : "CANCELLED"}">${booking.status === "CONFIRMED" ? "예약 완료" : "예약 취소"}</span><h1 class="page-title" style="margin-top:12px">${escapeHtml(booking.flight.originName)} → ${escapeHtml(booking.flight.destinationName)}</h1><p class="page-description">예약번호 ${escapeHtml(booking.bookingId)}</p></div></div>${flightSummary(booking.flight)}<div class="detail-grid" style="margin-top:30px"><div class="detail-item"><span>출발일</span><strong>${escapeHtml(booking.departureDate)}</strong></div><div class="detail-item"><span>좌석 등급</span><strong>${fareLabel(booking.fareClass)}</strong></div><div class="detail-item"><span>사용 쿠폰</span><strong>${booking.couponId ? escapeHtml(booking.couponId) : "미사용"}</strong></div><div class="detail-item"><span>결제 금액</span><strong>${currency(booking.totalPrice)}</strong></div></div>${booking.status === "CONFIRMED" ? '<div class="button-row end" style="margin-top:28px"><button class="danger-button" type="button" data-action="cancel-booking" data-booking="' + escapeHtml(booking.bookingId) + '">예약 취소</button></div>' : ""}</article></section>`;
  }

  async function cancelBooking(bookingId) {
    const bookings = getBookings();
    const booking = bookings.find((item) => item.bookingId === bookingId);
    if (!booking) return;
    const confirmed = await openConfirm({
      title: "항공편 예약을 취소할까요?",
      message: booking.couponId
        ? "예약에 사용한 쿠폰은 다시 사용할 수 없으며 발급 수량도 복구되지 않습니다."
        : "선택한 항공편 예약을 취소합니다.",
      confirmText: "예약 취소",
      danger: true,
    });
    if (!confirmed) return;
    const action = booking.couponId ? `cancel:${state.userId}:${booking.couponId}` : null;
    try {
      if (booking.couponId) {
        await request(`/api/coupons/${booking.couponId}/cancel`, {
          method: "POST",
          headers: { "Idempotency-Key": idempotencyKeyFor(action) },
        });
      }
      booking.status = "CANCELLED";
      booking.cancelledAt = new Date().toISOString();
      saveBookings(bookings);
      if (action) finishIdempotentAction(action);
      toast("예약 취소 완료", "예약과 사용한 쿠폰의 취소가 완료됐습니다.");
      renderBookingDetail(bookingId);
    } catch (error) {
      if (action) releaseIdempotencyKeyAfterFailure(action, error);
      toast("예약 취소 실패", errorMessage(error), "error");
    }
  }

  async function renderCoupons(token) {
    if (previewMode) {
      app.innerHTML = `<section class="page-shell"><header class="page-header"><div><p class="page-kicker">My coupons</p><h1 class="page-title">내 쿠폰</h1></div></header><div class="empty-state"><div class="empty-icon">%</div><h2>보유한 쿠폰이 없습니다</h2><p>진행 중인 얼리버드 이벤트를 확인해 보세요.</p><button class="primary-button" type="button" data-route="campaigns">특가 이벤트 보기</button></div></section>`;
      return;
    }
    try {
      const [response, campaigns] = await Promise.all([
        request(`/api/users/${state.userId}/coupons`),
        getCampaigns().catch(() => []),
      ]);
      if (token !== state.renderToken) return;
      const coupons = response.coupons || [];
      const campaignNames = new Map(
        campaigns.map((campaign) => [Number(campaign.campaignId), campaign.name]),
      );
      app.innerHTML = `
        <section class="page-shell"><header class="page-header"><div><p class="page-kicker">My coupons</p><h1 class="page-title">내 쿠폰</h1><p class="page-description">쿠폰 사용은 항공권 결제 단계에서만 가능합니다.</p></div></header>
          ${coupons.length ? `<div class="coupon-list">${coupons.map((coupon) => `<article class="coupon-row"><div><h3 class="row-title">${escapeHtml(campaignNames.get(Number(coupon.campaignId)) || "얼리버드 할인 쿠폰")}</h3><span class="row-muted">쿠폰 ${escapeHtml(coupon.couponId)} · ${formatDate(coupon.issuedAt)} 발급</span></div><div><span class="badge ${escapeHtml(coupon.status)}">${escapeHtml(couponStatusLabel(coupon.status))}</span></div><button class="ghost-button" type="button" data-route="coupon/${escapeHtml(coupon.couponId)}">상세 보기</button></article>`).join("")}</div>` : '<div class="empty-state"><div class="empty-icon">%</div><h2>보유한 쿠폰이 없습니다</h2><p>진행 중인 얼리버드 이벤트를 확인해 보세요.</p><button class="primary-button" type="button" data-route="campaigns">특가 이벤트 보기</button></div>'}
        </section>`;
    } catch (error) {
      if (token === state.renderToken) app.innerHTML = errorView("쿠폰 목록을 불러오지 못했습니다", error, "coupons");
    }
  }

  async function renderCouponDetail(couponId, token, attempt = 0) {
    try {
      const coupon = await request(`/api/coupons/${couponId}`);
      if (token !== state.renderToken) return;
      state.currentCoupon = coupon;
      app.innerHTML = `
        <section class="page-shell narrow"><button class="back-button" type="button" data-route="coupons">← 내 쿠폰</button><article class="card"><div class="page-header"><div><span class="badge ${escapeHtml(coupon.status)}">${escapeHtml(couponStatusLabel(coupon.status))}</span><h1 class="page-title" style="margin-top:12px">얼리버드 할인 쿠폰</h1><p class="page-description">${routeLabel(coupon.routeId)} · ${fareLabel(coupon.fareClass)}</p></div></div><div class="detail-grid"><div class="detail-item"><span>쿠폰 번호</span><strong>${escapeHtml(coupon.couponId)}</strong></div><div class="detail-item"><span>회원 번호</span><strong>${escapeHtml(coupon.userId)}</strong></div><div class="detail-item"><span>발급 시각</span><strong>${formatDate(coupon.issuedAt)}</strong></div><div class="detail-item"><span>유효기간</span><strong>${formatDate(coupon.expireAt)}</strong></div><div class="detail-item"><span>사용 시각</span><strong>${formatDate(coupon.usedAt)}</strong></div><div class="detail-item"><span>취소 시각</span><strong>${formatDate(coupon.cancelledAt)}</strong></div><div class="detail-item"><span>만료 처리 시각</span><strong>${formatDate(coupon.expiredAt)}</strong></div></div><div class="button-row end" style="margin-top:28px">${coupon.status === "ISSUED" ? '<button class="primary-button" type="button" data-action="search-with-coupon">항공편 검색하기 →</button>' : ""}${coupon.status === "USED" ? '<button class="primary-button" type="button" data-route="bookings">내 예약 보기 →</button>' : ""}</div></article></section>`;
    } catch (error) {
      if (error.errorCode === "COUPON_NOT_READY" && attempt < 3) {
        const delays = [500, 1000, 2000];
        window.setTimeout(() => renderCouponDetail(couponId, token, attempt + 1), delays[attempt]);
        app.innerHTML = `<div class="page-loading"><span class="spinner"></span><p>쿠폰 발급 정보를 저장하고 있습니다. 잠시만 기다려 주세요.</p></div>`;
        return;
      }
      if (token === state.renderToken) app.innerHTML = errorView("쿠폰을 불러오지 못했습니다", error, "coupons");
    }
  }

  function adminLayout(active, content) {
    return `<section class="admin-shell"><aside class="admin-sidebar"><h2>U-Ply Admin</h2><p>서비스 운영 관리</p><nav class="admin-menu"><button class="${active === "dashboard" ? "active" : ""}" data-route="admin">대시보드</button><button class="${active === "stocks" ? "active" : ""}" data-route="admin/stocks">캠페인·재고</button><button class="${active === "cache" ? "active" : ""}" data-route="admin/cache">캠페인 데이터 준비</button><button class="${active === "revoke" ? "active" : ""}" data-route="admin/revoke">미사용 쿠폰 회수</button><button class="${active === "batches" ? "active" : ""}" data-route="admin/batches">일괄 작업 실행</button><button class="${active === "verification" ? "active" : ""}" data-route="admin/verification">데이터 검증 결과</button><button class="${active === "monitoring" ? "active" : ""}" data-route="admin/monitoring">시스템 모니터링</button></nav></aside><div class="admin-content">${content}</div></section>`;
  }

  async function renderAdmin(route, token) {
    const page = route.split("/")[1] || "dashboard";
    try {
      if (page === "dashboard") return await renderAdminDashboard(token);
      if (page === "stocks") return await renderAdminStocks(token);
      if (page === "cache") return await renderAdminCache(token);
      if (page === "revoke") return await renderAdminRevoke(token);
      if (page === "batches") return await renderAdminBatches();
      if (page === "verification") return await renderAdminVerification(token);
      if (page === "verification-run") return await renderVerificationRun(route.split("/")[2], token);
      if (page === "monitoring") return await renderMonitoring(token);
      setRoute("admin");
    } catch (error) {
      if (token === state.renderToken) app.innerHTML = adminLayout(page, errorView("관리자 데이터를 불러오지 못했습니다", error, route));
    }
  }

  async function renderAdminDashboard(token) {
    let campaigns = [];
    let runs = [];
    try {
      campaigns = await getCampaigns(true);
      if (!previewMode) runs = await request("/api/admin/batch/verification/runs?limit=5");
    } catch (error) {
      if (!previewMode) throw error;
    }
    if (token !== state.renderToken) return;
    const open = campaigns.filter((campaign) => campaign.status === "OPEN").length;
    const attention = runs.filter((run) => !["PASSED", "BASELINE"].includes(run.verdict)).length;
    const latestVerdict = runs[0]?.verdict || "-";
    app.innerHTML = adminLayout("dashboard", `<header class="page-header"><div><p class="page-kicker">Operations</p><h1 class="page-title">관리자 대시보드</h1><p class="page-description">캠페인 운영과 데이터 검증 현황을 한곳에서 확인합니다.</p></div></header><div class="metric-grid"><article class="metric-card"><span>전체 캠페인</span><strong>${campaigns.length}</strong></article><article class="metric-card"><span>진행 중 캠페인</span><strong>${open}</strong></article><article class="metric-card"><span>확인 필요 회차</span><strong>${attention}</strong></article><article class="metric-card"><span>최근 검증 결과</span><strong style="font-size:18px">${escapeHtml(verdictLabel(latestVerdict))}</strong></article></div><div class="admin-action-grid"><button class="admin-action-card" data-route="admin/stocks"><strong>캠페인·재고 현황</strong><span>노선과 좌석 등급별 남은 쿠폰 수량을 확인합니다.</span></button><button class="admin-action-card" data-route="admin/cache"><strong>캠페인 데이터 준비</strong><span>이벤트 오픈 전 데이터를 준비하거나 누락된 정보를 복구합니다.</span></button><button class="admin-action-card" data-route="admin/revoke"><strong>미사용 쿠폰 일괄 회수</strong><span>특정 캠페인에서 아직 사용하지 않은 쿠폰을 회수합니다.</span></button><button class="admin-action-card" data-route="admin/batches"><strong>일괄 작업 실행</strong><span>쿠폰 만료, 데이터 검증, 재고 일치 확인을 실행합니다.</span></button><button class="admin-action-card" data-route="admin/verification"><strong>데이터 검증 결과</strong><span>회차별 검사 결과와 문제가 발견된 데이터를 확인합니다.</span></button><button class="admin-action-card" data-route="admin/monitoring"><strong>시스템 모니터링</strong><span>응답 속도와 서버 상태를 대시보드에서 확인합니다.</span></button></div>`);
  }

  async function renderAdminStocks(token) {
    const campaigns = await getCampaigns(true);
    const details = await Promise.all(campaigns.map((campaign) => getCampaign(campaign.campaignId).catch(() => ({ ...campaign, stocks: [] }))));
    if (token !== state.renderToken) return;
    const rows = details.flatMap((campaign) => (campaign.stocks || []).map((stock) => ({ campaign, stock })));
    app.innerHTML = adminLayout("stocks", `<header class="page-header"><div><p class="page-kicker">Campaign stock</p><h1 class="page-title">캠페인·재고 현황</h1><p class="page-description">노선과 좌석 등급별 쿠폰 발급 현황입니다.</p></div><button class="ghost-button" data-route="admin/stocks">새로고침</button></header><div class="table-wrap"><table class="data-table"><thead><tr><th>캠페인</th><th>상태</th><th>노선</th><th>등급</th><th>전체 수량</th><th>남은 수량</th><th>발급률</th></tr></thead><tbody>${rows.map(({ campaign, stock }) => { const rate = stock.totalStock ? Math.round(((stock.totalStock - stock.remainingStock) / stock.totalStock) * 100) : 0; return `<tr><td>${escapeHtml(campaign.name)}</td><td><span class="badge ${escapeHtml(campaign.status)}">${campaignStatusLabel(campaign.status)}</span></td><td>${escapeHtml(routeLabel(stock.routeId))}</td><td>${fareLabel(stock.fareClass)}</td><td>${stock.totalStock.toLocaleString()}</td><td>${stock.remainingStock.toLocaleString()}</td><td>${rate}%</td></tr>`; }).join("") || '<tr><td colspan="7">표시할 재고가 없습니다.</td></tr>'}</tbody></table></div>`);
  }

  async function renderAdminCache(token) {
    const campaigns = await getCampaigns(true);
    if (token !== state.renderToken) return;
    app.innerHTML = adminLayout(
      "cache",
      `<header class="page-header"><div><p class="page-kicker">Campaign readiness</p><h1 class="page-title">캠페인 데이터 준비</h1><p class="page-description">특가 오픈 전 데이터를 준비하거나 운영 중 누락된 정보만 복구합니다.</p></div></header>
      <article class="card">
        <div class="field"><label for="cache-campaign">대상 캠페인</label><select id="cache-campaign">${campaigns.map((campaign) => `<option value="${campaign.campaignId}">${escapeHtml(campaign.name)} · ${campaignStatusLabel(campaign.status)}</option>`).join("")}</select></div>
        <div class="admin-action-grid compact-actions">
          <button class="admin-action-card" data-action="recover-cache" ${previewMode || !campaigns.length ? "disabled" : ""}><strong>누락 정보 복구</strong><span>운영 중인 캠페인의 기존 수량은 유지하고, 사라진 정보만 다시 채웁니다.</span></button>
          <button class="admin-action-card danger-outline" data-action="warmup-cache" ${previewMode || !campaigns.length ? "disabled" : ""}><strong>오픈 전 전체 준비</strong><span>현재 저장된 정보를 캠페인 원본 데이터로 다시 구성합니다. 발급이 시작된 뒤에는 실행하지 마세요.</span></button>
        </div>
        <div id="cache-operation-result"></div>
      </article>`,
    );
  }

  async function runCacheOperation(mode) {
    const campaignId = Number(document.querySelector("#cache-campaign")?.value);
    if (!campaignId) return;
    const warmup = mode === "warmup";
    const confirmed = await openConfirm({
      title: warmup ? "캠페인 데이터를 전체 준비할까요?" : "누락된 캠페인 정보를 복구할까요?",
      message: warmup
        ? "쿠폰 발급이 시작되지 않았거나 발급 요청이 완전히 중단된 상태에서만 실행해 주세요."
        : "현재 발급 수량은 변경하지 않고 누락된 정보만 복구합니다.",
      confirmText: warmup ? "전체 준비" : "누락 정보 복구",
      danger: warmup,
    });
    if (!confirmed) return;

    try {
      const result = await request(
        `/api/admin/campaigns/${campaignId}/cache/${warmup ? "warmup" : "recover"}`,
        { method: "POST" },
      );
      const hasMismatch = (result.mismatches || []).length > 0;
      const target = document.querySelector("#cache-operation-result");
      if (target) {
        target.innerHTML = `<div class="notice ${hasMismatch ? "" : "info"}" style="margin-top:20px"><strong>${warmup ? "캠페인 데이터 준비 완료" : "누락 정보 복구 완료"}</strong>${hasMismatch ? `<br />추가 확인이 필요한 항목: ${escapeHtml(result.mismatches.join(", "))}` : "<br />확인 필요한 불일치가 없습니다."}</div>`;
      }
      toast("작업 완료", warmup ? "캠페인 데이터 준비를 마쳤습니다." : "누락된 정보 복구를 마쳤습니다.");
    } catch (error) {
      toast("작업 실패", errorMessage(error), "error");
    }
  }

  async function renderAdminRevoke(token) {
    const campaigns = await getCampaigns(true);
    if (token !== state.renderToken) return;
    app.innerHTML = adminLayout("revoke", `<header class="page-header"><div><p class="page-kicker">Airline revoke</p><h1 class="page-title">미사용 쿠폰 일괄 회수</h1><p class="page-description">선택한 캠페인에서 아직 사용하지 않은 쿠폰만 회수합니다.</p></div></header><article class="card"><div class="notice">이미 사용했거나 취소·만료된 쿠폰은 변경되지 않습니다. 회수 후 발급 가능 수량은 늘어나지 않습니다.</div><form id="revoke-form"><div class="field"><label for="revoke-campaign">대상 캠페인</label><select id="revoke-campaign" name="campaignId">${campaigns.map((campaign) => `<option value="${campaign.campaignId}">${escapeHtml(campaign.name)} · ${campaignStatusLabel(campaign.status)}</option>`).join("")}</select></div><div class="button-row end" style="margin-top:22px"><button class="danger-button" type="submit" ${previewMode ? "disabled" : ""}>미사용 쿠폰 회수</button></div></form><div id="revoke-result"></div></article>`);
  }

  function renderAdminBatches() {
    const execution = state.execution;
    app.innerHTML = adminLayout("batches", `<header class="page-header"><div><p class="page-kicker">Batch operations</p><h1 class="page-title">일괄 작업 실행</h1><p class="page-description">작업을 접수하면 완료될 때까지 진행 상태를 자동으로 확인합니다.</p></div></header><article class="card batch-control"><div class="field"><label for="verification-round">검증 대상 발급 방식</label><select id="verification-round"><option value="V3">Redis + Kafka</option><option value="V2">Redis + MySQL</option><option value="V1">MySQL 비관적 락</option><option value="V0">락 없는 기준 측정</option></select></div></article><div class="admin-action-grid"><button class="admin-action-card" data-action="run-batch" data-job="expiration" ${previewMode ? "disabled" : ""}><strong>기간 만료 쿠폰 정리</strong><span>유효기간이 지난 미사용 쿠폰을 만료 처리합니다.</span></button><button class="admin-action-card" data-action="run-batch" data-job="verification" ${previewMode ? "disabled" : ""}><strong>데이터 검증</strong><span>쿠폰, 상태 변경 이력, 재고 수량이 서로 맞는지 검사합니다.</span></button><button class="admin-action-card" data-action="run-batch" data-job="reconcile" ${previewMode ? "disabled" : ""}><strong>재고 일치 확인</strong><span>실시간 발급 수량과 저장된 재고 수량의 차이를 확인합니다.</span></button></div>${execution ? `<article class="card" style="margin-top:18px"><div class="job-status"><div><span class="badge ${escapeHtml(execution.status)}" id="execution-status">${escapeHtml(batchStatusLabel(execution.status))}</span><h2 class="card-title" style="margin:12px 0 4px">${escapeHtml(execution.job || execution.jobName)}</h2><p class="row-muted">작업 번호 ${escapeHtml(execution.jobExecutionId)} · 실행 ID ${escapeHtml(execution.runId)}</p></div><button class="ghost-button" data-action="refresh-execution">상태 새로고침</button></div><div id="execution-detail"></div></article>` : ""}`);
  }

  async function runBatch(job) {
    let query = "";
    if (job === "verification") {
      const round = document.querySelector("#verification-round")?.value || "V3";
      query = `?round=${encodeURIComponent(round.toUpperCase())}&failOnViolation=false`;
    }
    try {
      state.execution = await request(`/api/admin/batch/${job}${query}`, { method: "POST" });
      renderAdminBatches();
      toast("작업 접수 완료", `작업 번호 ${state.execution.jobExecutionId}`);
      watchExecution();
    } catch (error) {
      toast("작업 실행 실패", errorMessage(error), "error");
    }
  }

  async function refreshExecution() {
    if (!state.execution?.jobExecutionId) return;
    try {
      const detail = await request(`/api/admin/batch/executions/${state.execution.jobExecutionId}`);
      state.execution = { ...state.execution, ...detail };
      const status = document.querySelector("#execution-status");
      const target = document.querySelector("#execution-detail");
      if (status) {
        status.className = `badge ${detail.status}`;
        status.textContent = batchStatusLabel(detail.status);
      }
      if (target) target.innerHTML = `<div class="detail-grid" style="margin-top:22px"><div class="detail-item"><span>시작 시각</span><strong>${formatDate(detail.startTime)}</strong></div><div class="detail-item"><span>종료 시각</span><strong>${formatDate(detail.endTime)}</strong></div><div class="detail-item"><span>처리 결과</span><strong>${escapeHtml(batchStatusLabel(detail.status))}</strong></div><div class="detail-item"><span>실패 원인</span><strong>${escapeHtml((detail.failures || []).join(", ") || "없음")}</strong></div></div>${(detail.steps || []).length ? `<div class="table-wrap compact-table"><table class="data-table"><thead><tr><th>처리 단계</th><th>읽음</th><th>저장</th><th>반영</th></tr></thead><tbody>${detail.steps.map((step) => `<tr><td>${escapeHtml(step.name)}</td><td>${Number(step.readCount || 0).toLocaleString()}</td><td>${Number(step.writeCount || 0).toLocaleString()}</td><td>${Number(step.commitCount || 0).toLocaleString()}</td></tr>`).join("")}</tbody></table></div>` : ""}`;
      return detail.status;
    } catch (error) {
      toast("상태 조회 실패", errorMessage(error), "error");
      return null;
    }
  }

  function watchExecution() {
    if (state.batchPollTimer) window.clearInterval(state.batchPollTimer);
    const timer = window.setInterval(async () => {
      const status = await refreshExecution();
      if (["COMPLETED", "FAILED", "STOPPED", "ABANDONED"].includes(status)) {
        window.clearInterval(timer);
        if (state.batchPollTimer === timer) state.batchPollTimer = null;
        toast("작업 종료", `최종 결과: ${batchStatusLabel(status)}`, status === "COMPLETED" ? "success" : "error");
      }
    }, 1200);
    state.batchPollTimer = timer;
    window.setTimeout(() => {
      window.clearInterval(timer);
      if (state.batchPollTimer === timer) state.batchPollTimer = null;
    }, 60000);
  }

  async function renderAdminVerification(token) {
    if (previewMode) {
      app.innerHTML = adminLayout("verification", '<header class="page-header"><div><p class="page-kicker">Verification</p><h1 class="page-title">데이터 검증 결과</h1></div></header><div class="empty-state"><h2>아직 검증 결과가 없습니다</h2><p>일괄 작업 메뉴에서 데이터 검증을 실행해 주세요.</p></div>');
      return;
    }
    const runs = await request("/api/admin/batch/verification/runs?limit=20");
    if (token !== state.renderToken) return;
    app.innerHTML = adminLayout("verification", `<header class="page-header"><div><p class="page-kicker">Verification</p><h1 class="page-title">데이터 검증 결과</h1><p class="page-description">실행 ID를 선택하면 검사 항목별 결과와 문제가 발견된 데이터를 확인할 수 있습니다.</p></div><button class="ghost-button" data-route="admin/verification">새로고침</button></header><div class="table-wrap"><table class="data-table"><thead><tr><th>실행 ID</th><th>발급 방식</th><th>기준 시각</th><th>전체 위반</th><th>문제 항목</th><th>최종 결과</th></tr></thead><tbody>${runs.map((run) => { const verdict = run.verdict || (Number(run.total_violations) > 0 ? "FAILED" : "PASSED"); return `<tr class="clickable" data-route="admin/verification-run/${encodeURIComponent(run.run_id)}"><td>${escapeHtml(run.run_id)}</td><td>${escapeHtml(run.round || "-")}</td><td>${formatDate(run.snapshot_at)}</td><td>${Number(run.total_violations || 0).toLocaleString()}</td><td>${Number(run.failed_rules || 0).toLocaleString()}</td><td><span class="badge ${escapeHtml(verdict)}">${escapeHtml(verdictLabel(verdict))}</span></td></tr>`; }).join("") || '<tr><td colspan="6">아직 검증 결과가 없습니다.</td></tr>'}</tbody></table></div>`);
  }

  async function renderVerificationRun(runId, token) {
    const decoded = decodeURIComponent(runId);
    const [rules, violations] = await Promise.all([request(`/api/admin/batch/verification/runs/${encodeURIComponent(decoded)}`), request(`/api/admin/batch/verification/runs/${encodeURIComponent(decoded)}/violations?limit=100`)]);
    if (token !== state.renderToken) return;
    app.innerHTML = adminLayout("verification", `<button class="back-button" data-route="admin/verification">← 검증 결과 목록</button><header class="page-header"><div><p class="page-kicker">Verification detail</p><h1 class="page-title">${escapeHtml(decoded)}</h1><p class="page-description">검사 항목별 처리 범위와 발견된 문제입니다.</p></div><a class="secondary-button" href="/api/admin/batch/verification/runs/${encodeURIComponent(decoded)}/report" target="_blank" rel="noopener">결과 보고서 열기 ↗</a></header><div class="table-wrap"><table class="data-table"><thead><tr><th>규칙</th><th>검사 항목</th><th>상태</th><th>검사 행</th><th>위반</th><th>소요 시간</th></tr></thead><tbody>${rules.map((rule) => { const status = rule.status || (Boolean(rule.passed) ? "CHECKED" : "FAILED"); return `<tr><td>${escapeHtml(rule.rule_code)}</td><td>${escapeHtml(rule.rule_name)}</td><td><span class="badge ${escapeHtml(status)}">${escapeHtml(ruleStatusLabel(status))}</span></td><td>${rule.checked_rows == null ? "-" : Number(rule.checked_rows).toLocaleString()}</td><td>${Number(rule.violation_count || 0).toLocaleString()}</td><td>${Number(rule.elapsed_ms || 0).toLocaleString()} ms</td></tr>`; }).join("")}</tbody></table></div><h2 class="card-title" style="margin-top:30px">발견된 문제</h2><div class="table-wrap"><table class="data-table"><thead><tr><th>규칙</th><th>데이터 구분</th><th>대상 ID</th><th>상세 내용</th></tr></thead><tbody>${violations.map((item) => `<tr><td>${escapeHtml(item.rule_code)}</td><td>${escapeHtml(item.target_table)}</td><td>${escapeHtml(item.target_id)}</td><td>${escapeHtml(item.detail)}</td></tr>`).join("") || '<tr><td colspan="4">발견된 문제가 없습니다.</td></tr>'}</tbody></table></div>`);
  }

  // ---------------------------------------------------------------------------
  // 시스템 모니터링 화면
  //
  // 데이터는 두 갈래에서 온다.
  //   1) Prometheus HTTP API — 모든 앱 인스턴스의 지표를 합산한다. 연결할 수 없을 때만
  //      /actuator/prometheus로 내려가 현재 앱 한 대의 지표임을 화면에 명시한다. 누적
  //      카운터는 직전 표본과 차분해 초당 처리량으로 바꾼다.
  //   2) SSE(/api/campaigns/{id}/status/stream) — 선택한 재고풀의 잔여 재고.
  //      SSE는 재고풀 하나당 연결 하나다. 브라우저의 오리진당 동시 연결 한도(HTTP/1.1에서 6)
  //      에 걸리면 화면의 다른 요청까지 굶으므로, 한 번에 한 재고풀만 구독한다.
  // ---------------------------------------------------------------------------

  const MONITOR = {
    pollMs: 2000,
    windowSize: 90, // 2초 × 90 = 최근 3분
    issueUri: "/api/coupons/issue",
    // 색각 이상(적/녹/청) 시뮬레이션 검증을 통과한 두 색이다. 시리즈를 늘려야 하면
    // 색을 추가하지 말고 차트를 나누거나, 막대 + 직접 라벨 형태로 바꾼다.
    primary: "#356ad8",
    accent: "#e6007e",
    ink: "#171321",
    muted: "#716b7b",
    grid: "#e8e5ec",
  };
  const PROMETHEUS_URL_KEY = "uply.prometheus.url";

  const REASON_LABELS = {
    out_of_stock: "재고 소진",
    already_issued: "중복 발급",
    campaign_not_open: "오픈 전 요청",
    campaign_expired: "종료된 캠페인",
    duplicate_request: "동일 요청 재진입",
    lock_timeout: "락 대기 초과",
    concurrency_conflict: "DB 경합",
    connection_unavailable: "DB 커넥션 부족",
    db_save_failed: "DB 저장 실패",
    kafka_publish_failed: "Kafka 발행 실패",
    save_result_unknown: "저장 결과 불명확",
    campaign_not_cached: "캐시 미준비",
    system_error: "시스템 오류",
  };

  // 정상적인 비즈니스 거절과 시스템 실패를 가른다. 색이 아니라 글자로 구분해야
  // 색각 이상이 있어도, 흑백으로 인쇄해도 읽힌다.
  const BUSINESS_REASONS = new Set([
    "out_of_stock",
    "already_issued",
    "campaign_not_open",
    "campaign_expired",
    "duplicate_request",
  ]);

  const monitor = {
    prev: null,
    history: { successTps: [], failTps: [], p95: [], p99: [] },
    totals: { success: 0, compensation: 0 },
    reasons: new Map(),
    gauges: {},
    pools: [],
    pool: null,
    stock: { total: 0, remaining: null, series: [], live: false },
    metricsError: null,
    stockError: null,
    source: null,
    prometheusUrl: null,
  };

  const chartState = Object.create(null);

  function resetMonitorState() {
    monitor.prev = null;
    monitor.history = { successTps: [], failTps: [], p95: [], p99: [] };
    monitor.totals = { success: 0, compensation: 0 };
    monitor.reasons = new Map();
    monitor.gauges = {};
    monitor.stock = { total: 0, remaining: null, series: [], live: false };
    monitor.metricsError = null;
    monitor.stockError = null;
    monitor.source = null;
  }

  // --- Prometheus 텍스트 파싱 ------------------------------------------------

  function parseLabels(text) {
    const labels = Object.create(null);
    const pattern = /([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"/g;
    let match;
    while ((match = pattern.exec(text)) !== null) {
      labels[match[1]] = match[2].replace(/\\"/g, '"').replace(/\\n/g, "\n").replace(/\\\\/g, "\\");
    }
    return labels;
  }

  function parsePrometheusText(text) {
    const series = new Map();
    for (const raw of text.split("\n")) {
      const line = raw.trim();
      if (!line || line.charCodeAt(0) === 35) continue; // '#' 주석
      const brace = line.indexOf("{");
      let name;
      let labelPart = "";
      let rest;
      if (brace === -1) {
        const space = line.indexOf(" ");
        if (space === -1) continue;
        name = line.slice(0, space);
        rest = line.slice(space + 1);
      } else {
        const close = line.lastIndexOf("}");
        if (close < brace) continue;
        name = line.slice(0, brace);
        labelPart = line.slice(brace + 1, close);
        rest = line.slice(close + 1);
      }
      const value = Number.parseFloat(rest.trim().split(" ")[0]);
      if (!Number.isFinite(value)) continue;
      if (!series.has(name)) series.set(name, []);
      series.get(name).push({ labels: parseLabels(labelPart), value });
    }
    return series;
  }

  function sumMetric(parsed, name, filter) {
    const rows = parsed.get(name);
    if (!rows) return 0;
    let total = 0;
    for (const row of rows) {
      if (!filter || filter(row.labels)) total += row.value;
    }
    return total;
  }

  function maxMetric(parsed, name, filter) {
    let max = null;
    for (const row of parsed.get(name) || []) {
      if (filter && !filter(row.labels)) continue;
      if (max == null || row.value > max) max = row.value;
    }
    return max;
  }

  function groupMetric(parsed, name, labelKey) {
    const grouped = new Map();
    for (const row of parsed.get(name) || []) {
      const key = row.labels[labelKey];
      if (key === undefined) continue;
      grouped.set(key, (grouped.get(key) || 0) + row.value);
    }
    return grouped;
  }

  function pushPoint(list, at, value) {
    list.push({ at, value });
    if (list.length > MONITOR.windowSize) list.shift();
  }

  // 카운터는 누적값이다. 앱이 재기동하면 0으로 돌아가는데, 그때 차분이 음수가 되므로
  // 그 구간은 버린다(가짜 스파이크를 만드는 대신 한 표본을 잃는 쪽을 택한다).
  function perSecond(current, previous, seconds) {
    if (previous == null || seconds <= 0) return null;
    const delta = current - previous;
    if (delta < 0) return null;
    return delta / seconds;
  }

  // --- 값 표기 ---------------------------------------------------------------

  function compactNumber(value) {
    if (!Number.isFinite(value)) return "-";
    return Math.round(value).toLocaleString();
  }

  function rateText(value) {
    if (value == null || !Number.isFinite(value)) return "-";
    if (value >= 100) return `${Math.round(value).toLocaleString()}/s`;
    return `${value.toFixed(1)}/s`;
  }

  function secondsToMsText(value) {
    if (value == null || !Number.isFinite(value)) return "-";
    return `${Math.round(value * 1000).toLocaleString()} ms`;
  }

  function clockText(at) {
    return new Date(at).toLocaleTimeString("ko-KR", { hour12: false });
  }

  // --- 선형 차트 -------------------------------------------------------------
  //
  // 컨테이너 실제 폭을 재서 픽셀 좌표로 그린다. viewBox를 늘려 쓰면 축 글자와 선 굵기가
  // 같이 늘어나므로 쓰지 않는다. 2초마다 다시 그리므로 창 크기를 바꿔도 곧 맞춰진다.

  function chartPath(points, geo) {
    let path = "";
    let open = false;
    points.forEach((point, index) => {
      if (point.value == null) {
        open = false;
        return;
      }
      const x = geo.left + index * geo.stepX;
      const y = geo.bottom - (point.value - geo.min) * geo.scaleY;
      path += `${open ? "L" : "M"}${x.toFixed(1)} ${y.toFixed(1)}`;
      open = true;
    });
    return path;
  }

  function drawLineChart(chartId, config) {
    const plot = document.querySelector(`[data-chart="${chartId}"] .chart-plot`);
    if (!plot) return;

    const svg = plot.querySelector("svg");
    const width = Math.max(plot.clientWidth || 0, 320);
    const height = config.height || 200;
    const padding = { top: 16, right: 62, bottom: 26, left: 8 };

    const lengths = config.series.map((entry) => entry.points.length);
    const count = Math.max(...lengths, 0);
    if (count < 2) {
      svg.setAttribute("width", width);
      svg.setAttribute("height", height);
      svg.innerHTML = `<text x="${width / 2}" y="${height / 2}" text-anchor="middle" fill="${MONITOR.muted}" font-size="13">표본을 모으는 중입니다…</text>`;
      return;
    }

    let max = 0;
    let min = 0;
    for (const entry of config.series) {
      for (const point of entry.points) {
        if (point.value == null) continue;
        if (point.value > max) max = point.value;
        if (point.value < min) min = point.value;
      }
    }
    if (config.forceMax != null) max = Math.max(max, config.forceMax);
    if (max === min) max = min + 1;
    const headroom = (max - min) * 0.12;
    max += headroom;

    const geo = {
      left: padding.left,
      bottom: height - padding.bottom,
      stepX: (width - padding.left - padding.right) / (count - 1),
      scaleY: (height - padding.top - padding.bottom) / (max - min),
      min,
      max,
      count,
      width,
      height,
    };
    // 시리즈 길이가 서로 다를 수 있으므로 시간축은 가장 긴 시리즈를 기준으로 잡는다.
    const axisPoints = config.series.reduce(
      (longest, entry) => (entry.points.length > longest.length ? entry.points : longest),
      [],
    );
    chartState[chartId] = { geo, config, axisPoints };

    const format = config.format || compactNumber;
    let markup = "";

    // 눈금선은 배경으로 물러나 있어야 데이터가 앞으로 나온다.
    for (let step = 0; step <= 2; step += 1) {
      const value = min + ((max - min) / 2) * step;
      const y = geo.bottom - (value - min) * geo.scaleY;
      markup += `<line x1="${geo.left}" y1="${y.toFixed(1)}" x2="${width - padding.right}" y2="${y.toFixed(1)}" stroke="${MONITOR.grid}" stroke-width="1" />`;
      markup += `<text x="${width - padding.right + 8}" y="${(y + 4).toFixed(1)}" fill="${MONITOR.muted}" font-size="11">${escapeHtml(format(value))}</text>`;
    }

    config.series.forEach((entry) => {
      const path = chartPath(entry.points, geo);
      if (!path) return;
      if (config.series.length === 1) {
        const area = `${path}L${(geo.left + (count - 1) * geo.stepX).toFixed(1)} ${geo.bottom}L${geo.left} ${geo.bottom}Z`;
        markup += `<path d="${area}" fill="${entry.color}" fill-opacity="0.10" stroke="none" />`;
      }
      markup += `<path d="${path}" fill="none" stroke="${entry.color}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round" />`;

      // 마지막 점만 직접 라벨을 단다. 모든 점에 숫자를 붙이면 선이 읽히지 않는다.
      const last = [...entry.points].reverse().find((point) => point.value != null);
      if (last) {
        const lastIndex = entry.points.lastIndexOf(last);
        const x = geo.left + lastIndex * geo.stepX;
        const y = geo.bottom - (last.value - min) * geo.scaleY;
        markup += `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="4" fill="${entry.color}" stroke="#ffffff" stroke-width="2" />`;
      }
    });

    if (axisPoints.length) {
      markup += `<text x="${geo.left}" y="${height - 6}" fill="${MONITOR.muted}" font-size="11">${escapeHtml(clockText(axisPoints[0].at))}</text>`;
      markup += `<text x="${(width - padding.right).toFixed(1)}" y="${height - 6}" text-anchor="end" fill="${MONITOR.muted}" font-size="11">${escapeHtml(clockText(axisPoints[axisPoints.length - 1].at))}</text>`;
    }

    svg.setAttribute("width", width);
    svg.setAttribute("height", height);
    svg.innerHTML = markup;

    const hovered = plot.dataset.hoverIndex;
    if (hovered !== undefined && hovered !== "") showChartHover(chartId, Number(hovered));
  }

  function showChartHover(chartId, rawIndex) {
    const entry = chartState[chartId];
    const plot = document.querySelector(`[data-chart="${chartId}"] .chart-plot`);
    if (!entry || !plot) return;
    const index = Math.max(0, Math.min(entry.geo.count - 1, rawIndex));
    const cursor = plot.querySelector(".chart-cursor");
    const tip = plot.querySelector(".chart-tip");
    if (!cursor || !tip) return;

    const x = entry.geo.left + index * entry.geo.stepX;
    cursor.style.left = `${x}px`;
    cursor.style.height = `${entry.geo.bottom - 10}px`;
    cursor.hidden = false;

    const format = entry.config.format || compactNumber;
    const stamp = (entry.axisPoints || [])[index];
    const rows = entry.config.series
      .map((series) => {
        const point = series.points[index];
        const text = point && point.value != null ? format(point.value) : "-";
        return `<div class="chart-tip-row"><span class="chart-dot" style="background:${series.color}"></span>${escapeHtml(series.name)}<strong>${escapeHtml(text)}</strong></div>`;
      })
      .join("");
    tip.innerHTML = `<div class="chart-tip-time">${escapeHtml(stamp ? clockText(stamp.at) : "")}</div>${rows}`;
    tip.hidden = false;
    const flip = x > entry.geo.width * 0.6;
    tip.style.left = `${flip ? x - tip.offsetWidth - 12 : x + 12}px`;
    plot.dataset.hoverIndex = String(index);
  }

  function hideChartHover(plot) {
    const cursor = plot.querySelector(".chart-cursor");
    const tip = plot.querySelector(".chart-tip");
    if (cursor) cursor.hidden = true;
    if (tip) tip.hidden = true;
    delete plot.dataset.hoverIndex;
  }

  function bindChartHover() {
    document.querySelectorAll("[data-chart] .chart-plot").forEach((plot) => {
      const chartId = plot.closest("[data-chart]").dataset.chart;
      plot.addEventListener("mousemove", (event) => {
        const entry = chartState[chartId];
        if (!entry) return;
        const bounds = plot.getBoundingClientRect();
        const offset = event.clientX - bounds.left - entry.geo.left;
        showChartHover(chartId, Math.round(offset / entry.geo.stepX));
      });
      plot.addEventListener("mouseleave", () => hideChartHover(plot));
    });
  }

  function chartCard(chartId, title, description, legend) {
    return `<figure class="chart-card" data-chart="${chartId}">
      <figcaption>
        <h3>${escapeHtml(title)}</h3>
        <p>${escapeHtml(description)}</p>
      </figcaption>
      ${legend || ""}
      <div class="chart-plot"><svg role="img" aria-label="${escapeHtml(title)}"></svg><div class="chart-cursor" hidden></div><div class="chart-tip" hidden></div></div>
    </figure>`;
  }

  function chartLegend(items) {
    return `<div class="chart-legend">${items
      .map((item) => `<span><i style="background:${item.color}"></i>${escapeHtml(item.name)}</span>`)
      .join("")}</div>`;
  }

  // --- 실패 사유 막대 --------------------------------------------------------
  //
  // 사유가 열 개를 넘을 수 있어 색으로 구분하지 않는다. 이름이 바로 옆에 붙는 가로 막대라
  // 정체는 라벨이, 크기는 막대 길이가 나른다. 색은 한 가지만 쓴다.

  function failureBars() {
    const rows = [...monitor.reasons.entries()]
      .filter(([, value]) => value > 0)
      .sort((left, right) => right[1] - left[1]);

    if (!rows.length) {
      return '<p class="chart-empty">아직 실패로 집계된 요청이 없습니다.</p>';
    }

    const max = rows[0][1];
    return `<ul class="bar-list">${rows
      .map(([reason, value]) => {
        const label = REASON_LABELS[reason] || reason;
        const kind = BUSINESS_REASONS.has(reason) ? "정상 거절" : "시스템 실패";
        const width = Math.max(2, (value / max) * 100);
        return `<li class="bar-row${BUSINESS_REASONS.has(reason) ? "" : " system"}">
          <div class="bar-head"><span class="bar-name">${escapeHtml(label)}</span><span class="bar-kind">${kind}</span><strong>${compactNumber(value)}</strong></div>
          <div class="bar-track"><span style="width:${width.toFixed(1)}%"></span></div>
        </li>`;
      })
      .join("")}</ul>`;
  }

  function statTile(label, value, note, tone) {
    return `<article class="metric-card stat-tile${tone ? ` ${tone}` : ""}">
      <span>${escapeHtml(label)}</span>
      <strong>${escapeHtml(value)}</strong>
      <small>${escapeHtml(note || "")}</small>
    </article>`;
  }

  // --- 화면 갱신 -------------------------------------------------------------

  function updateMonitoringView() {
    if (currentRoute() !== "admin/monitoring") return;

    const notice = document.querySelector("#monitor-notice");
    if (notice) {
      const messages = [monitor.metricsError, monitor.stockError].filter(Boolean);
      notice.hidden = messages.length === 0;
      notice.textContent = messages.join(" ");
    }

    const tiles = document.querySelector("#monitor-tiles");
    if (tiles) {
      const tps = monitor.history.successTps.at(-1);
      const pending = monitor.gauges.hikariPending;
      const mismatch = monitor.gauges.reconcileMismatch;
      tiles.innerHTML = [
        statTile(
          "발급 성공 누계",
          compactNumber(monitor.totals.success),
          monitor.source || "지표 수집 준비 중",
        ),
        statTile("초당 발급", rateText(tps ? tps.value : null), "최근 2초 구간"),
        statTile(
          "커넥션 대기",
          pending == null ? "-" : compactNumber(pending),
          "HikariCP pending — 0을 벗어나면 풀 포화",
          pending > 0 ? "alert" : "",
        ),
        statTile(
          "Redis 보상 발생",
          compactNumber(monitor.totals.compensation),
          "DB 저장 실패 후 재고 되돌림",
          monitor.totals.compensation > 0 ? "alert" : "",
        ),
        statTile(
          "재고 불일치 (REC-01)",
          mismatch == null ? "-" : compactNumber(mismatch),
          "0이 아니면 회차를 자료로 쓸 수 없음",
          mismatch > 0 ? "alert" : "",
        ),
      ].join("");
    }

    const bars = document.querySelector("#monitor-failures");
    if (bars) bars.innerHTML = failureBars();

    const stockNote = document.querySelector("#monitor-stock-note");
    if (stockNote) {
      const { total, remaining, live } = monitor.stock;
      if (remaining == null) {
        stockNote.textContent = "재고풀을 선택하면 실시간으로 표시됩니다.";
      } else {
        const used = total ? Math.round(((total - remaining) / total) * 100) : 0;
        stockNote.textContent = `${remaining.toLocaleString()} / ${total.toLocaleString()}장 남음 · ${used}% 소진 · ${live ? "실시간 수신 중" : "자동 갱신 중"}`;
      }
    }

    drawLineChart("stock", {
      height: 200,
      format: compactNumber,
      forceMax: monitor.stock.total || null,
      series: [{ name: "잔여 재고", color: MONITOR.accent, points: monitor.stock.series }],
    });

    drawLineChart("throughput", {
      height: 200,
      format: rateText,
      series: [{ name: "발급 성공", color: MONITOR.primary, points: monitor.history.successTps }],
    });

    drawLineChart("latency", {
      height: 200,
      format: secondsToMsText,
      series: [
        { name: "p95", color: MONITOR.primary, points: monitor.history.p95 },
        { name: "p99", color: MONITOR.accent, points: monitor.history.p99 },
      ],
    });
  }

  // --- 지표 수집 -------------------------------------------------------------

  function prometheusBaseUrl() {
    const configured = String(
      monitor.prometheusUrl ||
        window.UPLY_PROMETHEUS_URL ||
        window.localStorage.getItem(PROMETHEUS_URL_KEY) ||
        "",
    )
      .trim()
      .replace(/\/$/, "");
    if (configured) return configured;
    return `${window.location.protocol}//${window.location.hostname || "localhost"}:9090`;
  }

  async function prometheusQuery(query) {
    const url = `${prometheusBaseUrl()}/api/v1/query?query=${encodeURIComponent(query)}`;
    const response = await fetch(url, { headers: { Accept: "application/json" } });
    if (!response.ok) throw new Error(`Prometheus ${response.status}`);
    const payload = await response.json();
    if (payload.status !== "success") throw new Error(payload.error || "Prometheus query failed");
    return payload.data?.result || [];
  }

  function prometheusScalar(rows) {
    const value = Number.parseFloat(rows?.[0]?.value?.[1]);
    return Number.isFinite(value) ? value : 0;
  }

  async function samplePrometheusMetrics() {
    const issueFilter = 'uri="/api/coupons/issue"';
    const [success, failures, compensation, pending, mismatch, p95, p99] = await Promise.all([
      prometheusQuery("sum(coupon_issue_success_total)"),
      prometheusQuery("sum by (reason) (coupon_issue_failure_total)"),
      prometheusQuery("sum(coupon_redis_compensation_total)"),
      prometheusQuery("sum(hikaricp_connections_pending)"),
      prometheusQuery("max(coupon_reconciliation_mismatch_count)"),
      prometheusQuery(
        `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{${issueFilter}}[1m])))`,
      ),
      prometheusQuery(
        `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{${issueFilter}}[1m])))`,
      ),
    ]);

    return {
      success: prometheusScalar(success),
      failure: failures.reduce((sum, row) => sum + prometheusScalar([row]), 0),
      reasons: new Map(
        failures.map((row) => [row.metric?.reason || "unknown", prometheusScalar([row])]),
      ),
      compensation: prometheusScalar(compensation),
      pending: prometheusScalar(pending),
      mismatch: mismatch.length ? prometheusScalar(mismatch) : null,
      p95: p95.length ? prometheusScalar(p95) : null,
      p99: p99.length ? prometheusScalar(p99) : null,
      source: "Prometheus · 전체 앱 인스턴스",
    };
  }

  async function sampleLocalMetrics() {
    const response = await fetch("/actuator/prometheus", { headers: { Accept: "text/plain" } });
    if (!response.ok) throw new Error(String(response.status));
    const parsed = parsePrometheusText(await response.text());
    if (!parsed.has("coupon_issue_success_total")) {
      throw new Error("coupon_issue_success_total missing");
    }
    const isIssue = (labels) => labels.uri === MONITOR.issueUri;
    const quantile = (value) =>
      maxMetric(
        parsed,
        "http_server_requests_seconds",
        (labels) => isIssue(labels) && labels.quantile === value,
      );
    return {
      success: sumMetric(parsed, "coupon_issue_success_total"),
      failure: sumMetric(parsed, "coupon_issue_failure_total"),
      reasons: groupMetric(parsed, "coupon_issue_failure_total", "reason"),
      compensation: sumMetric(parsed, "coupon_redis_compensation_total"),
      pending: sumMetric(parsed, "hikaricp_connections_pending"),
      mismatch: parsed.has("coupon_reconciliation_mismatch_count")
        ? sumMetric(parsed, "coupon_reconciliation_mismatch_count")
        : null,
      p95: quantile("0.95"),
      p99: quantile("0.99"),
      source: "현재 앱 인스턴스",
    };
  }

  async function sampleMetrics() {
    if (currentRoute() !== "admin/monitoring") return;

    let sample;
    try {
      sample = await samplePrometheusMetrics();
      monitor.metricsError = null;
    } catch (_prometheusError) {
      try {
        sample = await sampleLocalMetrics();
        monitor.metricsError =
          "Prometheus에 연결하지 못해 현재 앱 인스턴스의 지표만 표시합니다. 전체 비교 결과는 Grafana에서 확인해 주세요.";
      } catch (_localError) {
        monitor.metricsError =
          "실시간 지표를 읽지 못했습니다. Prometheus와 애플리케이션 Actuator 상태를 확인해 주세요.";
        updateMonitoringView();
        return;
      }
    }

    const at = Date.now();
    monitor.source = sample.source;
    monitor.totals.success = sample.success;
    monitor.totals.compensation = sample.compensation;
    monitor.reasons = sample.reasons;
    monitor.gauges.hikariPending = sample.pending;
    monitor.gauges.reconcileMismatch = sample.mismatch;

    if (monitor.prev) {
      const seconds = (at - monitor.prev.at) / 1000;
      const successTps = perSecond(sample.success, monitor.prev.success, seconds);
      const failTps = perSecond(sample.failure, monitor.prev.failure, seconds);
      pushPoint(monitor.history.successTps, at, successTps);
      pushPoint(monitor.history.failTps, at, failTps);
      pushPoint(monitor.history.p95, at, sample.p95);
      pushPoint(monitor.history.p99, at, sample.p99);
    }

    monitor.prev = { at, success: sample.success, failure: sample.failure };
    updateMonitoringView();
  }

  // --- 재고 스트림 -----------------------------------------------------------

  function stopMonitorStock() {
    if (state.monitorStream) state.monitorStream.close();
    state.monitorStream = null;
    if (state.monitorStockTimer) window.clearInterval(state.monitorStockTimer);
    state.monitorStockTimer = null;
  }

  function applyStockSample(status) {
    monitor.stock.total = status.totalStock ?? monitor.stock.total;
    monitor.stock.remaining = status.remainingStock;
    pushPoint(monitor.stock.series, Date.now(), status.remainingStock);
    updateMonitoringView();
  }

  function startMonitorStock() {
    stopMonitorStock();
    monitor.stock = { total: 0, remaining: null, series: [], live: false };
    const pool = monitor.pool;
    if (!pool || previewMode) return;

    const params = new URLSearchParams({ routeId: pool.routeId, fareClass: pool.fareClass });
    const stream = new EventSource(`/api/campaigns/${pool.campaignId}/status/stream?${params}`);
    state.monitorStream = stream;

    stream.addEventListener("open", () => {
      monitor.stock.live = true;
      monitor.stockError = null;
      updateMonitoringView();
    });

    stream.addEventListener("stock-update", (event) => {
      if (state.monitorStream !== stream) return;
      try {
        applyStockSample(JSON.parse(event.data));
      } catch (_error) {
        // 다음 이벤트에서 다시 받는다.
      }
    });

    let polling = false;
    // SSE가 정상일 때는 시간 축을 흐르게 하고, 연결이 끊긴 뒤에는 실제 상태 API를
    // 호출한다. 마지막 값을 복사하는 것만으로는 끊긴 연결을 정상처럼 보이게 만들 수 있다.
    state.monitorStockTimer = window.setInterval(async () => {
      if (currentRoute() !== "admin/monitoring") return stopMonitorStock();
      if (monitor.stock.live && monitor.stock.remaining != null) {
        pushPoint(monitor.stock.series, Date.now(), monitor.stock.remaining);
        updateMonitoringView();
      } else if (!polling) {
        polling = true;
        try {
          applyStockSample(await getCampaignStatus(pool.campaignId, pool));
          monitor.stockError = null;
        } catch (_error) {
          monitor.stockError = "실시간 재고를 갱신하지 못했습니다. 잠시 후 다시 시도합니다.";
          updateMonitoringView();
        } finally {
          polling = false;
        }
      }
    }, MONITOR.pollMs);

    stream.addEventListener("error", () => {
      if (state.monitorStream !== stream) return;
      // 연결이 끊기면 폴링으로 내려앉는다. 화면이 조용히 멈춰 있는 것이 가장 나쁘다.
      stream.close();
      state.monitorStream = null;
      monitor.stock.live = false;
      monitor.stockError = "실시간 연결이 끊겨 재고 조회 방식으로 자동 전환했습니다.";
      updateMonitoringView();
    });

    getCampaignStatus(pool.campaignId, pool).then(applyStockSample).catch(() => {});
  }

  // --- 화면 조립 -------------------------------------------------------------

  function poolOptions() {
    return monitor.pools
      .map((pool) => {
        const value = `${pool.campaignId}|${pool.routeId}|${pool.fareClass}`;
        const selected = monitor.pool && value === monitor.pool.key ? " selected" : "";
        return `<option value="${escapeHtml(value)}"${selected}>${escapeHtml(pool.campaignName)} · ${escapeHtml(routeLabel(pool.routeId))} · ${fareLabel(pool.fareClass)}</option>`;
      })
      .join("");
  }

  async function loadMonitorPools() {
    monitor.pools = [];
    let campaigns = [];
    try {
      campaigns = await getCampaigns(true);
    } catch (_error) {
      return;
    }
    const details = await Promise.all(
      campaigns.map((campaign) =>
        getCampaign(campaign.campaignId).catch(() => ({ ...campaign, stocks: [] })),
      ),
    );
    details.forEach((campaign) => {
      (campaign.stocks || []).forEach((stock) => {
        monitor.pools.push({
          key: `${campaign.campaignId}|${stock.routeId}|${stock.fareClass}`,
          campaignId: campaign.campaignId,
          campaignName: campaign.name,
          campaignStatus: campaign.status,
          routeId: stock.routeId,
          fareClass: stock.fareClass,
        });
      });
    });
    // 진행 중인 캠페인의 재고풀을 먼저 고른다. 모니터링 화면을 여는 이유가 대개 그것이다.
    monitor.pool =
      monitor.pools.find((pool) => pool.campaignStatus === "OPEN") || monitor.pools[0] || null;
  }

  async function renderMonitoring(token) {
    resetMonitorState();
    monitor.prometheusUrl = prometheusBaseUrl();
    await loadMonitorPools();
    // 같은 페이지를 다시 열었을 때(예: 새로고침 클릭) 이전 호출이 이 시점에 막 await를 끝내고
    // 돌아와 방금 시작한 새 렌더를 덮어쓰는 경합을 막는다. 다른 admin 하위 페이지들과 같은 패턴이다.
    if (token !== state.renderToken) return;

    const prometheusUrl = prometheusBaseUrl();
    let grafanaUrl = `http://${window.location.hostname || "localhost"}:3000`;
    try {
      const parsedPrometheusUrl = new URL(prometheusUrl);
      grafanaUrl = `${parsedPrometheusUrl.protocol}//${parsedPrometheusUrl.hostname}:3000`;
    } catch (_error) {
      // 입력값 검증은 변경 이벤트에서 수행한다. 여기서는 기본 링크를 유지한다.
    }
    const legendLatency = chartLegend([
      { name: "p95", color: MONITOR.primary },
      { name: "p99", color: MONITOR.accent },
    ]);

    app.innerHTML = adminLayout(
      "monitoring",
      `<header class="page-header">
        <div>
          <p class="page-kicker">Observability</p>
          <h1 class="page-title">시스템 모니터링</h1>
          <p class="page-description">발급이 지금 어떤 속도로 나가고 있는지, 무엇 때문에 막히는지를 2초마다 갱신해 보여줍니다.</p>
        </div>
        <div class="monitor-control">
          <div class="monitor-field">
            <label for="monitor-pool">실시간 재고풀</label>
            <select id="monitor-pool">${poolOptions() || '<option value="">재고풀 없음</option>'}</select>
          </div>
          <div class="monitor-field">
            <label for="monitor-prometheus-url">Prometheus 주소</label>
            <input id="monitor-prometheus-url" type="url" value="${escapeHtml(prometheusUrl)}" placeholder="http://localhost:9090" />
          </div>
        </div>
      </header>

      <p class="notice" id="monitor-notice" hidden></p>

      <div class="metric-grid five" id="monitor-tiles"></div>

      <div class="chart-grid">
        ${chartCard("stock", "재고 소진 곡선", "선택한 재고풀의 잔여 재고입니다. 선이 가파를수록 빠르게 나가고 있습니다.", `<p class="chart-note" id="monitor-stock-note">재고풀을 선택하면 실시간으로 표시됩니다.</p>`)}
        ${chartCard("throughput", "발급 처리량", "초당 실제 발급 성립 건수입니다. 누적값이 아니라 구간 속도입니다.")}
      </div>

      <div class="chart-grid">
        ${chartCard("latency", "발급 API 응답 지연", "p95와 p99입니다. 두 선이 벌어지면 일부 요청만 오래 걸리고 있다는 뜻입니다.", legendLatency)}
        <figure class="chart-card">
          <figcaption>
            <h3>실패 사유별 누계</h3>
            <p>재고 소진과 중복 발급은 정상 거절입니다. '시스템 실패'로 표시된 항목만 조치가 필요합니다.</p>
          </figcaption>
          <div id="monitor-failures"></div>
        </figure>
      </div>

      <div class="monitor-grid">
        <article class="monitor-card">
          <h3>통합 모니터링 대시보드</h3>
          <p>회차 기록용 상세 지표와 인프라 exporter 결과를 확인합니다.</p>
          <a id="monitor-grafana-link" class="primary-button" href="${escapeHtml(grafanaUrl)}/d/uply-coupon" target="_blank" rel="noopener">Grafana 열기 ↗</a>
        </article>
        <article class="monitor-card">
          <h3>수집 지표 조회</h3>
          <p>Prometheus가 실제로 무엇을 수집하고 있는지 직접 질의합니다.</p>
          <a id="monitor-prometheus-link" class="secondary-button" href="${escapeHtml(prometheusUrl)}/targets" target="_blank" rel="noopener">Prometheus 열기 ↗</a>
        </article>
        <article class="monitor-card">
          <h3>서비스 상태 확인</h3>
          <p>애플리케이션이 정상적으로 요청을 받을 수 있는지 확인합니다.</p>
          <a class="ghost-button" href="/actuator/health" target="_blank" rel="noopener">상태 확인 ↗</a>
        </article>
        <article class="monitor-card">
          <h3>데이터 반영 확인</h3>
          <p>메시지 처리 완료 후 재고와 저장 데이터가 일치하는지 확인합니다.</p>
          <button class="ghost-button" data-route="admin/batches">일괄 작업으로 이동</button>
        </article>
      </div>`,
    );

    const select = document.querySelector("#monitor-pool");
    if (select) {
      select.addEventListener("change", (event) => {
        monitor.pool = monitor.pools.find((pool) => pool.key === event.target.value) || null;
        startMonitorStock();
        updateMonitoringView();
      });
    }

    const prometheusInput = document.querySelector("#monitor-prometheus-url");
    if (prometheusInput) {
      prometheusInput.addEventListener("change", () => {
        const value = prometheusInput.value.trim().replace(/\/$/, "");
        try {
          const parsed = new URL(value);
          if (!/^https?:$/.test(parsed.protocol)) throw new Error("unsupported protocol");
          monitor.prometheusUrl = value;
          window.localStorage.setItem(PROMETHEUS_URL_KEY, value);
          monitor.prev = null;
          monitor.history = { successTps: [], failTps: [], p95: [], p99: [] };
          monitor.metricsError = null;
          const prometheusLink = document.querySelector("#monitor-prometheus-link");
          const grafanaLink = document.querySelector("#monitor-grafana-link");
          if (prometheusLink) prometheusLink.href = `${value}/targets`;
          if (grafanaLink) grafanaLink.href = `${parsed.protocol}//${parsed.hostname}:3000/d/uply-coupon`;
          sampleMetrics();
        } catch (_error) {
          monitor.metricsError = "Prometheus 주소는 http:// 또는 https://로 시작해야 합니다.";
          updateMonitoringView();
        }
      });
    }

    bindChartHover();
    updateMonitoringView();

    if (previewMode) {
      monitor.metricsError = "미리보기 모드에서는 실시간 지표를 수집하지 않습니다.";
      updateMonitoringView();
      return;
    }

    startMonitorStock();
    sampleMetrics();
    state.monitorTimer = window.setInterval(sampleMetrics, MONITOR.pollMs);
  }

  async function renderRoute() {
    clearPoller();
    const token = ++state.renderToken;
    const route = currentRoute();
    setActiveNav(route);
    window.scrollTo({ top: 0, behavior: "auto" });
    showLoading();

    if (route === "home") return renderHome(token);
    if (route === "campaigns") return renderCampaigns(token);
    if (route.startsWith("campaign/")) return renderCampaignDetail(route.split("/")[1], token);
    if (route === "issue-result") return renderIssueResult();
    if (route === "flight-search") return renderFlightSearch();
    if (route === "booking-info") return renderBookingInfo();
    if (route === "payment") return renderPayment(token);
    if (route === "booking-confirm") return renderBookingConfirm();
    if (route === "bookings") return renderBookings();
    if (route.startsWith("booking/")) return renderBookingDetail(route.split("/")[1]);
    if (route === "coupons") return renderCoupons(token);
    if (route.startsWith("coupon/")) return renderCouponDetail(route.split("/")[1], token);
    if (route.startsWith("admin")) return renderAdmin(route, token);
    setRoute("home");
  }

  document.addEventListener("click", async (event) => {
    const routeTarget = event.target.closest("[data-route]");
    if (routeTarget && !routeTarget.disabled) {
      event.preventDefault();
      setRoute(routeTarget.dataset.route);
      return;
    }

    const actionTarget = event.target.closest("[data-action]");
    if (!actionTarget || actionTarget.disabled) return;
    const action = actionTarget.dataset.action;

    if (action === "select-fare") {
      const stock = state.campaign?.stocks.find(
        (item) => item.routeId === state.selectedStock?.routeId && item.fareClass === actionTarget.dataset.fare,
      );
      if (stock) {
        state.selectedStock = stock;
        drawCampaignDetail(state.campaign);
        if (state.campaign?.status === "OPEN") startStockUpdates(state.campaign.campaignId);
      }
    }
    if (action === "issue-coupon") await issueCoupon();
    if (action === "select-flight") {
      state.selectedFlight = flights.find((flight) => flight.id === actionTarget.dataset.flight);
      setRoute("booking-info");
    }
    if (action === "confirm-booking") await confirmBooking();
    if (action === "cancel-booking") await cancelBooking(actionTarget.dataset.booking);
    if (action === "search-with-coupon") {
      syncSearchRoute(state.currentCoupon.routeId, state.currentCoupon.fareClass);
      setRoute("flight-search");
    }
    if (action === "run-batch") await runBatch(actionTarget.dataset.job);
    if (action === "refresh-execution") await refreshExecution();
    if (action === "recover-cache") await runCacheOperation("recover");
    if (action === "warmup-cache") await runCacheOperation("warmup");
  });

  document.addEventListener("submit", async (event) => {
    if (event.target.id === "home-search-form") {
      event.preventDefault();
      const form = new FormData(event.target);
      state.search = {
        origin: form.get("origin"),
        destination: form.get("destination"),
        date: form.get("date"),
        fareClass: form.get("fareClass"),
      };
      setRoute("flight-search");
    }

    if (event.target.id === "revoke-form") {
      event.preventDefault();
      const campaignId = Number(new FormData(event.target).get("campaignId"));
      const campaign = state.campaigns?.find((item) => item.campaignId === campaignId);
      const confirmed = await openConfirm({ title: "미사용 쿠폰을 일괄 회수할까요?", message: `${campaign?.name || `캠페인 ${campaignId}`}에서 아직 사용하지 않은 쿠폰을 회수합니다. 발급 가능 수량은 늘어나지 않습니다.`, confirmText: "일괄 회수", danger: true });
      if (!confirmed) return;
      const action = `revoke:${campaignId}`;
      try {
        const result = await request(`/api/admin/campaigns/${campaignId}/coupons/revoke`, { method: "POST", headers: { "Idempotency-Key": idempotencyKeyFor(action) } });
        const target = document.querySelector("#revoke-result");
        if (target) target.innerHTML = `<div class="notice info" style="margin-top:20px">캠페인 ${escapeHtml(result.campaignId)}에서 총 <strong>${Number(result.revokedCount).toLocaleString()}건</strong>을 회수했습니다.</div>`;
        toast("일괄 회수 완료", `${result.revokedCount}건의 쿠폰 상태를 변경했습니다.`);
        finishIdempotentAction(action);
      } catch (error) {
        releaseIdempotencyKeyAfterFailure(action, error);
        toast("일괄 회수 실패", errorMessage(error), "error");
      }
    }
  });

  document.addEventListener("change", (event) => {
    if (event.target.id === "demo-user") {
      state.userId = Number(event.target.value);
      localStorage.setItem("uply.userId", String(state.userId));
      state.selectedCouponId = null;
      toast("회원 변경", `회원 ${state.userId}의 정보로 전환했습니다.`);
      renderRoute();
    }
    if (event.target.id === "campaign-route") {
      state.selectedStock = state.campaign?.stocks.find((stock) => stock.routeId === event.target.value) || null;
      drawCampaignDetail(state.campaign);
      if (state.campaign?.status === "OPEN") startStockUpdates(state.campaign.campaignId);
    }
    if (event.target.name === "coupon") {
      state.selectedCouponId = event.target.value || null;
      const discount = discountAmount();
      const base = state.selectedFlight.prices[state.search.fareClass];
      const discountNode = document.querySelector("#discount-price");
      const finalNode = document.querySelector("#final-price");
      if (discountNode) discountNode.textContent = `-${currency(discount)}`;
      if (finalNode) finalNode.textContent = currency(base - discount);
    }
  });

  window.addEventListener("hashchange", renderRoute);
  if (!window.location.hash) window.location.hash = "#/home";
  else renderRoute();
})();
