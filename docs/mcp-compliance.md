# MCP compliance: what the hub implements and where each rule comes from

Read on 2026-10-03 from the live specification pages (Mintlify `.md` renditions where the
HTML was rate-limited), the release post and the Claude Code MCP docs; the subscriptions,
cancellation, resources, Streamable HTTP and changelog pages and `schema.ts` were re-read on
2026-10-12 for S03. `SPEC` below is
`https://modelcontextprotocol.io/specification/2026-07-28`. The TypeScript schema at
`github.com/modelcontextprotocol/modelcontextprotocol/blob/main/schema/2026-07-28/schema.ts`
is the source of truth; the JSON Schema is generated from it.

## Eras the hub serves (dual-era server)

| Term | Meaning | Source |
|---|---|---|
| Modern | 2026-07-28 and later: stateless, every request self-describing through `params._meta`; no `initialize` handshake, no sessions | `SPEC/basic/versioning`, `SPEC/changelog` |
| Legacy | 2025-11-25 and earlier: `initialize` → `notifications/initialized` handshake, `ping`, optional `Mcp-Session-Id` | `SPEC/basic/versioning`; `https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle` |
| Dual-era | both, selected per request: modern `_meta` → stateless 2026-07-28; `initialize` → legacy semantics (the spec scopes them to the stdio process or HTTP session; the hub keeps no per-connection state and serves legacy requests statelessly, which the spec allows since a legacy server MAY assign a session) | `SPEC/basic/versioning` ("Dual-era server") |

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
| Unsupported protocol version → `-32022` with `data.supported` and `data.requested`; HTTP 400 | enforced; `data.supported` lists the modern versions only, because legacy versions are reachable solely through `initialize` | `SPEC/basic/versioning`, `SPEC/schema` |
| Servers MUST NOT infer capabilities, version or identity from earlier requests; a stdio process is not a session | no per-connection state in S01 | `SPEC/basic` |
| Servers never initiate JSON-RPC requests (server→client input only via MRTR) | hub sends none | `SPEC/basic/patterns`, `SPEC/basic/patterns/mrtr` |
| JSON Schema dialect defaults to 2020-12 when `$schema` is absent | hub emits 2020-12 shapes without `$schema` | `SPEC/basic` |

## Methods

