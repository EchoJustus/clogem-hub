# ADR-0002: Dual-era MCP server, loopback-only, unauthenticated until S03

Status: Accepted (S01, 2026-10-03)

## Context

MCP revision 2026-07-28 made the protocol stateless: the `initialize` handshake, sessions,
`ping` and server-initiated requests are gone; every request carries its protocol version and
client capabilities in `params._meta`, `server/discover` is mandatory, results carry
`resultType` and list results carry cache hints. Hosts are in transition: Claude Code negotiates
2026-07-28 over HTTP but keeps stdio servers on the `initialize` handshake by default, and MCP
Inspector defaults to the legacy era. The hub must serve both from one daemon
(`docs/mcp-compliance.md` records every rule with its source).

The spec also says HTTP transports SHOULD follow the MCP Authorization specification (OAuth),
while the hub is a single-user loopback daemon whose clients are local processes.

## Decision

- **Dual-era, decided per request.** A request whose `params._meta` declares
  `io.modelcontextprotocol/protocolVersion` is served statelessly as 2026-07-28 (`server/discover`,
  `tools/list`, `tools/call`; `_meta` validation; `-32022` for other versions; `404 + -32601` for
  unknown methods). Everything else is legacy: `initialize` negotiates `2025-11-25`, `2025-06-18`
  or `2025-03-26` (echoing the requested one, else `2025-11-25`), `notifications/initialized` and
  `ping` are accepted, unknown methods stay HTTP 200 with `-32601`. The hub mints no session ids
  for legacy clients (allowed: a server MAY assign one). Legacy requests are served identically
  whatever `MCP-Protocol-Version` says or omits; an unknown value is `400 + -32022`.
- **One result shape.** Every result, legacy included, carries `resultType "complete"` and
  `_meta io.modelcontextprotocol/serverInfo`; list results carry `ttlMs 60000` and
  `cacheScope "private"`. Legacy result types are open maps, so the extra keys are harmless and
  the method layer has a single code path.
- **Headers.** Modern requests must carry `MCP-Protocol-Version` (equal to the body),
  `Mcp-Method` (equal to `method`) and, for `tools/call`, `resources/read` and `prompts/get`,
  `Mcp-Name` (equal to `params.name`/`params.uri`, Base64 sentinel decoded). Any failure is
  `400 + -32020`. `Mcp-Param-*` (`x-mcp-header`) is not validated: the hub declares no such
  annotations, and the spec makes server-side validation optional.
- **Transport scope in S01.** JSON responses only. SSE (`subscriptions/listen` for modern
  clients, the GET stream and sessions for legacy clients) arrives in S03 with the subscription
  stream. `GET`/`DELETE` answer `405`.
- **Stdio is a proxy.** The stdio door forwards each frame to the daemon's HTTP endpoint,
  mirroring `method`, `name`/`uri` and the modern version into the headers the HTTP binding
  requires, so one process owns the registry and (from S02) the database (PD-4).
- **No authentication until S03, and no OAuth after it.** The listener binds a loopback address
  only (configuration rejects anything else), `Host` must name the listener, `Origin` must be
  absent, loopback or allowlisted, and the body must be `application/json`. S03 adds per-client
  bearer tokens from `clients.edn` mapped to profiles. This is a conscious deviation from the
  spec's "SHOULD follow the Authorization specification": the clients are local processes of the
  same OS user, and OAuth adds an authorization server to a single-user machine for no gain.
- **Development nREPL on TCP.** Babashka's nREPL server accepts only host and port (a `:socket`
  option is ignored and the CLI parses its argument as a port), so `bb dev` starts it on a random
  loopback port and records it in `daemon.edn` under the runtime directory instead of a Unix
  socket.

## Consequences

- Claude Code connects over HTTP (2026-07-28) and over stdio (legacy handshake) without
  configuration; Inspector works in its default legacy mode.
- Legacy clients get a stateless server: they must not rely on `Mcp-Session-Id`; none is issued.
- Until S03 the daemon trusts every local process; nothing but loopback reaches it.
- `ping` and `initialize` from a modern client are `-32601` (HTTP 404), as the spec prescribes.

## Revisit trigger

Claude Code or Inspector dropping the legacy handshake on stdio (then the legacy era can be
removed after the twelve-month window), a client that needs sessions on the legacy path, or a
multi-user deployment that would justify the Authorization specification.
