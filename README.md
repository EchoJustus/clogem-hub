# clogem-hub

Local-first Babashka daemon for the Clogem ecosystem: module registry, in-process event bus, single-writer SQLite, and an MCP interface (HTTP + stdio) for local LLM hosts and GUI clients. 

Status: S03. One daemon per OS user (`daemon.lock`), serving MCP over Streamable HTTP (`POST /mcp` on 127.0.0.1:7788) and through a stdio proxy that starts the daemon when none answers, as a dual-era server: stateless 2026-07-28 requests and the legacy `initialize` handshake (see `docs/mcp-compliance.md`). Every request carries a bearer token from `~/.config/clogem/clients.edn` (mode 0600, created on first start; `bb token llm` prints one) whose profile scopes the tools and resources it sees; `tools/call` and `resources/read` are audited; `subscriptions/listen` streams `tools/list_changed`, `resources/list_changed` and `resources/updated` over SSE (see `docs/adr/0005-lifecycle-and-security.md`). Loaded modules come from `manifest.edn` files validated by the SDK; the built-in `system` module exposes `system_health` and `system_list_modules`. Inside the daemon an in-process bus carries events and commands; the database (`~/.local/share/clogem/clogem.db`, WAL) is written by one writer thread through the `org.babashka/go-sqlite3` pod, read through a read-only facade, migrated per module and shared by the `jobs` and `audit` tables (see `docs/adr/0003-single-writer-and-pod-semantics.md`). `bb systemd:install` writes the user unit and prints the commands to enable it. Next: S04 (plugin runtime, process runner, media and ui modules, LLM adapter).

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

Requires Babashka 1.13.225 or newer (the SQLite pod downloads on first start), a sibling checkout of `clogem-sdk` (`../clogem-sdk`), and a JVM on PATH for the first classpath resolution of that sibling dependency (cached afterwards).

## License

© 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com). Licensed under the Eclipse Public License 2.0; see LICENSE.
