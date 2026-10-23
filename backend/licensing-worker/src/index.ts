/**
 * ShiftSync Organization Licensing & Device Seat Management Worker
 * Platform: Cloudflare Workers + Cloudflare D1 (Serverless SQL)
 *
 * Features:
 * - Organization-level credentials (org_code + license_key/password)
 * - Fixed numbered device slots (#1 .. max_devices) per organization
 * - Automatic lowest-free-slot assignment on login
 * - Self-selection slot takeover when all N slots are occupied (with anti-ping-pong rate limit)
 * - Session heartbeat verification (/api/v1/verify) with 3-day offline & expiry grace enforcement
 */

export interface Env {
    DB: D1Database;
    /** Secret key used to sign short-lived takeover tokens (set via `npx wrangler secret put HMAC_SECRET`) */
    HMAC_SECRET?: string;
}

const THREE_DAYS_MS = 3 * 24 * 60 * 60 * 1000;
const TAKEOVER_TOKEN_TTL_MS = 10 * 60 * 1000; // 10 minutes to pick a slot to replace
const MAX_SLOT_TAKEOVERS_PER_24H = 5;
const ONE_DAY_MS = 24 * 60 * 60 * 1000;

interface OrganizationRow {
    id: string;
    org_code: string;
    password_hash: string;
    display_name: string;
    max_devices: number;
    paid_until: number;
    is_suspended: number;
}

interface DeviceSeatRow {
    org_id: string;
    slot_number: number;
    device_install_id: string;
    device_label: string;
    session_token_hash: string;
    assigned_at: number;
    last_seen_at: number;
    takeover_count_24h: number;
    takeover_window_start_at: number;
}

export default {
    async fetch(request: Request, env: Env): Promise<Response> {
        if (request.method === "OPTIONS") {
            return new Response(null, { status: 204, headers: corsHeaders() });
        }

        const url = new URL(request.url);
        try {
            if (request.method === "POST" && url.pathname === "/api/v1/login") {
                return await handleLogin(request, env);
            }
            if (request.method === "POST" && url.pathname === "/api/v1/takeover") {
                return await handleTakeover(request, env);
            }
            if (request.method === "POST" && url.pathname === "/api/v1/verify") {
                return await handleVerify(request, env);
            }
            if (request.method === "POST" && url.pathname === "/api/v1/logout") {
                return await handleLogout(request, env);
            }
            if (request.method === "GET" && url.pathname === "/api/v1/health") {
                return jsonResponse({ status: "ok", serverTimeMillis: Date.now() }, 200);
            }
            return jsonResponse({ error: "NOT_FOUND", message: "Endpoint not found" }, 404);
        } catch (err: unknown) {
            const message = err instanceof Error ? err.message : "Internal server error";
            return jsonResponse({ error: "INTERNAL_ERROR", message }, 500);
        }
    }
};

/**
 * POST /api/v1/login
 * Body: { orgCode, password, deviceInstallId, deviceLabel }
 */
