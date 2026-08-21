#!/usr/bin/env bash
set -u

BASE="${BASE:-http://localhost:8081}"
CAMPAIGN_ID="${CAMPAIGN_ID:-31}"
ROUTE_ID="${ROUTE_ID:-JEJU}"
FARE_CLASS="${FARE_CLASS:-ECONOMY}"
COUNT="${COUNT:-100}"
OUT="${OUT:-round.txt}"

uuid4() {
  printf '%04x%04x-%04x-4%03x-%04x-%04x%04x%04x' \
    $((RANDOM%65536)) $((RANDOM%65536)) $((RANDOM%65536)) \
    $((RANDOM%4096))  $(((RANDOM%16384)+32768)) \
    $((RANDOM%65536)) $((RANDOM%65536)) $((RANDOM%65536))
}

: > "$OUT"
for i in $(seq 1 "$COUNT"); do
  (
    key=$(uuid4)
    body=$(printf '{"userId":%d,"campaignId":%d,"routeId":"%s","fareClass":"%s"}' \
      "$i" "$CAMPAIGN_ID" "$ROUTE_ID" "$FARE_CLASS")
    resp=$(curl -s -w '\n%{http_code}' -X POST "$BASE/api/coupons/issue" \
      -H 'Content-Type: application/json' \
      -H "Idempotency-Key: $key" \
      -d "$body")
    code=$(printf '%s' "$resp" | tail -n1)
    payload=$(printf '%s' "$resp" | sed '$d')
    reason=$(printf '%s' "$payload" | grep -o '"errorCode":"[A-Z_]*"' | head -1 | cut -d'"' -f4)
    echo "${code} ${reason:-OK}" >> "$OUT"
  ) &
done
wait 2>/dev/null

echo "--- result ---"
sort "$OUT" | uniq -c | sort -rn
