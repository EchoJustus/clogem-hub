# ADR-0005: One daemon per user, bearer tokens with profiles, audit, subscription stream

Status: Accepted (S03, 2026-10-12). Supersedes the "unauthenticated until S03" part of ADR-0002.

## Context

S01 and S02 built a dual-era MCP daemon over a registry, a bus and a single-writer database,
trusting every local process. S03 has to make it a service: exactly one instance per OS user,
started by systemd or on demand, that authenticates its clients, scopes what each may see and
call, keeps a record of what was called, and pushes change notifications the way MCP 2026-07-28
defines them (`subscriptions/listen`; the GET stream, sessions and `resources/subscribe` are
gone from that revision). The hub runs under WSL2 for a single user; its clients are local
processes of that user (a GUI, LLM hosts over HTTP, the stdio proxy).

## Decision

- **Single instance by OS file lock.** `start!` takes `daemon.lock` in the runtime directory
  (`java.nio` `tryLock`, an fcntl lock the kernel drops when the holder dies) before anything
  else and refuses with `:already-running` (exit code 3 from `bb daemon`) when it is held. The
  lock file is never deleted; `daemon.edn` stays the record other doors read (pid, port, lock,
  clients file). Babashka cannot touch the `FileLock` object, so releasing means closing the
  channel.
- **Authentication by bearer token, no OAuth.** `clients.edn` (config directory, mode 0600,
  created on first start with one client per default profile and random 256-bit tokens; any
  other permission is refused, not repaired) maps a client id to `{:profile :token}`. Every
  POST must carry `Authorization: Bearer <token>`; otherwise `401` with
  `WWW-Authenticate: Bearer realm="clogem-hub"` and an id-less JSON-RPC `-32600` body.
  Authentication precedes content-type and body checks; tokens are compared in constant time
  and never logged. Trust model: the file is readable by the OS user only, so possessing a token
  means being that user; this is why OAuth adds nothing here (ADR-0002's deviation stands).
- **Profiles scope list and call alike.** The authenticated client's profile (`:gui`,
  `:llm-small`, `:llm-large`, `:admin`) selects the tools, resources and templates a manifest
  exposes to it; a tool outside the profile is unknown to it (`-32602`). `:llm-small` sees at
  most 10 tools (`:profiles {:llm-small {:max-tools 10}}`): the registry keeps the first ten in
  deterministic order and warns once about the hidden rest, and `tools/call` resolves against
  the same capped list.
- **Audit without payloads.** The `audit` table (hub migration 0002) records one row per
  `tools/call`, `resources/read`, `subscriptions/listen` and per rejected authentication: time,
  client, profile, method, tool or URI name, ok flag, a capped error text, duration and request
  id. Never arguments, results or tokens. Rows go through a bounded queue and one thread
  submitting `:db/tx` as `:hub`, so a request never waits for the database and an overflow drops
  rows (counted) instead of blocking.
- **Resources and the subscription stream.** `resources/list`, `resources/templates/list` and
  `resources/read` are served from the manifests (handler contract in the SDK plugin guide:
  `{:text}` or `{:blob}` plus `:mime-type`, `nil` is `-32602 Resource not found`, an exception
  is `-32603` without the message). `subscriptions/listen` is modern-only and answers with an
  SSE stream: the status line travels with the acknowledgment (an empty first chunk would end
  the response), every message carries `io.modelcontextprotocol/subscriptionId` (the request
  id), the honored filter drops `promptsListChanged` and URIs the profile cannot read,
  `:registry/changed` on the bus becomes `tools/list_changed` and `resources/list_changed`,
  a module's `:resource/updated` event (SDK contract) becomes `resources/updated` for
  subscribed URIs, quiet streams get an SSE comment every 15 s, a client's disconnect ends its
  subscription, and stopping the daemon answers every open listen request with a
  `resultType "complete"` result before closing. Legacy clients are promised no notifications
  (`initialize` advertises `listChanged false`, `subscribe false`) and `GET /mcp` stays 405.
  Spec conflict resolved: over HTTP the graceful end is the listen result; the
  `notifications/cancelled` the cancellation page demands is a stdio rule, emitted by the
  stdio proxy when a stream ends without that result.
- **The stdio proxy is a full door.** It speaks as the `stdio` client of `clients.edn`
  (`CLOGEM_CLIENT` overrides), streams a `subscriptions/listen` as frames until the client's
  `notifications/cancelled` or the daemon ends it, and starts the daemon when none answers:
  `systemctl --user start clogem-hub` when that unit is enabled, else a detached `bb daemon`
  whose stderr goes to `<runtime-dir>/daemon.log`, waiting up to 15 s for `daemon.edn`.
- **systemd is rendered, never enabled, by the hub.** `bb systemd:install` writes the user unit
  (`ExecStart=bb --config <hub>/bb.edn daemon`, `KillSignal=SIGTERM`, `TimeoutStopSec=90`,
  `RestartPreventExitStatus=3`, stdout to null, stderr to the journal) and prints the enable
  commands. Decisions left to the owner, with fallbacks: linger (`loginctl enable-linger`;
  without it the daemon lives with the login session), WSL idle shutdown (`.wslconfig`
  `[wsl2] vmIdleTimeout`, default 60000 ms, and `[general] instanceIdleTimeout`, default
  15000 ms, -1 disables; read on Microsoft Learn 2026-10-12), keep-alive (none; the stdio proxy
  restarts a stopped daemon on demand).

## Consequences

- Claude Code connects over HTTP with `--header "Authorization: Bearer $(bb token llm)"` and
  over stdio with no configuration; `bb token <client>` prints a token (creating the file when
  missing).
- Every list result may differ per client (profiles), which is why cache hints stay
  `cacheScope "private"`.
- A client that loses its stream must re-subscribe; nothing is replayed (no `Last-Event-ID`).
- Rotating a token means editing `clients.edn` and restarting the daemon; a reload-on-SIGHUP is
  not implemented.
- The GUI's pairing (ADR-0004) is this file: a local GUI of the same user reads its token
  from `clients.edn`.

## Revisit trigger

A second OS user or a remote client (then OAuth or mTLS, not shared files); MCP publishing a
stdio-side stream semantics change; systemd socket activation, if start latency ever matters.
