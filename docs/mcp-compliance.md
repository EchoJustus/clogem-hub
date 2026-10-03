# MCP compliance: what the hub implements and where each rule comes from

Read on 2026-10-03 from the live specification pages (Mintlify `.md` renditions where the
HTML was rate-limited), the release post and the Claude Code MCP docs. `SPEC` below is
`https://modelcontextprotocol.io/specification/2026-07-28`. The TypeScript schema at
`github.com/modelcontextprotocol/modelcontextprotocol/blob/main/schema/2026-07-28/schema.ts`
is the source of truth; the JSON Schema is generated from it.

## Eras the hub serves (dual-era server)

| Term | Meaning | Source |
|---|---|---|
| Modern | 2026-07-28 and later: stateless, every request self-describing through `params._meta`; no `initialize` handshake, no sessions | `SPEC/basic/versioning`, `SPEC/changelog` |
| Legacy | 2025-11-25 and earlier: `initialize` → `notifications/initialized` handshake, `ping`, optional `Mcp-Session-Id` | `SPEC/basic/versioning`; `https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle` |
| Dual-era | both, selected per request: modern `_meta` → stateless 2026-07-28; `initialize` → legacy semantics scoped to the stdio process or HTTP session | `SPEC/basic/versioning` ("Dual-era server") |

Why dual-era: Claude Code keeps stdio servers on the legacy `initialize` handshake by default
(only `MCP_PROTOCOL_NEGOTIATION=auto` probes with `server/discover`), while its HTTP client asks
servers whether they support 2026-07-28 and uses it when they do
(`https://code.claude.com/docs/en/mcp`). MCP Inspector defaults to the legacy era too
(`https://modelcontextprotocol.io/docs/2026-07-28/tools/inspector/protocol-eras`).

Era detection in the hub: a request whose `params._meta` carries
`io.modelcontextprotocol/protocolVersion` is modern; everything else (including `initialize`,
`ping`, `notifications/initialized`) is legacy. Supported versions:
`2026-07-28` (modern) and `2025-11-25`, `2025-06-18`, `2025-03-26` (legacy, all share the
`initialize` lifecycle; differences are HTTP-header handling, see below).

## JSON-RPC rules (`SPEC/basic`)

| Rule | Hub behaviour | Source |
|---|---|---|
| Messages are JSON-RPC 2.0, UTF-8; `jsonrpc` must be `"2.0"` | else `-32600 Invalid Request` | `SPEC/basic` |
| Request `id` is a string or integer, never null; notifications carry no id and get no response | float/object/null id → `-32600`; notifications → no body | `SPEC/basic` |
| Exactly one message per POST body / per stdio line; batching removed in 2025-06-18 | JSON array → `-32600 Batch requests are not supported` | `SPEC/basic/transports/streamable-http`, `…/2025-06-18/changelog` |
| Error codes `-32700` parse, `-32600` invalid request, `-32601` method not found, `-32602` invalid params, `-32603` internal | as named; internal errors never leak exception text | `SPEC/basic` |
| MCP codes `-32020 HeaderMismatch`, `-32021 MissingRequiredClientCapability`, `-32022 UnsupportedProtocolVersion`; `-32020..-32099` reserved; never emit `-32002`/`-32042` | hub emits only the three; nothing else in the reserved range | `SPEC/basic`, `SPEC/changelog` |
| Every result carries `resultType` (`"complete"`; `"input_required"` is MRTR); older servers' results without it are read as complete | every hub result has `resultType "complete"`, also on legacy requests (legacy `Result` is an open map) | `SPEC/basic`, `SPEC/schema` |
| Servers SHOULD put `io.modelcontextprotocol/serverInfo` (`name`, `version`) in every result's `_meta` | done on every result | `SPEC/basic` |
| Every modern request MUST carry `_meta["io.modelcontextprotocol/protocolVersion"]` and `_meta["io.modelcontextprotocol/clientCapabilities"]`; missing → `-32602`, HTTP 400 | enforced for modern requests | `SPEC/basic`, `SPEC/schema` (RequestMetaObject) |
| Unsupported protocol version → `-32022` with `data.supported` and `data.requested`; HTTP 400 | enforced | `SPEC/basic/versioning`, `SPEC/schema` |
| Servers MUST NOT infer capabilities, version or identity from earlier requests; a stdio process is not a session | no per-connection state in S01 | `SPEC/basic` |
| Servers never initiate JSON-RPC requests (server→client input only via MRTR) | hub sends none | `SPEC/basic/patterns`, `SPEC/basic/patterns/mrtr` |
| JSON Schema dialect defaults to 2020-12 when `$schema` is absent | hub emits 2020-12 shapes without `$schema` | `SPEC/basic` |

