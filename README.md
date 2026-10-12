# clogem-hub

Local-first Babashka daemon for the Clogem ecosystem: module registry, in-process event bus, single-writer SQLite, and an MCP interface (HTTP + stdio) for local LLM hosts and GUI clients. 

Status: S03b. Windows is the product platform (see `docs/adr/0006-windows-native-lifecycle.md`); Linux stays a development and CI platform. One daemon per OS user (`daemon.lock`), serving MCP over Streamable HTTP (`POST /mcp` on 127.0.0.1:7788) and through a stdio proxy that starts the daemon when none answers, as a dual-era server: stateless 2026-07-28 requests and the legacy `initialize` handshake (see `docs/mcp-compliance.md`). Every request carries a bearer token from `clients.edn` in the config directory (`%LOCALAPPDATA%\clogem\` on Windows, `~/.config/clogem/` on POSIX; readable by this user only; created on first start; `bb token llm` prints one) whose profile scopes the tools and resources it sees; `tools/call` and `resources/read` are audited; `subscriptions/listen` streams `tools/list_changed`, `resources/list_changed` and `resources/updated` over SSE (see `docs/adr/0005-lifecycle-and-security.md`). Loaded modules come from `manifest.edn` files validated by the SDK; the built-in `system` module exposes `system_health`, `system_list_modules` and, for admin clients, `system_shutdown`, the graceful in-band stop (Windows has no SIGTERM). Inside the daemon an in-process bus carries events and commands; the database (`clogem.db` in the data directory, WAL, on a local NTFS/ReFS volume) is written by one writer thread through the `org.babashka/go-sqlite3` pod, read through a read-only facade, migrated per module and shared by the `jobs` and `audit` tables (see `docs/adr/0003-single-writer-and-pod-semantics.md`). CI runs the tests on `ubuntu-latest` and `windows-latest`; `scripts/acceptance-windows.ps1` repeats the acceptance checks on a build-19044 machine. Next: S04 (plugin runtime, process runner, media and ui modules, LLM adapter).

## Commands

```sh
bb test            # clojure.test over test/**/*_test.clj
bb lint            # fitness checks: headers, namespace ownership, module isolation, pods, prints, manifests, home paths
bb dev             # daemon in the foreground (CLOGEM_PORT overrides 7788) with an nREPL on a random loopback port
bb shim            # stdio MCP proxy to the running daemon (for hosts that spawn stdio servers)
bb guard:public    # leak guard over the tree and git history
bb guard:message <file>  # leak guard over a commit message (commit-msg hook)
bb hooks:install   # pre-commit (guard:public --staged) and commit-msg (guard:message) hooks
```

Requires Babashka 1.13.225 or newer (the SQLite pod downloads on first start), a sibling checkout of `clogem-sdk` (`../clogem-sdk`), and a JVM on PATH for the first classpath resolution of that sibling dependency (cached afterwards). On Windows the in-box Windows PowerShell is used for the owner-only file check and the volume check; nothing else is installed.

## License

© 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com). Licensed under the Eclipse Public License 2.0; see LICENSE.
