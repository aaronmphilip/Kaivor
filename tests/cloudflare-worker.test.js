import { describe, expect, it } from "vitest";
import worker from "../apps/cloudflare-worker/src/index.js";
class MockPrepared {
    db;
    query;
    values = [];
    constructor(db, query) {
        this.db = db;
        this.query = query;
    }
    bind(...values) {
        this.values = values;
        return this;
    }
    async first() {
        return this.db.first(this.query, this.values);
    }
    async all() {
        return this.db.all(this.query, this.values);
    }
    async run() {
        return this.db.run(this.query, this.values);
    }
}
class MockD1 {
    mode;
    tenants = [];
    owners = [];
    tenantConfigs = [];
    subscriptions = [];
    auditEvents = [];
    waitlist = [];
    constructor(mode = "ok") {
        this.mode = mode;
    }
    prepare(query) {
        if (this.mode === "fail") {
            throw new Error("db down");
        }
        return new MockPrepared(this, query);
    }
    async first(query, values) {
        if (query.includes("SELECT phone FROM owner_contacts WHERE tenant_id=?")) {
            const tenantId = String(values[0] ?? "");
            const owner = this.owners.find((row) => row.tenant_id === tenantId && row.is_primary === 1) ?? null;
            return owner ? { phone: owner.phone } : null;
        }
        if (query.includes("SELECT * FROM tenants WHERE id=?")) {
            const tenantId = String(values[0] ?? "");
            return (this.tenants.find((row) => row.id === tenantId) ?? null);
        }
        if (query.includes("SELECT name,phone,email FROM owner_contacts WHERE tenant_id=?")) {
            const tenantId = String(values[0] ?? "");
            const owner = this.owners.find((row) => row.tenant_id === tenantId && row.is_primary === 1) ?? null;
            return owner ? { name: owner.name, phone: owner.phone, email: owner.email } : null;
        }
        if (query.includes("SELECT metadata FROM tenant_configs WHERE tenant_id=?")) {
            const tenantId = String(values[0] ?? "");
            const row = this.tenantConfigs.find((item) => item.tenant_id === tenantId) ?? null;
            return row ? { metadata: row.metadata } : null;
        }
        if (query.includes("SELECT t.name AS tenant_name,c.metadata")) {
            const tenantId = String(values[0] ?? "");
            const tenant = this.tenants.find((row) => row.id === tenantId) ?? null;
            const config = this.tenantConfigs.find((row) => row.tenant_id === tenantId) ?? null;
            return tenant && config ? { tenant_name: tenant.name, metadata: config.metadata } : null;
        }
        if (query.includes("SELECT t.id AS tenant_id,t.name AS business_name,o.name AS owner_name,o.email,o.phone,c.metadata")) {
            const ownerEmail = String(values[0] ?? "").toLowerCase();
            const businessName = String(values[1] ?? "").toLowerCase();
            const owner = this.owners.find((row) => row.is_primary === 1 && String(row.email ?? "").toLowerCase() === ownerEmail) ?? null;
            const tenant = owner
                ? this.tenants.find((row) => row.id === owner.tenant_id && String(row.name ?? "").toLowerCase() === businessName) ?? null
                : null;
            const config = tenant ? this.tenantConfigs.find((row) => row.tenant_id === tenant.id) ?? null : null;
            return tenant && owner && config
                ? {
                    tenant_id: tenant.id,
                    business_name: tenant.name,
                    owner_name: owner.name,
                    email: owner.email,
                    phone: owner.phone,
                    metadata: config.metadata
                }
                : null;
        }
        if (query.includes("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=? AND status='FOLLOWUP_PENDING'")) {
            return { count: 0 };
        }
        if (query.includes("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=? AND status!='CLOSED'")) {
            return { count: 0 };
        }
        if (query.includes("SELECT COUNT(*) AS count FROM leads WHERE tenant_id=?")) {
            return { count: 0 };
        }
        throw new Error(`Unhandled first query: ${query}`);
    }
    async all(query, _values) {
        if (query.includes("PRAGMA table_info(leads)")) {
            return {
                results: [
                    { name: "id" },
                    { name: "tenant_id" },
                    { name: "customer_phone" },
                    { name: "customer_name" },
                    { name: "preferred_language" },
                    { name: "requirement" },
                    { name: "workflow_mode" },
                    { name: "workflow_details" },
                    { name: "status" },
                    { name: "bot_paused_until" },
                    { name: "last_inbound_at" },
                    { name: "last_outbound_at" },
                    { name: "created_at" },
                    { name: "updated_at" }
                ]
            };
        }
        if (query.includes("SELECT metadata FROM tenant_configs")) {
            return {
                results: this.tenantConfigs.map((row) => ({ metadata: row.metadata }))
            };
        }
        if (query.includes("FROM leads") && query.includes("ORDER BY updated_at DESC")) {
            return { results: [] };
        }
        throw new Error(`Unhandled all query: ${query}`);
    }
    async run(query, values) {
        if (query.includes("CREATE TABLE IF NOT EXISTS leads") ||
            query.includes("CREATE TABLE IF NOT EXISTS chat_routes") ||
            query.includes("CREATE INDEX IF NOT EXISTS idx_chat_routes_tenant")) {
            return { meta: { changes: 0 } };
        }
        if (query.includes("CREATE TABLE IF NOT EXISTS waitlist_signups")) {
            return { meta: { changes: 0 } };
        }
        if (query.includes("INSERT INTO waitlist_signups")) {
            this.waitlist.push({
                name: String(values[1]),
                business_name: String(values[2]),
                email: values[3] == null ? null : String(values[3])
            });
            return { meta: { changes: 1 } };
        }
        if (query.includes("INSERT INTO tenants")) {
            this.tenants.push({
                id: String(values[0]),
                name: String(values[1]),
                slug: String(values[2]),
                whatsapp_phone_number_id: String(values[3]),
                tenant_api_key_hash: String(values[4]),
                trial_ends_at: String(values[5]),
                created_at: String(values[6])
            });
            return { meta: { changes: 1 } };
        }
        if (query.includes("INSERT INTO owner_contacts")) {
            this.owners.push({
                id: String(values[0]),
                tenant_id: String(values[1]),
                name: String(values[2]),
                phone: String(values[3]),
                email: values[4] == null ? null : String(values[4]),
                is_primary: 1,
                created_at: String(values[5])
            });
            return { meta: { changes: 1 } };
        }
        if (query.includes("INSERT INTO tenant_configs")) {
            this.tenantConfigs.push({
                tenant_id: String(values[0]),
                metadata: String(values[5])
            });
            return { meta: { changes: 1 } };
        }
        if (query.includes("INSERT INTO subscriptions")) {
            this.subscriptions.push({
                id: String(values[0]),
                tenant_id: String(values[1]),
                status: String(values[2])
            });
            return { meta: { changes: 1 } };
        }
        if (query.includes("INSERT INTO audit_events")) {
            this.auditEvents.push({
                tenant_id: String(values[1]),
                action: String(values[4])
            });
            return { meta: { changes: 1 } };
        }
        if (query.includes("DELETE FROM tenants WHERE id=?")) {
            const tenantId = String(values[0] ?? "");
            this.tenants = this.tenants.filter((row) => row.id !== tenantId);
            this.owners = this.owners.filter((row) => row.tenant_id !== tenantId);
            this.tenantConfigs = this.tenantConfigs.filter((row) => row.tenant_id !== tenantId);
            this.subscriptions = this.subscriptions.filter((row) => row.tenant_id !== tenantId);
            return { meta: { changes: 1 } };
        }
        throw new Error(`Unhandled run query: ${query}`);
    }
}
function createEnv(db) {
    return {
        DB: db,
        MASTER_API_KEY: "master-test-key",
        TELEGRAM_WEBHOOK_SECRET: "telegram-secret-test",
        OWNER_CONNECT_BOT_TOKEN: "shared-bot-token",
        OWNER_CONNECT_BOT_SECRET: "owner-secret",
        OWNER_CONNECT_BOT_USERNAME: "kaivorbot",
        PUBLIC_SIGNUP_ENABLED: "true",
        FREE_MODE: "true"
    };
}
describe("cloudflare worker", () => {
    it("creates a start in shared bot mode", async () => {
        const env = createEnv(new MockD1());
        const request = new Request("https://kaivor-telegram.kaivor.workers.dev/public/start", {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({
                businessName: "Acme Business",
                ownerName: "Aaron",
                ownerEmail: "aaron@example.com"
            })
        });
        const response = await worker.fetch(request, env);
        const body = (await response.json());
        expect(response.status).toBe(201);
        expect(body.ok).toBe(true);
        expect(String(body.ownerPairCode)).toMatch(/^KV/);
        expect(String(body.leadEntryUrl)).toContain("lead_");
        expect(String(body.ownerConnectBotUrl)).toBe("https://t.me/kaivorbot");
        expect(String(body.profile?.workflowLabel)).toBe("Lead capture");
        expect(String(body.statusUrl)).toContain("/public/start/");
    });
    it("returns structured json instead of crashing when the database throws", async () => {
        const env = createEnv(new MockD1("fail"));
        const request = new Request("https://kaivor-telegram.kaivor.workers.dev/public/start", {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({
                businessName: "Acme Business",
                ownerName: "Aaron"
            })
        });
        const response = await worker.fetch(request, env);
        const body = (await response.json());
        expect(response.status).toBe(500);
        expect(body.code).toBe("WORKER_RUNTIME_ERROR");
        expect(String(body.detail)).toContain("db down");
    });
    it("returns authenticated workspace data", async () => {
        const env = createEnv(new MockD1());
        const createRequest = new Request("https://kaivor-telegram.kaivor.workers.dev/public/start", {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({
                businessName: "Acme Business",
                ownerName: "Aaron",
                ownerEmail: "aaron@example.com"
            })
        });
        const createResponse = await worker.fetch(createRequest, env);
        const created = (await createResponse.json());
        const workspace = new URL(String(created.workspaceUrl));
        const tenantId = workspace.searchParams.get("tenantId");
        const token = workspace.searchParams.get("token");
        const workspaceResponse = await worker.fetch(new Request(`https://kaivor-telegram.kaivor.workers.dev/public/workspaces/${tenantId}?token=${token}`), env);
        const body = (await workspaceResponse.json());
        expect(workspaceResponse.status).toBe(200);
        expect(body.businessName).toBe("Acme Business");
        expect(String(body.leadEntryUrl)).toContain("lead_");
    });
    it("stores waitlist signups", async () => {
        const db = new MockD1();
        const env = createEnv(db);
        const request = new Request("https://kaivor-telegram.kaivor.workers.dev/public/waitlist", {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({
                name: "Aaron",
                businessName: "Acme Business",
                email: "aaron@example.com"
            })
        });
        const response = await worker.fetch(request, env);
        const body = (await response.json());
        expect(response.status).toBe(201);
        expect(body.ok).toBe(true);
        expect(db.waitlist).toHaveLength(1);
        expect(db.waitlist[0]?.business_name).toBe("Acme Business");
    });
    it("does not crash when tenant metadata contains invalid json", async () => {
        const db = new MockD1();
        db.tenants.push({
            id: "tenant-1",
            name: "Acme Business",
            slug: "acme-business",
            whatsapp_phone_number_id: "telegram:acme-business",
            tenant_api_key_hash: "salt:hash",
            trial_ends_at: new Date().toISOString(),
            created_at: new Date().toISOString()
        });
        db.tenantConfigs.push({
            tenant_id: "tenant-1",
            metadata: "{broken"
        });
        const env = createEnv(db);
        const request = new Request("https://kaivor-telegram.kaivor.workers.dev/webhooks/telegram/tenant-1", {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-telegram-bot-api-secret-token": "wrong-secret"
            },
            body: JSON.stringify({
                update_id: 1,
                message: {
                    date: Math.floor(Date.now() / 1000),
                    chat: { id: "12345" },
                    from: { id: "12345" },
                    text: "hello"
                }
            })
        });
        const response = await worker.fetch(request, env);
        expect(response.status).toBe(401);
    });
});
