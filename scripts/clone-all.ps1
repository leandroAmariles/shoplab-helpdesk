<#
.SYNOPSIS
    Clona todos los repositorios hermanos del ecosistema ShopLab Helpdesk en carpetas adyacentes (LAB-110).
.PARAMETER GitBaseUrl
    URL base de la organización o usuario en GitHub (defecto: https://github.com/leandroAmariles).
#>
[CmdletBinding()]
param(
    [string]$GitBaseUrl = "https://github.com/leandroAmariles"
)

$ErrorActionPreference = 'Stop'

$repos = @(
    "shoplab-gateway",
    "shoplab-chat-service",
    "shoplab-retrieval-service",
    "shoplab-eval-service",
    "shoplab-catalog-service",
    "shoplab-frontend",
    "shoplab-ingest",
    "shoplab-eval",
    "shoplab-contracts",
    "shoplab-ci",
    "shoplab-infra"
)

$parentDir = Resolve-Path (Join-Path $PSScriptRoot "..") | Split-Path -Parent

Write-Host "==> Verificando y clonando repositorios hermanos en: $parentDir" -ForegroundColor Cyan

foreach ($repo in $repos) {
    $targetPath = Join-Path $parentDir $repo
    if (Test-Path $targetPath) {
        Write-Host "  [SKIP] $repo ya existe en $targetPath" -ForegroundColor Yellow
    } else {
        $repoUrl = "$GitBaseUrl/$repo.git"
        Write-Host "  [CLONING] $repo desde $repoUrl..." -ForegroundColor Green
        git clone $repoUrl $targetPath
        if ($LASTEXITCODE -eq 0) {
            Write-Host "  [CLONED] $repo clonado exitosamente." -ForegroundColor Green
        } else {
            Write-Error "Fallo al clonar $repo"
        }
    }
}

Write-Host "==> Clonacion completada exitosamente." -ForegroundColor Cyan
