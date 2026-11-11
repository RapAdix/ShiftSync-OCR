# ShiftSync Organization Licensing Server (Cloudflare Workers + D1)

This folder contains a serverless **Cloudflare Worker (TypeScript)** backed by **Cloudflare D1 (Serverless SQLite)** for managing B2B organization subscriptions and numbered device seats (`#1 .. #N`).

---

## Why Cloudflare Workers + D1?

- **100% Free Tier**: 100,000 requests/day, 5,000,000 D1 row reads/day, and 100,000 row writes/day.
- **Never Sleeps**: Unlike Supabase or free container tiers, Cloudflare Workers & D1 never pause or cold-sleep due to inactivity.
- **Built-in DDoS & Edge WAF Protection**: Unmetered Layer-3/4/7 DDoS protection + edge WAF Rate Limiting rules prevent spam traffic from consuming your Worker quota.

---

## Updating & Deploying the Server

All routine development and deployment tasks are defined in `package.json` (using `npm.cmd` / `npx.cmd` on Windows PowerShell):

### Logging In / Re-authenticating Wrangler

```powershell
# Check whether you are currently logged in
npx.cmd wrangler whoami

# Log back in (opens Cloudflare login in your browser)
npx.cmd wrangler login
```

### Routine Commands

```powershell
# 1. Start local development server (with local D1 SQLite emulation)
npm.cmd run dev

# 2. Deploy the Worker live to Cloudflare edge
npm.cmd run deploy

# 3. View live production logs in real time
npx.cmd wrangler tail

# 4. Set or rotate the HMAC secret for slot-takeover tokens
npx.cmd wrangler secret put HMAC_SECRET

# 5. Initialize or reset D1 database tables from schema.sql
npm.cmd run db:init        # Production (remote D1: shiftsync-licensing-db)
npm.cmd run db:init:local  # Local development emulator
```

---

## Creating & Managing Organizations with `npm.cmd run hash-password`

ShiftSync stores passwords using salted SHA-256 (`sha256:<salt>:<hex_hash>`), matching `verifyPassword` in `src/index.ts`. The helper CLI script `scripts/hash-password.mjs` generates the salted hash and outputs ready-to-run D1 CLI commands for both production (`--remote`) and local testing (`--local`).

### Usage

```powershell
# Generate hash & template SQL commands
npm.cmd run hash-password -- <password>

# Generate full INSERT / UPDATE commands for a specific organization
npm.cmd run hash-password -- <org_code> <password> [display_name] [max_devices] [days_valid]
```

### Parameters
- **`org_code`**: Organization login identifier, normalized to uppercase (e.g. `DEMO-CORP`).
- **`password`**: Plain-text license key / password to hash.
- **`display_name`** *(optional)*: Human-readable company name (defaults to `org_code`).
- **`max_devices`** *(optional)*: Seat count limit between 1 and 600 (defaults to `1`).
- **`days_valid`** *(optional)*: Subscription duration in days from now (defaults to `30`).

### Example: Onboarding a New Client

```powershell
npm.cmd run hash-password -- DEMO-CORP MyClientSecret2026 "Demo Warsaw" 5 30
```

The script outputs ready-to-run wrangler commands:

```powershell
# Create organization in Production (remote):
npx.cmd wrangler d1 execute DB --remote --command="INSERT INTO organizations (id, org_code, password_hash, display_name, max_devices, paid_until, is_suspended, created_at) VALUES ('org_demo_corp_a1b2', 'DEMO-CORP', 'sha256:...', 'Demo Warsaw', 5, 1796998000000, 0, 1794406000000);"

# Update password in Production (remote):
npx.cmd wrangler d1 execute DB --remote --command="UPDATE organizations SET password_hash = 'sha256:...' WHERE UPPER(org_code) = 'DEMO-CORP';"
```

Simply copy and run the generated command in your terminal.

---

## Database Schema & Administration (`DB` / `shiftsync-licensing-db`)

The database consists of two tables defined in `schema.sql`:

