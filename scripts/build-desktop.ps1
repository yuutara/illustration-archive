# Builds the Windows desktop app as a self-contained app image (no installer, no auto-update yet).
# Output: dist\IllustrationArchive\IllustrationArchive.exe plus a zip of that folder.
# Tests are not run here; run them separately (see docs/DESKTOP_POC.md).
param(
    # A JDK 21+ that contains jlink and jpackage, with its jmods.
    [string]$JavaHome = $env:JAVA_HOME,
    # Extra Maven arguments, e.g. '-Dmaven.repo.local=C:\Users\me\.m2\repository'.
    [string[]]$MavenArgs = @(),
    # Another folder lets you build while the app in dist is running.
    [string]$OutputDir = (Join-Path (Split-Path -Parent $PSScriptRoot) 'dist')
)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$java = Join-Path $JavaHome 'bin\java.exe'
$jlink = Join-Path $JavaHome 'bin\jlink.exe'
$jpackage = Join-Path $JavaHome 'bin\jpackage.exe'
if (-not (Test-Path $jpackage) -or -not (Test-Path $jlink)) {
    throw "jlink / jpackage not found under '$JavaHome'. Pass -JavaHome with a JDK 21+ directory."
}

# The runtime holds what the app and its libraries use, not the JDK's development tools.
$modules = @(
    'java.base', 'java.compiler', 'java.datatransfer', 'java.desktop', 'java.instrument', 'java.logging',
    'java.management', 'java.management.rmi', 'java.naming', 'java.net.http', 'java.prefs', 'java.rmi',
    'java.scripting', 'java.security.jgss', 'java.security.sasl', 'java.sql', 'java.sql.rowset',
    'java.transaction.xa', 'java.xml', 'java.xml.crypto', 'jdk.charsets', 'jdk.crypto.ec', 'jdk.crypto.mscapi',
    'jdk.httpserver', 'jdk.localedata', 'jdk.management', 'jdk.naming.dns', 'jdk.net', 'jdk.security.auth',
    'jdk.security.jgss', 'jdk.unsupported', 'jdk.zipfs'
) -join ','

Push-Location $root
try {
    # A running app locks files in the image; deleting around them would leave a half-removed app.
    $imagePrefix = Join-Path $OutputDir 'IllustrationArchive\'
    $running = Get-Process -Name IllustrationArchive -ErrorAction SilentlyContinue |
        Where-Object { $_.Path -and $_.Path.StartsWith($imagePrefix, [StringComparison]::OrdinalIgnoreCase) }
    if ($running) {
        throw "The app is running from $OutputDir (PID $($running.Id -join ', ')). Close its window, then rebuild."
    }

    $env:JAVA_HOME = $JavaHome
    # Not 'mvn clean': a locally running instance may hold files under target\. Removing compiled classes
    # and old jars is enough to keep stale classes out of the package.
    Remove-Item target\classes -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item target\*.jar, target\*.jar.original -Force -ErrorAction SilentlyContinue
    & .\mvnw.cmd @MavenArgs '-DskipTests' package
    if ($LASTEXITCODE -ne 0) { throw 'Maven package failed.' }

    $jar = Get-ChildItem target\*.jar | Where-Object Name -notlike '*-plain.jar' | Select-Object -First 1

    # Unpacked libraries start about a quarter faster than jars nested in the Boot jar, and class data
    # sharing only works with them.
    $inputDir = Join-Path $root 'target\desktop-input'
    Remove-Item $inputDir -Recurse -Force -ErrorAction SilentlyContinue
    & $java -Djarmode=tools -jar $jar.FullName extract --destination $inputDir
    if ($LASTEXITCODE -ne 0) { throw 'Extracting the application failed.' }
    # The java launcher picks the @125pct and similar files next to the named image by display scale.
    Copy-Item packaging\icon\splash*.png $inputDir

    # jpackage's own runtime has no base CDS archive, which the application archive below is built on.
    $runtime = Join-Path $root 'target\desktop-runtime'
    Remove-Item $runtime -Recurse -Force -ErrorAction SilentlyContinue
    & $jlink --add-modules $modules --generate-cds-archive --no-man-pages --no-header-files --output $runtime
    if ($LASTEXITCODE -ne 0) { throw 'jlink failed.' }

    $dist = $OutputDir
    $image = Join-Path $dist 'IllustrationArchive'
    Remove-Item $image -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory $dist -Force | Out-Null

    # The flag switches main() to the desktop launcher. app.jsa is made below; if it ever stops fitting, the
    # JVM ignores it and starts a few seconds slower.
    & $jpackage --type app-image `
        --name IllustrationArchive `
        --app-version 0.0.1 `
        --vendor yuutara `
        --description 'Illustration Archive' `
        --icon packaging\icon\app.ico `
        --runtime-image $runtime `
        --input $inputDir `
        --main-jar $jar.Name `
        --java-options '-splash:$APPDIR/splash.png' `
        --java-options '-XX:SharedArchiveFile=$APPDIR/app.jsa' `
        --java-options '-Dillustration-archive.desktop=true' `
        --java-options '-Dfile.encoding=UTF-8' `
        --dest $dist
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }

    # The archive remembers each jar's timestamp. A zip keeps only whole, even seconds, so the jars get such
    # a time first; otherwise unzipping would invalidate the archive.
    $appDir = Join-Path $image 'app'
    $stamp = [datetime]::new(2026, 1, 1, 0, 0, 0, [DateTimeKind]::Utc)
    Get-ChildItem $appDir -Recurse -Filter *.jar | ForEach-Object { $_.LastWriteTimeUtc = $stamp }
    # Slow, about a minute: recording class data is why this happens here and not on a user's first launch.
    & (Join-Path $image 'runtime\bin\java.exe') "-XX:ArchiveClassesAtExit=$appDir\app.jsa" '-Dfile.encoding=UTF-8' `
        -jar (Join-Path $appDir $jar.Name) --train-class-data
    if ($LASTEXITCODE -ne 0) { throw 'Class data training failed.' }

    & (Join-Path $PSScriptRoot 'check-desktop-package.ps1') -Image $image

    $zip = Join-Path $dist 'IllustrationArchive-win64.zip'
    Remove-Item $zip -Force -ErrorAction SilentlyContinue
    Compress-Archive -Path $image -DestinationPath $zip
    $size = (Get-ChildItem $image -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
    Write-Host ("App image: {0} ({1:N0} MB)" -f $image, $size)
    Write-Host "Zip:       $zip"
}
finally {
    Pop-Location
}