async function handleLogin(request: Request, env: Env): Promise<Response> {
    const body = await request.json<Record<string, unknown>>();
    const orgCode = String(body.orgCode ?? "").trim().toUpperCase();
    const password = String(body.password ?? "").trim();
    const deviceInstallId = String(body.deviceInstallId ?? "").trim();
    const deviceLabel = String(body.deviceLabel ?? "Android Device").trim().slice(0, 80);

    if (!orgCode || !password || !deviceInstallId) {
        return jsonResponse({ error: "INVALID_REQUEST", message: "Missing organization code, license key, or device ID." }, 400);
    }

    const org = await env.DB.prepare(
        "SELECT id, org_code, password_hash, display_name, max_devices, paid_until, is_suspended FROM organizations WHERE UPPER(org_code) = ?"
    )
        .bind(orgCode)
        .first<OrganizationRow>();

    if (!org) {
        return jsonResponse({ error: "INVALID_CREDENTIALS", message: "Invalid organization code or license key." }, 401);
    }

    const passwordValid = await verifyPassword(password, org.password_hash);
    if (!passwordValid) {
        return jsonResponse({ error: "INVALID_CREDENTIALS", message: "Invalid organization code or license key." }, 401);
    }

    if (org.is_suspended !== 0) {
        return jsonResponse({ error: "ACCOUNT_SUSPENDED", message: "This organization account is currently suspended." }, 403);
    }

    const now = Date.now();
    // Block fresh sign-in if the contract expired more than 3 days ago
    if (now > org.paid_until + THREE_DAYS_MS) {
        return jsonResponse({
            error: "SUBSCRIPTION_EXPIRED",
            message: "This organization's subscription expired more than 3 days ago.",
            paidUntilMillis: org.paid_until,
            serverTimeMillis: now
        }, 403);
    }

    const seatsResult = await env.DB.prepare(
        "SELECT * FROM device_seats WHERE org_id = ? AND slot_number <= ? ORDER BY slot_number ASC"
    )
        .bind(org.id, org.max_devices)
        .all<DeviceSeatRow>();

    const existingSeats = seatsResult.results ?? [];

    // 1. Check if this exact physical app installation already holds a slot
    const existingForInstall = existingSeats.find(s => s.device_install_id === deviceInstallId);
    if (existingForInstall) {
        const rawToken = generateRandomToken();
        const tokenHash = await sha256Hex(rawToken);
        await env.DB.prepare(
            "UPDATE device_seats SET device_label = ?, session_token_hash = ?, last_seen_at = ? WHERE org_id = ? AND slot_number = ?"
        )
            .bind(deviceLabel, tokenHash, now, org.id, existingForInstall.slot_number)
            .run();

        return jsonResponse({
            status: "OK",
            sessionToken: rawToken,
            organizationCode: org.org_code,
            organizationName: org.display_name,
            slotNumber: existingForInstall.slot_number,
            maxDevices: org.max_devices,
            deviceLabel,
            paidUntilMillis: org.paid_until,
            serverTimeMillis: now
        }, 200);
    }

    // 2. Find the lowest available slot number in 1..max_devices
    const occupiedSlotNumbers = new Set(existingSeats.map(s => s.slot_number));
    let freeSlot: number | null = null;
    for (let slot = 1; slot <= org.max_devices; slot++) {
        if (!occupiedSlotNumbers.has(slot)) {
            freeSlot = slot;
            break;
        }
    }

    if (freeSlot !== null) {
        const rawToken = generateRandomToken();
        const tokenHash = await sha256Hex(rawToken);
        await env.DB.prepare(
            `INSERT OR REPLACE INTO device_seats
             (org_id, slot_number, device_install_id, device_label, session_token_hash, assigned_at, last_seen_at, takeover_count_24h, takeover_window_start_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0)`
        )
            .bind(org.id, freeSlot, deviceInstallId, deviceLabel, tokenHash, now, now)
            .run();

        return jsonResponse({
            status: "OK",
            sessionToken: rawToken,
            organizationCode: org.org_code,
            organizationName: org.display_name,
            slotNumber: freeSlot,
            maxDevices: org.max_devices,
            deviceLabel,
            paidUntilMillis: org.paid_until,
            serverTimeMillis: now
        }, 200);
    }

    // 3. All slots 1..max_devices are occupied -> return 409 SLOTS_FULL so the user can choose which device # to replace
    const takeoverToken = await signTakeoverToken(
        { orgId: org.id, deviceInstallId, deviceLabel, exp: now + TAKEOVER_TOKEN_TTL_MS },
        env.HMAC_SECRET ?? org.password_hash
    );

    const activeDevices = existingSeats.map(seat => ({
        slotNumber: seat.slot_number,
        deviceLabel: seat.device_label,
        assignedAtMillis: seat.assigned_at,
        lastSeenAtMillis: seat.last_seen_at
    }));

    return jsonResponse({
        status: "SLOTS_FULL",
        message: `All ${org.max_devices} device slots are currently in use. Select which device slot (#1–#${org.max_devices}) to replace.`,
        organizationCode: org.org_code,
        organizationName: org.display_name,
        maxDevices: org.max_devices,
        paidUntilMillis: org.paid_until,
        serverTimeMillis: now,
        takeoverToken,
        activeDevices
    }, 409);
}

/**
 * POST /api/v1/takeover
 * Body: { orgCode, takeoverToken, slotNumber, deviceInstallId, deviceLabel }
 */
