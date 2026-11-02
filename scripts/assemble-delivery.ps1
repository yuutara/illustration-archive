#requires -Version 7.0
# Puts a finished installer, the user guide and the third-party notice into one delivery folder and writes its checksums.
# It builds nothing, so these checks run quickly on their own: see test-delivery.ps1.
# Output: <OutputDir>\IllustrationArchive-<Version>\, created only when every check passes.
param(
    # The release number. The installer and the guide's first line must both say the same.
    [Parameter(Mandatory)][string]$Version,
    # IllustrationArchive-Setup-<Version>.exe from build-installer.ps1.
    [Parameter(Mandatory)][string]$Setup,
    # packaging\delivery\使用说明.txt.
    [Parameter(Mandatory)][string]$Guide,
    # The output of build-third-party-notice.ps1.
    [Parameter(Mandatory)][string]$Notice,
    [Parameter(Mandatory)][string]$OutputDir
)
$ErrorActionPreference = 'Stop'

# Windows keeps version numbers as four 16-bit parts, and Setup's comparison reads them the same way.
if ($Version -notmatch '^\d{1,5}(\.\d{1,5}){1,3}$' -or ($Version -split '\.' | Where-Object { [int]$_ -gt 65535 })) {
    throw "Version '$Version' is not two to four numbers from 0 to 65535, such as 0.6.0."
}

# 0.6 and 0.6.0.0 are the same version, which is why the check compares this form.
function Get-PackedVersion([string]$Value) {
    (((($Value -split '\.') + @('0', '0', '0'))[0..3]) | ForEach-Object { [int]$_ }) -join '.'
}

foreach ($path in $Setup, $Guide, $Notice) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "'$path' does not exist." }
}

$setupName = "IllustrationArchive-Setup-$Version.exe"
if ((Split-Path -Leaf $Setup) -ne $setupName) { throw "'$Setup' is not $setupName." }
$setupInfo = [Diagnostics.FileVersionInfo]::GetVersionInfo((Resolve-Path -LiteralPath $Setup).Path)
foreach ($found in @($setupInfo.FileVersion, $setupInfo.ProductVersion)) {
    # Inno pads these strings with spaces.
    $number = "$found".Trim()
    if ($number -notmatch '^[\d.]+$' -or (Get-PackedVersion $number) -ne (Get-PackedVersion $Version)) {
        throw "Version mismatch: asked for $Version, but the installer says $($setupInfo.FileVersion) / $($setupInfo.ProductVersion)."
    }
}

$title = "插画档案 $Version 使用说明"
$firstLine = Get-Content -LiteralPath $Guide -Encoding utf8 -TotalCount 1
if ($firstLine -ne $title) { throw "The guide's first line is '$firstLine', not '$title'." }

# These two go to other people, so nothing from the build machine may end up in them.
$machine = @($env:USERNAME, $env:COMPUTERNAME) | Where-Object { $_ } | ForEach-Object { [regex]::Escape($_) }
$localPath = '(?<![A-Za-z])[A-Za-z]:[\\/](?![\\/])|\\Users\\' + (($machine | ForEach-Object { "|\b$_\b" }) -join '')
foreach ($path in $Guide, $Notice) {
    $line = Select-String -LiteralPath $path -Pattern $localPath -Encoding utf8 | Select-Object -First 1
    if ($line) { throw "'$path' line $($line.LineNumber) looks like a local path or machine name: $($line.Line.Trim())" }
}

$folderName = "IllustrationArchive-$Version"
New-Item -ItemType Directory $OutputDir -Force | Out-Null
$output = (Resolve-Path -LiteralPath $OutputDir).Path.TrimEnd('\')
$target = Join-Path $output $folderName
# A package may already have gone out under this name; a new build of it is a decision, not a side effect.
if (Test-Path -LiteralPath $target) { throw "'$target' already exists." }

$stage = Join-Path $output "$folderName-stage-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory $stage | Out-Null
try {
    Copy-Item -LiteralPath $Setup (Join-Path $stage $setupName)
    Copy-Item -LiteralPath $Guide (Join-Path $stage '使用说明.txt')
    Copy-Item -LiteralPath $Notice (Join-Path $stage '第三方许可.txt')

    # sha256sum's format: reads back with 'sha256sum -c', which wants UTF-8 names and plain line feeds.
    $sums = Get-ChildItem -LiteralPath $stage -File | Sort-Object Name | ForEach-Object {
        '{0}  {1}' -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $_.Name
    }
    [IO.File]::WriteAllText((Join-Path $stage 'SHA256SUMS.txt'), (($sums -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))

    Rename-Item -LiteralPath $stage $folderName
}
finally {
    Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host "Delivery folder: $target"
