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
bb shim            # stdio MCP proxy to the running daemon
bb guard:public    # leak guard: denylist + home paths + tokens + private files, tree and history
bb hooks:install   # pre-commit (guard:public --staged) + commit-msg (guard:message) hooks
```

`bb guard:public` reads `../.clogem/public-denylist.txt` from the private workspace (or
`$CLOGEM_DENYLIST`). Outside the workspace it fails, except under CI where only the built-in
rules run. The denylist is never copied into this repository.

## Layout

```text
LICENSE  README.md  CLAUDE.md  bb.edn  .gitignore
src/clogem/hub/{registry,config,log,daemon,runtime}.clj   src/clogem/hub/mcp/{jsonrpc,methods,http,stdio}.clj
src/clogem/module/system/{core,tools}.clj                resources/clogem/module/system/manifest.edn
test/clogem/…   test/fixtures/jsonrpc/*.json (golden JSON-RPC fixtures)
docs/adr/  docs/mcp-compliance.md  docs/deps.md
planned: bin/clogem-open (S07), src/clogem/hub/{bus,uri}.clj and db/ (S02), media/ llm/ and
         module/{media,ui} (S04), resources/…/migrations/ (S02), docs/roadmap.md
```

## Rules

- **Depends on the SDK:** `{:local/root "../clogem-sdk"}` (sibling checkout). Never copy SDK
  source into this repo; implement its runtime protocol. The SDK never depends on the hub.
- **Namespaces:** only `clogem.hub.*` and the built-in modules `clogem.module.{system,media,ui}.*`
  (`media` and `ui` from S04).
- **Two channels (PD-2):** modules, built-in ones included, talk only through the bus and
  `clogem.api`. A module never requires `clogem.hub.*` or another module, never calls the MCP
  endpoint; no business logic in the MCP layer; the daemon is not an MCP client (v1).
- **Single writer (PD-3):** only `clogem.hub.db.writer` runs SQL mutations, on one thread draining
  a bounded queue; only `clogem.hub.db.*` loads the `org.babashka/go-sqlite3` pod. Reads go
  through `clogem.api/query` with `PRAGMA query_only=ON`.
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
  `metosin/malli` and the pod `org.babashka/go-sqlite3` (only from `clogem.hub.db.*`).
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
- **ADRs:** `docs/adr/NNNN-slug.md`, public-safe: 0001 modular monolith and two channels, 0002
  dual-era MCP, loopback-only and unauthenticated until S03. Session logs never live here.
