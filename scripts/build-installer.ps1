# Packs an app image from build-desktop.ps1 into a per-user installer. Every input is named here and checked before
# anything compiles, so a build cannot quietly pick up the wrong image, version or bootstrapper.
# Output: <OutputDir>\IllustrationArchive-Setup-<Version>.exe, plus a .build.txt recording the inputs.
param(
    # The app image folder, the one holding IllustrationArchive.exe.
    [Parameter(Mandatory)][string]$Image,
    # Must equal the image's version. The installer compares it against what is already installed.
    [Parameter(Mandatory)][string]$Version,
    # ISCC.exe from Inno Setup 7.
    [Parameter(Mandatory)][string]$Compiler,
    # MicrosoftEdgeWebview2Setup.exe, the Evergreen Bootstrapper as downloaded from Microsoft.
    [Parameter(Mandatory)][string]$Bootstrapper,
    # Must not be inside the image.
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

if ((Split-Path -Leaf $Compiler) -ne 'ISCC.exe' -or -not (Test-Path -LiteralPath $Compiler -PathType Leaf)) {
    throw "'$Compiler' is not an ISCC.exe. Pass the one from Inno Setup 7."
}
$iss = Join-Path (Split-Path -Parent $PSScriptRoot) 'packaging\installer\IllustrationArchive.iss'

$root = (Resolve-Path -LiteralPath $Image).Path.TrimEnd('\')
$exe = Join-Path $root 'IllustrationArchive.exe'
$state = Join-Path $root 'app\.jpackage.xml'
if (-not (Test-Path -LiteralPath $exe -PathType Leaf) -or -not (Test-Path -LiteralPath $state -PathType Leaf)) {
    throw "'$root' is not an app image: IllustrationArchive.exe or app\.jpackage.xml is missing."
}

$expected = Get-PackedVersion $Version
$imageVersion = ([xml](Get-Content -LiteralPath $state -Raw)).'jpackage-state'.'app-version'
$exeVersion = [Diagnostics.FileVersionInfo]::GetVersionInfo($exe)
foreach ($found in @($imageVersion, $exeVersion.FileVersion, $exeVersion.ProductVersion)) {
    if ((Get-PackedVersion $found) -ne $expected) {
        throw "Version mismatch: asked for $Version, but the image says $imageVersion and its EXE says $($exeVersion.FileVersion) / $($exeVersion.ProductVersion). Rebuild it with build-desktop.ps1 -AppVersion $Version."
    }
}

# Any Microsoft-signed program would pass the signature alone, so the product is checked as well.
$bootstrapperPath = (Resolve-Path -LiteralPath $Bootstrapper).Path
$signature = Get-AuthenticodeSignature -LiteralPath $bootstrapperPath
$bootstrapperInfo = [Diagnostics.FileVersionInfo]::GetVersionInfo($bootstrapperPath)
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch '(^|, )O=Microsoft Corporation(,|$)') {
    throw "'$bootstrapperPath' has no valid Microsoft signature (status $($signature.Status), signer $($signature.SignerCertificate.Subject))."
}
if ($bootstrapperInfo.ProductName -ne 'Microsoft Edge Update') {
    throw "'$bootstrapperPath' is signed by Microsoft but is not the WebView2 bootstrapper (product '$($bootstrapperInfo.ProductName)')."
}

$files = Get-ChildItem -LiteralPath $root -Recurse -File -Force
# A wildcard in the Inno script silently leaves hidden files out.
$hidden = $files | Where-Object { $_.Attributes -band [IO.FileAttributes]::Hidden }
if ($hidden) { throw "The image has hidden files, which the installer would leave out: $($hidden.Name -join ', ')" }

& (Join-Path $PSScriptRoot 'check-desktop-package.ps1') -Image $root

New-Item -ItemType Directory $OutputDir -Force | Out-Null
$output = (Resolve-Path -LiteralPath $OutputDir).Path.TrimEnd('\')
if (($output + '\').StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputDir '$output' is inside the image."
}

# A new name every run: whatever already exists in OutputDir is the caller's, possibly this very build's input.
$stage = Join-Path $output "installer-stage-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory $stage | Out-Null
try {
    Copy-Item -LiteralPath $bootstrapperPath (Join-Path $stage 'MicrosoftEdgeWebview2Setup.exe')
    # The installer checks what landed against this, and the next installer uses it to find files to remove.
    $manifest = $files | ForEach-Object {
        '{0}  {1}' -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $_.FullName.Substring($root.Length + 1)
    }
    [IO.File]::WriteAllLines((Join-Path $stage 'install-manifest.txt'), [string[]]$manifest, [Text.UTF8Encoding]::new($false))

    & $Compiler '/Qp' "/DAppVersion=$Version" "/DImageDir=$root" "/DStageDir=$stage" "/O$output" $iss
    if ($LASTEXITCODE -ne 0) { throw "ISCC failed with exit code $LASTEXITCODE." }
}
finally {
    Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
}

$setup = Join-Path $output "IllustrationArchive-Setup-$Version.exe"
$bootstrapperHash = (Get-FileHash -LiteralPath $bootstrapperPath -Algorithm SHA256).Hash
Set-Content -LiteralPath "$setup.build.txt" -Encoding utf8 -Value @(
    "Version: $Version"
    "Image: $root ($($files.Count) files)"
    "WebView2 bootstrapper: $($bootstrapperInfo.FileVersion), SHA-256 $bootstrapperHash"
    "Signer: $($signature.SignerCertificate.Subject)"
)
Write-Host ("Installer: {0} ({1:N0} MB)" -f $setup, ((Get-Item -LiteralPath $setup).Length / 1MB))
