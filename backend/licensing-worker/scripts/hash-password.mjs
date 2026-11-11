#!/usr/bin/env node
import { createHash, randomBytes } from "node:crypto";

function hashPassword(password) {
  const salt = randomBytes(16).toString("hex");
  const digest = createHash("sha256").update(`${salt}:${password}`).digest("hex");
  return `sha256:${salt}:${digest}`;
}

function sqlEscape(value) {
  return String(value).replace(/'/g, "''");
}

const args = process.argv.slice(2);

if (args.length === 0 || args.includes("-h") || args.includes("--help")) {
  console.log(`
Usage:
  npm.cmd run hash-password -- <password>
  npm.cmd run hash-password -- <org_code> <password> [display_name] [max_devices] [days_valid]

Arguments:
  org_code      Organization login code (normalized to UPPERCASE, e.g. DEMO-CORP)
  password      Plain-text license key / password to hash
  display_name  Optional display name (default: org_code)
  max_devices   Optional device seat limit 1..600 (default: 1)
  days_valid    Optional subscription validity in days from now (default: 30)

Examples:
  npm.cmd run hash-password -- MyClientSecret2026
  npm.cmd run hash-password -- DEMO-CORP MyClientSecret2026 "Demo Warsaw" 5 30
`);
  process.exit(args.length === 0 ? 1 : 0);
}

if (args.length === 1) {
  const password = args[0].trim();
  const hash = hashPassword(password);
  const now = Date.now();
  const paidUntil = now + 30 * 24 * 60 * 60 * 1000;

  console.log("Password Hash:");
  console.log(hash);
  console.log("\nTemplate D1 commands:");
  console.log("\n# Insert new organization (remote):");
  console.log(
    `npx.cmd wrangler d1 execute DB --remote --command="INSERT INTO organizations (id, org_code, password_hash, display_name, max_devices, paid_until, is_suspended, created_at) VALUES ('org_001', '<ORG_CODE>', '${hash}', '<DISPLAY_NAME>', 1, ${paidUntil}, 0, ${now});"`
  );
  console.log("\n# Insert new organization (local):");
  console.log(
    `npx.cmd wrangler d1 execute DB --local --command="INSERT INTO organizations (id, org_code, password_hash, display_name, max_devices, paid_until, is_suspended, created_at) VALUES ('org_001', '<ORG_CODE>', '${hash}', '<DISPLAY_NAME>', 1, ${paidUntil}, 0, ${now});"`
  );
  console.log("\n# Update existing organization's password (remote):");
  console.log(
    `npx.cmd wrangler d1 execute DB --remote --command="UPDATE organizations SET password_hash = '${hash}' WHERE UPPER(org_code) = '<ORG_CODE>';"`
  );
  console.log("\n# Update existing organization's password (local):");
  console.log(
    `npx.cmd wrangler d1 execute DB --local --command="UPDATE organizations SET password_hash = '${hash}' WHERE UPPER(org_code) = '<ORG_CODE>';"`
  );

  console.log("\nPassword Hash:");
  console.log(hash);
  process.exit(0);
}

const orgCode = args[0].trim().toUpperCase();
const password = args[1].trim();
const displayName = args[2] !== undefined && args[2].trim() !== "" ? args[2].trim() : orgCode;
const maxDevicesRaw = args[3] !== undefined ? Number.parseInt(args[3], 10) : 1;
const maxDevices = Number.isFinite(maxDevicesRaw) ? Math.min(600, Math.max(1, maxDevicesRaw)) : 1;
const daysValidRaw = args[4] !== undefined ? Number.parseFloat(args[4]) : 30;
const daysValid = Number.isFinite(daysValidRaw) && daysValidRaw > 0 ? daysValidRaw : 30;

const now = Date.now();
const paidUntil = Math.round(now + daysValid * 24 * 60 * 60 * 1000);
const slug = orgCode.toLowerCase().replace(/[^a-z0-9]+/g, "_").replace(/^_|_$/g, "") || "org";
const orgId = `org_${slug}_${randomBytes(2).toString("hex")}`;
const hash = hashPassword(password);

const sqlId = sqlEscape(orgId);
const sqlOrgCode = sqlEscape(orgCode);
const sqlDisplayName = sqlEscape(displayName);

console.log("Generated Password Hash:");
console.log(hash);

console.log("\n--- Local Commands (Development) ---");
console.log("\n# Create organization:");
console.log(
  `npx.cmd wrangler d1 execute DB --local --command="INSERT INTO organizations (id, org_code, password_hash, display_name, max_devices, paid_until, is_suspended, created_at) VALUES ('${sqlId}', '${sqlOrgCode}', '${hash}', '${sqlDisplayName}', ${maxDevices}, ${paidUntil}, 0, ${now});"`
);
console.log("\n# Update password:");
console.log(
  `npx.cmd wrangler d1 execute DB --local --command="UPDATE organizations SET password_hash = '${hash}' WHERE UPPER(org_code) = '${sqlOrgCode}';"`
);

console.log("\n--- Remote Commands (Production) ---");
console.log("\n# Create organization:");
console.log(
  `npx.cmd wrangler d1 execute DB --remote --command="INSERT INTO organizations (id, org_code, password_hash, display_name, max_devices, paid_until, is_suspended, created_at) VALUES ('${sqlId}', '${sqlOrgCode}', '${hash}', '${sqlDisplayName}', ${maxDevices}, ${paidUntil}, 0, ${now});"`
);
console.log("\n# Update password:");
console.log(
  `npx.cmd wrangler d1 execute DB --remote --command="UPDATE organizations SET password_hash = '${hash}' WHERE UPPER(org_code) = '${sqlOrgCode}';"`
);
