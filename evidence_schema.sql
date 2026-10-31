CREATE TABLE IF NOT EXISTS observer_state(id INTEGER PRIMARY KEY CHECK(id=1),wall REAL NOT NULL,at TEXT NOT NULL,state TEXT NOT NULL,last_success_at TEXT);
CREATE TABLE IF NOT EXISTS collection_gaps(id INTEGER PRIMARY KEY,network_id TEXT NOT NULL,started_at TEXT NOT NULL,ended_at TEXT NOT NULL,wall_seconds REAL NOT NULL,monotonic_seconds REAL,reason TEXT NOT NULL,interval_seconds REAL NOT NULL);
CREATE INDEX IF NOT EXISTS gaps_network ON collection_gaps(network_id,id);
CREATE TABLE IF NOT EXISTS collection_batches(id INTEGER PRIMARY KEY,network_id TEXT NOT NULL,started_at TEXT NOT NULL,kind TEXT NOT NULL,contract TEXT NOT NULL,failure_catalog TEXT NOT NULL);
CREATE INDEX IF NOT EXISTS batches_network ON collection_batches(network_id,id);
CREATE TABLE IF NOT EXISTS evidence_sequences(name TEXT PRIMARY KEY,value INTEGER NOT NULL);
INSERT OR IGNORE INTO evidence_sequences SELECT 'observations',coalesce(max(id),0) FROM observations;
INSERT OR IGNORE INTO evidence_sequences SELECT 'events',coalesce(max(id),0) FROM events;
CREATE TRIGGER IF NOT EXISTS observation_sequence AFTER INSERT ON observations BEGIN
UPDATE evidence_sequences SET value=max(value,new.id) WHERE name='observations'; END;
CREATE TRIGGER IF NOT EXISTS event_sequence AFTER INSERT ON events BEGIN
UPDATE evidence_sequences SET value=max(value,new.id) WHERE name='events'; END;