| Method | Era | Request | Result | Source |
|---|---|---|---|---|
| `server/discover` | modern (MUST implement); without `_meta` → `400` + `-32602` naming `supportedVersions` so a probing client recognizes a modern server | only `_meta` | `resultType`, `supportedVersions`, `capabilities`, `instructions`, `ttlMs`, `cacheScope`, `_meta.serverInfo` | `SPEC/server/discover`, `SPEC/basic/versioning` |
| `tools/list` | both | optional `cursor` (an unknown cursor → `-32602`) | `tools[]`, deterministic order (module id, tool name), `ttlMs`, `cacheScope`, no `nextCursor` (single page) | `SPEC/server/tools`, `SPEC/server/utilities/caching`, `SPEC/server/utilities/pagination` |
| `tools/call` | both | `name`, `arguments` | `content[]` (text block with the serialized `structuredContent`), `structuredContent`, `isError` | `SPEC/server/tools` |
| `initialize` | legacy | `protocolVersion`, `capabilities`, `clientInfo` | `protocolVersion` (requested if supported, else `2025-11-25`), `capabilities`, `serverInfo`, `instructions` | `…/2025-11-25/basic/lifecycle` |
| `notifications/initialized` | legacy | notification | none (HTTP 202) | `…/2025-11-25/basic/lifecycle` |
| `ping` | legacy only; removed in 2026-07-28 | none | `{}` (plus `resultType`/`_meta`, harmless in an open map) | `…/2025-11-25/basic/utilities/ping`; `SPEC/changelog` (removal) |
| `notifications/cancelled` | both (stdio and HTTP; accepted with 202) | `requestId`, `reason?` | none at the daemon (handlers are synchronous); the stdio proxy closes the listen stream it names | `SPEC/basic/patterns/cancellation` |
| `resources/list` | both | optional `cursor` | `resources[]` (`uri name title? description? mimeType?`, the profile's resources ordered by URI), `ttlMs`, `cacheScope` | `SPEC/server/resources` |
| `resources/templates/list` | both | optional `cursor` | `resourceTemplates[]` (`uriTemplate name title? description? mimeType?`) | `SPEC/server/resources` |
| `resources/read` | both | `uri` | `contents[]` with `uri`, `mimeType`, `text` or `blob`; a template instance resolves its `{var}` segments; unknown → `-32602 Resource not found` with `data.uri` (never an empty `contents`); handler exception → `-32603` | `SPEC/server/resources` |
| `subscriptions/listen` | modern only (legacy → `-32601`) | `notifications` filter: `toolsListChanged`, `promptsListChanged`, `resourcesListChanged`, `resourceSubscriptions[]` | an SSE stream: `notifications/subscriptions/acknowledged` first (honored filter: prompts dropped, URIs the profile cannot read dropped), then the opted-in notifications, each with `_meta io.modelcontextprotocol/subscriptionId` = the request id; ended by the client closing the stream, or by the server with the request's own `resultType "complete"` result | `SPEC/basic/patterns/subscriptions`, `SPEC/schema` (`SubscriptionsListenRequest`) |
| anything else | both | — | `-32601 Method not found`; HTTP 404 on modern requests | `SPEC/basic/transports/streamable-http` |

Not implemented (planned or out of scope): `prompts/list`, `prompts/get` (no `prompts`
capability, so `-32601`), `completion/complete`, MRTR (`input_required` results),
`notifications/progress`, `x-mcp-header` / `Mcp-Param-*` validation (optional for servers; the
hub declares no `x-mcp-header`), the legacy GET SSE stream and `resources/subscribe` (removed in
2026-07-28; legacy clients are promised no notifications).

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
`prompts {listChanged}`, `completions`, `logging` (deprecated), `extensions`. `server/discover`
declares `{"tools": {"listChanged": true}, "resources": {"subscribe": true, "listChanged": true}}`
because modern clients can open `subscriptions/listen`; the legacy `initialize` result declares
`listChanged false` and `subscribe false` because no stream exists for legacy clients. Logging
is deprecated (SEP-2577): the hub logs to stderr and declares no `logging`.

### Resources (`SPEC/server/resources`)

Resource and template objects come from the manifests (`clogem://<module>/<kind>/<id>`,
templates with `{var}` segments). The hub validates URIs against the registry (a declared URI or
an instance of a declared template, within the caller's profile) before any handler runs. The
not-found code is `-32602` (changed from `-32002` in 2026-07-28). Updates: a module publishes
the SDK event `:resource/updated {:uri}`; the hub forwards it as
`notifications/resources/updated` to the listen streams whose `resourceSubscriptions` name that
URI. `notifications/resources/list_changed` and `notifications/tools/list_changed` follow every
registry change.

### Subscriptions (`SPEC/basic/patterns/subscriptions`, `SPEC/basic/patterns/cancellation`)

| Rule | Hub behaviour |
|---|---|
| The server MUST NOT send notification types the client did not request | per-stream filter; prompts never, URIs outside the profile never |
| `notifications/subscriptions/acknowledged` MUST be the first message and carries the honored subset | sent synchronously before the stream is registered for fan-out |
| Every message carries `io.modelcontextprotocol/subscriptionId` = the listen request id | yes, including the closing result |
| Client cancellation: close the stream (HTTP) or `notifications/cancelled` (stdio) | HTTP: the disconnect removes the stream; stdio: the proxy closes the HTTP stream and forwards nothing more for it |
| Server teardown: SHOULD answer the listen request with a `complete` result, then close | done on daemon stop (`clogem.hub.mcp.subscriptions/stop!`) |
| Cancellation page: the server MUST send `notifications/cancelled` when tearing down a listen stream | read with the schema comment ("on stdio, the server also sends this notification"): over HTTP the result is the signal; the stdio proxy emits `notifications/cancelled` when a stream ends without the result (conflict recorded below) |
| Keep-alive comment lines are encouraged | `: keepalive` every `:subscriptions :keepalive-ms` (15 s) |
| No resumability (`Last-Event-ID`) | none; a reconnecting client re-subscribes |

## Streamable HTTP (`SPEC/basic/transports/streamable-http`)

| Rule | Hub behaviour | Source |
|---|---|---|
| One endpoint path supporting POST; GET stream and sessions removed | `POST /mcp`; `GET`/`DELETE`/others → `405 Method Not Allowed`, `Allow: POST`; other paths → 404 | transports/streamable-http |
| Servers MUST validate `Origin`: present and invalid → `403 Forbidden` (body MAY be an id-less JSON-RPC error); SHOULD bind 127.0.0.1 | absent Origin accepted; `http(s)://127.0.0.1[:port]`, `localhost`, `[::1]` and configured `:allowed-origins` accepted; anything else (including `null`) → 403 with an id-less JSON-RPC error | transports/streamable-http |
| Host header check (DNS-rebinding defence, house rule) | `Host` must be `127.0.0.1:<port>`, `localhost:<port>` or `[::1]:<port>` (bare host when the port is 80) → else 403 | house rule; rationale: transports/streamable-http "DNS rebinding" |
| Client MUST send `Accept: application/json, text/event-stream`; server answers JSON (one object) or SSE | JSON for every request except `subscriptions/listen`, which is `text/event-stream` with `Cache-Control: no-cache` and `X-Accel-Buffering: no`; `Accept` is not enforced (legacy clients vary) | transports/streamable-http |
| Request body `Content-Type: application/json` (media type compared exactly; parameters such as `charset` allowed) | else `415 Unsupported Media Type` | spec examples; house rule |
| Exactly one JSON value per body | trailing bytes, concatenated messages or an empty body → `400` + `-32700` | transports/streamable-http |
| A request (with id) named `notifications/*` | `-32600 Invalid Request` | `SPEC/basic` (notifications carry no id) |
| Accepted notification → `202 Accepted`, no body; rejected → 4xx with optional id-less error | `202`; a notification-shaped body that fails framing → 400 with a JSON-RPC error | transports/streamable-http |
| Every modern POST MUST carry `MCP-Protocol-Version` equal to `_meta…/protocolVersion`; mismatch or missing → `400` + `-32020 HeaderMismatch` | enforced for modern requests | transports/streamable-http |
| `Mcp-Method` (= `method`) REQUIRED on all modern requests; `Mcp-Name` (= `params.name` / `params.uri`) on `tools/call`, `resources/read`, `prompts/get`; mismatch/missing → `400` + `-32020` | enforced for modern requests; `Mcp-Name` checked only for those three methods | transports/streamable-http (SEP-2243) |
| Base64 sentinel `=?base64?{Base64}?=` (lowercase markers) for non-ASCII or whitespace-padded header values; servers MUST decode before comparing | decoded before comparison | transports/streamable-http |
| Header names case-insensitive, values case-sensitive | http-kit lower-cases names; values compared exactly | transports/streamable-http; RFC 9110 |
| Unsupported version → `400` + `-32022`; unknown method → `404` + `-32601` | enforced for modern requests | transports/streamable-http |
| Missing `MCP-Protocol-Version` MAY be treated as `2025-03-26` by servers supporting pre-2025-06-18 clients | legacy requests are served identically with or without the header (the three legacy revisions differ only in HTTP header handling, not in the methods the hub serves); an unknown version in the header → `400` + `-32022` | transports/streamable-http; `…/2025-11-25/basic/transports` |
| Modern server receiving legacy traffic: ignore `Mcp-Session-Id` (never mint/echo), ignore `Last-Event-ID` | S01 mints no sessions; legacy requests are served statelessly (allowed: a legacy server MAY assign a session) | transports/streamable-http; 2025-11-25 transports |
| Dual-era clients detect the era from a 400's body: a recognized modern error means modern | every 400 carries a well-formed modern JSON-RPC error body | transports/streamable-http |
| Closing an SSE stream is cancellation; `X-Accel-Buffering: no` on SSE | the listen stream's `on-close` ends the subscription; the header is sent | transports/streamable-http |
| HTTP transports SHOULD follow the MCP Authorization spec | **conscious deviation (ADR-0002, ADR-0005):** loopback daemon with per-client bearer tokens from `clients.edn` (mode 0600); no OAuth. Missing or wrong token → `401`, `WWW-Authenticate: Bearer realm="clogem-hub"`, id-less `-32600` body; checked after Host and Origin, before content type and body | `SPEC/basic` ("auth"); RFC 6750 §3 |

Authentication and profiles (S03): the token's client profile (`:gui :llm-small :llm-large
:admin`) selects the tools, resources and templates the request sees and may call; `:llm-small`
sees at most 10 tools. `tools/call`, `resources/read`, `subscriptions/listen` and rejected
authentications are recorded in the `audit` table (no arguments, results or tokens).

## stdio (`SPEC/basic/transports/stdio`)

| Rule | Hub behaviour | Source |
|---|---|---|
| Newline-delimited JSON-RPC on stdin/stdout, no embedded newlines; stdout carries only MCP messages; logs to stderr | the proxy writes frames to stdout only; all logging via `clogem.hub.log` on stderr (PD-4) | transports/stdio |
| No header layer: all metadata is in `_meta` | the proxy copies `method`, `params.name`/`params.uri` and `_meta…/protocolVersion` into `Mcp-Method`, `Mcp-Name`, `MCP-Protocol-Version` when forwarding to the daemon; legacy bodies are forwarded without a version header | transports/stdio; PD-4 |
| Dual-era clients probe stdio servers with `server/discover`; legacy clients send `initialize` | both are answered by the daemon through the proxy | transports/stdio, versioning |
| Server SHOULD exit promptly on stdin EOF | the proxy exits on EOF | transports/stdio |
| Cancellation on stdio is `notifications/cancelled` | forwarded as a notification; nothing to cancel in S01 | transports/stdio |
| Daemon discovery | the proxy reads `daemon.edn` from the runtime directory (port of a live pid), falling back to the configured port | house rule (PD-4) |
| Daemon unreachable | the proxy starts one (`systemctl --user start clogem-hub` when that unit is enabled, else a detached `bb daemon`) and waits up to `:stdio :start-timeout-ms` for `daemon.edn`; if none comes up it answers requests itself with `-32603` and `data.reason "daemon unavailable"`, and prints one clear line on stderr | house rule (PD-4, ADR-0005) |
| Authentication on stdio | there is no header layer for the client; the proxy adds the bearer token of its own `clients.edn` client (`:stdio :client`, `CLOGEM_CLIENT`) to every forwarded POST | house rule (ADR-0005) |
| `subscriptions/listen` on stdio: acknowledgment first, `subscriptionId` on every message, client cancels with `notifications/cancelled`, server tears down with `notifications/cancelled` | the proxy streams the daemon's SSE events as frames; a client `notifications/cancelled` naming the request closes the stream; when a stream ends without the listen result the proxy emits `notifications/cancelled` for it | `SPEC/basic/patterns/subscriptions`, `SPEC/basic/patterns/cancellation` |

## Claude Code specifics (`https://code.claude.com/docs/en/mcp`)

- `claude mcp add --transport http clogem http://127.0.0.1:7788/mcp --header "Authorization: Bearer $(bb token llm)"`;
  `claude mcp list` reports `✔ Connected`. Stdio: `claude mcp add --transport stdio clogem-stdio -- bb --config <abs>/clogem-hub/bb.edn shim`
  (the proxy carries the `stdio` client's token itself and starts the daemon when needed).
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
  the listen request with a `resultType: "complete"` result. Resolved in S03 with the schema's
  wording ("on stdio, the server also sends this notification"): over HTTP the hub sends the
  result and closes; over stdio the proxy adds `notifications/cancelled` when a stream ends
  without that result (ADR-0005).
- Header spelling differs between pages (`MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Session-Id`
  vs `MCP-Session-Id`); names are case-insensitive, so the hub compares lower-cased names.
