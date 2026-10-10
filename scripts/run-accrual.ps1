# Runs the daily interest accrual for ALL active accounts through lending-service
# (POST /api/v1/interest-accruals/run?businessDate=YYYY-MM-DD).
#
# Usage (PowerShell):
#   .\scripts\run-accrual.ps1 -BaseUrl https://lending-service-5tqm.onrender.com
#   .\scripts\run-accrual.ps1 -BaseUrl https://... -BusinessDate 2026-10-09 -CheckAccountId 1
#
# -BusinessDate defaults to today in India (IST). The run is idempotent per (account, date):
# running it twice for the same date does not create duplicate accruals.
# Only ACTIVE accounts accrue - accounts still PENDING_DISBURSEMENT are skipped.
param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [string]$BusinessDate = ([DateTime]::UtcNow.AddHours(5.5).ToString("yyyy-MM-dd")),
    [long]$CheckAccountId = 0   # optional: after the run, fetch this account's accrual as a spot check
)

$base = $BaseUrl.TrimEnd('/')
$uri = "$base/api/v1/interest-accruals/run?businessDate=$BusinessDate"

Write-Host "POST $uri"
Write-Host "(Render free services sleep when idle; the first call can take about a minute.)"

for ($attempt = 1; $attempt -le 5; $attempt++) {
    try {
        $response = Invoke-RestMethod -Method Post -Uri $uri -TimeoutSec 170
        $response | ConvertTo-Json -Depth 5
        break
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        if ($status -and $status -ge 400 -and $status -lt 500 -and $status -ne 408 -and $status -ne 429) {
            Write-Host "HTTP $status"
            Write-Host $_.ErrorDetails.Message
            exit 1
        }
        if ($attempt -eq 5) { throw }
        Write-Host "Attempt $attempt failed ($($_.Exception.Message)); service may be waking up, retrying in 15s..."
        Start-Sleep -Seconds 15
    }
}

if ($CheckAccountId -gt 0) {
    Write-Host ""
    Write-Host "Spot check: accrual for account $CheckAccountId on $BusinessDate"
    try {
        Invoke-RestMethod -Method Get -Uri "$base/api/v1/loan-accounts/$CheckAccountId/interest-accruals/$BusinessDate" |
            ConvertTo-Json -Depth 5
    } catch {
        Write-Host "HTTP $($_.Exception.Response.StatusCode.value__) - no accrual (account may not be ACTIVE yet)"
    }
}