## Methods

| Method | Era | Request | Result | Source |
|---|---|---|---|---|
| `server/discover` | modern (MUST implement) | only `_meta` | `resultType`, `supportedVersions`, `capabilities`, `instructions`, `ttlMs`, `cacheScope`, `_meta.serverInfo` | `SPEC/server/discover`, `SPEC/basic/versioning` |
| `tools/list` | both | optional `cursor` (an unknown cursor → `-32602`) | `tools[]`, deterministic order (module id, tool name), `ttlMs`, `cacheScope`, no `nextCursor` (single page) | `SPEC/server/tools`, `SPEC/server/utilities/caching`, `SPEC/server/utilities/pagination` |
| `tools/call` | both | `name`, `arguments` | `content[]` (text block with the serialized `structuredContent`), `structuredContent`, `isError` | `SPEC/server/tools` |
| `initialize` | legacy | `protocolVersion`, `capabilities`, `clientInfo` | `protocolVersion` (requested if supported, else `2025-11-25`), `capabilities`, `serverInfo`, `instructions` | `…/2025-11-25/basic/lifecycle` |
| `notifications/initialized` | legacy | notification | none (HTTP 202) | `…/2025-11-25/basic/lifecycle` |
| `ping` | legacy only; removed in 2026-07-28 | none | `{}` (plus `resultType`/`_meta`, harmless in an open map) | `…/2025-11-25/basic/utilities/ping`; `SPEC/changelog` (removal) |
| `notifications/cancelled` | both (stdio) | `requestId`, `reason?` | none; S01 handlers are synchronous so there is nothing to cancel | `SPEC/basic/patterns/cancellation` |
| anything else | both | — | `-32601 Method not found`; HTTP 404 on modern requests | `SPEC/basic/transports/streamable-http` |

Not in S01 (planned): `resources/list`, `resources/templates/list`, `resources/read`,
`prompts/list`, `prompts/get` (capabilities not declared, so their methods are `-32601`),
`subscriptions/listen` and the legacy GET SSE stream (S03), `completion/complete`, MRTR
(`input_required` results), `notifications/progress`, `x-mcp-header` / `Mcp-Param-*` validation
(optional for servers; the hub does not declare any `x-mcp-header`).

### Tool objects (`SPEC/server/tools`, `SPEC/schema`)

`name`, `title?`, `description`, `inputSchema` (root `type: "object"`; the hub emits
`additionalProperties: false` for closed maps; no-parameter tools emit
`{"type":"object","additionalProperties":false}`), `outputSchema?`, `annotations?`
(`title`, `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`; defaults
false/true/false/true), `_meta?`. Tool names SHOULD be 1–128 chars of `[A-Za-z0-9_.-]`; the house
rule `^[a-z][a-z0-9_]{0,63}$` is stricter and therefore compliant. The spec now allows root-level
`anyOf/oneOf/allOf` in `inputSchema` (SEP-2106) but Claude Code flattens or drops such schemas and
rejects property names outside `[A-Za-z0-9_.-]{1,64}`, so the house rule stays
(`https://code.claude.com/docs/en/mcp`). `_meta["anthropic/requiresUserInteraction"]: true` is a
Claude Code convention (permission prompt on every call), not an MCP field.

