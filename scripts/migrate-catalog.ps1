# Copia el catalogo a la base de catalog-service (LAB-113). Uso: .\scripts\migrate-catalog.ps1 [--replace]
bash "$PSScriptRoot/migrate-catalog.sh" @args
exit $LASTEXITCODE
