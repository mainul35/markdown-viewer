# Copies the chart library in from its own repository.
#
# mdchart lives next door, in ../mdchart, and is the source of truth for both the chart
# renderer and its stylesheet. MDViewer needs its own copy on the classpath - a jar
# cannot read a sibling working directory - so the two files are vendored in here rather
# than referenced. This script is what keeps the copy honest.
#
# Run it after changing anything in ../mdchart, then rebuild:
#
#   ./sync-mdchart.ps1
#   ./package.ps1
#
# Pass -Check to compare without copying, which is what a build or a hook would use.

param([switch]$Check)

$ErrorActionPreference = "Stop"

$library = Join-Path $PSScriptRoot "..\mdchart"
$pairs = @(
    @{ From = "src\mdchart.js";  To = "src\main\resources\js\mdchart.js" },
    @{ From = "src\mdchart.css"; To = "src\main\resources\css\mdchart.css" }
)

if (-not (Test-Path $library)) {
    Write-Host "mdchart not found at $library" -ForegroundColor Red
    Write-Host "Clone it beside this repository: git clone https://github.com/mainul35/mdchart"
    exit 1
}

$drifted = $false
foreach ($pair in $pairs) {
    $source = Join-Path $library $pair.From
    $target = Join-Path $PSScriptRoot $pair.To

    if (-not (Test-Path $source)) {
        Write-Host "missing in library: $($pair.From)" -ForegroundColor Red
        exit 1
    }

    $sourceHash = (Get-FileHash $source -Algorithm SHA256).Hash
    $targetHash = if (Test-Path $target) { (Get-FileHash $target -Algorithm SHA256).Hash } else { "" }

    if ($sourceHash -eq $targetHash) {
        Write-Host "up to date  $($pair.To)"
        continue
    }

    $drifted = $true
    if ($Check) {
        Write-Host "OUT OF DATE $($pair.To)" -ForegroundColor Yellow
    } else {
        New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
        Copy-Item $source $target -Force
        Write-Host "copied      $($pair.To)" -ForegroundColor Green
    }
}

if ($Check -and $drifted) {
    Write-Host ""
    Write-Host "The vendored chart library differs from ../mdchart. Run ./sync-mdchart.ps1" -ForegroundColor Yellow
    exit 2
}

if (-not $Check -and $drifted) {
    Write-Host ""
    Write-Host "Rebuild to pick these up: ./package.ps1"
}
