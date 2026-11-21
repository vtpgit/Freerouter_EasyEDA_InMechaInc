# Setup for the EasyEDA to Freerouting bridge on Windows.
# 1. Downloads the routing engine (Freerouting 2.5.0, GPLv3) from its GitHub release and checks the file.
# 2. If no Java 25 or later is installed, downloads the Eclipse Temurin Java runtime into the folder "runtime".
# Nothing is installed outside this folder.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$engineUrl = 'https://github.com/freerouting/freerouting/releases/download/v2.5.0/freerouting-2.5.0.jar'
$engineSha256 = 'f6f51bb02245e8e717f9359bd260cc9c5c0b1bc0acc8b7cb2cd5b8ffeb5de3c7'
$requiredJava = 25

function Get-JavaMajor([string]$exe) {
    try {
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $exe
        $psi.Arguments = '-version'
        $psi.RedirectStandardError = $true
        $psi.RedirectStandardOutput = $true
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $p = [System.Diagnostics.Process]::Start($psi)
        $text = $p.StandardError.ReadToEnd() + $p.StandardOutput.ReadToEnd()
        $p.WaitForExit()
        if ($text -match 'version "(\d+)') { return [int]$Matches[1] }
    } catch { }
    return 0
}

# --- engine
$engineDir = Join-Path $here 'engine'
New-Item -ItemType Directory -Force -Path $engineDir | Out-Null
$jar = Join-Path $engineDir 'freerouting-2.5.0.jar'
if (-not (Test-Path $jar)) {
    Write-Host 'Downloading the routing engine (65 MB) ...'
    Invoke-WebRequest -Uri $engineUrl -OutFile $jar -UseBasicParsing
}
$hash = (Get-FileHash -Path $jar -Algorithm SHA256).Hash.ToLower()
if ($hash -ne $engineSha256) {
    Remove-Item $jar
    throw "The downloaded engine file is not the expected one (checksum $hash). It was deleted. Run setup again."
}
Write-Host 'Routing engine: ok.'

# --- Java
$runtimeDir = Join-Path $here 'runtime'
$bundledJava = Join-Path (Join-Path $runtimeDir 'bin') 'java.exe'
if (Test-Path $bundledJava) {
    Write-Host 'Java runtime in the folder "runtime": ok.'
} else {
    $systemJava = Get-JavaMajor 'java'
    if ($systemJava -ge $requiredJava) {
        Write-Host "Installed Java $systemJava is new enough. No download needed."
    } else {
        $arch = 'x64'
        if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { $arch = 'aarch64' }
        $jreUrl = "https://api.adoptium.net/v3/binary/latest/$requiredJava/ga/windows/$arch/jre/hotspot/normal/eclipse"
        $zip = Join-Path $here 'runtime-download.zip'
        $tmp = Join-Path $here 'runtime-unpack'
        Write-Host "Downloading the Java $requiredJava runtime (Eclipse Temurin, about 50 MB) ..."
        Invoke-WebRequest -Uri $jreUrl -OutFile $zip -UseBasicParsing
        if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
        Expand-Archive -Path $zip -DestinationPath $tmp
        $top = Get-ChildItem -Path $tmp -Directory | Select-Object -First 1
        if ($null -eq $top) { throw 'The Java download has an unexpected layout.' }
        if (Test-Path $runtimeDir) { Remove-Item -Recurse -Force $runtimeDir }
        Move-Item -Path $top.FullName -Destination $runtimeDir
        Remove-Item -Recurse -Force $tmp
        Remove-Item $zip
        if (-not (Test-Path $bundledJava)) { throw 'The Java runtime was not unpacked correctly.' }
        Write-Host 'Java runtime: ok.'
    }
}
Write-Host ''
Write-Host 'Setup is complete. Start the router with run.bat.'
