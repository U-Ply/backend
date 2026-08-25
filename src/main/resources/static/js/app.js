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
    batchPollTimer: null,
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
    if (state.batchPollTimer) window.clearInterval(state.batchPollTimer);
    state.batchPollTimer = null;
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
    if (error instanceof ApiError) return `[${error.errorCode}] ${error.message}`;
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
      apiNotice = `<div class="notice">캠페인 API에 연결하지 못했습니다. 서버와 시드 데이터를 확인해 주세요. ${escapeHtml(errorMessage(error))}</div>`;
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
        ${previewMode ? '<div class="notice info">현재 화면은 <strong>preview=1</strong> 미리보기 데이터로 표시 중입니다. 실제 발급은 실행되지 않습니다.</div>' : ""}
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
          ${previewMode ? '<div class="notice info">프론트엔드 미리보기용 캠페인 데이터입니다.</div>' : ""}
          ${campaigns.length ? `<div class="campaign-list">${campaigns.map((campaign) => campaignCard(campaign)).join("")}</div>` : '<div class="empty-state"><div class="empty-icon">✈</div><h2>등록된 캠페인이 없습니다</h2><p>캠페인 시드 데이터를 확인해 주세요.</p></div>'}
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
      if (campaign.status === "OPEN" && state.selectedStock) {
        state.pollTimer = window.setInterval(() => refreshStock(campaign.campaignId), 1000);
      }
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
                    <div class="stock-board-head"><span>발급 현황</span><span class="live-dot">1초 폴링 중</span></div>
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

  async function refreshStock(campaignId) {
    const route = currentRoute();
    if (route !== `campaign/${campaignId}` || !state.selectedStock) return clearPoller();
    try {
      const status = await getCampaignStatus(campaignId, state.selectedStock);
      state.selectedStock = { ...state.selectedStock, ...status };
      const remaining = document.querySelector("#remaining-stock");
      const progress = document.querySelector("#stock-progress");
      const caption = document.querySelector("#stock-caption");
      const issueButton = document.querySelector('[data-action="issue-coupon"]');
      if (!remaining || !progress || !caption) return;
      const rate = Math.round(((status.totalStock - status.remainingStock) / status.totalStock) * 100);
      remaining.innerHTML = `${status.remainingStock.toLocaleString()}<small> 장</small>`;
      progress.style.width = `${rate}%`;
      caption.textContent = `${rate}% 발급 완료`;
      if (issueButton) {
        const soldOut = status.remainingStock <= 0;
        issueButton.disabled = soldOut || state.campaign?.status !== "OPEN";
        issueButton.textContent = soldOut ? "쿠폰 소진" : "얼리버드 쿠폰 받기";
      }
    } catch (error) {
      clearPoller();
      toast("재고 갱신 중단", errorMessage(error), "error");
    }
  }

  async function issueCoupon() {
    if (!state.campaign || !state.selectedStock) return;
    if (previewMode) {
      toast("미리보기 모드", "실제 발급은 서버 실행 후 이용해 주세요.", "error");
      return;
    }
    const confirmed = await openConfirm({
      title: "얼리버드 쿠폰을 발급할까요?",
      message: `${routeLabel(state.selectedStock.routeId)} · ${fareLabel(state.selectedStock.fareClass)} 쿠폰을 사용자 ${state.userId}에게 발급합니다.`,
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
            <article class="card"><h2 class="card-title">탑승객 정보</h2><div class="detail-grid"><div class="detail-item"><span>시연 사용자</span><strong>USER ${state.userId}</strong></div><div class="detail-item"><span>탑승객 유형</span><strong>성인 1명</strong></div></div></article>
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
        <header class="page-header"><div><p class="page-kicker">Payment</p><h1 class="page-title">결제 및 쿠폰 선택</h1><p class="page-description">예약을 확정할 때 선택한 쿠폰이 USED 상태로 변경됩니다.</p></div></header>
        <div class="summary-layout">
          <div>
            <article class="card"><h2 class="card-title">할인 쿠폰</h2>
              ${couponError ? `<div class="notice">쿠폰을 불러오지 못했습니다. ${escapeHtml(couponError)}</div>` : ""}
              <label class="coupon-option"><input type="radio" name="coupon" value="" checked /><span><strong>쿠폰을 사용하지 않음</strong><span>일반 운임으로 예약합니다.</span></span></label>
              ${coupons.map((coupon) => `<label class="coupon-option"><input type="radio" name="coupon" value="${escapeHtml(coupon.couponId)}" /><span><strong>얼리버드 쿠폰 · ${routeLabel(coupon.routeId)}</strong><span>${fareLabel(coupon.fareClass)} · ${formatDate(coupon.expireAt)}까지</span></span></label>`).join("")}
              ${!coupons.length ? '<div class="notice info" style="margin-top:14px">현재 항공편에 사용할 수 있는 ISSUED 쿠폰이 없습니다. 쿠폰 없이도 Mock 예약은 진행할 수 있습니다.</div>' : ""}
            </article>
            <article class="card"><h2 class="card-title">결제 수단</h2><div class="notice info">결제와 외부 PG 연동은 Mock 처리됩니다. 실제 결제는 발생하지 않습니다.</div><label class="coupon-option"><input type="radio" checked /><span><strong>U-Ply 테스트 카드</strong><span>•••• •••• •••• 0829</span></span></label></article>
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
        : "쿠폰 없이 Mock 항공권 예약을 확정합니다.",
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
      app.innerHTML = errorView("예약을 찾을 수 없습니다", new Error("브라우저에 저장된 Mock 예약 정보가 없습니다."), "bookings");
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
        ? "예약에 사용한 쿠폰은 CANCELLED 상태가 되며 재고와 쿠폰은 복구되지 않습니다."
        : "Mock 예약을 취소합니다. 실제 결제 취소는 발생하지 않습니다.",
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
      toast("예약 취소 완료", "쿠폰과 예약이 취소됐습니다. 재고는 복구되지 않습니다.");
      renderBookingDetail(bookingId);
    } catch (error) {
      if (action) releaseIdempotencyKeyAfterFailure(action, error);
      toast("예약 취소 실패", errorMessage(error), "error");
    }
  }

  async function renderCoupons(token) {
    if (previewMode) {
      app.innerHTML = `<section class="page-shell"><header class="page-header"><div><p class="page-kicker">My coupons</p><h1 class="page-title">내 쿠폰</h1></div></header><div class="empty-state"><div class="empty-icon">%</div><h2>미리보기 모드입니다</h2><p>실제 쿠폰 목록은 Spring Boot API 실행 후 확인할 수 있습니다.</p></div></section>`;
      return;
    }
    try {
      const response = await request(`/api/users/${state.userId}/coupons`);
      if (token !== state.renderToken) return;
      const coupons = response.coupons || [];
      app.innerHTML = `
        <section class="page-shell"><header class="page-header"><div><p class="page-kicker">My coupons</p><h1 class="page-title">내 쿠폰</h1><p class="page-description">쿠폰 사용은 항공권 결제 단계에서만 가능합니다.</p></div></header>
          ${coupons.length ? `<div class="coupon-list">${coupons.map((coupon) => `<article class="coupon-row"><div><h3 class="row-title">얼리버드 쿠폰</h3><span class="row-muted">쿠폰 ${escapeHtml(coupon.couponId)} · 캠페인 ${escapeHtml(coupon.campaignId)} · ${formatDate(coupon.issuedAt)} 발급</span></div><div><span class="badge ${escapeHtml(coupon.status)}">${escapeHtml(couponStatusLabel(coupon.status))}</span></div><button class="ghost-button" type="button" data-route="coupon/${escapeHtml(coupon.couponId)}">상세 보기</button></article>`).join("")}</div>` : '<div class="empty-state"><div class="empty-icon">%</div><h2>보유한 쿠폰이 없습니다</h2><p>진행 중인 얼리버드 이벤트를 확인해 보세요.</p><button class="primary-button" type="button" data-route="campaigns">특가 이벤트 보기</button></div>'}
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
        <section class="page-shell narrow"><button class="back-button" type="button" data-route="coupons">← 내 쿠폰</button><article class="card"><div class="page-header"><div><span class="badge ${escapeHtml(coupon.status)}">${escapeHtml(couponStatusLabel(coupon.status))}</span><h1 class="page-title" style="margin-top:12px">얼리버드 할인 쿠폰</h1><p class="page-description">${routeLabel(coupon.routeId)} · ${fareLabel(coupon.fareClass)}</p></div></div><div class="detail-grid"><div class="detail-item"><span>쿠폰 번호</span><strong>${escapeHtml(coupon.couponId)}</strong></div><div class="detail-item"><span>사용자</span><strong>USER ${escapeHtml(coupon.userId)}</strong></div><div class="detail-item"><span>발급 시각</span><strong>${formatDate(coupon.issuedAt)}</strong></div><div class="detail-item"><span>유효기간</span><strong>${formatDate(coupon.expireAt)}</strong></div><div class="detail-item"><span>사용 시각</span><strong>${formatDate(coupon.usedAt)}</strong></div><div class="detail-item"><span>취소 시각</span><strong>${formatDate(coupon.cancelledAt)}</strong></div></div><div class="button-row end" style="margin-top:28px">${coupon.status === "ISSUED" ? '<button class="primary-button" type="button" data-action="search-with-coupon">항공편 검색하기 →</button>' : ""}${coupon.status === "USED" ? '<button class="primary-button" type="button" data-route="bookings">내 예약 보기 →</button>' : ""}</div></article></section>`;
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
    return `<section class="admin-shell"><aside class="admin-sidebar"><h2>U-Ply Admin</h2><p>운영·정합성 관리</p><nav class="admin-menu"><button class="${active === "dashboard" ? "active" : ""}" data-route="admin">대시보드</button><button class="${active === "stocks" ? "active" : ""}" data-route="admin/stocks">캠페인·재고</button><button class="${active === "revoke" ? "active" : ""}" data-route="admin/revoke">미사용 쿠폰 회수</button><button class="${active === "batches" ? "active" : ""}" data-route="admin/batches">배치 실행·상태</button><button class="${active === "verification" ? "active" : ""}" data-route="admin/verification">검증 결과</button><button class="${active === "monitoring" ? "active" : ""}" data-route="admin/monitoring">시스템 모니터링</button></nav></aside><div class="admin-content">${content}</div></section>`;
  }

  async function renderAdmin(route, token) {
    const page = route.split("/")[1] || "dashboard";
    try {
      if (page === "dashboard") return await renderAdminDashboard(token);
      if (page === "stocks") return await renderAdminStocks(token);
      if (page === "revoke") return await renderAdminRevoke(token);
      if (page === "batches") return await renderAdminBatches();
      if (page === "verification") return await renderAdminVerification(token);
      if (page === "verification-run") return await renderVerificationRun(route.split("/")[2], token);
      if (page === "monitoring") return await renderMonitoring();
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
    const failed = runs.filter((run) => Number(run.total_violations) > 0).length;
    app.innerHTML = adminLayout("dashboard", `<header class="page-header"><div><p class="page-kicker">Operations</p><h1 class="page-title">관리자 대시보드</h1><p class="page-description">캠페인 운영과 데이터 정합성을 한곳에서 확인합니다.</p></div></header><div class="metric-grid"><article class="metric-card"><span>전체 캠페인</span><strong>${campaigns.length}</strong></article><article class="metric-card"><span>진행 중 캠페인</span><strong>${open}</strong></article><article class="metric-card"><span>최근 위반 회차</span><strong>${failed}</strong></article><article class="metric-card"><span>발급 전략</span><strong style="font-size:18px">환경 설정값</strong></article><article class="metric-card"><span>Kafka 상태</span><strong style="font-size:18px">모니터링</strong></article><article class="metric-card"><span>Redis–DB 대사</span><strong style="font-size:18px">수동 실행</strong></article></div><div class="admin-action-grid"><button class="admin-action-card" data-route="admin/stocks"><strong>캠페인·재고 현황</strong><span>노선·좌석 등급별 Redis 잔여 재고를 확인합니다.</span></button><button class="admin-action-card" data-route="admin/revoke"><strong>미사용 쿠폰 일괄 회수</strong><span>특정 캠페인의 ISSUED 쿠폰만 회수합니다.</span></button><button class="admin-action-card" data-route="admin/batches"><strong>배치 실행</strong><span>만료·검증·Redis–DB 대사 배치를 실행합니다.</span></button><button class="admin-action-card" data-route="admin/verification"><strong>검증 결과</strong><span>INV·REC 규칙의 위반 건수와 샘플을 확인합니다.</span></button></div>`);
  }

  async function renderAdminStocks(token) {
    const campaigns = await getCampaigns(true);
    const details = await Promise.all(campaigns.map((campaign) => getCampaign(campaign.campaignId).catch(() => ({ ...campaign, stocks: [] }))));
    if (token !== state.renderToken) return;
    const rows = details.flatMap((campaign) => (campaign.stocks || []).map((stock) => ({ campaign, stock })));
    app.innerHTML = adminLayout("stocks", `<header class="page-header"><div><p class="page-kicker">Campaign stock</p><h1 class="page-title">캠페인·재고 현황</h1><p class="page-description">관리자 전체 현황은 화면 진입 시 조회합니다. 실시간 관측은 Grafana를 사용합니다.</p></div><button class="ghost-button" data-route="admin/stocks">새로고침</button></header><div class="table-wrap"><table class="data-table"><thead><tr><th>캠페인</th><th>상태</th><th>노선</th><th>등급</th><th>총재고</th><th>잔여재고</th><th>발급률</th></tr></thead><tbody>${rows.map(({ campaign, stock }) => { const rate = stock.totalStock ? Math.round(((stock.totalStock - stock.remainingStock) / stock.totalStock) * 100) : 0; return `<tr><td>${escapeHtml(campaign.name)}</td><td><span class="badge ${escapeHtml(campaign.status)}">${campaignStatusLabel(campaign.status)}</span></td><td>${escapeHtml(routeLabel(stock.routeId))}</td><td>${fareLabel(stock.fareClass)}</td><td>${stock.totalStock.toLocaleString()}</td><td>${stock.remainingStock.toLocaleString()}</td><td>${rate}%</td></tr>`; }).join("") || '<tr><td colspan="7">표시할 재고가 없습니다.</td></tr>'}</tbody></table></div>`);
  }

  async function renderAdminRevoke(token) {
    const campaigns = await getCampaigns(true);
    if (token !== state.renderToken) return;
    app.innerHTML = adminLayout("revoke", `<header class="page-header"><div><p class="page-kicker">Airline revoke</p><h1 class="page-title">미사용 쿠폰 일괄 회수</h1><p class="page-description">ISSUED 쿠폰만 CANCELLED로 변경하며 재고는 복구하지 않습니다.</p></div></header><article class="card"><div class="notice">이 작업은 사용자 예약 취소와 다릅니다. 이미 사용한 USED 쿠폰은 변경하지 않습니다.</div><form id="revoke-form"><div class="field"><label for="revoke-campaign">대상 캠페인</label><select id="revoke-campaign" name="campaignId">${campaigns.map((campaign) => `<option value="${campaign.campaignId}">${escapeHtml(campaign.name)} · ${campaignStatusLabel(campaign.status)}</option>`).join("")}</select></div><div class="button-row end" style="margin-top:22px"><button class="danger-button" type="submit" ${previewMode ? "disabled" : ""}>미사용 쿠폰 회수</button></div></form><div id="revoke-result"></div></article>`);
  }

  function renderAdminBatches() {
    const execution = state.execution;
    app.innerHTML = adminLayout("batches", `<header class="page-header"><div><p class="page-kicker">Batch operations</p><h1 class="page-title">배치 실행·상태</h1><p class="page-description">배치는 202 Accepted 후 executionId로 진행 상태를 조회합니다.</p></div></header><div class="admin-action-grid"><button class="admin-action-card" data-action="run-batch" data-job="expiration" ${previewMode ? "disabled" : ""}><strong>쿠폰 만료 배치</strong><span>기한이 지난 ISSUED 쿠폰을 EXPIRED로 변경합니다.</span></button><button class="admin-action-card" data-action="run-batch" data-job="verification" ${previewMode ? "disabled" : ""}><strong>데이터 정합성 검증</strong><span>INV 규칙을 실행하고 회차별 검증 결과를 저장합니다.</span></button><button class="admin-action-card" data-action="run-batch" data-job="reconcile" ${previewMode ? "disabled" : ""}><strong>Redis–DB 재고 대사</strong><span>불일치를 탐지·기록하며 자동 교정하지 않습니다.</span></button></div>${execution ? `<article class="card" style="margin-top:18px"><div class="job-status"><div><span class="badge ${escapeHtml(execution.status)}" id="execution-status">${escapeHtml(execution.status)}</span><h2 class="card-title" style="margin:12px 0 4px">${escapeHtml(execution.job || execution.jobName)}</h2><p class="row-muted">executionId ${escapeHtml(execution.jobExecutionId)} · runId ${escapeHtml(execution.runId)}</p></div><button class="ghost-button" data-action="refresh-execution">상태 새로고침</button></div><div id="execution-detail"></div></article>` : ""}`);
  }

  async function runBatch(job) {
    let query = "";
    if (job === "verification") {
      const round = window.prompt("검증 회차를 입력해 주세요. (V0, V1, V2, V3)", "V3");
      if (!round) return;
      query = `?round=${encodeURIComponent(round.toUpperCase())}&failOnViolation=false`;
    }
    try {
      state.execution = await request(`/api/admin/batch/${job}${query}`, { method: "POST" });
      renderAdminBatches();
      toast("배치 접수 완료", `executionId ${state.execution.jobExecutionId}`);
      watchExecution();
    } catch (error) {
      toast("배치 실행 실패", errorMessage(error), "error");
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
        status.textContent = detail.status;
      }
      if (target) target.innerHTML = `<div class="detail-grid" style="margin-top:22px"><div class="detail-item"><span>시작</span><strong>${escapeHtml(detail.startTime)}</strong></div><div class="detail-item"><span>종료</span><strong>${escapeHtml(detail.endTime)}</strong></div><div class="detail-item"><span>Exit Code</span><strong>${escapeHtml(detail.exitCode)}</strong></div><div class="detail-item"><span>실패 원인</span><strong>${escapeHtml((detail.failures || []).join(", ") || "없음")}</strong></div></div>`;
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
        toast("배치 실행 종료", `최종 상태: ${status}`, status === "COMPLETED" ? "success" : "error");
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
      app.innerHTML = adminLayout("verification", '<header class="page-header"><div><p class="page-kicker">Verification</p><h1 class="page-title">검증 결과</h1></div></header><div class="empty-state"><h2>미리보기 모드</h2><p>실제 검증 결과는 백엔드 실행 후 표시됩니다.</p></div>');
      return;
    }
    const runs = await request("/api/admin/batch/verification/runs?limit=20");
    if (token !== state.renderToken) return;
    app.innerHTML = adminLayout("verification", `<header class="page-header"><div><p class="page-kicker">Verification</p><h1 class="page-title">검증 결과</h1><p class="page-description">회차를 선택하면 규칙별 결과와 위반 샘플을 확인할 수 있습니다.</p></div></header><div class="table-wrap"><table class="data-table"><thead><tr><th>runId</th><th>회차</th><th>기준 시각</th><th>전체 위반</th><th>실패 규칙</th><th>판정</th></tr></thead><tbody>${runs.map((run) => { const verdict = run.verdict || (Number(run.total_violations) > 0 ? "FAILED" : "PASSED"); return `<tr class="clickable" data-route="admin/verification-run/${encodeURIComponent(run.run_id)}"><td>${escapeHtml(run.run_id)}</td><td>${escapeHtml(run.round || "-")}</td><td>${formatDate(run.snapshot_at)}</td><td>${Number(run.total_violations || 0).toLocaleString()}</td><td>${Number(run.failed_rules || 0).toLocaleString()}</td><td><span class="badge ${escapeHtml(verdict)}">${escapeHtml(verdict)}</span></td></tr>`; }).join("") || '<tr><td colspan="6">검증 회차가 없습니다.</td></tr>'}</tbody></table></div>`);
  }

  async function renderVerificationRun(runId, token) {
    const decoded = decodeURIComponent(runId);
    const [rules, violations] = await Promise.all([request(`/api/admin/batch/verification/runs/${encodeURIComponent(decoded)}`), request(`/api/admin/batch/verification/runs/${encodeURIComponent(decoded)}/violations?limit=100`)]);
    if (token !== state.renderToken) return;
    app.innerHTML = adminLayout("verification", `<button class="back-button" data-route="admin/verification">← 검증 회차 목록</button><header class="page-header"><div><p class="page-kicker">Verification detail</p><h1 class="page-title">${escapeHtml(decoded)}</h1><p class="page-description">규칙별 판정과 위반 샘플입니다.</p></div></header><div class="table-wrap"><table class="data-table"><thead><tr><th>규칙</th><th>이름</th><th>상태</th><th>검사 행</th><th>위반</th><th>소요</th></tr></thead><tbody>${rules.map((rule) => { const status = rule.status || (Boolean(rule.passed) ? "CHECKED" : "FAILED"); return `<tr><td>${escapeHtml(rule.rule_code)}</td><td>${escapeHtml(rule.rule_name)}</td><td><span class="badge ${escapeHtml(status)}">${escapeHtml(status)}</span></td><td>${rule.checked_rows == null ? "-" : Number(rule.checked_rows).toLocaleString()}</td><td>${Number(rule.violation_count || 0).toLocaleString()}</td><td>${Number(rule.elapsed_ms || 0).toLocaleString()} ms</td></tr>`; }).join("")}</tbody></table></div><h2 class="card-title" style="margin-top:30px">위반 샘플</h2><div class="table-wrap"><table class="data-table"><thead><tr><th>규칙</th><th>테이블</th><th>대상 ID</th><th>상세</th></tr></thead><tbody>${violations.map((item) => `<tr><td>${escapeHtml(item.rule_code)}</td><td>${escapeHtml(item.target_table)}</td><td>${escapeHtml(item.target_id)}</td><td>${escapeHtml(item.detail)}</td></tr>`).join("") || '<tr><td colspan="4">위반 없음</td></tr>'}</tbody></table></div>`);
  }

  function renderMonitoring() {
    app.innerHTML = adminLayout("monitoring", `<header class="page-header"><div><p class="page-kicker">Observability</p><h1 class="page-title">시스템 모니터링</h1><p class="page-description">운영 지표는 전용 관측 도구에서 확인합니다.</p></div></header><div class="monitor-grid"><article class="monitor-card"><h3>Grafana Dashboard</h3><p>API 지연, JVM, HikariCP, Redis, Kafka, MySQL 및 컨테이너 자원을 한눈에 확인합니다.</p><a class="primary-button" href="http://localhost:3000" target="_blank" rel="noopener">Grafana 열기 ↗</a></article><article class="monitor-card"><h3>Prometheus</h3><p>Micrometer와 인프라 Exporter가 수집한 원시 시계열을 조회합니다.</p><a class="secondary-button" href="http://localhost:9090" target="_blank" rel="noopener">Prometheus 열기 ↗</a></article><article class="monitor-card"><h3>Spring Actuator</h3><p>애플리케이션 헬스와 Prometheus 노출 지표를 확인합니다.</p><a class="ghost-button" href="/actuator/health" target="_blank" rel="noopener">Health 확인 ↗</a></article><article class="monitor-card"><h3>Kafka Consumer Lag</h3><p>V3 부하 테스트 종료 판정은 lag 0과 DLT 0을 함께 확인합니다.</p><button class="ghost-button" data-route="admin/batches">대사 배치로 이동</button></article></div>`);
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
      const confirmed = await openConfirm({ title: "미사용 쿠폰을 일괄 회수할까요?", message: `${campaign?.name || `캠페인 ${campaignId}`}의 ISSUED 쿠폰을 모두 CANCELLED로 변경합니다. 재고는 복구되지 않습니다.`, confirmText: "일괄 회수", danger: true });
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
      toast("시연 사용자 변경", `사용자 ${state.userId}의 데이터로 전환했습니다.`);
      renderRoute();
    }
    if (event.target.id === "campaign-route") {
      state.selectedStock = state.campaign?.stocks.find((stock) => stock.routeId === event.target.value) || null;
      drawCampaignDetail(state.campaign);
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
