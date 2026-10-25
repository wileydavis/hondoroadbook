-- The roadbook library (Cloudflare D1). GPX bodies live in R2 under library/<id>.gpx; this is the list.
CREATE TABLE IF NOT EXISTS roadbooks (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  length_m REAL NOT NULL,
  cues INTEGER NOT NULL DEFAULT 0,
  min_lon REAL NOT NULL, min_lat REAL NOT NULL, max_lon REAL NOT NULL, max_lat REAL NOT NULL,
  line TEXT NOT NULL,            -- simplified [[lon,lat],…] for the map
  mode TEXT,                     -- the planner mode it was built with, if any
  paved REAL, unpaved REAL,      -- meters, when the planner knew
  source TEXT,                   -- app | planner
  created INTEGER NOT NULL,
  hidden INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS roadbooks_bbox ON roadbooks (min_lon, max_lon, min_lat, max_lat);
CREATE INDEX IF NOT EXISTS roadbooks_created ON roadbooks (created);
-- uploads per visitor per day, under a hash that changes daily
CREATE TABLE IF NOT EXISTS uploads (k TEXT PRIMARY KEY, n INTEGER NOT NULL DEFAULT 0);
