-- © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
--
-- This program and the accompanying materials are made available under the
-- terms of the Eclipse Public License 2.0 which is available at
-- https://www.eclipse.org/legal/epl-2.0
--
-- SPDX-License-Identifier: EPL-2.0

-- S03: who called what. Never arguments, results or tokens.
CREATE TABLE IF NOT EXISTS audit (
  id INTEGER PRIMARY KEY,
  ts INTEGER NOT NULL,
  client TEXT NOT NULL,
  profile TEXT NOT NULL,
  method TEXT NOT NULL,
  name TEXT,
  ok INTEGER NOT NULL CHECK (ok IN (0, 1)),
  error TEXT,
  duration_ms INTEGER NOT NULL,
  request_id TEXT
);
CREATE INDEX IF NOT EXISTS audit_ts ON audit (ts);
CREATE INDEX IF NOT EXISTS audit_client_ts ON audit (client, ts);
