#!/usr/bin/env bash
# Creates a loan account through lending-service's REST API (POST /api/v1/loan-accounts).
#
# Usage:
#   BASE_URL=https://lending-service.onrender.com ./scripts/create-loan-account.sh
#
# Optional overrides (environment variables):
#   CUSTOMER_ID      default 1001
#   SANCTION_REF_ID  default: current epoch seconds (unique per run). The API is idempotent on this
#                    value: re-running with the same SANCTION_REF_ID returns the SAME account
#                    instead of creating a second one.
#   PRODUCT_CODE     default PL01 (max 10 chars)
#   AMOUNT           default 500000.00
#   TENURE_MONTHS    default 36
#   RATE             default 10.5000 (annual %, fixed)
set -euo pipefail

: "${BASE_URL:?Set BASE_URL to your lending-service address, e.g. https://lending-service.onrender.com}"
BASE_URL="${BASE_URL%/}"

CUSTOMER_ID="${CUSTOMER_ID:-1001}"
SANCTION_REF_ID="${SANCTION_REF_ID:-$(date +%s)}"
PRODUCT_CODE="${PRODUCT_CODE:-PL01}"
AMOUNT="${AMOUNT:-500000.00}"
TENURE_MONTHS="${TENURE_MONTHS:-36}"
RATE="${RATE:-10.5000}"

PAYLOAD=$(cat <<JSON
{
  "customerId": ${CUSTOMER_ID},
  "sanctionRefId": ${SANCTION_REF_ID},
  "productCode": "${PRODUCT_CODE}",
  "accountType": "UNSECURED",
  "sanctionedAmount": ${AMOUNT},
  "tenureMonths": ${TENURE_MONTHS},
  "rateType": "FIXED",
  "interestRate": ${RATE},
  "dayCountConvention": "ACT_365"
}
JSON
)

echo "POST ${BASE_URL}/api/v1/loan-accounts"
echo "${PAYLOAD}"
echo
echo "(Render free services sleep when idle; the first call can take about a minute.)"

# --retry covers the cold start: connection errors and 502/503 while the service wakes up.
curl --silent --show-error --max-time 150 \
     --retry 4 --retry-delay 15 --retry-all-errors \
     --write-out '\nHTTP %{http_code}\n' \
     --header 'Content-Type: application/json' \
     --data "${PAYLOAD}" \
     "${BASE_URL}/api/v1/loan-accounts"
