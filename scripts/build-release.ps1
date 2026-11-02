#requires -Version 7.0
# Builds a delivery folder from one commit, not from the working tree, so the result can be traced back to it: the commit
# is exported to a new folder outside the repository and the build scripts run there, leaving target\classes alone.
# Output: <WorkDir>\delivery\IllustrationArchive-<Version>\, plus <WorkDir>\build-record.txt, which stays out of the delivery.
param(
    # The user's decision. Once a package has gone to anyone, its number is spent.
    [Parameter(Mandatory)][string]$Version,
    [Parameter(Mandatory)][string]$Commit,
    # A JDK 21+ that contains jlink and jpackage, with its jmods.
    [Parameter(Mandatory)][string]$JavaHome,
    # ISCC.exe from Inno Setup 7.
    [Parameter(Mandatory)][string]$Compiler,
    # MicrosoftEdgeWebview2Setup.exe, the Evergreen Bootstrapper as downloaded from Microsoft.
    [Parameter(Mandatory)][string]$Bootstrapper,
    # A new or empty folder outside the repository.
    [Parameter(Mandatory)][string]$WorkDir,
    # Extra Maven arguments, e.g. '-Dmaven.repo.local=C:\Users\me\.m2\repository'.
    [string[]]$MavenArgs = @()
)
$ErrorActionPreference = 'Stop'

# Only called here, so it never becomes a dependency of the project.
$licensePlugin = 'org.codehaus.mojo:license-maven-plugin:2.7.1'

# Windows keeps version numbers as four 16-bit parts, and Setup's comparison reads them the same way.
if ($Version -notmatch '^\d{1,5}(\.\d{1,5}){1,3}$' -or ($Version -split '\.' | Where-Object { [int]$_ -gt 65535 })) {
    throw "Version '$Version' is not two to four numbers from 0 to 65535, such as 0.6.0."
}

$repo = Split-Path -Parent $PSScriptRoot
$sha = & git -C $repo rev-parse --verify --quiet "$Commit^{commit}"
if ($LASTEXITCODE -ne 0 -or -not $sha) { throw "'$Commit' is not a commit in $repo." }

$workPath = [IO.Path]::GetFullPath($WorkDir).TrimEnd('\')
if (($workPath + '\').StartsWith($repo + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "WorkDir '$workPath' is inside the repository."
}
if ((Test-Path -LiteralPath $workPath) -and (Get-ChildItem -LiteralPath $workPath -Force | Select-Object -First 1)) {
    throw "WorkDir '$workPath' is not empty."
}
$work = (New-Item -ItemType Directory $workPath -Force).FullName

$archive = Join-Path $work 'source.zip'
$source = Join-Path $work 'source'
& git -C $repo archive --format=zip "--output=$archive" $sha
if ($LASTEXITCODE -ne 0) { throw 'git archive failed.' }
Expand-Archive -LiteralPath $archive -DestinationPath $source
Remove-Item -LiteralPath $archive

$imageDir = Join-Path $work 'image'
$image = Join-Path $imageDir 'IllustrationArchive'
$installerDir = Join-Path $work 'installer'
$licenseDir = Join-Path $work 'licenses'
$noticeFile = Join-Path $licenseDir 'third-party-notice.txt'
$setup = Join-Path $installerDir "IllustrationArchive-Setup-$Version.exe"

& (Join-Path $source 'scripts\build-desktop.ps1') -JavaHome $JavaHome -MavenArgs $MavenArgs -AppVersion $Version -OutputDir $imageDir
& (Join-Path $source 'scripts\build-installer.ps1') -Image $image -Version $Version -Compiler $Compiler `
    -Bootstrapper $Bootstrapper -OutputDir $installerDir

Push-Location $source
try {
    $env:JAVA_HOME = $JavaHome
    & .\mvnw.cmd @MavenArgs "${licensePlugin}:add-third-party" '-Dlicense.includedScopes=compile,runtime' `
        "-Dlicense.outputDirectory=$licenseDir" '-Dlicense.thirdPartyFilename=third-party.txt' '-Dlicense.failOnMissing=true'
    if ($LASTEXITCODE -ne 0) { throw 'The licence list failed.' }
}
finally {
    Pop-Location
}

& (Join-Path $source 'scripts\build-third-party-notice.ps1') -Image $image -List (Join-Path $licenseDir 'third-party.txt') `
    -JavaHome $JavaHome -Bootstrapper $Bootstrapper -Output $noticeFile
& (Join-Path $source 'scripts\assemble-delivery.ps1') -Version $Version -Setup $setup `
    -Guide (Join-Path $source 'packaging\delivery\使用说明.txt') -Notice $noticeFile -OutputDir (Join-Path $work 'delivery')

$javaVersion = (Get-Content -LiteralPath (Join-Path $JavaHome 'release') | Where-Object { $_ -match '^(IMPLEMENTOR|JAVA_RUNTIME_VERSION)=' }) -join ', '
Set-Content -LiteralPath (Join-Path $work 'build-record.txt') -Encoding utf8 -Value @(
    "Version: $Version"
    "Commit: $sha"
    "Built: $([datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))"
    "JDK: $javaVersion"
    "Inno Setup: $(& $Compiler '/?' 2>&1 | Where-Object { "$_" -match 'Inno Setup' } | Select-Object -First 1)"
    "Inno Setup compiler SHA-256: $((Get-FileHash -LiteralPath $Compiler -Algorithm SHA256).Hash)"
    "WebView2 bootstrapper SHA-256: $((Get-FileHash -LiteralPath $Bootstrapper -Algorithm SHA256).Hash)"
    "Licence list: $licensePlugin"
)
Write-Host "Release $Version from $sha is in $(Join-Path $work 'delivery')"
