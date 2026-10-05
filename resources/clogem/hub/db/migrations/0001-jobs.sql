-- © 2026 EchoJustus (Email: EchoJustus.Studio@outlook.com)
--
-- This program and the accompanying materials are made available under the
-- terms of the Eclipse Public License 2.0 which is available at
-- https://www.eclipse.org/legal/epl-2.0
--
-- SPDX-License-Identifier: EPL-2.0

-- Jobs of every module (clogem.api/job!). Progress is a fraction 0..1,
-- timestamps are integer milliseconds, input/result/error are EDN text.
CREATE TABLE IF NOT EXISTS jobs (
  id TEXT PRIMARY KEY,
  module TEXT NOT NULL,
  kind TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('queued', 'running', 'done', 'failed', 'cancelled')),
  progress REAL NOT NULL DEFAULT 0 CHECK (progress >= 0 AND progress <= 1),
  message TEXT,
  input TEXT,
  result TEXT,
  error TEXT,
  cancel_requested INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  finished_at INTEGER
);
CREATE INDEX IF NOT EXISTS jobs_module_status ON jobs (module, status);
CREATE INDEX IF NOT EXISTS jobs_updated_at ON jobs (updated_at);
