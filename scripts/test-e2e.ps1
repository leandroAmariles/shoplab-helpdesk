<#
.SYNOPSIS
    Smoke test e integración E2E de salud del sistema local (LAB-110).
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'
$failed = $false

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  ShopLab Helpdesk - Verificación E2E del Sistema Local   " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

function Test-ServiceEndpoint {
    param(
        [string]$Name,
        [string]$Url,
        [string]$ExpectedPattern
    )

    Write-Host -NoNewline ("Verificando {0,-20} ({1})... " -f $Name, $Url)
    try {
        $response = Invoke-RestMethod -Uri $Url -Method Get -TimeoutSec 5 -ErrorAction Stop
        $jsonStr = $response | ConvertTo-Json -Compress -Depth 2
        if ($jsonStr -match $ExpectedPattern -or $response -match $ExpectedPattern) {
            Write-Host "OK" -ForegroundColor Green
        } else {
            Write-Host "FALLO (Respuesta inesperada: $jsonStr)" -ForegroundColor Red
            $script:failed = $true
        }
    } catch {
        # Para frontend HTML estático
        try {
            $webResp = Invoke-WebRequest -Uri $Url -Method Get -TimeoutSec 5 -UseBasicParsing -ErrorAction Stop
            if ($webResp.Content -match $ExpectedPattern) {
                Write-Host "OK" -ForegroundColor Green
                return
            }
        } catch {}
        Write-Host "FALLO ($($_.Exception.Message))" -ForegroundColor Red
        $script:failed = $true
    }
}

Test-ServiceEndpoint -Name "LiteLLM Gateway" -Url "http://localhost:4000/health/liveliness" -ExpectedPattern "healthy|ok|true|alive"
Test-ServiceEndpoint -Name "Ingest Service" -Url "http://localhost:8000/health" -ExpectedPattern "ok|healthy"
Test-ServiceEndpoint -Name "Chat Health" -Url "http://localhost:8083/actuator/health" -ExpectedPattern "UP"
Test-ServiceEndpoint -Name "Eval Health" -Url "http://localhost:8084/actuator/health" -ExpectedPattern "UP"
Test-ServiceEndpoint -Name "Retrieval Health" -Url "http://localhost:8082/actuator/health" -ExpectedPattern "UP"
# Gateway (LAB-107): su salud y una lectura de la API a través de él
Test-ServiceEndpoint -Name "Gateway Health" -Url "http://localhost:8080/actuator/health" -ExpectedPattern "UP"
Test-ServiceEndpoint -Name "API por el gateway" -Url "http://localhost:8080/api/documents" -ExpectedPattern "\[|\{"
Test-ServiceEndpoint -Name "Frontend Web" -Url "http://localhost:4200" -ExpectedPattern "<!doctype html>|<app-root>|html"

Write-Host "==========================================================" -ForegroundColor Cyan
if (-not $failed) {
    Write-Host "  RESULTADO: Todos los servicios están saludables (OK)    " -ForegroundColor Green
    Write-Host "==========================================================" -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "  RESULTADO: Uno o más servicios reportaron fallo         " -ForegroundColor Red
    Write-Host "==========================================================" -ForegroundColor Cyan
    exit 1
}
