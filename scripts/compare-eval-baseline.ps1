<#
.SYNOPSIS
    Captura (-Record) o compara contra la linea base las respuestas HTTP del historial de evaluacion (LAB-130, FR-026).
#>
[CmdletBinding()]
param(
    [switch]$Record,
    [Parameter(Mandatory = $true)][string]$BaseUrl,
    [string]$BaselineDir = "specs/LAB-130-eval-service-extraction/baseline/monolith"
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
New-Item -ItemType Directory -Force -Path $BaselineDir | Out-Null

function Get-NormalizedJson([string]$Path) {
    # Windows PowerShell 5.1 decodes application/json without charset as Latin-1: read the bytes as UTF-8.
    $response = Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl$Path" -Headers @{ Accept = 'application/json' }
    $raw = [System.Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    return ($raw -replace '"trace_url":"[^"]*"', '"trace_url":"<TRACE_URL>"')
}

$endpoints = @(
    @{ File = 'runs-quality.json'; Path = '/api/eval/history/runs?kind=QUALITY' },
    @{ File = 'runs-retrieval.json'; Path = '/api/eval/history/runs?kind=RETRIEVAL' }
)

$idsFile = Join-Path $BaselineDir 'ids.txt'
if ($Record) {
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    foreach ($ep in $endpoints) {
        $target = Join-Path $BaselineDir $ep.File
        [System.IO.File]::WriteAllText($target, (Get-NormalizedJson $ep.Path), $utf8NoBom)
        Write-Output "grabado $target"
    }
    if (Test-Path $idsFile) {
        foreach ($line in Get-Content $idsFile) {
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            $parts = $line -split '\|'
            $id1 = $parts[1]; $id2 = $parts[2]; $metric = $parts[3]
            [System.IO.File]::WriteAllText((Join-Path $BaselineDir "detail-$id1.json"),
                (Get-NormalizedJson "/api/eval/history/runs/$id1"), $utf8NoBom)
            if ($id2 -and $metric) {
                [System.IO.File]::WriteAllText((Join-Path $BaselineDir "compare-$id1-$id2.json"),
                    (Get-NormalizedJson "/api/eval/history/compare?base=$id1&target=$id2&metric=$metric"), $utf8NoBom)
            }
        }
    }
    exit 0
}

$failed = $false
function Test-Endpoint([string]$File, [string]$Path) {
    $expectedPath = Join-Path $BaselineDir $File
    if (-not (Test-Path $expectedPath)) { throw "Falta $expectedPath" }
    $expected = [System.IO.File]::ReadAllText($expectedPath, [System.Text.Encoding]::UTF8)
    $actual = Get-NormalizedJson $Path
    if ($actual -ceq $expected) {
        Write-Output ("IGUAL     {0,-32} {1}" -f $File, $Path)
    } else {
        Write-Error ("DISTINTO  {0,-32} {1}" -f $File, $Path) -ErrorAction Continue
        $script:failed = $true
    }
}

foreach ($ep in $endpoints) { Test-Endpoint $ep.File $ep.Path }
if (Test-Path $idsFile) {
    foreach ($line in Get-Content $idsFile) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $parts = $line -split '\|'
        $id1 = $parts[1]; $id2 = $parts[2]; $metric = $parts[3]
        Test-Endpoint "detail-$id1.json" "/api/eval/history/runs/$id1"
        if ($id2 -and $metric) {
            Test-Endpoint "compare-$id1-$id2.json" "/api/eval/history/compare?base=$id1&target=$id2&metric=$metric"
        }
    }
}

if ($failed) { exit 1 }
Write-Output "0 diferencias frente a la linea base"
