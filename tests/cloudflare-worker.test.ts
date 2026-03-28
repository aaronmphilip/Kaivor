import { describe, expect, it } from "vitest";
import worker from "../apps/cloudflare-worker/src/index.js";

type TenantRow = {
  id: string;
  name: string;
  slug: string;
  whatsapp_phone_number_id: string;
  tenant_api_key_hash: string;
  trial_ends_at: string;
  created_at: string;
};

type OwnerRow = {
  id: string;
  tenant_id: string;
  name: string;
  phone: string;
  email: string | null;
  is_primary: number;
  created_at: string;
};

type TenantConfigRow = {
  tenant_id: string;
  metadata: string;
};

class MockPrepared {
  private values: unknown[] = [];

  constructor(
    private readonly db: MockD1,
    private readonly query: string
  ) {}

  bind(...values: unknown[]) {
    this.values = values;
    return this;
  }

  async first<T = Record<string, unknown>>(): Promise<T | null> {
    return this.db.first<T>(this.query, this.values);
  }

  async all<T = Record<string, unknown>>(): Promise<{ results: T[] }> {
    return this.db.all<T>(this.query, this.values);
  }

  async run(): Promise<{ meta?: { changes?: number } }> {
    return this.db.run(this.query, this.values);
  }
}

class MockD1 {
  public tenants: TenantRow[] = [];
  public owners: OwnerRow[] = [];
  public tenantConfigs: TenantConfigRow[] = [];
  public subscriptions: Array<{ id: string; tenant_id: string; status: string }> = [];

  constructor(private readonly mode: "ok" | "fail" = "ok") {}

  prepare(query: string) {
    if (this.mode === "fail") {
      throw new Error("db down");
    }
    return new MockPrepared(this, query);
  }

  async first<T>(query: string, values: unknown[]): Promise<T | null> {
    if (query.includes("SELECT phone FROM owner_contacts WHERE tenant_id=?")) {
      const tenantId = String(values[0] ?? "");
      const owner = this.owners.find((row) => row.tenant_id === tenantId && row.is_primary === 1) ?? null;
      return owner ? ({ phone: owner.phone } as T) : null;
    }

    if (query.includes("SELECT * FROM tenants WHERE id=?")) {
      const tenantId = String(values[0] ?? "");
      return (this.tenants.find((row) => row.id === tenantId) ?? null) as T | null;
    }

    if (query.includes("SELECT metadata FROM tenant_configs WHERE tenant_id=?")) {
      const tenantId = String(values[0] ?? "");
      const row = this.tenantConfigs.find((item) => item.tenant_id === tenantId) ?? null;
      return row ? ({ metadata: row.metadata } as T) : null;
    }

    throw new Error(`Unhandled first query: ${query}`);
  }

  async all<T>(query: string, _values: unknown[]): Promise<{ results: T[] }> {
    if (query.includes("SELECT metadata FROM tenant_configs")) {
      return {
        results: this.tenantConfigs.map((row) => ({ metadata: row.metadata } as T))
      };
    }

    throw new Error(`Unhandled all query: ${query}`);
  }

  async run(query: string, values: unknown[]): Promise<{ meta?: { changes?: number } }> {
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

function createEnv(db: MockD1) {
  return {
    DB: db,
    MASTER_API_KEY: "master-test-key",
    TELEGRAM_WEBHOOK_SECRET: "telegram-secret-test",
    OWNER_CONNECT_BOT_TOKEN: "shared-bot-token",
    OWNER_CONNECT_BOT_SECRET: "owner-secret",
    OWNER_CONNECT_BOT_USERNAME: "bharatclawbot",
    PUBLIC_SIGNUP_ENABLED: "true",
    FREE_MODE: "true"
  };
}

describe("cloudflare worker", () => {
  it("creates a workspace in shared bot mode", async () => {
    const env = createEnv(new MockD1());
    const request = new Request("https://bharatclaw-telegram.bharatclaw.workers.dev/public/free-trial", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        businessName: "Acme Business",
        ownerName: "Aaron",
        ownerEmail: "aaron@example.com"
      })
    });

    const response = await worker.fetch(request, env);
    const body = (await response.json()) as Record<string, unknown>;

    expect(response.status).toBe(201);
    expect(body.ok).toBe(true);
    expect(String(body.ownerPairCode)).toMatch(/^BC/);
    expect(String(body.leadEntryUrl)).toContain("lead_");
    expect(String(body.ownerConnectBotUrl)).toBe("https://t.me/bharatclawbot");
  });

  it("returns structured json instead of crashing when the database throws", async () => {
    const env = createEnv(new MockD1("fail"));
    const request = new Request("https://bharatclaw-telegram.bharatclaw.workers.dev/public/free-trial", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        businessName: "Acme Business",
        ownerName: "Aaron"
      })
    });

    const response = await worker.fetch(request, env);
    const body = (await response.json()) as Record<string, unknown>;

    expect(response.status).toBe(500);
    expect(body.code).toBe("WORKER_RUNTIME_ERROR");
    expect(String(body.detail)).toContain("db down");
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
    const request = new Request("https://bharatclaw-telegram.bharatclaw.workers.dev/webhooks/telegram/tenant-1", {
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
