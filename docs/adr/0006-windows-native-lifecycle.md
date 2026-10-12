# ADR-0006: Windows as the product platform: paths, owner-only files, in-band shutdown, volume check

Status: Accepted (S03b, 2026-10-12). Amends ADR-0005.

## Context

The hub was built and verified on Linux (WSL2): a systemd user unit, XDG directories, mode 0600
on `clients.edn`, SIGTERM as the graceful stop, `findmnt` behind the filesystem rule. The product
line is delivered on native Windows (Windows 10 version 21H2, build 19044, or later) through the
Microsoft Store, with the hub as a per-user, full-trust core process that the apps start on
demand. Linux stays a development and CI platform. The daemon's MCP facade, bus, registry and
single-writer database were already platform-neutral; what follows is the thin layer that was not,
and what Babashka allows: no `java.nio.file.attribute.Acl*` classes and no methods on
`java.nio.file.FileStore` are reachable from Babashka 1.13, so the Windows checks shell out to
the in-box Windows PowerShell and speak in SIDs, never in display names.

## Decision

- **One namespace for platform differences.** `clogem.hub.platform` holds every call that exists
  on one platform only (POSIX permissions, `findmnt`, PowerShell), behind `windows?`. Decisions
  (`secure-acl?`, `volume-problem`) are pure functions over the tools' output and are unit-tested
  on either host; the shell glue runs in the Windows CI job. The lint rule `:restricted-call`
  (SDK `clogem.sdk.lint`) refuses those calls in any other namespace.
- **Directories.** Windows: config and data in `%LOCALAPPDATA%\clogem\`, cache in
  `…\clogem\cache\`, runtime files (`daemon.edn`, `daemon.lock`, `daemon.log`) in
  `…\clogem\run\`. POSIX keeps the XDG layout. `CLOGEM_CONFIG_DIR`, `CLOGEM_SHARED_DIR` and the
  `XDG_*` variables override on both platforms (`clogem.hub.config/paths` is pure and tested for
  both). A configured shared directory (the packaged hub's publisher cache folder, handed in by
  the package launcher as `CLOGEM_SHARED_DIR`) receives a copy of `daemon.edn` and one owner-only
  `<client>.token` file per client, which is how other packages of the same publisher discover
  and authenticate to the hub; `clients.edn` stays the source of truth.
- **Owner-only files.** `clients.edn` and the token files are readable by this user only: mode
  0600 on POSIX; on Windows a DACL whose Allow entries name only the caller, SYSTEM
  (S-1-5-18) and Administrators (S-1-5-32-544), set with inheritance off through `Set-Acl` and
  verified through `Get-Acl` translated to SIDs. A file that does not pass is refused, never
  repaired.
- **The lock.** `daemon.lock` keeps `java.nio` `tryLock`. On Windows the lock is mandatory: another
  process can probe it but cannot read the locked bytes, so the holder's pid is read from
  `daemon.edn` next to the lock when the file cannot be read, and the lock file is never
  deleted while held. The POSIX descriptor rule (never open a second descriptor on a file this
  process locks) stays.
- **Graceful stop in-band.** Windows has no SIGTERM and a hard kill runs nothing, so the stop is
  a tool: `system_shutdown` (admin profile only, `destructiveHint true`, `idempotentHint true`,
  `_meta {"anthropic/requiresUserInteraction": true}`, audited like every `tools/call`). The
  `system` module sends the bus command `:system/shutdown`; the daemon owns it, replies at once
  and stops on another thread 250 ms later (streams completed, audit and writer drained,
  database closed, lock released); under `bb daemon` the process then exits 0. SIGTERM and SIGINT
  keep their handlers where the OS delivers them, and the JVM shutdown hook remains the last
  resort. A stop runs once per daemon (`:stopped?`), later calls are no-ops. The systemd unit,
  `bb systemd:install` and `clogem.hub.systemd` are removed; the stdio proxy's autostart runs a
  detached `bb daemon` with the binary running the proxy.
- **Volume check (PD-10).** Windows: the drive holding the database must be a fixed NTFS or ReFS
  volume (`System.IO.DriveInfo`); UNC paths, network, removable, optical and FAT volumes are
  refused; a path under a OneDrive folder is allowed with a warning. POSIX: 9p, v9fs, drvfs and
  network filesystems are refused (`findmnt`). Unknown values pass with a warning.
- **CI and evidence.** The public repositories run `bb test`, `bb lint` and `bb guard:public` on
  `ubuntu-latest` and `windows-latest` with Babashka 1.13.225 downloaded from its release
  (checksum pinned). `windows-latest` is a current Windows Server image, so the build-19044
  floor is proven separately with `scripts/acceptance-windows.ps1` on such a machine (second
  daemon refuses, no token → 401, `system_shutdown` leaves the database consistent, the lock is
  released), its output recorded by the owner.

## Consequences

- Reading `clients.edn` costs one PowerShell process on Windows (daemon start, `bb token`, proxy
  start); acceptable for a per-user daemon.
- The tests exercise the platform they run on: the SIGTERM stop test runs on POSIX, the
  `system_shutdown` stop test on Windows, the in-process `system_shutdown` test everywhere.
- Rotating a token still means editing `clients.edn` and restarting (now through
  `system_shutdown`); the shared token files are rewritten on the next start.
- Nothing is installed on Windows beyond Babashka: the owner-only check and the volume check use
  the in-box Windows PowerShell.

## Revisit trigger

Babashka exposing `AclFileAttributeView` or `FileStore` methods (drop the PowerShell glue); a
Linux or macOS product build (systemd or launchd return as a packaging concern); a request for
a reload-on-demand of `clients.edn`.
