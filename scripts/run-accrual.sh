#!/usr/bin/env bash
# Runs the daily interest accrual for ALL active accounts through lending-service
# (POST /api/v1/interest-accruals/run?businessDate=YYYY-MM-DD).
#
# Usage:
#   BASE_URL=https://lending-service-5tqm.onrender.com ./scripts/run-accrual.sh
#   BASE_URL=https://... BUSINESS_DATE=2026-10-09 CHECK_ACCOUNT_ID=1 ./scripts/run-accrual.sh
#
# BUSINESS_DATE defaults to today in India (IST). Idempotent per (account, date).
# Only ACTIVE accounts accrue - PENDING_DISBURSEMENT accounts are skipped.
set -euo pipefail
: "${BASE_URL:?Set BASE_URL, e.g. https://lending-service-5tqm.onrender.com}"
BASE_URL="${BASE_URL%/}"
BUSINESS_DATE="${BUSINESS_DATE:-$(TZ=Asia/Kolkata date +%F)}"
URI="$BASE_URL/api/v1/interest-accruals/run?businessDate=$BUSINESS_DATE"

echo "POST $URI"
echo "(Render free services sleep when idle; the first call can take about a minute.)"
for attempt in 1 2 3 4 5; do
  code=$(curl -sS -m 170 -o /tmp/accrual.out -w '%{http_code}' -X POST "$URI" || echo 000)
  if [[ "$code" =~ ^2 ]]; then cat /tmp/accrual.out; echo; break; fi
  if [[ "$code" =~ ^4 && "$code" != 408 && "$code" != 429 ]]; then echo "HTTP $code"; cat /tmp/accrual.out; echo; exit 1; fi
  [[ $attempt -eq 5 ]] && { echo "HTTP $code - giving up"; exit 1; }
  echo "Attempt $attempt got HTTP $code; service may be waking up, retrying in 15s..."; sleep 15
done

if [[ -n "${CHECK_ACCOUNT_ID:-}" ]]; then
  echo; echo "Spot check: accrual for account $CHECK_ACCOUNT_ID on $BUSINESS_DATE"
  curl -sS "$BASE_URL/api/v1/loan-accounts/$CHECK_ACCOUNT_ID/interest-accruals/$BUSINESS_DATE"; echo
fi
