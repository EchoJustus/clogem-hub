# clogem-hub — repository guardrails

Public repository, EPL-2.0. Everything committed here is visible to the world.
The workspace `CLAUDE.md` (one directory up) holds the prime directives; this file adds
what is specific to the hub.

## Purpose

The Clogem daemon: exactly one per OS user, a modular monolith. It owns the registry (loads
modules from their `manifest.edn`), the in-process `core.async` bus, the single-writer SQLite
store, the MCP facade (Streamable HTTP on 127.0.0.1:7788 plus a stdio proxy), the process runner
for ffmpeg/ffprobe/whisper-cli, the LLM provider adapter (Ollama first) and the runtime behind
`clogem.api`. Built-in modules: `system` (S01), `media` and `ui` (S04). Proprietary modules may be
loaded at runtime from `~/.config/clogem/modules.edn`; this repository never references them.

## Commands

```sh
bb test            # clojure.test over test/**/*_test.clj
bb lint            # fitness checks; must be green before every commit
bb dev             # daemon in the foreground; nREPL on a random loopback port, both recorded in daemon.edn
bb daemon          # daemon in the foreground without nREPL (systemd unit, stdio autostart); a second one exits 3
bb shim            # stdio MCP proxy to the running daemon, starting it when none answers
bb token <client>  # print a client's bearer token from clients.edn (created on first use)
bb systemd:install # write ~/.config/systemd/user/clogem-hub.service and print the enable commands
bb guard:public    # leak guard: denylist + home paths + tokens + private files, tree and history
bb hooks:install   # pre-commit (guard:public --staged) + commit-msg (guard:message) hooks
```

`bb guard:public` reads `../.clogem/public-denylist.txt` from the private workspace (or
`$CLOGEM_DENYLIST`). Outside the workspace it fails, except under CI where only the built-in
rules run. The denylist is never copied into this repository.

## Layout

```text
LICENSE  README.md  CLAUDE.md  bb.edn  .gitignore
src/clogem/hub/{registry,bus,config,log,daemon,runtime,jobs,lock,auth,audit,systemd}.clj
src/clogem/hub/mcp/{jsonrpc,methods,http,stdio,subscriptions}.clj   resources/clogem/hub/systemd/clogem-hub.service
src/clogem/hub/db/{sqlite,sql,writer,reader,migrate,store}.clj      resources/clogem/hub/db/migrations/NNNN-slug.sql
src/clogem/module/system/{core,tools}.clj                resources/clogem/module/system/manifest.edn
test/clogem/…   test/fixtures/jsonrpc/*.json (golden JSON-RPC fixtures)   test/fixtures/db/ (kill-test child script)
docs/adr/  docs/mcp-compliance.md  docs/deps.md
planned: bin/clogem-open (S07), src/clogem/hub/uri.clj, media/ llm/ and module/{media,ui} (S04), docs/roadmap.md
```

## Rules

- **Depends on the SDK:** `{:local/root "../clogem-sdk"}` (sibling checkout). Never copy SDK
  source into this repo; implement its runtime protocol. The SDK never depends on the hub.
- **Namespaces:** only `clogem.hub.*` and the built-in modules `clogem.module.{system,media,ui}.*`
  (`media` and `ui` from S04).
- **Two channels (PD-2):** modules, built-in ones included, talk only through the bus and
  `clogem.api`. A module never requires `clogem.hub.*` or another module, never calls the MCP
  endpoint; no business logic in the MCP layer; the daemon is not an MCP client (v1).
- **Single writer (PD-3):** only `clogem.hub.db.writer` runs SQL mutations, as owner of the bus
  command `:db/tx` on one thread draining a bounded queue, one pod call per transaction
  (`PRAGMA busy_timeout; PRAGMA foreign_keys=ON; BEGIN IMMEDIATE; …; COMMIT`). Only
  `clogem.hub.db.sqlite` loads the `org.babashka/go-sqlite3` pod, serializes every call (the pod
  deadlocks on concurrent calls), rejects blank SQL (it hangs the pod) and restarts a hung pod.
  Reads go through `clogem.api/query`: one read-only statement under `PRAGMA query_only=ON`.
  Migrations: `NNNN-slug.sql` files, `schema_migrations(module, version)`, module objects
  prefixed `<id>_`, WAL set once. Jobs: the `jobs` table behind `clogem.api/job!`. ADR-0003.
