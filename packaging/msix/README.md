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
   physical path is community knowledge; Microsoft documents only the WinRT accessor);
2. whether a packaged process's writes under `%LOCALAPPDATA%\clogem\` land there or in the
   package's virtualized `%LOCALAPPDATA%\Packages\<pfn>\LocalCache\…` (file-system
   virtualization applies to packaged desktop apps and is deleted at uninstall), both for the
   launcher and for the child `bb.exe`;
3. whether the launcher can start the in-package `bb.exe` at all (a child process of a packaged
   app may need the breakaway attribute or its own `<Application>` entry);
4. the startup task's state after the first launch;
5. protocol activation reaching the launcher with the URI.

Run (elevated PowerShell, in the hub checkout with `..\clogem-sdk` next to it):

```powershell
powershell -ExecutionPolicy Bypass -File packaging\msix\build-msix.ps1 -BbExe C:\tools\bb\bb.exe
powershell -ExecutionPolicy Bypass -File packaging\msix\build-msix.ps1 -BbExe C:\tools\bb\bb.exe -Uninstall
```

It needs the Windows 10/11 SDK (MakeAppx, SignTool), Flutter 3.47.7 (`dart compile exe`) and
Babashka. It creates a self-signed development certificate whose subject equals the manifest
publisher, imports it into Trusted People, signs with `/fd SHA256`, sideloads with
`Add-AppxPackage`, probes, and `-Uninstall` removes both. The Store re-signs submissions, so no
certificate authority is involved later; the identity name and publisher of a real submission
come from Partner Center and must match the manifest exactly.
