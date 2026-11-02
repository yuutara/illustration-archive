#requires -Version 7.0
# Runs the real delivery scripts over fake inputs in a throwaway folder: the checks that refuse a bad package, the
# checksums, and the third-party notice. No Java build, so it takes seconds. A real build is build-release.ps1.
param(
    # A new or empty folder outside the repository.
    [Parameter(Mandatory)][string]$WorkDir
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$assemble = Join-Path $PSScriptRoot 'assemble-delivery.ps1'
$buildNotice = Join-Path $PSScriptRoot 'build-third-party-notice.ps1'
$buildRelease = Join-Path $PSScriptRoot 'build-release.ps1'
$realGuide = Join-Path (Split-Path -Parent $PSScriptRoot) 'packaging\delivery\使用说明.txt'
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$bootstrapper = Join-Path $env:WINDIR 'System32\cmd.exe'

if ((Test-Path -LiteralPath $WorkDir) -and (Get-ChildItem -LiteralPath $WorkDir -Force | Select-Object -First 1)) {
    throw "WorkDir '$WorkDir' is not empty."
}
$work = (New-Item -ItemType Directory $WorkDir -Force).FullName
$failures = 0

function Report([string]$Name, [bool]$Passed, [string]$Detail = '') {
    if ($Passed) { Write-Host "PASS  $Name" } else { Write-Host "FAIL  $Name  $Detail"; $script:failures++ }
}

function Expect-Refused([string]$Name, [string]$Pattern, [scriptblock]$Action) {
    try {
        & $Action 6>$null | Out-Null
        Report $Name $false 'it was accepted'
    } catch {
        Report $Name ($_.Exception.Message -match $Pattern) $_.Exception.Message
    }
}

function New-FakeSetup([string]$Folder, [string]$NameVersion, [string]$MetadataVersion, [string]$ProductVersion = $MetadataVersion) {
    New-Item -ItemType Directory $Folder -Force | Out-Null
    $source = Join-Path $work "setup-$(New-Guid).cs"
    Set-Content -LiteralPath $source -Encoding utf8 -Value @"
using System.Reflection;
[assembly: AssemblyVersion("$MetadataVersion")]
[assembly: AssemblyFileVersion("$MetadataVersion")]
[assembly: AssemblyInformationalVersion("$ProductVersion")]
class FakeSetup { static void Main() { } }
"@
    $path = Join-Path $Folder "IllustrationArchive-Setup-$NameVersion.exe"
    $result = & $csc /nologo /target:exe "/out:$path" $source
    if ($LASTEXITCODE -ne 0) { throw "csc failed: $result" }
    $path
}

function New-FakeJar([string]$Path, [hashtable]$Entries = @{}) {
    $jar = [IO.Compression.ZipFile]::Open($Path, 'Create')
    try {
        foreach ($name in @($Entries.Keys) + 'build.txt') {
            $writer = [IO.StreamWriter]::new($jar.CreateEntry($name).Open())
            try { $writer.Write([string]$Entries[$name]) } finally { $writer.Dispose() }
        }
    } finally { $jar.Dispose() }
}

function New-FakeImage([string]$Name) {
    $image = Join-Path $work $Name
    $lib = (New-Item -ItemType Directory (Join-Path $image 'app\lib') -Force).FullName
    New-FakeJar (Join-Path $lib 'slf4j-api-2.0.18.jar') @{ 'META-INF/LICENSE.txt' = 'SLF4J LICENCE TEXT' }
    New-FakeJar (Join-Path $lib 'mysql-connector-j-9.7.0.jar') @{ 'LICENSE' = 'MYSQL LICENCE TEXT' }
    New-FakeJar (Join-Path $lib 'spring-boot-jarmode-tools-4.1.1.jar')
    $image
}

$goodList = @'

Lists of 4 third-party dependencies.
     (MIT) SLF4J API Module (org.slf4j:slf4j-api:2.0.18 - http://www.slf4j.org)
     (The GNU General Public License, v2 with Universal FOSS Exception, v1.0) MySQL Connector/J (com.mysql:mysql-connector-j:9.7.0 - http://dev.mysql.com/doc/connector-j/en/)
     (Apache License, Version 2.0) spring-boot-starter (org.springframework.boot:spring-boot-starter:4.1.1 - https://spring.io/projects/spring-boot)
     (EPL 2.0) (GPL2 w/ CPE) Jakarta Annotations API (Beta) (jakarta.annotation:jakarta.annotation-api:3.0.0 - https://projects.eclipse.org/projects/ee4j.ca)
'@

function New-List([string]$Name, [string]$Text) {
    $path = Join-Path $work $Name
    Set-Content -LiteralPath $path -Value $Text -Encoding utf8
    $path
}

# Assembling a package

$version = '9.8.7'
$setup = New-FakeSetup (Join-Path $work 'setup-good') $version $version
$guide = Join-Path $work 'guide.txt'
Set-Content -LiteralPath $guide -Encoding utf8BOM -Value "插画档案 $version 使用说明", '', '默认在 %LOCALAPPDATA%\IllustrationArchive。', 'https://example.com/a/b'
$notice = Join-Path $work 'notice.txt'
Set-Content -LiteralPath $notice -Encoding utf8BOM -Value '第三方许可', 'com.example:name:1.0  http://example.com/'

function Invoke-Assemble([string]$Output, [string]$Ver = $version, [string]$SetupFile = $setup, [string]$GuideFile = $guide, [string]$NoticeFile = $notice) {
    & $assemble -Version $Ver -Setup $SetupFile -Guide $GuideFile -Notice $NoticeFile -OutputDir $Output
}

$out = Join-Path $work 'out-good'
Invoke-Assemble $out 6>$null
$package = Join-Path $out "IllustrationArchive-$version"
$names = @(Get-ChildItem -LiteralPath $package -Name | Sort-Object)
$expected = @("IllustrationArchive-Setup-$version.exe", 'SHA256SUMS.txt', '使用说明.txt', '第三方许可.txt' | Sort-Object)
Report 'package holds exactly the four files' (($names -join '|') -ceq ($expected -join '|')) ($names -join ', ')
Report 'no stage folder is left behind' (@(Get-ChildItem -LiteralPath $out -Name).Count -eq 1)

$sumsPath = Join-Path $package 'SHA256SUMS.txt'
$sumsBytes = [IO.File]::ReadAllBytes($sumsPath)
$sums = [Text.Encoding]::UTF8.GetString($sumsBytes)
Report 'checksums have no byte order mark' ($sumsBytes[0] -ne 0xEF)
Report 'checksums use plain line feeds' (-not $sums.Contains("`r") -and $sums.EndsWith("`n"))
$sumLines = @($sums.TrimEnd("`n") -split "`n")
Report 'checksums cover the other three files' ($sumLines.Count -eq 3 -and $sums -notmatch 'SHA256SUMS')
$allMatch = $true
foreach ($line in $sumLines) {
    if ($line -notmatch '^([0-9a-f]{64})  (.+)$') { $allMatch = $false; continue }
    $hash = $Matches[1]
    $file = Join-Path $package $Matches[2]
    if ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $hash) { $allMatch = $false }
}
Report 'checksums match the files' $allMatch

$sha256sum = Join-Path (Split-Path -Parent (Split-Path -Parent (Get-Command git).Source)) 'usr\bin\sha256sum.exe'
if (Test-Path -LiteralPath $sha256sum) {
    Push-Location $package
    try {
        $check = & $sha256sum -c SHA256SUMS.txt 2>&1
        Report 'sha256sum -c reads the file' ($LASTEXITCODE -eq 0) ($check -join ' ')
    } finally { Pop-Location }
} else {
    Write-Host "SKIP  sha256sum -c: not found at $sha256sum"
}
Report 'guide and notice arrive byte for byte' (
    (Get-FileHash -LiteralPath $guide).Hash -eq (Get-FileHash -LiteralPath (Join-Path $package '使用说明.txt')).Hash -and
    (Get-FileHash -LiteralPath $notice).Hash -eq (Get-FileHash -LiteralPath (Join-Path $package '第三方许可.txt')).Hash)

$marker = Join-Path $package 'existing.txt'
Set-Content -LiteralPath $marker -Value 'keep'
Expect-Refused 'an existing package is not overwritten' 'already exists' { Invoke-Assemble $out }
Report 'the existing package is untouched' (Test-Path -LiteralPath $marker)

function Expect-NoPackage([string]$Name, [string]$Pattern, [hashtable]$Arguments) {
    $target = Join-Path $work "out-$(New-Guid)"
    Expect-Refused $Name $Pattern { Invoke-Assemble $target @Arguments }
    Report "$Name, leaving nothing" (-not (Test-Path -LiteralPath (Join-Path $target "IllustrationArchive-$version")))
}

Expect-Refused 'a malformed version is refused' 'not two to four numbers' { Invoke-Assemble (Join-Path $work 'out-x') -Ver '0.6.0-rc1' }
Expect-Refused 'a version part above 65535 is refused' 'not two to four numbers' { Invoke-Assemble (Join-Path $work 'out-x') -Ver '1.70000' }
Expect-NoPackage 'a missing installer is refused' 'does not exist' @{ SetupFile = (Join-Path $work 'nope.exe') }
Expect-NoPackage 'a missing guide is refused' 'does not exist' @{ GuideFile = (Join-Path $work 'nope.txt') }
Expect-NoPackage 'a missing notice is refused' 'does not exist' @{ NoticeFile = (Join-Path $work 'nope.txt') }
Expect-NoPackage 'an installer named for another version is refused' 'is not IllustrationArchive-Setup-9\.8\.7\.exe' @{
    SetupFile = (New-FakeSetup (Join-Path $work 'setup-other-name') '9.8.8' '9.8.8')
}
Expect-NoPackage 'an installer built as another version is refused' 'Version mismatch' @{
    SetupFile = (New-FakeSetup (Join-Path $work 'setup-other-meta') $version '9.8.8')
}
Report 'a version padded with spaces, as Inno writes it, is accepted' $(
    $padded = New-FakeSetup (Join-Path $work 'setup-padded') $version $version "$version          "
    try { Invoke-Assemble (Join-Path $work 'out-padded') -SetupFile $padded 6>$null; $true } catch { $false })
Expect-NoPackage 'an installer with another product version is refused' 'Version mismatch' @{
    SetupFile = (New-FakeSetup (Join-Path $work 'setup-other-product') $version $version '9.8.8')
}
Expect-NoPackage 'an installer with a non-numeric product version is refused' 'Version mismatch' @{
    SetupFile = (New-FakeSetup (Join-Path $work 'setup-text-product') $version $version '9.8.7-beta')
}
Report 'the same version in another form is accepted' $(
    $short = New-FakeSetup (Join-Path $work 'setup-short') '9.8' '9.8.0.0'
    $shortGuide = Join-Path $work 'guide-short.txt'
    Set-Content -LiteralPath $shortGuide -Encoding utf8BOM -Value '插画档案 9.8 使用说明'
    try { Invoke-Assemble (Join-Path $work 'out-short') -Ver '9.8' -SetupFile $short -GuideFile $shortGuide 6>$null; $true } catch { $false })

function New-Text([string]$Name, [string[]]$Lines) {
    $path = Join-Path $work $Name
    Set-Content -LiteralPath $path -Encoding utf8BOM -Value $Lines
    $path
}

$firstLine = "插画档案 $version 使用说明"
Expect-NoPackage "a guide for another version is refused" "first line" @{ GuideFile = (New-Text 'guide-old.txt' '插画档案 0.0.1 使用说明') }
Expect-NoPackage 'a guide with a drive path is refused' 'local path' @{ GuideFile = (New-Text 'guide-path.txt' $firstLine, '数据在 D:\Temp\library') }
Expect-NoPackage 'a guide with a forward-slash drive path is refused' 'local path' @{ GuideFile = (New-Text 'guide-slash.txt' $firstLine, 'c:/work/x') }
Expect-NoPackage 'a guide with a user folder is refused' 'local path' @{ GuideFile = (New-Text 'guide-user.txt' $firstLine, '\\server\Users\someone') }
Expect-NoPackage "a guide naming this machine's user is refused" 'local path' @{ GuideFile = (New-Text 'guide-name.txt' $firstLine, "作者 $env:USERNAME") }
Expect-NoPackage 'a notice with a drive path is refused' 'local path' @{ NoticeFile = (New-Text 'notice-path.txt' '许可', 'C:\Users\someone\.m2\x.jar') }

# The real guide

$guideBytes = [IO.File]::ReadAllBytes($realGuide)
$guideText = [Text.Encoding]::UTF8.GetString($guideBytes)
Report 'the guide starts with a byte order mark' ($guideBytes[0] -eq 0xEF -and $guideBytes[1] -eq 0xBB -and $guideBytes[2] -eq 0xBF)
Report 'the guide uses Windows line ends' (($guideText -split "`r`n").Count -gt 10 -and $guideText -notmatch "[^`r]`n")
$guideTitle = Get-Content -LiteralPath $realGuide -Encoding utf8 -TotalCount 1
Report 'the guide has one version, in its first line' ($guideTitle -match '^插画档案 \d+(\.\d+){1,3} 使用说明$' -and -not ($guideText -split "`r`n" | Select-Object -Skip 1 | Where-Object { $_ -match '\d+\.\d+\.\d+' }))
Report 'the guide contains the SmartScreen notice Microsoft requires' ($guideText.Contains('Microsoft Defender SmartScreen') -and $guideText.Contains('privacy-whitepaper#smartscreen'))
$pathHits = Select-String -LiteralPath $realGuide -Pattern '(?<![A-Za-z])[A-Za-z]:[\\/](?![\\/])' -Encoding utf8
Report 'the guide has no drive path' (-not $pathHits) ($pathHits.Line -join ' ')

# The third-party notice

$jdk = New-Item -ItemType Directory (Join-Path $work 'jdk') -Force
Set-Content -LiteralPath (Join-Path $jdk 'release') -Value 'IMPLEMENTOR="Test Vendor"', 'JAVA_RUNTIME_VERSION="21.0.99+1-LTS"'

function Invoke-Notice([string]$Image, [string]$List, [string]$Output = (Join-Path $work "notice-$(New-Guid).txt")) {
    & $buildNotice -Image $Image -List $List -JavaHome $jdk.FullName -Bootstrapper $bootstrapper -Output $Output
}

$image = New-FakeImage 'image-good'
$list = New-List 'list-good.txt' $goodList
$noticeOut = Join-Path $work 'notice-good.txt'
Invoke-Notice $image $list $noticeOut 6>$null
$noticeBytes = [IO.File]::ReadAllBytes($noticeOut)
$noticeText = [Text.Encoding]::UTF8.GetString($noticeBytes)
Report 'the notice starts with a byte order mark' ($noticeBytes[0] -eq 0xEF -and $noticeBytes[1] -eq 0xBB -and $noticeBytes[2] -eq 0xBF)
Report 'the notice uses Windows line ends' ($noticeText -notmatch "[^`r]`n")
Report 'the notice carries the MIT licence' ($noticeText.Contains('MIT License') -and $noticeText.Contains('Copyright (c) 2026 yuutara'))
Report 'the notice names the runtime from the JDK' $noticeText.Contains('Test Vendor OpenJDK 21.0.99+1-LTS')
$cmdVersion = [Diagnostics.FileVersionInfo]::GetVersionInfo($bootstrapper).FileVersion
Report 'the notice gives the bootstrapper version' $noticeText.Contains($cmdVersion)
Report 'the notice contains the SmartScreen notice Microsoft requires' ($noticeText.Contains('Microsoft Defender SmartScreen') -and $noticeText.Contains('privacy-whitepaper#smartscreen'))
Report 'the notice lists what ships' ($noticeText.Contains('org.slf4j:slf4j-api:2.0.18') -and $noticeText.Contains('com.mysql:mysql-connector-j:9.7.0'))
Report "the notice adds Boot's own jar" $noticeText.Contains('org.springframework.boot:spring-boot-jarmode-tools:4.1.1')
Report 'the notice leaves out what Boot dropped' (-not $noticeText.Contains('spring-boot-starter:4.1.1'))
Report 'the notice counts the libraries' $noticeText.Contains('共 3 个')
Report 'the notice carries the licence texts' ($noticeText.Contains('Apache License') -and $noticeText.Contains('Eclipse Public License - v 2.0') -and $noticeText.Contains('Mozilla Public License Version 2.0'))
Report 'the notice takes MySQL and SLF4J licences from their jars' ($noticeText.Contains('MYSQL LICENCE TEXT') -and $noticeText.Contains('SLF4J LICENCE TEXT'))
Report 'the notice has no placeholders left' ($noticeText -notmatch '\{\{')

$dualImage = New-FakeImage 'image-dual'
New-FakeJar (Join-Path $dualImage 'app\lib\jakarta.annotation-api-3.0.0.jar')
$dualOut = Join-Path $work 'notice-dual.txt'
Invoke-Notice $dualImage $list $dualOut 6>$null
Report 'a dual licence reads as a choice, and parentheses in a title survive' (
    (Get-Content -LiteralPath $dualOut -Raw -Encoding utf8).Contains('EPL-2.0 / GPL-2.0-with-classpath-exception  Jakarta Annotations API (Beta)  jakarta.annotation:'))

$extraImage = New-FakeImage 'image-extra'
New-FakeJar (Join-Path $extraImage 'app\lib\mystery-1.0.jar')
Expect-Refused 'a shipped jar Maven does not describe is refused' 'mystery-1\.0\.jar ships' { Invoke-Notice $extraImage $list }

$versionImage = New-FakeImage 'image-version'
Rename-Item -LiteralPath (Join-Path $versionImage 'app\lib\slf4j-api-2.0.18.jar') 'slf4j-api-2.0.17.jar'
Expect-Refused 'a jar of another version than Maven described is refused' 'slf4j-api-2\.0\.17\.jar ships' { Invoke-Notice $versionImage $list }

Expect-Refused 'an unknown licence is refused' 'Unreviewed licence' {
    Invoke-Notice $image (New-List 'list-unknown.txt' ($goodList + "`n     (Unknown license) Thing (com.example:thing:1.0 - no url defined)"))
}
Expect-Refused 'an unreviewed licence is refused' 'Unreviewed licence ''AGPL-3.0''' {
    Invoke-Notice $image (New-List 'list-agpl.txt' ($goodList + "`n     (AGPL-3.0) Thing (com.example:thing:1.0 - https://example.com)"))
}
Expect-Refused 'a line that cannot be read is refused' 'Cannot read this line' {
    Invoke-Notice $image (New-List 'list-odd.txt' ($goodList + "`n     something else entirely"))
}

$noText = New-FakeImage 'image-no-text'
Remove-Item -LiteralPath (Join-Path $noText 'app\lib\mysql-connector-j-9.7.0.jar')
New-FakeJar (Join-Path $noText 'app\lib\mysql-connector-j-9.7.0.jar')
Expect-Refused 'a licence text that is no longer in its jar is refused' 'has no LICENSE' { Invoke-Notice $noText $list }

$empty = New-Item -ItemType Directory (Join-Path $work 'image-empty\app\lib') -Force
Expect-Refused 'an image with no libraries is refused' 'no libraries' { Invoke-Notice (Join-Path $work 'image-empty') $list }

# build-release.ps1 refuses bad input before it builds anything

function Invoke-Release([string]$Ver = '9.8.7', [string]$Commit = 'HEAD', [string]$Dir = (Join-Path $work "release-$(New-Guid)")) {
    & $buildRelease -Version $Ver -Commit $Commit -JavaHome 'unused' -Compiler 'unused' -Bootstrapper 'unused' -WorkDir $Dir
}

$repo = Split-Path -Parent $PSScriptRoot
Expect-Refused 'a release with a malformed version is refused' 'not two to four numbers' { Invoke-Release -Ver 'latest' }
Expect-Refused 'a release of an unknown commit is refused' 'is not a commit' { Invoke-Release -Commit '0000000000000000000000000000000000000000' }
Expect-Refused 'a release inside the repository is refused' 'inside the repository' { Invoke-Release -Dir (Join-Path $repo 'target\release') }
$busy = New-Item -ItemType Directory (Join-Path $work 'release-busy') -Force
Set-Content -LiteralPath (Join-Path $busy 'something.txt') -Value 'x'
Expect-Refused 'a release into a used folder is refused' 'is not empty' { Invoke-Release -Dir $busy.FullName }

if ($failures) { throw "$failures check(s) failed." }
Write-Host 'All delivery checks passed.'
