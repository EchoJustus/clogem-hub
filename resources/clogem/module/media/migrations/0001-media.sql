-- © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
--
-- This program and the accompanying materials are made available under the
-- terms of the Eclipse Public License 2.0 which is available at
-- https://www.eclipse.org/legal/epl-2.0
--
-- SPDX-License-Identifier: EPL-2.0

-- The media library (built-in `media` module). One row per content: id is
-- "m-" plus 32 hex characters of sha256(fingerprint "|" duration_ms), path
-- is where that content was last registered (NULL once another content was
-- registered at that path), probe is the ffprobe document as JSON text,
-- timestamps are integer milliseconds.
CREATE TABLE IF NOT EXISTS media_items (
  id TEXT PRIMARY KEY,
  path TEXT UNIQUE,
  size INTEGER NOT NULL,
  fingerprint TEXT NOT NULL,
  duration_ms INTEGER NOT NULL,
  probe TEXT,
  created_ms INTEGER NOT NULL,
  updated_ms INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS media_items_fingerprint ON media_items (fingerprint);
