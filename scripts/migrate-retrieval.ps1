<#
.SYNOPSIS
    Copia los datos de retrieval a su base propia, contra el PostgreSQL del compose (LAB-129).
.PARAMETER Replace
    Borra el esquema destino y vuelve a copiar. Solo antes del corte.
#>
[CmdletBinding()]
param([switch]$Replace)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$container = $env:PG_CONTAINER
if (-not $container) { $container = docker compose -f "$root/infra/docker-compose.yml" ps -q postgres }
if (-not $container) { Write-Error "el servicio postgres del compose no esta arriba" }

# En base64 para que ni los saltos de linea de Windows ni la codificacion de la consola alteren el script.
$source = (Get-Content -Raw -Encoding UTF8 "$root/infra/postgres/retrieval-migrate.sh") -replace "`r", ""
$encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($source))
$option = if ($Replace) { "--replace" } else { "" }
docker exec $container bash -c "echo $encoded | base64 -d | bash -s -- $option"
exit $LASTEXITCODE
