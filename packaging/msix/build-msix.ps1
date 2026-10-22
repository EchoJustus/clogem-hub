# © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License 2.0 which is available at
# https://www.eclipse.org/legal/epl-2.0
#
# SPDX-License-Identifier: EPL-2.0

# S05 prototype: build, sign, sideload and probe the hub MSIX package on a
# Windows 10 21H2 (build 19044) or later machine. Owner-run; it changes the
# machine (installs a package, imports a development certificate into
# Trusted People), so read it before running and remove both afterwards
# (-Uninstall). Run from an elevated PowerShell in the clogem-hub checkout
# with the SDK next to it (..\clogem-sdk):
#
#   powershell -ExecutionPolicy Bypass -File packaging\msix\build-msix.ps1 `
#     -BbExe C:\tools\bb\bb.exe -IdentityName ClogemHubProto `
#     -Publisher "CN=Clogem Dev" -PublisherDisplayName "Clogem (dev)"
#
# Prerequisites: Flutter 3.47.7 (for dart compile exe), the Windows 10/11 SDK
# (MakeAppx.exe, SignTool.exe under "C:\Program Files (x86)\Windows Kits\10\bin\<build>\x64"),
# Babashka 1.13.225 windows-amd64 (its bb.exe is copied into the package).
# Outputs: packaging\msix\build\ (gitignored): stage\ (the package layout),
# ClogemHubProto.msix, ClogemHubProto.pfx, probe.json (what the VM showed).
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)] [string]$BbExe,
  [string]$IdentityName = "ClogemHubProto",
  [string]$Publisher = "CN=Clogem Dev",
  [string]$PublisherDisplayName = "Clogem (dev)",
  [string]$CertPassword = "clogem-dev",
  [switch]$Uninstall
)
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$hub = Resolve-Path (Join-Path $here "..\..")
$sdk = Resolve-Path (Join-Path $hub "..\clogem-sdk")
$build = Join-Path $here "build"
$stage = Join-Path $build "stage"
$msix = Join-Path $build "$IdentityName.msix"
$pfx = Join-Path $build "$IdentityName.pfx"

function Find-SdkTool($name) {
  $kits = "${env:ProgramFiles(x86)}\Windows Kits\10\bin"
  $tool = Get-ChildItem -Path $kits -Recurse -Filter $name -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -like "*\x64\*" } | Sort-Object FullName -Descending | Select-Object -First 1
  if (-not $tool) { throw "$name not found under $kits (install the Windows 10/11 SDK)" }
  $tool.FullName
}

if ($Uninstall) {
  Get-AppxPackage -Name $IdentityName | Remove-AppxPackage
  Get-ChildItem Cert:\LocalMachine\TrustedPeople | Where-Object { $_.Subject -eq $Publisher } | Remove-Item
  Write-Host "removed package $IdentityName and the $Publisher certificate from Trusted People"
  exit 0
}