### Tool errors (`SPEC/server/tools`)

Unknown tool or invalid arguments → protocol error `-32602` (HTTP 200 on a modern request:
only the `_meta`-related `-32602` carries 400). A handler exception, or a result that violates
the declared `outputSchema`, → `isError: true` in the result, never a protocol error.

### Cache hints (`SPEC/server/utilities/caching`)

`ttlMs` (≥ 0) and `cacheScope` (`"public"` | `"private"`) are REQUIRED on `server/discover`,
`tools/list`, `prompts/list`, `resources/list`, `resources/templates/list`, `resources/read`.
The hub uses `ttlMs 60000` and `cacheScope "private"` (lists vary by client profile from S03).

### Capabilities (`SPEC/schema`)

`ServerCapabilities`: `tools {listChanged}`, `resources {subscribe, listChanged}`,
`prompts {listChanged}`, `completions`, `logging` (deprecated), `extensions`. S01 declares
`{"tools": {"listChanged": false}}` only; `listChanged: true` arrives with `subscriptions/listen`
(S03). Logging is deprecated (SEP-2577): the hub logs to stderr and declares no `logging`.

## Streamable HTTP (`SPEC/basic/transports/streamable-http`)

| Rule | Hub behaviour | Source |
|---|---|---|
| One endpoint path supporting POST; GET stream and sessions removed | `POST /mcp`; `GET`/`DELETE`/others → `405 Method Not Allowed`, `Allow: POST`; other paths → 404 | transports/streamable-http |
| Servers MUST validate `Origin`: present and invalid → `403 Forbidden` (body MAY be an id-less JSON-RPC error); SHOULD bind 127.0.0.1 | absent Origin accepted; `http(s)://127.0.0.1[:port]`, `localhost`, `[::1]` and configured `:allowed-origins` accepted; anything else (including `null`) → 403 with an id-less JSON-RPC error | transports/streamable-http |
| Host header check (DNS-rebinding defence, house rule) | `Host` must be `127.0.0.1:<port>`, `localhost:<port>` or `[::1]:<port>` (bare host when the port is 80) → else 403 | house rule; rationale: transports/streamable-http "DNS rebinding" |
| Client MUST send `Accept: application/json, text/event-stream`; server answers JSON (one object) or SSE | hub answers `application/json` only in S01; `Accept` is not enforced (legacy clients vary) | transports/streamable-http |
| Request body `Content-Type: application/json` | else `415 Unsupported Media Type` | spec examples; house rule |
| Accepted notification → `202 Accepted`, no body; rejected → 4xx with optional id-less error | `202`; a notification-shaped body that fails framing → 400 with a JSON-RPC error | transports/streamable-http |
| Every modern POST MUST carry `MCP-Protocol-Version` equal to `_meta…/protocolVersion`; mismatch or missing → `400` + `-32020 HeaderMismatch` | enforced for modern requests | transports/streamable-http |
| `Mcp-Method` (= `method`) REQUIRED on all modern requests; `Mcp-Name` (= `params.name` / `params.uri`) on `tools/call`, `resources/read`, `prompts/get`; mismatch/missing → `400` + `-32020` | enforced for modern requests; `Mcp-Name` checked only for those three methods | transports/streamable-http (SEP-2243) |
| Base64 sentinel `=?base64?{Base64}?=` (lowercase markers) for non-ASCII or whitespace-padded header values; servers MUST decode before comparing | decoded before comparison | transports/streamable-http |
| Header names case-insensitive, values case-sensitive | http-kit lower-cases names; values compared exactly | transports/streamable-http; RFC 9110 |
| Unsupported version → `400` + `-32022`; unknown method → `404` + `-32601` | enforced for modern requests | transports/streamable-http |
| Missing `MCP-Protocol-Version` MAY be treated as `2025-03-26` by servers supporting pre-2025-06-18 clients | legacy requests without the header are served as `2025-03-26`-era; a legacy request with an unknown version in the header → `400` + `-32022` | transports/streamable-http; `…/2025-11-25/basic/transports` |
| Modern server receiving legacy traffic: ignore `Mcp-Session-Id` (never mint/echo), ignore `Last-Event-ID` | S01 mints no sessions; legacy requests are served statelessly (allowed: a legacy server MAY assign a session) | transports/streamable-http; 2025-11-25 transports |
| Dual-era clients detect the era from a 400's body: a recognized modern error means modern | every 400 carries a well-formed modern JSON-RPC error body | transports/streamable-http |
| Closing an SSE stream is cancellation; `X-Accel-Buffering: no` on SSE | no SSE in S01 | transports/streamable-http |
| HTTP transports SHOULD follow the MCP Authorization spec | **conscious deviation (ADR-0002):** loopback daemon with per-client bearer tokens from `clients.edn` (S03); no OAuth | `SPEC/basic` ("auth") |

