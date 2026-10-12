# © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License 2.0 which is available at
# https://www.eclipse.org/legal/epl-2.0
#
# SPDX-License-Identifier: EPL-2.0

<#
.SYNOPSIS
  The S03b acceptance checks on a Windows machine (ADR-0006), meant for a
  Windows 10 build-19044 machine, which the GitHub-hosted Windows runner is
  not. Runs under the in-box Windows PowerShell 5.1 from a checkout with the
  SDK next to it:

    powershell -ExecutionPolicy Bypass -File scripts\acceptance-windows.ps1

  Everything runs against temporary directories under %TEMP%; nothing of the
  user's configuration is touched. Prints one PASS/FAIL line per check and
  exits 1 when any failed. Paste the output into the session log.
#>

$ErrorActionPreference = 'Stop'
$hub = Split-Path -Parent $PSScriptRoot
Set-Location $hub

$script:results = @()
function Check([string]$name, [scriptblock]$body) {
  $verdict = 'FAIL'; $note = ''
  try { if (& $body) { $verdict = 'PASS' } } catch { $note = " (" + $_.Exception.Message + ")" }
  $script:results += [pscustomobject]@{ check = $name; result = $verdict }
  Write-Host ("{0,-62} {1}{2}" -f $name, $verdict, $note)
}

$os = Get-CimInstance Win32_OperatingSystem
Write-Host ("Windows {0} build {1} ({2}); bb {3}" -f $os.Version, $os.BuildNumber, $os.Caption, (bb --version))

$base = Join-Path $env:TEMP ("clogem-acceptance-" + [guid]::NewGuid().ToString('N'))
$config = Join-Path $base 'config'; $data = Join-Path $base 'data'; $run = Join-Path $base 'run'
New-Item -ItemType Directory -Path $config, $data, $run | Out-Null
Set-Content -Path (Join-Path $config 'config.edn') -Value '{:http {:port 0} :stdio {:autostart? false}}'
$env:CLOGEM_CONFIG_DIR = $config
$env:XDG_DATA_HOME = $data
$env:XDG_RUNTIME_DIR = $run
$runtime = Join-Path $run 'clogem'
$daemonEdn = Join-Path $runtime 'daemon.edn'
$dbPath = (Join-Path (Join-Path $data 'clogem') 'clogem.db') -replace '\\', '/'

# Clojure snippets go through files: Windows PowerShell 5.1 cannot pass
# embedded double quotes to a native command reliably.
$lockCheck = Join-Path $base 'lock-check.clj'
Set-Content -Path $lockCheck -Value "(require 'clogem.hub.lock) (System/exit (if (clogem.hub.lock/held? (System/getenv `"CLOGEM_RUN`")) 1 0))"
$integrity = Join-Path $base 'integrity.clj'
Set-Content -Path $integrity -Value "(require '[clogem.hub.db.sqlite :as s]) (println (pr-str (s/query `"$dbPath`" `"PRAGMA integrity_check`")))"

Check 'bb test is green' { (bb test 2>&1 | Out-String) -match '0 failures, 0 errors' }
Check 'bb lint is green' { (bb lint 2>&1 | Out-String) -match ' 0 violation' }

$daemon = Start-Process -FilePath 'bb' -ArgumentList 'daemon' -PassThru -WindowStyle Hidden -RedirectStandardError (Join-Path $base 'daemon.err')
$deadline = (Get-Date).AddSeconds(60)
while (-not (Test-Path $daemonEdn) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 200 }
Check 'the daemon records itself in daemon.edn' { Test-Path $daemonEdn }
$port = [regex]::Match((Get-Content $daemonEdn -Raw), ':port (\d+)').Groups[1].Value
$url = "http://127.0.0.1:$port/mcp"

Check 'a second daemon refuses (exit 3)' {
  $second = Start-Process -FilePath 'bb' -ArgumentList 'daemon' -PassThru -WindowStyle Hidden -Wait -RedirectStandardError (Join-Path $base 'second.err')
  $second.ExitCode -eq 3
}
Check 'clients.edn is owner-only' {
  $acl = Get-Acl -LiteralPath (Join-Path $config 'clients.edn')
  $me = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
  $foreign = @($acl.Access | Where-Object { $_.AccessControlType -eq 'Allow' } | ForEach-Object {
      $_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value } |
    Where-Object { $_ -notin @($me, 'S-1-5-18', 'S-1-5-32-544') })
  $foreign.Count -eq 0
}
Check 'no token: 401' {
  try { Invoke-WebRequest -Uri $url -Method Post -ContentType 'application/json' -Body '{"jsonrpc":"2.0","id":1,"method":"ping"}' -UseBasicParsing | Out-Null; $false }
  catch { $_.Exception.Response.StatusCode.value__ -eq 401 }
}
$admin = (bb token admin | Out-String).Trim()
$headers = @{ Authorization = "Bearer $admin"; 'Mcp-Method' = 'tools/call'; 'Mcp-Name' = 'system_shutdown'; 'MCP-Protocol-Version' = '2026-07-28'; Accept = 'application/json, text/event-stream' }
$call = '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"system_shutdown","arguments":{},"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}}}'
Check 'system_shutdown replies {stopping true}' {
  $r = Invoke-RestMethod -Uri $url -Method Post -ContentType 'application/json' -Headers $headers -Body $call
  $r.result.structuredContent.stopping -eq $true
}
Check 'the daemon exits 0 after the stop' { $daemon.WaitForExit(60000) | Out-Null; $daemon.ExitCode -eq 0 }
Check 'daemon.edn removed and the lock released' {
  $env:CLOGEM_RUN = $runtime
  bb $lockCheck | Out-Null
  (-not (Test-Path $daemonEdn)) -and ($LASTEXITCODE -eq 0)
}
Check 'the database passes integrity_check' { (bb $integrity 2>$null | Out-String) -match ':integrity_check "ok"' }
Check 'stderr carried EDN log lines only' {
  $lines = @(Get-Content (Join-Path $base 'daemon.err') | Where-Object { $_.Trim() -ne '' })
  @($lines | Where-Object { -not $_.StartsWith('{') }).Count -eq 0
}

Remove-Item -Recurse -Force $base -ErrorAction SilentlyContinue
$failed = @($script:results | Where-Object { $_.result -eq 'FAIL' }).Count
Write-Host ("{0} checks, {1} failed" -f $script:results.Count, $failed)
exit $(if ($failed -gt 0) { 1 } else { 0 })
