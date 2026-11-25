<#
.SYNOPSIS
    Compara los datos de retrieval en las dos bases, contra el PostgreSQL del compose (LAB-129). Sale con 0 si coinciden.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$container = $env:PG_CONTAINER
if (-not $container) { $container = docker compose -f "$root/infra/docker-compose.yml" ps -q postgres }
if (-not $container) { Write-Error "el servicio postgres del compose no esta arriba" }

$source = (Get-Content -Raw -Encoding UTF8 "$root/infra/postgres/retrieval-verify.sh") -replace "`r", ""
$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($source))
docker exec $container bash -c "echo $encoded | base64 -d | bash"
exit $LASTEXITCODE