No authentication in S01, by design: loopback binding, the Host and Origin checks and the
JSON-only content type bound the exposure until S03 adds bearer tokens (hub ADR-0002).

## stdio (`SPEC/basic/transports/stdio`)

| Rule | Hub behaviour | Source |
|---|---|---|
| Newline-delimited JSON-RPC on stdin/stdout, no embedded newlines; stdout carries only MCP messages; logs to stderr | the proxy writes frames to stdout only; all logging via `clogem.hub.log` on stderr (PD-4) | transports/stdio |
| No header layer: all metadata is in `_meta` | the proxy copies `method`, `params.name`/`params.uri` and `_meta…/protocolVersion` into `Mcp-Method`, `Mcp-Name`, `MCP-Protocol-Version` when forwarding to the daemon; legacy bodies are forwarded without a version header | transports/stdio; PD-4 |
| Dual-era clients probe stdio servers with `server/discover`; legacy clients send `initialize` | both are answered by the daemon through the proxy | transports/stdio, versioning |
| Server SHOULD exit promptly on stdin EOF | the proxy exits on EOF | transports/stdio |
| Cancellation on stdio is `notifications/cancelled` | forwarded as a notification; nothing to cancel in S01 | transports/stdio |
| Daemon unreachable | the proxy answers the request itself with `-32603` and `data.reason "daemon unavailable"`, and prints one clear line on stderr | house rule (PD-4) |

## Claude Code specifics (`https://code.claude.com/docs/en/mcp`)

- `claude mcp add --transport http clogem http://127.0.0.1:7788/mcp`; `claude mcp get clogem`
  reports `✔ Connected`. Stdio: `claude mcp add --transport stdio clogem-stdio -- bb --config <abs>/clogem-hub/bb.edn shim`.
- Claude Code v2 asks HTTP servers for 2026-07-28; stdio servers stay on `initialize` unless
  `MCP_PROTOCOL_NEGOTIATION=auto`. Hence the dual-era server.
- Tool descriptions and server `instructions` are truncated at 2,048 characters (house limit:
  descriptions ≤ 1,000); with tool search only names and instructions load at session start, so
  `instructions` describe the tool families.
- Schemas with root-level combinators are flattened or dropped; property names outside
  `[A-Za-z0-9_.-]{1,64}` exclude the tool; `_meta["anthropic/requiresUserInteraction"]` forces a
  permission prompt on every call.

## Spec wording conflicts recorded (PD-11)

- `SPEC` index still says extensions are "negotiated during initialization"; the versioning page
  says capabilities are per request. The per-request model is authoritative.
- Subscription teardown: the cancellation page says the server MUST send `notifications/cancelled`
  when tearing down a `subscriptions/listen` stream; the subscriptions page says it SHOULD answer
  the listen request with a `resultType: "complete"` result. Resolve in S03.
- Header spelling differs between pages (`MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Session-Id`
  vs `MCP-Session-Id`); names are case-insensitive, so the hub compares lower-cased names.
