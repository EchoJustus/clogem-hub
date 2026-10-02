# clogem-hub

Local-first Babashka daemon for the Clogem ecosystem: module registry, in-process event bus, single-writer SQLite, and an MCP interface (HTTP + stdio) for local LLM hosts and GUI clients.

Status: bootstrapped (session S00). The daemon lands incrementally from S01 (registry, MCP router, HTTP and stdio transports), S02 (bus, single writer, migrations, jobs), S03 (lifecycle, auth, audit) and S04 (plugin runtime, process runner, built-in modules, LLM adapter).

## Commands

```sh
bb test            # clojure.test over test/**/*_test.clj
bb lint            # fitness checks (license headers now; more rules from S01)
bb dev             # daemon with nREPL (arrives in S01)
bb shim            # stdio MCP proxy to the running daemon (arrives in S01)
bb guard:public    # leak guard over the tree and git history
bb hooks:install   # pre-commit hook that runs guard:public
```

Requires Babashka 1.13.225 or newer and a sibling checkout of `clogem-sdk` (`../clogem-sdk`).

## License

© 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com). Licensed under the Eclipse Public License 2.0; see LICENSE.
