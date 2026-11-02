#requires -Version 7.0
# Writes the notice that ships as 第三方许可.txt. Maven describes the libraries, but the image decides what ships: Boot
# leaves its empty starters out and adds a jar of its own, so each jar in the image must be accounted for here.
param(
    # The app image folder, the one holding IllustrationArchive.exe.
    [Parameter(Mandatory)][string]$Image,
    # The file from license-maven-plugin's add-third-party goal.
    [Parameter(Mandatory)][string]$List,
    # The JDK the image was built with. Its release file names the runtime.
    [Parameter(Mandatory)][string]$JavaHome,
    # The bootstrapper the installer embeds.
    [Parameter(Mandatory)][string]$Bootstrapper,
    # The file to write.
    [Parameter(Mandatory)][string]$Output
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

# Every name Maven reports, as one identifier. A name that is not here stops the build, so a new licence is read
# before it ships.
$licenses = @{
    'Apache License, Version 2.0'                                            = 'Apache-2.0'
    'Apache-2.0'                                                             = 'Apache-2.0'
    'The Apache License, Version 2.0'                                        = 'Apache-2.0'
    'The Apache Software License, Version 2.0'                               = 'Apache-2.0'
    'EPL 1.0'                                                                = 'EPL-1.0'
    'EPL 2.0'                                                                = 'EPL-2.0'
    'EPL-2.0'                                                                = 'EPL-2.0'
    'GPL2 w/ CPE'                                                            = 'GPL-2.0-with-classpath-exception'
    'LGPL-2.1-only'                                                          = 'LGPL-2.1-only'
    'MIT'                                                                    = 'MIT'
    'MIT-0'                                                                  = 'MIT-0'
    'MPL 2.0'                                                                = 'MPL-2.0'
    'The GNU General Public License, v2 with Universal FOSS Exception, v1.0' = 'GPL-2.0-with-Universal-FOSS-exception'
}
# Packed by Boot's plugin rather than declared in the POM, so Maven's list never has it.
$bootTools = 'spring-boot-jarmode-tools'

$delivery = Join-Path (Split-Path -Parent $PSScriptRoot) 'packaging\delivery'
$described = @{}
foreach ($line in Get-Content -LiteralPath $List -Encoding utf8) {
    if (-not $line.Trim() -or $line -match '^Lists of \d+ third-party dependencies\.$') { continue }
    if ($line -notmatch '^\s*(?<names>(\([^()]*\)\s*)+)(?<title>.+) \((?<group>[^\s:()]+):(?<artifact>[^\s:()]+):(?<version>[^\s:()]+) - (?<url>.*)\)$') {
        throw "Cannot read this line of the licence list: $line"
    }
    $entry = $Matches
    $ids = [regex]::Matches($entry.names, '\(([^()]*)\)') | ForEach-Object {
        $name = $_.Groups[1].Value
        if (-not $licenses.ContainsKey($name)) { throw "Unreviewed licence '$name' on $($entry.artifact). Read it, then add it to this script." }
        $licenses[$name]
    }
    $described["$($entry.artifact)-$($entry.version)"] = '{0}  {1}  {2}:{3}:{4}  {5}' -f ($ids -join ' / '), $entry.title, $entry.group, $entry.artifact, $entry.version, $entry.url
}

$jars = Get-ChildItem -LiteralPath (Join-Path $Image 'app\lib') -Filter *.jar
if (-not $jars) { throw "'$Image' has no libraries in app\lib." }
$rows = foreach ($jar in $jars) {
    if ($described.ContainsKey($jar.BaseName)) {
        $described[$jar.BaseName]
    } elseif ($jar.BaseName -match "^$bootTools-(?<version>.+)$") {
        "Apache-2.0  Spring Boot Jarmode Tools  org.springframework.boot:${bootTools}:$($Matches.version)  https://spring.io/projects/spring-boot"
    } else {
        throw "$($jar.Name) ships in the image but Maven's list does not describe it."
    }
}

function Read-JarText([string]$Artifact, [string]$Entry) {
    $jar = $jars | Where-Object { $_.BaseName -match "^$([regex]::Escape($Artifact))-\d" } | Select-Object -First 1
    if (-not $jar) { throw "The image has no $Artifact jar to take its licence from." }
    $zip = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
    try {
        $found = $zip.GetEntry($Entry)
        if (-not $found) { throw "$($jar.Name) has no $Entry." }
        $reader = [IO.StreamReader]::new($found.Open(), [Text.Encoding]::UTF8)
        try { $reader.ReadToEnd().Trim() } finally { $reader.Dispose() }
    } finally { $zip.Dispose() }
}

function Read-ReleaseValue([string]$Name) {
    $line = Get-Content -LiteralPath (Join-Path $JavaHome 'release') | Where-Object { $_ -like "$Name=*" } | Select-Object -First 1
    if (-not $line) { throw "'$JavaHome' has no $Name in its release file." }
    $line.Substring($Name.Length + 1).Trim('"')
}

$bootstrapperVersion = [Diagnostics.FileVersionInfo]::GetVersionInfo((Resolve-Path -LiteralPath $Bootstrapper).Path).FileVersion
if (-not $bootstrapperVersion) { throw "'$Bootstrapper' has no version." }

$values = [ordered]@{
    LICENSE              = (Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\LICENSE') -Raw -Encoding utf8).Trim()
    JAVA_NAME            = '{0} OpenJDK {1}' -f (Read-ReleaseValue 'IMPLEMENTOR'), (Read-ReleaseValue 'JAVA_RUNTIME_VERSION')
    BOOTSTRAPPER_VERSION = $bootstrapperVersion
    LIBRARY_COUNT        = $jars.Count
    LIBRARIES            = ($rows | Sort-Object) -join "`n"
    APACHE_2             = (Get-Content -LiteralPath (Join-Path $delivery 'licenses\Apache-2.0.txt') -Raw -Encoding utf8).Trim()
    EPL_2                = (Get-Content -LiteralPath (Join-Path $delivery 'licenses\EPL-2.0.txt') -Raw -Encoding utf8).Trim()
    MPL_2                = (Get-Content -LiteralPath (Join-Path $delivery 'licenses\MPL-2.0.txt') -Raw -Encoding utf8).Trim()
    SLF4J                = Read-JarText 'slf4j-api' 'META-INF/LICENSE.txt'
    MYSQL                = Read-JarText 'mysql-connector-j' 'LICENSE'
}
$text = Get-Content -LiteralPath (Join-Path $delivery 'third-party-notice.txt') -Raw -Encoding utf8
foreach ($name in $values.Keys) { $text = $text.Replace("{{$name}}", [string]$values[$name]) }
if ($text -match '\{\{[A-Z0-9_]+\}\}') { throw "The notice still has the placeholder $($Matches[0])." }

# Notepad-friendly: a byte order mark and Windows line ends.
[IO.File]::WriteAllText($Output, ($text -replace "`r?`n", "`r`n"), [Text.UTF8Encoding]::new($true))
Write-Host "Third-party notice: $Output ($($jars.Count) libraries)"
