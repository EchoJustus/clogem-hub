# ADR-0001: Modular monolith with two channels

Status: Accepted (S01, 2026-10-03)

## Context

The hub serves local LLM hosts and a desktop GUI with capabilities that are implemented by
several modules: built-in ones (`system`, `media`, `ui`) and others loaded at runtime from
`~/.config/clogem/modules.edn`. Each module needs state, external processes (ffmpeg, ffprobe,
whisper-cli), a local LLM and a database. Splitting them into processes would multiply SQLite
writers, ports and lifecycles on a single-user machine, and would let modules bypass the
daemon's policies (local-first egress, media safety, auditing).

## Decision

**One daemon per OS user, every module in-process.** The registry loads each module from its
`manifest.edn` (the single source of truth for its tools, resources, prompts, bus events,
migrations, UI card and routes), resolves its entry point and handlers under the module's own
namespace prefix (`clogem.module.<id>.*`), starts it and tracks its status: `:ready`,
`:degraded` (started, health not ok) or `:unavailable` (with a reason). Ordering is
deterministic: module id, then tool name. Registry-wide uniqueness of module ids, tool names and
resource URIs is enforced before a module starts; a conflicting module becomes `:unavailable`
instead of shadowing another.

**Two channels, two jobs.**

- Inside the daemon, modules talk only through the in-process bus (events are broadcast facts;
  commands go to a single owner and are answered on a reply channel) and the `clogem.api`
  facade from the SDK. Every API function takes the module `ctx` first and dispatches to the
  runtime the hub placed in `(:clogem/runtime ctx)`; there is no global state. The hub's
  runtime implements that protocol (`clogem.hub.runtime`); in S01 it answers registry commands
  and logs, the bus and the single writer follow in S02, processes and the LLM adapter in S04.
- Outside, MCP (JSON-RPC 2.0 over Streamable HTTP and stdio) is the only door, for LLM hosts
  and the GUI alike. The MCP layer (`clogem.hub.mcp.*`) carries no business logic: it validates
  framing and arguments, projects manifests into tool lists and calls handlers with the module
  ctx.

**Forbidden, and checked by `bb lint`:** a module requiring `clogem.hub.*` or another module's
namespaces; a module loading pods, spawning processes or opening HTTP connections itself
(`babashka.process`, `babashka.pods`, `clojure.java.shell`, `org.httpkit.*`,
`babashka.http-client` are not in the module allowlist; `clojure.core.async` is reserved for the
bus); printing outside `clogem.hub.log`; business logic in the MCP layer (a review rule); the
daemon acting as an MCP client (v1).

## Consequences

- Every module, built-in or not, is written the same way and tested against the SDK test kit
  without a running hub.
- A module failure is contained: it is reported in `system_health` and its tools disappear;
  the daemon keeps serving the others.
- Handler errors are tool results with `isError`, never protocol errors, so a host can show
  them; protocol errors are reserved for malformed requests, unknown methods and invalid
  arguments.
- Adding a capability means adding a manifest and handlers, never a port or a service.

## Revisit trigger

A module that cannot run in Babashka in-process (native dependencies, a different runtime), or
a requirement for process isolation (untrusted third-party modules). Either would need a
sandboxed out-of-process module protocol and a new record.
