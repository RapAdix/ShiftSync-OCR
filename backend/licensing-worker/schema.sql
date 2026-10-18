-- Cloudflare D1 (SQLite) Schema for ShiftSync Organization Licensing & Device Seat Management
-- Run with: npx wrangler d1 execute shiftsync-licensing-db --file=./schema.sql

CREATE TABLE IF NOT EXISTS organizations (
    id TEXT PRIMARY KEY,
    org_code TEXT NOT NULL UNIQUE COLLATE NOCASE,
    password_hash TEXT NOT NULL,
    display_name TEXT NOT NULL,
    max_devices INTEGER NOT NULL DEFAULT 1 CHECK (max_devices >= 1 AND max_devices <= 600),
    paid_until INTEGER NOT NULL,           -- Unix epoch milliseconds when paid contract expires
    is_suspended INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS device_seats (
    org_id TEXT NOT NULL,
    slot_number INTEGER NOT NULL CHECK (slot_number >= 1),
    device_install_id TEXT NOT NULL,
    device_label TEXT NOT NULL,
    session_token_hash TEXT NOT NULL,
    assigned_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    takeover_count_24h INTEGER NOT NULL DEFAULT 0,
    last_takeover_at INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (org_id, slot_number),
    FOREIGN KEY (org_id) REFERENCES organizations(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_device_seats_token ON device_seats(session_token_hash);
CREATE INDEX IF NOT EXISTS idx_device_seats_org_install ON device_seats(org_id, device_install_id);
