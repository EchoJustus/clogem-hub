# Dependencies

`bb.edn` is the source of truth. Exact pins only: no ranges, SNAPSHOT or LATEST.
Before adopting a dependency, smoke-test it in the installed Babashka
(`bb -Sdeps '{:deps {…}}' -e "(require '…)"`) and record it here.

Minimum Babashka: 1.13.225 (installed when the repository was bootstrapped). The floor is
1.12.208, the release from which bb waits for non-daemon threads, which the daemon relies on
(CHANGELOG entry for babashka/babashka#1843, 2025-09-04).

## Allowlist

- Babashka built-ins: `babashka.fs`, `babashka.process`, `babashka.cli`, `babashka.http-client`,
  `cheshire`, `org.httpkit.server`, `clojure.core.async`, `clojure.edn`, `clojure.test`
- `metosin/malli` (from S01, after a smoke test)
- Pod `org.babashka/go-sqlite3` (from S02), loaded only by `clogem.hub.db.*`
- The SDK as a sibling checkout: `{:local/root "../clogem-sdk"}`

Anything else is Ask-first.

## Record

| Session | Dependency | Version | Purpose | Smoke test |
|---|---|---|---|---|
| S00 | `clogem/sdk` | `{:local/root "../clogem-sdk"}` | plugin contract, shared lint and guard | resolves through tools.deps (needs a JVM on PATH for the first classpath build) |
| S01 | `metosin/malli` 0.20.2 | transitive via the SDK | tool argument validation, JSON Schema | see the SDK's deps.md |
| S02 | pod `org.babashka/go-sqlite3` 0.2.8 | loaded only by `clogem.hub.db.sqlite` | SQLite (WAL, FTS5, JSON) | newest in the pod registry on 2026-10-05; fresh connection per call, pragmas per batch, batches not atomic without BEGIN/COMMIT, FTS5 works, SQLite 3.45.1; blank SQL hangs the pod and concurrent calls deadlock it, hence the serialized gateway (ADR-0003). In this cloud container the pod was installed by hand into `~/.babashka/pods/repository/org.babashka/go-sqlite3/0.2.8/{manifest.edn,linux/x86_64/}` because `bb` does not trust the proxy CA; a workstation downloads it on first `load-pod` |
| S02 | built-in `clojure.core.async` | bb 1.13.225 | bus (channels, `thread`, `promise-chan`, `alts!!`) | `async/thread` pool threads are daemon threads named `async-mixed-N` |
| S01 | built-ins `org.httpkit.server`, `babashka.http-client`, `cheshire.core`, `babashka.nrepl.server` | bb 1.13.225 | HTTP transport, stdio proxy client, JSON, dev nREPL | `run-server` binds 127.0.0.1 with `:port 0` and reports `server-port`; nREPL supports TCP only (no Unix sockets) |
| S04 | built-ins `babashka.process`, `babashka.fs/which`, `babashka.classpath`, `babashka.http-client`, `org.httpkit.server` (tests), `cheshire.core` | bb 1.13.225 | process runner (argv, streams, `destroy-tree`), binary resolution (`.exe`/`.com`/`.bat`/`.cmd` on Windows), the external module loader (`add-classpath`), the Ollama adapter, the fake Ollama server in tests, JSON | `babashka.process/process` leaves `:out` as a readable stream when unset and never throws on a non-zero exit; the timed `deref` is not implemented in bb (a deadline polls `.waitFor`); `fs/which` reads `:win-exts`, not `PATHEXT`; `add-classpath` takes one string split on `java.io.File/pathSeparator`; no new dependency (deps record unchanged) |

Classpath resolution for `:deps` goes through tools.deps, which Babashka runs on a JVM the
first time (cached afterwards in `.cpcache/`). A plain `:paths`-only bb.edn needs no JVM.
