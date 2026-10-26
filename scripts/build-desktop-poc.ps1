# Builds the Windows desktop PoC as a self-contained app image (no installer, no auto-update).
# Output: dist\IllustrationArchivePoC\IllustrationArchivePoC.exe plus a zip of that folder.
# Tests are not run here; run them separately (see docs/DESKTOP_POC.md).
param(
    # A JDK 17+ that contains jpackage; its runtime is bundled into the app image.
    [string]$JavaHome = $env:JAVA_HOME,
    # Extra Maven arguments, e.g. '-Dmaven.repo.local=C:\Users\me\.m2\repository'.
    [string[]]$MavenArgs = @()
)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$jpackage = Join-Path $JavaHome 'bin\jpackage.exe'
if (-not (Test-Path $jpackage)) {
    throw "jpackage not found under '$JavaHome'. Pass -JavaHome with a JDK 17+ directory."
}

Push-Location $root
try {
    # A running PoC locks files in the image; deleting around them would leave a half-removed app.
    $imagePrefix = Join-Path $root 'dist\IllustrationArchivePoC\'
    $running = Get-Process -Name IllustrationArchivePoC -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and $_.Path.StartsWith($imagePrefix, [StringComparison]::OrdinalIgnoreCase) }
    if ($running) {
        throw "The PoC is running from dist (PID $($running.Id -join ', ')). Exit it from its control window, then rebuild."
    }

    $env:JAVA_HOME = $JavaHome
    # Not 'mvn clean': a locally running instance may hold files under target\. Removing compiled classes
    # and old jars is enough to keep stale classes out of the package.
    Remove-Item target\classes -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item target\*.jar, target\*.jar.original -Force -ErrorAction SilentlyContinue
    & .\mvnw.cmd @MavenArgs '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }

    $jar = Get-ChildItem target\*.jar | Where-Object Name -notlike '*-plain.jar' | Select-Object -First 1
    $inputDir = Join-Path $root 'target\desktop-input'
    Remove-Item $inputDir -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory $inputDir | Out-Null
    Copy-Item $jar.FullName $inputDir

    $dist = Join-Path $root 'dist'
    $image = Join-Path $dist 'IllustrationArchivePoC'
    Remove-Item $image -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory $dist -Force | Out-Null

    # The Boot jar keeps its normal Main-Class; the flag switches main() to the desktop launcher.
    & $jpackage --type app-image `
        --name IllustrationArchivePoC `
        --app-version 0.0.1 `
        --vendor yuutara `
        --description 'Illustration Archive Windows desktop PoC' `
        --input $inputDir `
        --main-jar $jar.Name `
        --java-options '-Dillustration-archive.desktop=true' `
        --java-options '-Dfile.encoding=UTF-8' `
        --dest $dist
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }

    $zip = Join-Path $dist 'IllustrationArchivePoC-win64.zip'
    Remove-Item $zip -Force -ErrorAction SilentlyContinue
    Compress-Archive -Path $image -DestinationPath $zip
    $size = (Get-ChildItem $image -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
    Write-Host ("App image: {0} ({1:N0} MB)" -f $image, $size)
    Write-Host "Zip:       $zip"
}
finally {
    Pop-Location
}
