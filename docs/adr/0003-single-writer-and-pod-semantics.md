# ADR-0003: Single writer on the SQLite pod, serialized pod gateway, migrations and jobs

Status: Accepted (S02, 2026-10-05)

## Context

PD-3 requires one writer: only `clogem.hub.db.writer` mutates the database, everyone else
submits `:db/tx` and awaits the reply; reads are read-only; only `clogem.hub.db.*` loads the
SQLite pod. S02 smoke-tested `org.babashka/go-sqlite3` 0.2.8 (the newest release in the pod
registry on 2026-10-05) in Babashka 1.13.225 and measured:

- a fresh connection per call: flag pragmas (`foreign_keys`, `query_only`, `busy_timeout`)
  live only inside the call that sets them; `journal_mode=WAL` is persistent;
- several statements in one `execute!` all run, but are atomic only inside an explicit
  `BEGIN … COMMIT`; a connection closed mid-transaction rolls back; parameters are consumed
  across the statements of a batch in order;
- `query` prepares every statement of a batch but steps only the last; flag pragmas still
  apply because SQLite executes them at prepare time; a write refused by `query_only` returns
  no rows and no error (the pod drops `rows.Err()`);
- a blank statement sent to `query` hangs the pod forever; an empty string can kill it; after
  death every call throws "Stream closed" until the pod is loaded again (6 ms);
- **concurrent pod calls deadlock**: 2 writer threads, 8 reader threads with no writer, and
  one writer plus one reader all hung past 60 s, with and without `busy_timeout`, while 1,000
  sequential single-row transactions took 1.9 s. This is pod behaviour, not SQLite's.

## Decision

- **One gateway.** `clogem.hub.db.sqlite` is the only namespace that loads the pod (`bb lint`
  rule `:pods`, demonstrated to fail on a load anywhere else). Every call validates the SQL
  (never blank), runs serialized through one lock and under a watchdog (default 30 s); a hung or
  dead pod process is destroyed and the pod loaded again, generation-counted so concurrent
  failures restart it once. Errors are classified `:sql-error`, `:busy`, `:pod-timeout`,
  `:pod-crashed` and counted.
- **One writer, one call per transaction.** The writer owns the bus command `:db/tx` with a
  bounded queue (256). A transaction is `{:module id :statements [sql | [sql & params] …]}` and
  runs as `PRAGMA busy_timeout=5000; PRAGMA foreign_keys=ON; BEGIN IMMEDIATE; …; COMMIT;` in one
  `execute!`. Statements hold one statement each and may not start with transaction control,
  `PRAGMA`, `ATTACH`, `DETACH` or `VACUUM`; migration files (hub use) may hold several. Replies
  are `{:ok true :result {:rows-affected :last-inserted-id :statements}}` or
  `{:ok false :error {:type …}}`. Unregistering the owner drains the queue (graceful stop).
- **Reads** are one `SELECT`/`WITH`/`VALUES`/`EXPLAIN` statement each, run as
  `PRAGMA busy_timeout; PRAGMA query_only=ON; <query>` on the caller's thread through the same
  gateway. Multi-statement input is rejected so a second statement cannot flip the flag.
- **Migrations** live in a directory (classpath or absolute) of `NNNN-slug.sql` files named by
  the manifest's `:db :migrations`; each applies once, in version order, as one transaction with
  its `schema_migrations(module, version, name, applied_at)` row. Module objects must be prefixed
  `<id>_` (dashes become underscores); the hub (`:hub`) is exempt. WAL is set once, at open.
- **Jobs** (`jobs` table, hub migration 0001): queued → running → done | failed | cancelled, one
  conditional `UPDATE` per step through the writer, ownership per module (the hub may touch
  any), every change published as `:job/*` on the bus; progress replies carry the cancellation
  flag.
- **Shutdown**: drain the writer, then stop the bus. No explicit checkpoint: each call's
  connection close already checkpoints, and an explicit one races Babashka's pod shutdown hook.

## Consequences

- Throughput is bounded by the serialized pod: about 2 ms per transaction or read on the test
  machine; 1,000 concurrent `submit-tx!` calls land in a few seconds with zero `SQLITE_BUSY`.
  Reads wait behind a long write. Acceptable for a single-user local daemon.
- A module cannot hold a transaction open across calls; multi-step work is one `:statements`
  vector or several transactions. Values belong in parameters, not in SQL text (a `;` inside a
  literal is rejected).
- The hub's own tables (`schema_migrations`, `jobs`) carry no prefix; a module may read them
  through the read facade.
- Killing the daemon and its pod mid-transaction leaves the file consistent (test with a child
  process: `integrity_check` ok, only committed rows survive).

## Revisit trigger

A pod release that fixes concurrent calls (then reads could bypass the lock), a need for
transactions spanning calls, or sustained write rates above a few hundred per second.
