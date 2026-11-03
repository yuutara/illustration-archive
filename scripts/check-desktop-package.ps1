# Fails if a built app image holds anything that belongs to the developer or a user: local configuration,
# credentials, a library, logs, media or a path of the build machine. build-desktop.ps1 runs this before making the zip.
param(
    [Parameter(Mandatory)][string]$Image,
    # Optional marker planted in fake configuration, to prove its value reached no file, binary ones included.
    [string]$Sentinel = ''
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

# Credentials and per-user state, wherever they turn up.
$forbiddenName = '^(application-local.*\.properties|desktop\.properties|settings\.properties|x-oauth-tokens.*\.properties|gemini-key.*|.*\.mv\.db|.*\.trace\.db|\.env(\..*)?|instance\.lock|entry\.txt|desktop\.log)$'
# Folders the data directory and the repository use. Only checked at the top of the image and of app\, because
# library jars have packages with these names.
$forbiddenFolder = '^(app[\\/])?(config|data|media|storage|logs|window|\.maven|\.git)([\\/]|$)'
# A value here would be a credential built into the app; application.properties only names environment variables.
$forbiddenValue = '^\s*(x\.oauth\.client-(id|secret)|gemini\.api-key|spring\.datasource\.password)\s*=\s*(?!\$\{[A-Z0-9_]+(:)?\}\s*$).+'

$root = (Resolve-Path $Image).Path.TrimEnd('\')
$problems = [System.Collections.Generic.List[string]]::new()
$files = Get-ChildItem $root -Recurse -File -Force
$entries = 0

function Test-Bytes([byte[]]$bytes, [string]$text) {
    if ($bytes.Length -lt $text.Length) { return $false }
    return [Text.Encoding]::Latin1.GetString($bytes).Contains($text, [StringComparison]::Ordinal)
}

# Folders of the machine that built the image. Tools record paths in binary files too (a JVM class data archive keeps
# the class path it was trained with), so every file's bytes are searched, in the encodings Windows programs use.
[Text.Encoding]::RegisterProvider([Text.CodePagesEncodingProvider]::Instance)
$ansi = [Text.Encoding]::GetEncoding([Globalization.CultureInfo]::CurrentCulture.TextInfo.ANSICodePage)
$machineFolders = @($root, (Split-Path -Parent $PSScriptRoot), $env:USERPROFILE) | Where-Object { $_ } |
    ForEach-Object { $_.TrimEnd('\') } | Sort-Object -Unique
$machinePaths = $machineFolders | ForEach-Object { $_; $_.Replace('\', '/') }
# Seen through Latin-1, a path written as UTF-8 or in the ANSI code page is a plain string to search for.
$machineBytes = $machinePaths | ForEach-Object {
    [Text.Encoding]::Latin1.GetString([Text.Encoding]::UTF8.GetBytes($_))
    [Text.Encoding]::Latin1.GetString($ansi.GetBytes($_))
} | Sort-Object -Unique

function Find-MachinePath([byte[]]$bytes) {
    $latin = [Text.Encoding]::Latin1.GetString($bytes)
    foreach ($text in $machineBytes) { if ($latin.Contains($text, [StringComparison]::OrdinalIgnoreCase)) { return $text } }
    # UTF-16 can start at an odd offset.
    foreach ($start in 0, 1) {
        if ($bytes.Length -le $start) { continue }
        $wide = [Text.Encoding]::Unicode.GetString($bytes, $start, $bytes.Length - $start)
        foreach ($text in $machinePaths) { if ($wide.Contains($text, [StringComparison]::OrdinalIgnoreCase)) { return $text } }
    }
}

foreach ($file in $files) {
    $relative = $file.FullName.Substring($root.Length + 1)
    if ($file.Name -match $forbiddenName) { $problems.Add("file: $relative") }
    if ($relative -match $forbiddenFolder) { $problems.Add("folder: $relative") }
    $bytes = [IO.File]::ReadAllBytes($file.FullName)
    $found = Find-MachinePath $bytes
    if ($found) { $problems.Add("build machine path in: $relative ($found)") }
    if ($Sentinel -and $file.Extension -ne '.jar' -and (Test-Bytes $bytes $Sentinel)) {
        $problems.Add("sentinel in: $relative")
    }
    if ($file.Extension -ne '.jar') { continue }
    $zip = [IO.Compression.ZipFile]::OpenRead($file.FullName)
    try {
        foreach ($entry in $zip.Entries) {
            $entries++
            if ($entry.Name -match $forbiddenName) { $problems.Add("entry: $relative!$($entry.FullName)") }
            $isConfig = $entry.Name -match '\.(properties|ya?ml|txt|json)$'
            if (-not $isConfig -and -not $Sentinel) { continue }
            $stream = $entry.Open()
            try {
                $copy = [IO.MemoryStream]::new()
                $stream.CopyTo($copy)
            } finally { $stream.Dispose() }
            $bytes = $copy.ToArray()
            if ($Sentinel -and (Test-Bytes $bytes $Sentinel)) { $problems.Add("sentinel in: $relative!$($entry.FullName)") }
            if ($entry.Name -like 'application*.properties') {
                foreach ($line in [Text.Encoding]::UTF8.GetString($bytes) -split "`r?`n") {
                    if ($line -match $forbiddenValue) { $problems.Add("value: $relative!$($entry.FullName): $($line -replace '=.*', '=...')") }
                }
            }
        }
    } finally { $zip.Dispose() }
}

if ($problems.Count) {
    $problems | ForEach-Object { Write-Host "  $_" }
    throw "The app image holds $($problems.Count) thing(s) that must not ship."
}
Write-Host ("Package check passed: {0} files, {1} jar entries." -f $files.Count, $entries)