Write-Host "== stage"
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force -Path $stage, (Join-Path $stage "bin"), (Join-Path $stage "hub"), (Join-Path $stage "clogem-sdk"), (Join-Path $stage "Assets") | Out-Null
Copy-Item $BbExe (Join-Path $stage "bin\bb.exe")
foreach ($d in "src", "resources", "bb.edn") { Copy-Item -Recurse (Join-Path $hub $d) (Join-Path $stage "hub\$d") }
foreach ($d in "src", "examples", "bb.edn", "deps.edn") { Copy-Item -Recurse (Join-Path $sdk $d) (Join-Path $stage "clogem-sdk\$d") }
# the hub's bb.edn depends on ../clogem-sdk: inside the package the SDK sits next to hub\
(Get-Content (Join-Path $stage "hub\bb.edn")) -replace '"\.\./clogem-sdk"', '"../clogem-sdk"' | Set-Content (Join-Path $stage "hub\bb.edn")
# placeholder tile images (solid squares) so MakeAppx accepts the manifest
Add-Type -AssemblyName System.Drawing
foreach ($spec in @(@("StoreLogo.png", 50), @("Square150x150Logo.png", 150), @("Square44x44Logo.png", 44))) {
  $bmp = New-Object System.Drawing.Bitmap($spec[1], $spec[1])
  $g = [System.Drawing.Graphics]::FromImage($bmp); $g.Clear([System.Drawing.Color]::DarkSlateBlue); $g.Dispose()
  $bmp.Save((Join-Path $stage "Assets\$($spec[0])"), [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
}

Write-Host "== launcher (dart compile exe)"
Push-Location (Join-Path $here "launcher")
try {
  dart pub get | Out-Null
  dart compile exe clogem_hub_launcher.dart -o (Join-Path $stage "clogem_hub_launcher.exe")
} finally { Pop-Location }

Write-Host "== manifest"
(Get-Content (Join-Path $here "AppxManifest.xml")) `
  -replace "PLACEHOLDER_IDENTITY_NAME", $IdentityName `
  -replace "PLACEHOLDER_PUBLISHER_DISPLAY_NAME", $PublisherDisplayName `
  -replace "PLACEHOLDER_PUBLISHER", $Publisher |
  Set-Content (Join-Path $stage "AppxManifest.xml") -Encoding utf8

Write-Host "== pack"
$makeappx = Find-SdkTool "makeappx.exe"
& $makeappx pack /v /h SHA256 /o /d $stage /p $msix
if ($LASTEXITCODE -ne 0) { throw "makeappx failed" }

Write-Host "== development certificate (Subject must equal the manifest Publisher)"
$cert = New-SelfSignedCertificate -Type Custom -Subject $Publisher -KeyUsage DigitalSignature `
  -CertStoreLocation "Cert:\CurrentUser\My" -FriendlyName "Clogem hub prototype (dev)" `
  -TextExtension @("2.5.29.37={text}1.3.6.1.5.5.7.3.3", "2.5.29.19={text}")
$pw = ConvertTo-SecureString -String $CertPassword -Force -AsPlainText
Export-PfxCertificate -Cert $cert -FilePath $pfx -Password $pw | Out-Null
Import-PfxCertificate -FilePath $pfx -CertStoreLocation "Cert:\LocalMachine\TrustedPeople" -Password $pw | Out-Null
Remove-Item $cert.PSPath

Write-Host "== sign"
$signtool = Find-SdkTool "signtool.exe"
& $signtool sign /fd SHA256 /a /f $pfx /p $CertPassword $msix
if ($LASTEXITCODE -ne 0) { throw "signtool failed" }

Write-Host "== install (sideload; needs 'Allow all trusted apps to install', the default on 2004+)"
Add-AppxPackage -Path $msix
$pkg = Get-AppxPackage -Name $IdentityName
Write-Host "installed $($pkg.PackageFullName) at $($pkg.InstallLocation)"

Write-Host "== probe"
$probe = [ordered]@{}
$probe.os = (Get-CimInstance Win32_OperatingSystem).Caption + " build " + (Get-CimInstance Win32_OperatingSystem).BuildNumber
$probe.package_family_name = $pkg.PackageFamilyName
$publisherId = $pkg.PackageFamilyName.Split("_")[-1]
$probe.publisher_cache_folder = "$env:LOCALAPPDATA\Publishers\$publisherId\clogem"
$probe.publisher_cache_folder_exists_before_launch = Test-Path $probe.publisher_cache_folder
# 1. the execution alias runs the launcher, which runs bb daemon; stop it after a few seconds
$proc = Start-Process -FilePath "clogem-hub.exe" -ArgumentList "daemon" -PassThru
Start-Sleep -Seconds 8
$probe.daemon_edn_in_shared_dir = Test-Path (Join-Path $probe.publisher_cache_folder "daemon.edn")
$probe.publisher_cache_folder_exists_after_launch = Test-Path $probe.publisher_cache_folder
$probe.launcher_log_real_localappdata = Test-Path "$env:LOCALAPPDATA\clogem\run\launcher.log"
$probe.launcher_log_virtualized = (Get-ChildItem -Recurse -Filter launcher.log "$env:LOCALAPPDATA\Packages\$($pkg.PackageFamilyName)" -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
$probe.bb_processes = @(Get-Process -Name bb -ErrorAction SilentlyContinue).Count
Stop-Process -Id $proc.Id -ErrorAction SilentlyContinue
Get-Process -Name bb -ErrorAction SilentlyContinue | Stop-Process
# 2. the startup task: state after the first launch (Enabled="true" is honoured after one run)
$probe.startup_task = (Get-ChildItem "HKCU:\Software\Classes\Local Settings\Software\Microsoft\Windows\CurrentVersion\AppModel\SystemAppData\$($pkg.PackageFamilyName)" -Recurse -ErrorAction SilentlyContinue | Where-Object { $_.Name -like "*ClogemHubDaemon*" } | ForEach-Object { $_.GetValue("State") }) -join ","
# 3. protocol activation: the launcher logs the URI it received
Start-Process "clogem-hubproto://open?media=m-test&a=1&b=2"
Start-Sleep -Seconds 3
$log = if ($probe.launcher_log_real_localappdata) { "$env:LOCALAPPDATA\clogem\run\launcher.log" } else { $probe.launcher_log_virtualized }
$probe.launcher_log_tail = if ($log -and (Test-Path $log)) { Get-Content $log -Tail 3 } else { "launcher.log not found" }
$probe | ConvertTo-Json -Depth 4 | Tee-Object -FilePath (Join-Path $build "probe.json")
Write-Host ""
Write-Host "Paste build\probe.json into the private session log (S05); then run with -Uninstall to remove the package and the certificate."