async function handleTakeover(request: Request, env: Env): Promise<Response> {
    const body = await request.json<Record<string, unknown>>();
    const orgCode = String(body.orgCode ?? "").trim().toUpperCase();
    const takeoverToken = String(body.takeoverToken ?? "").trim();
    const slotNumber = Number(body.slotNumber ?? 0);
    const deviceInstallId = String(body.deviceInstallId ?? "").trim();
    const deviceLabel = String(body.deviceLabel ?? "Android Device").trim().slice(0, 80);

    if (!orgCode || !takeoverToken || !deviceInstallId || !Number.isInteger(slotNumber) || slotNumber < 1) {
        return jsonResponse({ error: "INVALID_REQUEST", message: "Invalid takeover parameters." }, 400);
    }

    const org = await env.DB.prepare(
        "SELECT id, org_code, password_hash, display_name, max_devices, paid_until, is_suspended FROM organizations WHERE UPPER(org_code) = ?"
    )
        .bind(orgCode)
        .first<OrganizationRow>();

    if (!org || org.is_suspended !== 0) {
        return jsonResponse({ error: "UNAUTHORIZED", message: "Organization is invalid or suspended." }, 403);
    }

    if (slotNumber > org.max_devices) {
        return jsonResponse({ error: "INVALID_SLOT", message: `Slot number must be between 1 and ${org.max_devices}.` }, 400);
    }

    const verifiedPayload = await verifyTakeoverToken(takeoverToken, env.HMAC_SECRET ?? org.password_hash);
    if (!verifiedPayload || verifiedPayload.orgId !== org.id || verifiedPayload.deviceInstallId !== deviceInstallId) {
        return jsonResponse({ error: "INVALID_TAKEOVER_TOKEN", message: "Takeover session expired or invalid. Please sign in again." }, 401);
    }

    const now = Date.now();
    const currentSeat = await env.DB.prepare(
        "SELECT * FROM device_seats WHERE org_id = ? AND slot_number = ?"
    )
        .bind(org.id, slotNumber)
        .first<DeviceSeatRow>();

    let takeoverCount = 1;
    let windowStartTime = now;

    if (currentSeat && currentSeat.takeover_window_start_at > 0) {
        const isWithinWindow = (now - currentSeat.takeover_window_start_at) < ONE_DAY_MS;
        if (isWithinWindow) {
            takeoverCount = currentSeat.takeover_count_24h + 1;
            windowStartTime = currentSeat.takeover_window_start_at; // Preserve original window start time
            if (takeoverCount > MAX_SLOT_TAKEOVERS_PER_24H) {
                return jsonResponse({
                    error: "TAKEOVER_RATE_LIMIT",
                    message: `Device #${slotNumber} has reached the limit of ${MAX_SLOT_TAKEOVERS_PER_24H} reassignments within a 24-hour window. Please pick another slot or contact support.`
                }, 429);
            }
        } else {
            // More than 24h passed since window started -> start a fresh window
            takeoverCount = 1;
            windowStartTime = now;
        }
    }

    const rawToken = generateRandomToken();
    const tokenHash = await sha256Hex(rawToken);

    await env.DB.prepare(
        `INSERT OR REPLACE INTO device_seats
         (org_id, slot_number, device_install_id, device_label, session_token_hash, assigned_at, last_seen_at, takeover_count_24h, takeover_window_start_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
    )
        .bind(org.id, slotNumber, deviceInstallId, deviceLabel, tokenHash, now, now, takeoverCount, windowStartTime)
        .run();

    return jsonResponse({
        status: "OK",
        sessionToken: rawToken,
        organizationCode: org.org_code,
        organizationName: org.display_name,
        slotNumber,
        maxDevices: org.max_devices,
        deviceLabel,
        paidUntilMillis: org.paid_until,
        serverTimeMillis: now
    }, 200);
}

/**
 * POST /api/v1/verify
 * Body: { sessionToken, deviceInstallId }
 */
async function handleVerify(request: Request, env: Env): Promise<Response> {
    const body = await request.json<Record<string, unknown>>();
    const sessionToken = String(body.sessionToken ?? "").trim();
    const deviceInstallId = String(body.deviceInstallId ?? "").trim();

    if (!sessionToken || !deviceInstallId) {
        return jsonResponse({ error: "INVALID_REQUEST", message: "Missing session token." }, 400);
    }

    const tokenHash = await sha256Hex(sessionToken);
    const seat = await env.DB.prepare(
        "SELECT * FROM device_seats WHERE session_token_hash = ?"
    )
        .bind(tokenHash)
        .first<DeviceSeatRow>();

    const now = Date.now();
    if (!seat || seat.device_install_id !== deviceInstallId) {
        return jsonResponse({
            status: "SESSION_REVOKED",
            message: "This device slot was signed out or reassigned to another phone.",
            serverTimeMillis: now
        }, 401);
    }

    const org = await env.DB.prepare(
        "SELECT id, org_code, password_hash, display_name, max_devices, paid_until, is_suspended FROM organizations WHERE id = ?"
    )
        .bind(seat.org_id)
        .first<OrganizationRow>();

    if (!org || org.is_suspended !== 0 || seat.slot_number > org.max_devices) {
        return jsonResponse({
            status: "ACCOUNT_SUSPENDED",
            message: "This organization account or device slot is no longer active.",
            serverTimeMillis: now
        }, 403);
    }

    await env.DB.prepare(
        "UPDATE device_seats SET last_seen_at = ? WHERE org_id = ? AND slot_number = ?"
    )
        .bind(now, org.id, seat.slot_number)
        .run();

    return jsonResponse({
        status: "OK",
        organizationCode: org.org_code,
        organizationName: org.display_name,
        slotNumber: seat.slot_number,
        maxDevices: org.max_devices,
        deviceLabel: seat.device_label,
        paidUntilMillis: org.paid_until,
        serverTimeMillis: now
    }, 200);
}

/**
 * POST /api/v1/logout
 * Body: { sessionToken }
 */
async function handleLogout(request: Request, env: Env): Promise<Response> {
    const body = await request.json<Record<string, unknown>>();
    const sessionToken = String(body.sessionToken ?? "").trim();
    if (sessionToken) {
        const tokenHash = await sha256Hex(sessionToken);
        await env.DB.prepare("DELETE FROM device_seats WHERE session_token_hash = ?")
            .bind(tokenHash)
            .run();
    }
    return jsonResponse({ status: "OK" }, 200);
}

// --- Cryptographic Helpers (Web Crypto API) ---

function generateRandomToken(): string {
    const bytes = new Uint8Array(32);
    crypto.getRandomValues(bytes);
    return Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
}

async function sha256Hex(input: string): Promise<string> {
    const data = new TextEncoder().encode(input);
    const digest = await crypto.subtle.digest("SHA-256", data);
    return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Supports either:
 * 1) "sha256:<salt>:<hex_hash>" (salted SHA-256)
 * 2) Plain SHA-256 hex of the password (easy to generate in any online/CLI tool)
 */
async function verifyPassword(plainPassword: string, storedHash: string): Promise<boolean> {
    if (storedHash.startsWith("sha256:")) {
        const parts = storedHash.split(":");
        if (parts.length !== 3) return false;
        const salt = parts[1];
        const expectedHex = parts[2].toLowerCase();
        const actualHex = await sha256Hex(`${salt}:${plainPassword}`);
        return timingSafeEqual(actualHex, expectedHex);
    }
    const actualHex = await sha256Hex(plainPassword);
    return timingSafeEqual(actualHex, storedHash.toLowerCase());
}

function timingSafeEqual(a: string, b: string): boolean {
    if (a.length !== b.length) return false;
    let diff = 0;
    for (let i = 0; i < a.length; i++) {
        diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
    }
    return diff === 0;
}

interface TakeoverPayload {
    orgId: string;
    deviceInstallId: string;
    deviceLabel: string;
    exp: number;
}

async function signTakeoverToken(payload: TakeoverPayload, secret: string): Promise<string> {
    const jsonStr = JSON.stringify(payload);
    const b64 = btoa(jsonStr);
    const sig = await sha256Hex(`${secret}.${b64}`);
    return `${b64}.${sig}`;
}

async function verifyTakeoverToken(token: string, secret: string): Promise<TakeoverPayload | null> {
    const dotIndex = token.lastIndexOf(".");
    if (dotIndex <= 0) return null;
    const b64 = token.slice(0, dotIndex);
    const sig = token.slice(dotIndex + 1);
    const expectedSig = await sha256Hex(`${secret}.${b64}`);
    if (!timingSafeEqual(sig, expectedSig)) return null;
    try {
        const payload = JSON.parse(atob(b64)) as TakeoverPayload;
        if (Date.now() > payload.exp) return null;
        return payload;
    } catch {
        return null;
    }
}

function corsHeaders(): Record<string, string> {
    return {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
        "Access-Control-Allow-Headers": "Content-Type, Authorization"
    };
}

function jsonResponse(payload: unknown, status: number): Response {
    return new Response(JSON.stringify(payload), {
        status,
        headers: {
            "Content-Type": "application/json",
            ...corsHeaders()
        }
    });
}
