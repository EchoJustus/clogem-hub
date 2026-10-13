-- © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
--
-- This program and the accompanying materials are made available under the
-- terms of the Eclipse Public License 2.0 which is available at
-- https://www.eclipse.org/legal/epl-2.0
--
-- SPDX-License-Identifier: EPL-2.0

-- S04: commands other modules address to a GUI client (:ui/command events),
-- kept until the client acknowledges them (ui_ack). The command is JSON
-- text; timestamps are integer milliseconds; acked_ms NULL means unread.
CREATE TABLE IF NOT EXISTS ui_inbox (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  client TEXT NOT NULL,
  command TEXT NOT NULL,
  created_ms INTEGER NOT NULL,
  acked_ms INTEGER
);
CREATE INDEX IF NOT EXISTS ui_inbox_client_acked ON ui_inbox (client, acked_ms);
