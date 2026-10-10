# Creates a loan account through lending-service's REST API (POST /api/v1/loan-accounts).
#
# Usage (PowerShell):
#   .\scripts\create-loan-account.ps1 -BaseUrl https://lending-service.onrender.com
#
# SanctionRefId defaults to the current epoch seconds (unique per run). The API is idempotent on
# it: re-running with the same -SanctionRefId returns the SAME account instead of a second one.
param(
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [long]$CustomerId = 1001,
    [long]$SanctionRefId = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds(),
    [string]$ProductCode = "PL01",
    [decimal]$Amount = 500000.00,
    [int]$TenureMonths = 36,
    [decimal]$Rate = 10.5
)

$uri = ($BaseUrl.TrimEnd('/')) + "/api/v1/loan-accounts"
$body = @{
    customerId         = $CustomerId
    sanctionRefId      = $SanctionRefId
    productCode        = $ProductCode
    accountType        = "UNSECURED"
    sanctionedAmount   = $Amount
    tenureMonths       = $TenureMonths
    rateType           = "FIXED"
    interestRate       = $Rate
    dayCountConvention = "ACT_365"
} | ConvertTo-Json

Write-Host "POST $uri"
Write-Host $body
Write-Host "(Render free services sleep when idle; the first call can take about a minute.)"

for ($attempt = 1; $attempt -le 5; $attempt++) {
    try {
        $response = Invoke-RestMethod -Method Post -Uri $uri -ContentType "application/json" -Body $body -TimeoutSec 150
        $response | ConvertTo-Json -Depth 5
        break
    } catch {
        $status = $_.Exception.Response.StatusCode.value__
        # 4xx other than 408/429 means the request itself was rejected: show it and stop.
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
