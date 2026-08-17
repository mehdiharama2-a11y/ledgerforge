ALTER TABLE outbox_events ADD COLUMN failures_remaining integer NOT NULL DEFAULT 0 CHECK (failures_remaining >= 0);
