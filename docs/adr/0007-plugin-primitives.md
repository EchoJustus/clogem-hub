# ADR-0007: Plugin primitives — process runner, LLM adapter, module loader, `media` and `ui`

Status: Accepted (S04, 2026-10-12)

## Context

Up to S03 the hub offered modules a bus, a single-writer store, jobs and configuration, and
`clogem.api` declared `run-process!` and `llm-chat!` without implementing them. S04 freezes the
SDK contract at 1.0.0 (SDK ADR-0002) and delivers what the frozen functions promise: external
programs (ffmpeg, ffprobe, whisper-cli) as CLI processes with timeouts, progress and cancellation
(PD-5), a local LLM behind one adapter (PD-9), and a way to load proprietary modules at runtime
from a configuration file (PD-1). Two built-in modules give every later module something to build
on: `media` (a registry of media files with stable ids) and `ui` (a per-client command inbox that a
GUI watches through the subscription stream). The hub runs on native Windows (ADR-0006), so every
primitive must behave the same on Windows and POSIX hosts.

## Decision

1. **Two runtime seams.** `clogem.hub.runtime` carries two functions, `:process` and `:llm`,
   that implement `run-process!` and `llm-chat!`. The daemon wires the real ones
   (`clogem.hub.media.process/runner`, `clogem.hub.llm/chat-fn`); module tests inject fakes, as the
   SDK test kit does for modules outside the hub. Without a function the runtime throws
   `{:type :unsupported}` rather than failing silently.
2. **Process runner (`clogem.hub.media.process`).** Argv vectors only, never a shell string. The
   program is resolved from the configured `:bins` map, else from `PATH` through `babashka.fs/which`
   (`.com`, `.exe`, `.bat`, `.cmd` on Windows); a missing program throws
   `:process/binary-missing` before anything starts. Stdout and stderr are drained on their own
   threads so a chatty child never blocks; the deadline and the caller's cancel atom are polled
   every `:poll-ms`; a kill is `destroy-tree` followed by `destroyForcibly` (a hard kill, the only
   kind Windows offers, so partial outputs stay temp files the caller removes). Output is capped at
   `:max-output-bytes` (`:truncated? true`). ffmpeg's `-progress pipe:1` blocks (key=value lines
   ended by `progress=`) are parsed and delivered through `:on-progress` as they arrive; stdout is
   then not accumulated. A helper runs ffprobe with `-print_format json -show_format -show_streams
   -show_error` and converts `format.duration` (seconds, a string) to integer milliseconds.
   Defaults come from `:process {:timeout-ms :max-output-bytes :poll-ms}` in the configuration.
3. **LLM adapter (`clogem.hub.llm`).** A `Provider` protocol with Ollama as the first
   implementation: `POST /api/chat` with `stream false`, `format` passed through (`"json"` or a
   JSON Schema map for structured output), `options.temperature` 0 unless the request says
   otherwise, base64 images per message, `keep_alive` from the configuration; `GET /api/version`
   and `GET /api/tags` for the preflight. The reply carries the content, the parsed JSON when a
   format was requested and the content parses (nil otherwise, with a warning), the model, the
   provider, the done reason, the duration and the raw body. Errors are `:llm/invalid-request`,
   `:llm/unreachable`, `:llm/timeout` and `:llm/provider-error`. The endpoint URL is validated by
   the configuration (loopback or LAN only unless `:allow-remote? true`, PD-9); the adapter never
   logs message contents or images.
4. **External module loader (`clogem.hub.modules`).** `modules.edn` in the configuration
   directory is `{:modules [{:id :keyword :root "directory" :enabled? true} …]}`; `:root` holds
   `src/` and `resources/` and may be relative to the file. At startup, after the built-in modules,
   each enabled root's `src` and `resources` are added to the classpath once, its manifest is read
   with the SDK reader and registered through the registry (which validates it and resolves the
   entry and handlers under `clogem.module.<id>.`). A failing entry is logged and recorded; the
   daemon never aborts because of a module. A missing `modules.edn` means no external modules.
5. **Binaries are a health matter, not a load matter.** The registry does not check a manifest's
   `:bins`; a module that needs a program checks it in its `:health` hook and reports
   `:degraded` when it is missing. The hub stays up and `system_health` says what is degraded.
6. **Built-in `media`.** Table `media_items`. The fingerprint is the SHA-256 of the file size
   (8 bytes, big-endian) followed by its first and last `min(size, 1 MiB)` bytes, so moving or
   renaming a file keeps it and changing its content changes it without reading gigabytes; the id is
   `m-` plus the first 32 hex digits of the SHA-256 of `<fingerprint>|<duration-ms>`. Registering
   the same file again is a no-op; the same content at a new path relinks the row; new content at a
   known path retires the old row's path and inserts a new row. Tools `media_register` and
   `media_probe`, resource `clogem://media/item/{id}`, event `:media/registered`.
7. **Built-in `ui`.** Table `ui_inbox`. The module subscribes to `:ui/command`
   (`{:client string :command map}`), stores each command and publishes `:resource/updated` for
   `clogem://ui/inbox/{client}`; the GUI reads the resource and acknowledges with `ui_ack`. An empty
   inbox is `[]`, a resource like any other.

## Consequences

- Module authors test against the SDK test kit or inject fakes into the hub runtime; no test
  needs ffmpeg, ffprobe or a running model, and CI on both runners proves the primitives with
  fixture scripts and an in-process HTTP server.
- A cancelled or timed-out process is killed hard everywhere; every caller writing media output
  must write to a temp file it can delete (PD-6 already requires this).
- Adding a provider means implementing `Provider` and a configuration key; the request and
  reply shapes stay those of `clogem.api` 1.0.0.
- Proprietary modules are loaded only from `modules.edn`; this repository never names them.
  Their source directories join the classpath at startup, so a change to a module needs a daemon
  restart (`system_shutdown`, then the next door starts it again).

## Revisit trigger

Streaming model replies or tool calling in `llm-chat!` (a contract change: major version and
ADR); a second provider; reloading modules without a restart; loading modules from a packaged
location rather than a directory (ADR-0006, Store packaging); the registry refusing modules
whose `:bins` are absent instead of leaving it to health.
