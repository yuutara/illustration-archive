# Runs the real installer script over a tiny fake app in a throwaway folder: install, upgrade, refusals, the WebView2
# branches and uninstall, all silent, so these paths can be repeated without a desktop. It never starts the real app.
# Its AppId, app name and WebView2 key are its own, and it removes them at the end, so a real install is not involved.
# This does not replace Tier C: the fake bootstrapper proves the branches, not that Microsoft's installer works.
# Without -Compiler, only the input checks of build-installer.ps1 run.
param(
    # A new or empty folder outside the repository.
    [Parameter(Mandatory)][string]$WorkDir,
    # ISCC.exe from Inno Setup 7.
    [string]$Compiler = '',
    # The real bootstrapper: lets the input checks that come after its own check run.
    [string]$Bootstrapper = ''
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$testGuid ='8B4CDF78-5740-40A5-9EAA-39696BFBDA16'
$appName = 'IA Installer Test'
$runtimeSubKey = 'Software\IllustrationArchiveInstallerTest\WebView2'
$runtimeKey = "HKCU:\$runtimeSubKey"
$uninstallKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\{$testGuid}_is1"
$buildInstaller = Join-Path $PSScriptRoot 'build-installer.ps1'
$iss = Join-Path (Split-Path -Parent $PSScriptRoot) 'packaging\installer\IllustrationArchive.iss'
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
# The value build-desktop.ps1 gives the jars. Every version shares it, as real builds do.
$stamp = [datetime]::new(2026, 1, 1, 0, 0, 0, [DateTimeKind]::Utc)

if ((Test-Path -LiteralPath $WorkDir) -and (Get-ChildItem -LiteralPath $WorkDir -Force | Select-Object -First 1)) {
    throw "WorkDir '$WorkDir' is not empty."
}
$work = (New-Item -ItemType Directory $WorkDir -Force).FullName
$logs = (New-Item -ItemType Directory (Join-Path $work 'logs')).FullName
$failures = 0
$runs = 0

function Report([string]$Name, [bool]$Passed, [string]$Detail = '') {
    if ($Passed) { Write-Host "PASS  $Name" } else { Write-Host "FAIL  $Name  $Detail"; $script:failures++ }
}

function Compile-Source([string]$Name, [string]$Output, [string]$Source, [string]$Target) {
    $path = Join-Path $work "$Name.cs"
    Set-Content -LiteralPath $path -Value $Source -Encoding utf8
    $result = & $csc /nologo "/target:$Target" "/out:$Output" /reference:System.dll $path
    if ($LASTEXITCODE -ne 0) { throw "csc failed for ${Name}: $result" }
}

# check-desktop-package.ps1 opens every jar, so these have to be real archives.
function New-FakeJar([string]$Path, [string]$Text) {
    $jar = [IO.Compression.ZipFile]::Open($Path, 'Create')
    try {
        $writer = [IO.StreamWriter]::new($jar.CreateEntry('build.txt').Open())
        try { $writer.Write($Text) } finally { $writer.Dispose() }
    } finally { $jar.Dispose() }
}

# A stand-in for the launcher: same file name, a real process to find, and real version metadata.
function New-FakeImage([string]$Version) {
    $image = Join-Path $work "image-$Version"
    New-Item -ItemType Directory (Join-Path $image 'app\lib'), (Join-Path $image 'runtime\lib') -Force | Out-Null
    Compile-Source "fake-app-$Version" (Join-Path $image 'IllustrationArchive.exe') @"
using System.Reflection;
using System.Threading;
[assembly: AssemblyVersion("$Version")]
[assembly: AssemblyFileVersion("$Version")]
[assembly: AssemblyInformationalVersion("$Version")]
class FakeApp { static void Main() { Thread.Sleep(Timeout.Infinite); } }
"@ winexe
    Set-Content -LiteralPath (Join-Path $image 'app\.jpackage.xml') -Encoding utf8 -Value @"
<?xml version="1.0" ?>
<jpackage-state version="21.0.11" platform="windows">
  <app-version>$Version</app-version>
</jpackage-state>
"@
    New-FakeJar (Join-Path $image 'app\illustration-archive.jar') "same name, build $Version"
    New-FakeJar (Join-Path $image "app\lib\only-in-$Version.jar") "library of build $Version"
    Set-Content -LiteralPath (Join-Path $image 'runtime\lib\modules') -Value "runtime of build $Version"
    Set-Content -LiteralPath (Join-Path $image 'app\app.jsa') -Value "class data of build $Version"
    # The same file version with different bytes, which Setup would leave alone without ignoreversion.
    New-Item -ItemType Directory (Join-Path $image 'runtime\bin') -Force | Out-Null
    Compile-Source "fake-runtime-$Version" (Join-Path $image 'runtime\bin\same-version.exe') @"
using System.Reflection;
[assembly: AssemblyFileVersion("5.0.0.0")]
class FakeRuntime { static string Build = "$Version"; static void Main() { System.Console.Write(Build); } }
"@ exe
    Get-ChildItem -LiteralPath (Join-Path $image 'app') -Recurse -Filter *.jar | ForEach-Object { $_.LastWriteTimeUtc = $stamp }
    # The real launcher is read-only; app.jsa stands in for any other read-only file.
    foreach ($name in 'app\app.jsa', 'IllustrationArchive.exe') { (Get-Item -LiteralPath (Join-Path $image $name)).IsReadOnly = $true }
    $image
}

function Write-Manifest([string]$Image, [string]$Target, [string]$WrongDigestFor = '') {
    $root = $Image.TrimEnd('\')
    $lines = Get-ChildItem -LiteralPath $root -Recurse -File -Force | ForEach-Object {
        $path = $_.FullName.Substring($root.Length + 1)
        $digest = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($path -eq $WrongDigestFor) { $digest = '0' * 64 }
        "$digest  $path"
    }
    [IO.File]::WriteAllLines($Target, [string[]]$lines, [Text.UTF8Encoding]::new($false))
}

function New-Setup([string]$Version, [string]$Label, [string]$WrongDigestFor = '', [string]$ProcessClass = 'Win32_Process') {
    $stage = Join-Path $work "stage-$Label"
    New-Item -ItemType Directory $stage | Out-Null
    Copy-Item -LiteralPath $fakeBootstrapper (Join-Path $stage 'MicrosoftEdgeWebview2Setup.exe')
    $image = Join-Path $work "image-$Version"
    Write-Manifest $image (Join-Path $stage 'install-manifest.txt') $WrongDigestFor
    $output = & $Compiler '/Qp' "/DAppVersion=$Version" "/DImageDir=$image" "/DStageDir=$stage" `
        "/DAppGuid=$testGuid" "/DAppDisplayName=$appName" "/DWebView2Key=$runtimeSubKey" "/DProcessClass=$ProcessClass" `
        "/O$work" "/Fsetup-$Label" $iss
    if ($LASTEXITCODE -ne 0) { throw "ISCC failed for ${Label}:`n$($output -join "`n")" }
    Join-Path $work "setup-$Label.exe"
}

function Invoke-Setup([string]$Setup, [string]$Dir) {
    $script:runs++
    $log = Join-Path $logs ('{0:000}-{1}.log' -f $script:runs, (Split-Path -LeafBase $Setup))
    (Start-Process -FilePath $Setup -Wait -PassThru -ArgumentList @(
        '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', "/DIR=`"$Dir`"", "/LOG=`"$log`"")).ExitCode
}

function Invoke-Uninstall([string]$Dir) {
    $script:runs++
    $log = Join-Path $logs ('{0:000}-uninstall.log' -f $script:runs)
    (Start-Process -FilePath (Join-Path $Dir 'unins000.exe') -Wait -PassThru -ArgumentList @(
        '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', "/LOG=`"$log`"")).ExitCode
}

# The uninstaller's exit code can arrive while its copy in TEMP is still removing files.
function Wait-Removed([string]$Path) {
    $deadline = (Get-Date).AddSeconds(30)
    while ((Test-Path -LiteralPath $Path) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 200 }
    -not (Test-Path -LiteralPath $Path)
}

function Get-InstalledVersion {
    (Get-ItemProperty -LiteralPath $uninstallKey -ErrorAction SilentlyContinue).DisplayVersion
}

function Get-TreeHash([string]$Root) {
    $root = (Resolve-Path -LiteralPath $Root).Path
    (Get-ChildItem -LiteralPath $root -Recurse -File -Force | Sort-Object FullName | ForEach-Object {
        "$($_.FullName.Substring($root.Length)) $((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash)"
    }) -join "`n"
}

function Test-SameBytes([string]$Left, [string]$Right) {
    (Get-FileHash -LiteralPath $Left -Algorithm SHA256).Hash -eq (Get-FileHash -LiteralPath $Right -Algorithm SHA256).Hash
}

function Set-Runtime([string]$Pv) {
    Remove-Item -LiteralPath $runtimeKey -Force -ErrorAction SilentlyContinue
    if ($Pv) {
        New-Item -Path $runtimeKey -Force | Out-Null
        New-ItemProperty -Path $runtimeKey -Name pv -Value $Pv -PropertyType String | Out-Null
    }
}

function Stop-FakeApps {
    Get-CimInstance Win32_Process -Filter "Name = 'IllustrationArchive.exe'" |
        Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($work + '\', [StringComparison]::OrdinalIgnoreCase) } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
}

function Test-InputChecks {
    $image = Join-Path $work 'image-0.6.0'
    $stubDir = (New-Item -ItemType Directory (Join-Path $work 'stub')).FullName
    $stubCompiler = Join-Path $stubDir 'ISCC.exe'
    Set-Content -LiteralPath $stubCompiler -Value 'never run: every case here stops before compiling'
    $good = @{ Image = $image; Version = '0.6.0'; Compiler = $stubCompiler; Bootstrapper = $fakeBootstrapper; OutputDir = (Join-Path $work 'out') }

    function Test-Rejected([string]$Name, [string]$Expect, [hashtable]$Change) {
        $arguments = $good.Clone()
        foreach ($key in $Change.Keys) { $arguments[$key] = $Change[$key] }
        try {
            & $buildInstaller @arguments *> $null
            Report $Name $false 'was accepted'
        } catch {
            Report $Name ($_.Exception.Message -match $Expect) $_.Exception.Message
        }
    }

    $missing = & (Get-Process -Id $PID).Path -NoProfile -NonInteractive -File $buildInstaller -Version 0.6.0 2>&1 | Out-String
    Report 'missing parameters are refused' ($LASTEXITCODE -ne 0 -and $missing -match 'Image|missing') $missing
    Test-Rejected 'one-part version' 'two to four numbers' @{ Version = '1' }
    Test-Rejected 'non-numeric version' 'two to four numbers' @{ Version = '1.2-beta' }
    Test-Rejected 'version over 65535' 'two to four numbers' @{ Version = '70000.0' }
    Test-Rejected 'compiler that is not ISCC' 'not an ISCC' @{ Compiler = $fakeBootstrapper }
    Test-Rejected 'folder that is not an image' 'not an app image' @{ Image = $stubDir }
    Test-Rejected 'version differs from the image' 'Version mismatch' @{ Version = '0.6.1' }
    Test-Rejected 'unsigned bootstrapper' 'no valid Microsoft signature' @{}
    Test-Rejected 'Microsoft-signed file that is not the bootstrapper' 'not the WebView2 bootstrapper' @{ Bootstrapper = (Join-Path $env:WINDIR 'explorer.exe') }

    if ($Bootstrapper) {
        $good.Bootstrapper = $Bootstrapper
        Test-Rejected 'output inside the image' 'inside the image' @{ OutputDir = (Join-Path $image 'out') }
        $hidden = Join-Path $image 'app\hidden.txt'
        Set-Content -LiteralPath $hidden -Value 'x'
        (Get-Item -LiteralPath $hidden).Attributes = 'Hidden'
        Test-Rejected 'hidden file in the image' 'hidden files' @{}
        Remove-Item -LiteralPath $hidden -Force
        $secret = Join-Path $image 'app\gemini-key.txt'
        Set-Content -LiteralPath $secret -Value 'not a real key'
        Test-Rejected 'credential file in the image' 'must not ship' @{}
        Remove-Item -LiteralPath $secret -Force

        # An input that sits where an older version of the script kept, and wiped, its temporary files.
        $collision = (New-Item -ItemType Directory (Join-Path $work 'collision')).FullName
        $sameName = Join-Path $collision 'installer-stage'
        Copy-Item -LiteralPath $image $sameName -Recurse
        $before = Get-TreeHash $sameName
        $arguments = $good.Clone()
        $arguments.Image = $sameName
        $arguments.OutputDir = $collision
        if ($Compiler) { $arguments.Compiler = $Compiler }
        $problem = ''
        try { & $buildInstaller @arguments *> $null } catch { $problem = $_.Exception.Message }
        Report 'a build does not touch its own input' ((Test-Path -LiteralPath $sameName) -and (Get-TreeHash $sameName) -eq $before)
        Report 'a build leaves no temporary files' (-not (Get-ChildItem -LiteralPath $collision -Filter 'installer-stage-*'))
        if ($Compiler) {
            Report 'the build still finishes' ($problem -eq '' -and (Test-Path -LiteralPath (Join-Path $collision 'IllustrationArchive-Setup-0.6.0.exe'))) $problem
        }
    } else {
        Write-Host 'SKIP  checks after the bootstrapper: no -Bootstrapper given'
    }
}

function Test-InstallerRuns {
    $dir = Join-Path $work 'app'
    $libraryHome = Join-Path $work 'home'
    New-Item -ItemType Directory (Join-Path $libraryHome 'data'), (Join-Path $libraryHome 'media'), (Join-Path $libraryHome 'config') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $libraryHome 'data\archive.mv.db') -Value 'fake library'
    Set-Content -LiteralPath (Join-Path $libraryHome 'media\one.png') -Value 'fake image'
    Set-Content -LiteralPath (Join-Path $libraryHome 'config\gemini-key.txt') -Value 'fake credential'
    $libraryBefore = Get-TreeHash $libraryHome
    $shortcut = Join-Path ([Environment]::GetFolderPath('Programs')) "$appName.lnk"

    foreach ($version in '1.10', '1.11') { New-FakeImage $version | Out-Null }
    $v19 = New-Setup '1.9' '1.9'
    $v110Bad = New-Setup '1.10' '1.10-bad' 'app\illustration-archive.jar'
    $v110 = New-Setup '1.10' '1.10'
    $v111 = New-Setup '1.11' '1.11'
    $noProcessList = New-Setup '1.10' '1.10-no-process-list' '' 'Win32_NoSuchClass'
    $imageOf = { param($v, $name) Join-Path $work "image-$v\$name" }
    $installed = { param($name) Join-Path $dir $name }

    Set-Runtime '100.0.0.1'

    Report 'first install' ((Invoke-Setup $v19 $dir) -eq 0)
    Report 'installed version is registered per user' ((Get-InstalledVersion) -eq '1.9') (Get-InstalledVersion)
    Report 'files match the image' (Test-SameBytes (& $imageOf '1.9' 'app\illustration-archive.jar') (& $installed 'app\illustration-archive.jar'))
    Report 'jar keeps its UTC timestamp' ((Get-Item -LiteralPath (& $installed 'app\illustration-archive.jar')).LastWriteTimeUtc -eq $stamp)
    Report 'start menu shortcut exists' (Test-Path -LiteralPath $shortcut)
    # Why the script needs no read-only handling, although the launcher is read-only in the image.
    Report 'read-only in the image is not carried over' (-not (Get-Item -LiteralPath (& $installed 'app\app.jsa')).IsReadOnly)
    Set-Content -LiteralPath (& $installed 'app\not-ours.txt') -Value 'someone else put this here'

    Report 'same version reinstalls' ((Invoke-Setup $v19 $dir) -eq 0)

    Set-ItemProperty -LiteralPath $uninstallKey -Name DisplayVersion -Value 'not a version'
    $code = Invoke-Setup $v19 $dir
    Report 'an unreadable installed version refuses to overwrite' ($code -eq 1) "exit $code"
    Set-ItemProperty -LiteralPath $uninstallKey -Name DisplayVersion -Value '1.9'

    $code = Invoke-Setup $noProcessList $dir
    Report 'a failed process query stops setup' ($code -eq 7 -and (Get-Content -LiteralPath (Get-ChildItem -LiteralPath $logs | Sort-Object Name | Select-Object -Last 1).FullName -Raw -Encoding utf8) -match '无法确认') "exit $code"

    # The manifest in the install folder can be edited by anyone; these lines point at files it does not own.
    $outside = Join-Path $work 'outside.txt'
    $absolute = Join-Path $work 'absolute.txt'
    Set-Content -LiteralPath $outside, $absolute -Value 'not the installer''s'
    Add-Content -LiteralPath (& $installed 'install-manifest.txt') -Value @(("0" * 64) + '  ..\outside.txt'), @(("0" * 64) + "  $absolute")
    $editedManifest = Get-Content -LiteralPath (& $installed 'install-manifest.txt') -Raw

    # What Setup's "skip this file" leaves behind: one file on disk is not the file that was built.
    $code = Invoke-Setup $v110Bad $dir
    Report 'a file that did not land fails the install' ($code -eq 9) "exit $code"
    Report 'recorded manifest stays the old one' ((Get-Content -LiteralPath (& $installed 'install-manifest.txt') -Raw) -eq $editedManifest)
    Report 'old files wait for a complete install' (Test-Path -LiteralPath (& $installed 'app\lib\only-in-1.9.jar'))

    Report 'upgrade after the failure' ((Invoke-Setup $v110 $dir) -eq 0)
    Report 'same timestamp still replaces bytes' (Test-SameBytes (& $imageOf '1.10' 'app\illustration-archive.jar') (& $installed 'app\illustration-archive.jar'))
    Report 'launcher is the new build' (Test-SameBytes (& $imageOf '1.10' 'IllustrationArchive.exe') (& $installed 'IllustrationArchive.exe'))
    Report 'same file version still replaces bytes' (Test-SameBytes (& $imageOf '1.10' 'runtime\bin\same-version.exe') (& $installed 'runtime\bin\same-version.exe'))
    Report 'recorded manifest is now the new one' (Test-SameBytes (& $installed 'install-manifest.txt') (Join-Path $work 'stage-1.10\install-manifest.txt'))
    Report 'a manifest line cannot reach outside the folder' ((Test-Path -LiteralPath $outside) -and (Test-Path -LiteralPath $absolute))
    Report 'old library is gone' (-not (Test-Path -LiteralPath (& $installed 'app\lib\only-in-1.9.jar')))
    Report 'new library is there' (Test-Path -LiteralPath (& $installed 'app\lib\only-in-1.10.jar'))
    Report 'unrelated file survives the upgrade' (Test-Path -LiteralPath (& $installed 'app\not-ours.txt'))

    $before = Get-TreeHash $dir
    $code = Invoke-Setup $v19 $dir
    Report '1.9 cannot replace 1.10' ($code -eq 1) "exit $code"
    Report 'refused downgrade changes nothing' ((Get-TreeHash $dir) -eq $before -and (Get-InstalledVersion) -eq '1.10')

    $running = Start-Process -FilePath (& $installed 'IllustrationArchive.exe') -PassThru
    Start-Sleep -Seconds 1
    $before = Get-TreeHash $dir
    $code = Invoke-Setup $v111 $dir
    Report 'upgrade stops while the app runs' ($code -eq 7) "exit $code"
    Report 'stopped upgrade changes nothing' ((Get-TreeHash $dir) -eq $before -and (Get-InstalledVersion) -eq '1.10')
    $code = Invoke-Uninstall $dir
    Report 'uninstall stops while the app runs' ($code -ne 0) "exit $code"
    Report 'stopped uninstall changes nothing' ((Get-TreeHash $dir) -eq $before -and (Get-InstalledVersion) -eq '1.10')
    Stop-Process -Id $running.Id -Force
    Stop-FakeApps

    $elsewhere = (New-Item -ItemType Directory (Join-Path $work 'elsewhere')).FullName
    Copy-Item -LiteralPath (& $installed 'IllustrationArchive.exe') $elsewhere
    Start-Process -FilePath (Join-Path $elsewhere 'IllustrationArchive.exe')
    Start-Sleep -Seconds 1
    $code = Invoke-Setup $v110 $dir
    Report 'a copy in another folder does not block' ($code -eq 0) "exit $code"
    Stop-FakeApps

    $locked = [IO.File]::Open((& $installed 'runtime\lib\modules'), 'Open', 'Read', 'None')
    try {
        $code = Invoke-Setup $v111 $dir
        Report 'a file held open does not end in success' ($code -ne 0) "exit $code"
    } finally { $locked.Dispose() }
    Report 'upgrade works once the file is free' ((Invoke-Setup $v111 $dir) -eq 0)
    Report 'version is the new one' ((Get-InstalledVersion) -eq '1.11') (Get-InstalledVersion)

    $env:ILLUSTRATION_ARCHIVE_DESKTOP_HOME = $libraryHome
    try {
        $inside = Join-Path $libraryHome 'program'
        $code = Invoke-Setup $v111 $inside
        Report 'cannot install inside the data folder' ($code -eq 7 -and -not (Test-Path -LiteralPath $inside)) "exit $code"
        $code = Invoke-Setup $v111 $work
        Report 'cannot install around the data folder' ($code -eq 7) "exit $code"
    } finally { Remove-Item Env:ILLUSTRATION_ARCHIVE_DESKTOP_HOME }

    Report 'uninstall' ((Invoke-Uninstall $dir) -eq 0)
    Report 'program files are gone' (Wait-Removed (& $installed 'IllustrationArchive.exe'))
    Report 'uninstaller is gone' (Wait-Removed (& $installed 'unins000.exe'))
    Report 'recorded manifest is gone' (Wait-Removed (& $installed 'install-manifest.txt'))
    Report 'registration is gone' (-not (Test-Path -LiteralPath $uninstallKey))
    Report 'shortcut is gone' (Wait-Removed $shortcut)
    Report 'unrelated file stays' (Test-Path -LiteralPath (& $installed 'app\not-ours.txt'))
    Report 'library is untouched' ((Get-TreeHash $libraryHome) -eq $libraryBefore)
    Remove-Item -LiteralPath $dir -Recurse -Force

    $calls = Join-Path $work 'bootstrapper-calls.txt'
    $env:IA_FAKE_BOOTSTRAPPER_LOG = $calls
    $env:IA_FAKE_BOOTSTRAPPER_KEY = $runtimeSubKey

    Set-Runtime ''
    $env:IA_FAKE_BOOTSTRAPPER = 'fails'
    $code = Invoke-Setup $v111 $dir
    Report 'failed runtime install stops setup' ($code -eq 7) "exit $code"
    Report 'nothing is installed before the runtime' (-not (Test-Path -LiteralPath $dir))
    Report 'bootstrapper gets the documented arguments' ((Get-Content -LiteralPath $calls -Tail 1) -eq '/silent /install') (Get-Content -LiteralPath $calls -Raw)

    $env:IA_FAKE_BOOTSTRAPPER = 'does-nothing'
    $code = Invoke-Setup $v111 $dir
    Report 'exit 0 without a runtime still stops setup' ($code -eq 7) "exit $code"

    Set-Runtime '0.0.0.0'
    $env:IA_FAKE_BOOTSTRAPPER = 'fails'
    $code = Invoke-Setup $v111 $dir
    Report 'pv 0.0.0.0 counts as missing' ($code -eq 7) "exit $code"

    Set-Runtime ''
    $env:IA_FAKE_BOOTSTRAPPER = 'installs'
    $code = Invoke-Setup $v111 $dir
    Report 'setup continues once the runtime appears' ($code -eq 0 -and (Test-Path -LiteralPath (& $installed 'IllustrationArchive.exe'))) "exit $code"

    $callsBefore = (Get-Content -LiteralPath $calls).Count
    Report 'reinstall with the runtime present' ((Invoke-Setup $v111 $dir) -eq 0)
    Report 'bootstrapper does not run when the runtime is present' ((Get-Content -LiteralPath $calls).Count -eq $callsBefore)

    Report 'second uninstall' ((Invoke-Uninstall $dir) -eq 0)
    Report 'reinstall after uninstall is possible' (Wait-Removed (& $installed 'unins000.exe'))
}

$fakeBootstrapper = Join-Path $work 'fake-bootstrapper.exe'
Compile-Source 'fake-bootstrapper' $fakeBootstrapper @'
using System;
using System.IO;
using Microsoft.Win32;
class FakeBootstrapper {
    static int Main(string[] args) {
        File.AppendAllText(Environment.GetEnvironmentVariable("IA_FAKE_BOOTSTRAPPER_LOG"), string.Join(" ", args) + "\r\n");
        string mode = Environment.GetEnvironmentVariable("IA_FAKE_BOOTSTRAPPER");
        if (mode == "fails") return 1603;
        if (mode == "installs") {
            using (RegistryKey key = Registry.CurrentUser.CreateSubKey(Environment.GetEnvironmentVariable("IA_FAKE_BOOTSTRAPPER_KEY"))) {
                key.SetValue("pv", "123.0.0.1");
            }
        }
        return 0;
    }
}
'@ exe

New-FakeImage '0.6.0' | Out-Null
Test-InputChecks

if (-not $Compiler) {
    Write-Host 'SKIP  installer runs: no -Compiler given'
} else {
    New-FakeImage '1.9' | Out-Null
    try {
        Test-InstallerRuns
    } finally {
        Stop-FakeApps
        foreach ($folder in (Join-Path $work 'app')) {
            if (Test-Path -LiteralPath (Join-Path $folder 'unins000.exe')) { Invoke-Uninstall $folder | Out-Null }
        }
        Remove-Item -LiteralPath $uninstallKey -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath 'HKCU:\Software\IllustrationArchiveInstallerTest' -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ($failures) { throw "$failures check(s) failed. Logs: $logs" }
Write-Host 'All checks that ran passed.'