- **Bus (PD-2):** `clogem.hub.bus`: validated envelopes, one dispatcher thread, bounded per-subscriber
  channels and threads (`async/thread`, never go blocks), command owners with reply channels and
  timeouts, failures as `:bus/error`. Modules publish/subscribe only the event types their
  manifest declares; payloads are validated against the declared schema.
- **One daemon, many doors (PD-4):** every stdio entry point is a thin proxy to the running daemon.
  In stdio processes stdout carries protocol frames only; logs are one EDN map per line on
  stderr via `clogem.hub.log`. No `print`/`println`/`prn` elsewhere in daemon or stdio paths.
- **FFmpeg discipline (PD-5):** ffmpeg/ffprobe/whisper-cli only as CLI processes through
  `clogem.hub.media.process` (argv vectors, timeouts, cancellation, stderr to logs,
  `-progress pipe:1`). No media libraries on the Clojure side.
- **Never harm user media (PD-6):** outputs go to a temp file in the same directory, are verified
  (ffprobe + per-stream hashes) and atomically renamed to a NEW name. In-place replacement only
  behind an explicit option, after verification, with a backup.
- **Local-first (PD-9):** runtime egress only to explicitly configured loopback/LAN endpoints;
  config validation rejects anything else unless the user overrides. No telemetry.
- **Filesystem hygiene (PD-10):** data on ext4; refuse 9p/v9fs/drvfs paths (`findmnt -n -o FSTYPE -T`);
  sockets and locks in `$XDG_RUNTIME_DIR/clogem/`; never write under `/mnt/*`.
- **Spec over memory (PD-11):** MCP revision 2026-07-28 and legacy `initialize` clients. Read
  the spec before implementing; `docs/mcp-compliance.md` lists every method, header, field,
  status and error code with its source URL.
- **Headers:** every authored source file starts with the EPL-2.0 header; the exact text is
  `clogem.sdk.lint/header-lines`. Never write "All rights reserved" in this repo. No Secondary
  License is designated (changing that is a decision for the owner).
- **Public-safe wording (PD-8):** no proprietary code, private repository or module names,
  prompts, secrets, tokens, absolute personal paths, or session logs. `bb guard:public` enforces
  it; the denylist lives outside this repo.
- **Dependencies (§2.5):** exact pins; prefer bb built-ins; allowlisted additions are
  `metosin/malli` and the pod `org.babashka/go-sqlite3` 0.2.8 (only from `clogem.hub.db.sqlite`).
  Smoke-test and record new deps in `docs/deps.md`. `:min-bb-version` is the installed bb
  (1.13.225; floor 1.12.208, from which bb waits for non-daemon threads).
- **Conventions (§2.7):** manifests are the source of truth for tools, resources, prompts, events,
  migrations, UI cards and routes; tool names `^[a-z][a-z0-9_]{0,63}$` prefixed `<id>_`; honest
  `destructiveHint`/`idempotentHint`; resources `clogem://<module>/<kind>/<id>`; integer
  milliseconds internally; XDG paths; `clojure.edn` only for external EDN, never `eval`.
- **Guardrails are executable (PD-12):** never weaken, skip or delete a lint, guard or test.
- **MCP (S01):** `docs/mcp-compliance.md` is the rule book (every method, header, status and error
  code with its source). Dual-era: modern requests carry `params._meta`; `initialize`/`ping` are
  legacy. Every result has `resultType` and `_meta serverInfo`; lists carry `ttlMs`/`cacheScope`.
- **Lifecycle and security (S03, ADR-0005):** one daemon per user through `daemon.lock`
  (`clogem.hub.lock`); every HTTP request needs `Authorization: Bearer <token>` from
  `clients.edn` (mode 0600, `clogem.hub.auth`; 401 otherwise, no bypass); the client's profile
  scopes list and call, `:llm-small` sees at most 10 tools; `tools/call`, `resources/read`,
  `subscriptions/listen` and rejected authentications land in the `audit` table without
  arguments, results or tokens (`clogem.hub.audit`); `subscriptions/listen` is an SSE stream fed
  by `clogem.hub.mcp.subscriptions` from `:registry/changed` and `:resource/updated`; the stdio
  proxy carries the `stdio` client's token, streams listen frames and starts the daemon when
  none answers; `bb systemd:install` writes the user unit and only prints the enable commands.
- **ADRs:** `docs/adr/NNNN-slug.md`, public-safe: 0001 modular monolith and two channels, 0002
  dual-era MCP, 0003 single writer and pod semantics, 0005 lifecycle and security (lock, tokens,
  profiles, audit, subscription stream, systemd). Session logs never live here.
