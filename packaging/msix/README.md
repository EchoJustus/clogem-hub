# Hub MSIX package — S05 prototype

A prototype, not the product package: it exists to measure, on a Windows 10 21H2 (build
19044) machine or a Windows 11 one, what the packaging decisions of workspace ADR-0006 rely on.
Nothing here is wired into `bb.edn`; the daemon's own lifecycle is unchanged.

What the package contains: `clogem_hub_launcher.exe` (the Dart launcher in `launcher/`,
compiled by the script), `bin\bb.exe` (Babashka, copied from the path you pass), `hub\`
(this repository's `src`, `resources`, `bb.edn`) and `clogem-sdk\` next to it, `pods\` (the
SQLite pod `org.babashka/go-sqlite3` 0.2.8 for Windows in the pod registry's layout, downloaded by
the script at build time), plus placeholder tiles. The manifest (`AppxManifest.xml`) declares, for
build 19044 and later:

| Element | Why |
|---|---|
| `uap10:RuntimeBehavior="packagedClassicApp"`, `uap10:TrustLevel="mediumIL"`, `runFullTrust` | a full-trust Win32 process: Babashka, SQLite pod, ffmpeg (ADR-0006 decision 3); `runFullTrust` is a restricted capability the Store reviews |
| `desktop:StartupTask` (`Enabled="true"`, parameters `daemon`) | the start-at-logon of decision 3, honoured after the first launch and user-controllable in Task Manager. `Enabled="true"` makes the registration observable in the probe but inverts decision 3 (opt-in): the product manifest says `Enabled="false"` and the hub enables the task from its settings with `RequestEnableAsync` (no consent dialog for a packaged desktop app) |
| `uap3:AppExecutionAlias` `clogem-hub.exe` | the command-line door. S07 adds the link opener: Microsoft's desktop-extensions page allows one alias per application while the `uap3:AppExecutionAlias` schema allows up to 100 choices, so S07 either adds a second `Application` or verifies a second alias on the VM |
| `uap3:Protocol` `clogem-hubproto` | the activation mechanics only; the product scheme `clogem://` belongs to the app packages (decision 8) and this throwaway scheme is removed before any submission |
| `windows.publisherCacheFolders` → `clogem` | the shared folder of decision 5 where `daemon.edn` and the token files are published (`CLOGEM_SHARED_DIR`) |

First launch, two facts the package design rests on: the hub loads the SQLite pod by registry
coordinates at start (`clogem.hub.db.sqlite`), which on a plain workstation downloads it from
GitHub into `%USERPROFILE%\.babashka\pods` on the first run (hub `docs/deps.md`); a product package
may not do that (PD-9: no runtime egress beyond the configured local endpoints), so the script
stages the Windows pod inside the package and the launcher points Babashka at it
(`BABASHKA_PODS_DIR`, with `BABASHKA_PODS_OS_NAME`/`BABASHKA_PODS_OS_ARCH` fixed so the lookup is
`windows\x86_64`); the probe records whether a profile copy appeared anyway. Second, the package
directory is read-only: Babashka's dependency resolution (deps.clj) places its `.cpcache` in the
user cache directory when the deps directory is not writable, which the probe does not check.

What the probe records in `build\probe.json` (paste it into the workspace session log S05):

1. whether the publisher cache folder exists at `%LOCALAPPDATA%\Publishers\<PublisherId>\clogem`
   before and after the first launch, and whether the daemon wrote `daemon.edn` there within
   two minutes (the physical layout is the one Microsoft's Teams admin pages show for
   `Publishers\8wekyb3d8bbwe\…`, not the app-model reference, which documents only the WinRT
   accessor with a reported failure for Desktop Bridge apps on Windows 10 2004/20H2; so the
   launcher uses the path, never WinRT);
2. whether a packaged process's writes under `%LOCALAPPDATA%\clogem\` land there or in the
   package's virtualized `%LOCALAPPDATA%\Packages\<pfn>\LocalCache\…`: Microsoft scopes
   AppData virtualization to appContainer apps and does not say whether a
   `packagedClassicApp`/`mediumIL` package is virtualized, so this is measured, for the launcher
   and for the child `bb.exe` (per-folder exclusions exist only from Windows 11; on 19044 the
   only switch is the all-or-nothing `unvirtualizedResources` restricted capability);
3. whether the launcher can start the in-package `bb.exe`. Microsoft documents that child
   processes of a packaged desktop app break away from the package by default
   (`PROCESS_CREATION_DESKTOP_APP_BREAKAWAY_*` on `UpdateProcThreadAttribute`). The community
   thread WindowsAppSDK#4651 reports `ERROR_ACCESS_DENIED` for exactly this case, a direct
   `CreateProcess` of an in-package helper that has no `Application` or alias of its own, with
   success once the helper is declared; only its Node `exec` case was explained by `cmd.exe`.
   dart:io's `Process.start` cannot set the breakaway attribute, so this probe decides the
   design: if it records "launch failed", the options are a second `Application`/execution
   alias for `bin\bb.exe` (with the "headless app" Store-review risk the same thread reports),
   the Package Support Framework's `inPackageContext` fixup, or a native launcher that passes
   `PROCESS_CREATION_DESKTOP_APP_BREAKAWAY_OVERRIDE`;
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

It needs the Windows 10/11 SDK (MakeAppx, SignTool), Flutter 3.47.7 (`dart compile exe`),
Babashka and, at build time, GitHub (the pod zip and its registry manifest; development egress,
not runtime). It creates a self-signed development certificate whose subject equals the manifest
publisher, imports it into Trusted People, signs with `/fd SHA256`, sideloads with
`Add-AppxPackage`, probes, and `-Uninstall` removes both. The Store re-signs submissions, so no
certificate authority is involved later; the identity name and publisher of a real submission
come from Partner Center and must match the manifest exactly.
