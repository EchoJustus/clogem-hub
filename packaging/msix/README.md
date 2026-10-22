# Hub MSIX package — S05 prototype

A prototype, not the product package: it exists to measure, on a Windows 10 21H2 (build
19044) machine or a Windows 11 one, what the packaging decisions of workspace ADR-0006 rely on.
Nothing here is wired into `bb.edn`; the daemon's own lifecycle is unchanged.

What the package contains: `clogem_hub_launcher.exe` (the Dart launcher in `launcher/`,
compiled by the script), `bin\bb.exe` (Babashka, copied from the path you pass), `hub\`
(this repository's `src`, `resources`, `bb.edn`) and `clogem-sdk\` next to it, plus placeholder
tiles. The manifest (`AppxManifest.xml`) declares, for build 19044 and later:

| Element | Why |
|---|---|
| `uap10:RuntimeBehavior="packagedClassicApp"`, `uap10:TrustLevel="mediumIL"`, `runFullTrust` | a full-trust Win32 process: Babashka, SQLite pod, ffmpeg (ADR-0006 decision 3); `runFullTrust` is a restricted capability the Store reviews |
| `desktop:StartupTask` (`Enabled="true"`, parameters `daemon`) | the opt-in start-at-logon of decision 3; honoured after the first launch, user-controllable in Task Manager |
| `uap3:AppExecutionAlias` `clogem-hub.exe` | the command-line door (S07 adds the link opener as a second alias) |
| `uap3:Protocol` `clogem-hubproto` | the activation mechanics only; the product scheme `clogem://` belongs to the app packages (decision 9) and this throwaway scheme is removed before any submission |
| `windows.publisherCacheFolders` → `clogem` | the shared folder of decision 5 where `daemon.edn` and the token files are published (`CLOGEM_SHARED_DIR`) |

What the probe records in `build\probe.json` (paste it into the workspace session log S05):

1. whether the publisher cache folder exists at `%LOCALAPPDATA%\Publishers\<PublisherId>\clogem`
   before and after the first launch, and whether the daemon wrote `daemon.edn` there (the
   physical layout is the one Microsoft's own Teams deployment pages show for
   `Publishers\8wekyb3d8bbwe\…`; the app-model reference documents only the WinRT accessor,
   which has a reported failure for Desktop Bridge apps on Windows 10 2004/20H2, so the
   launcher uses the path, never WinRT);
2. whether a packaged process's writes under `%LOCALAPPDATA%\clogem\` land there or in the
   package's virtualized `%LOCALAPPDATA%\Packages\<pfn>\LocalCache\…`: Microsoft scopes
   AppData virtualization to appContainer apps and does not say whether a
   `packagedClassicApp`/`mediumIL` package is virtualized, so this is measured, for the launcher
   and for the child `bb.exe` (per-folder exclusions exist only from Windows 11; on 19044 the
   only switch is the all-or-nothing `unvirtualizedResources` restricted capability);
3. whether the launcher can start the in-package `bb.exe`: Microsoft documents that child
   processes of a packaged desktop app break away from the package by default
   (`PROCESS_CREATION_DESKTOP_APP_BREAKAWAY_*` on `UpdateProcThreadAttribute`), and the
   community failures involve launches through `cmd.exe`; the launcher starts `bb.exe` directly;
4. the startup task's state after the first launch;
5. protocol activation reaching the launcher with the URI.

Run (elevated PowerShell, in the hub checkout with `..\clogem-sdk` next to it):

```powershell
powershell -ExecutionPolicy Bypass -File packaging\msix\build-msix.ps1 -BbExe C:\tools\bb\bb.exe
powershell -ExecutionPolicy Bypass -File packaging\msix\build-msix.ps1 -BbExe C:\tools\bb\bb.exe -Uninstall
```

Known constraints the probe does not change: the startup task registers only after the user
has launched the app once, and a task the user disables in Task Manager cannot be re-enabled by
the app; web one-click install through `ms-appinstaller:` is disabled by default since December
2023, so sideloading is a file download plus `Add-AppxPackage` (the Store is the product channel).

It needs the Windows 10/11 SDK (MakeAppx, SignTool), Flutter 3.47.7 (`dart compile exe`) and
Babashka. It creates a self-signed development certificate whose subject equals the manifest
publisher, imports it into Trusted People, signs with `/fd SHA256`, sideloads with
`Add-AppxPackage`, probes, and `-Uninstall` removes both. The Store re-signs submissions, so no
certificate authority is involved later; the identity name and publisher of a real submission
come from Partner Center and must match the manifest exactly.