1. **`organizations`**:
   - `id` (TEXT PRIMARY KEY): Unique internal slug (e.g. `org_demo_corp_a1b2`).
   - `org_code` (TEXT NOT NULL UNIQUE COLLATE NOCASE): Uppercase login code.
   - `password_hash` (TEXT NOT NULL): Salted hash (`sha256:<salt>:<hex>`).
   - `display_name` (TEXT NOT NULL): Friendly company or branch name.
   - `max_devices` (INTEGER NOT NULL DEFAULT 1): Allowed seat count (1..600).
   - `paid_until` (INTEGER NOT NULL): Epoch timestamp (ms) when paid subscription expires.
   - `is_suspended` (INTEGER NOT NULL DEFAULT 0): `1` blocks all logins and heartbeat verifications.
   - `created_at` (INTEGER NOT NULL): Epoch timestamp (ms) of creation.

2. **`device_seats`**:
   - `org_id` (TEXT NOT NULL, FK $\rightarrow$ `organizations.id` ON DELETE CASCADE)
   - `slot_number` (INTEGER NOT NULL, 1..`max_devices`)
   - `device_install_id` (TEXT NOT NULL): Client app installation UUID.
   - `device_label` (TEXT NOT NULL): Model + optional nickname (e.g. `Pixel 8 (Kasa 1)`).
   - `session_token_hash` (TEXT NOT NULL): SHA-256 hash of active session token.
   - `assigned_at` (INTEGER NOT NULL): When the slot was claimed.
   - `last_seen_at` (INTEGER NOT NULL): Last `/api/v1/verify` heartbeat.
   - `takeover_count_24h` / `takeover_window_start_at`: Anti-ping-pong rate limiter for slot takeovers.

### Useful SQL Management Queries

Run with `npx.cmd wrangler d1 execute DB --remote --command="..."`:

- **List All Organizations & Seat Usage**:
  ```sql
  SELECT o.org_code, o.display_name, o.max_devices,
         COUNT(s.slot_number) AS occupied_seats,
         DATETIME(o.paid_until / 1000, 'unixepoch') AS paid_until,
         o.is_suspended
  FROM organizations o
  LEFT JOIN device_seats s ON s.org_id = o.id
  GROUP BY o.id;
  ```

- **Extend Subscription by 90 Days**:
  ```sql
  UPDATE organizations
  SET paid_until = paid_until + (90 * 24 * 60 * 60 * 1000)
  WHERE UPPER(org_code) = 'DEMO-CORP';
  ```

- **Suspend / Unsuspend an Organization**:
  ```sql
  -- Suspend
  UPDATE organizations SET is_suspended = 1 WHERE UPPER(org_code) = 'DEMO-CORP';
  -- Unsuspend
  UPDATE organizations SET is_suspended = 0 WHERE UPPER(org_code) = 'DEMO-CORP';
  ```

- **Force Clear All Device Seats for an Organization**:
  ```sql
  DELETE FROM device_seats
  WHERE org_id = (SELECT id FROM organizations WHERE UPPER(org_code) = 'DEMO-CORP');
  ```

---

## First-Time Setup & Database Initialization

If deploying from scratch into a fresh Cloudflare account:

1. **Sign Up**: Create a free account at [https://dash.cloudflare.com/sign-up](https://dash.cloudflare.com/sign-up) (no credit card required).
2. **Install & Log In**:
   ```powershell
   npm.cmd install -g wrangler
   npx.cmd wrangler login
   ```
3. **Create the D1 Database**:
   ```powershell
   npx.cmd wrangler d1 create shiftsync-licensing-db
   ```
   Copy the `database_id` output by Wrangler and paste it into `wrangler.toml`:
   ```toml
   [[d1_databases]]
   binding = "DB"
   database_name = "shiftsync-licensing-db"
   database_id = "<PASTE_DATABASE_ID_HERE>"
   ```
4. **Initialize Tables**:
   ```powershell
   npm.cmd run db:init
   ```
5. **Set HMAC Secret & Deploy**:
   ```powershell
   npx.cmd wrangler secret put HMAC_SECRET
   npm.cmd run deploy
   ```

---

## Setting Up Free Anti-DDoS / Spam Rate Limiting (Cloudflare WAF)

To prevent brute-force login attempts from exhausting the Worker request quota:
1. When attaching a custom domain to the Worker:
2. Open **Cloudflare Dashboard $\rightarrow$ Security $\rightarrow$ WAF $\rightarrow$ Rate limiting rules**.
3. Create a free rule:
   - **If incoming requests match**: `URI Path` `starts with` `/api/v1/login`
   - **With the same**: `IP`
   - **When rate exceeds**: `10 requests` per `1 minute`
   - **Then take action**: `Block` for `1 minute`
Requests blocked by edge WAF do not invoke your Worker and do not count toward your daily free quota.
