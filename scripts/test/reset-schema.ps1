$ErrorActionPreference = "Stop"

$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
Push-Location $ProjectRoot

try {
    Write-Host "WARNING: This will DROP and recreate every table in coupon_db."
    Write-Host "All local coupon, history, campaign, stock, user, and verification data will be deleted."
    $confirmation = Read-Host "Type RESET to continue"

    if ($confirmation -ne "RESET") {
        Write-Host "Cancelled. No data was changed."
        exit 1
    }

    docker compose up -d mysql
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to start the MySQL container."
    }

    $mysqlReady = $false
    for ($attempt = 1; $attempt -le 30; $attempt++) {
        docker exec coupon-mysql mysqladmin ping -uroot -proot1234 --silent 2>$null | Out-Null

        if ($LASTEXITCODE -eq 0) {
            $mysqlReady = $true
            break
        }

        Write-Host "Waiting for MySQL..."
        Start-Sleep -Seconds 2
    }

    if (-not $mysqlReady) {
        throw "MySQL did not become ready within 60 seconds."
    }

    $schemaPath = Join-Path $ProjectRoot "docs/schema.sql"
    $mysqlProcess = Start-Process `
        -FilePath "docker" `
        -ArgumentList @("exec", "-i", "coupon-mysql", "mysql", "-uroot", "-proot1234") `
        -RedirectStandardInput $schemaPath `
        -NoNewWindow `
        -Wait `
        -PassThru

    if ($mysqlProcess.ExitCode -ne 0) {
        throw "Schema reset failed with exit code $($mysqlProcess.ExitCode)."
    }

    Write-Host "Schema reset completed."
}
finally {
    Pop-Location
}
