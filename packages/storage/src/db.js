import { Pool } from "pg";
export class Database {
    pool;
    constructor(connectionString) {
        this.pool = new Pool({
            connectionString,
            max: 10
        });
    }
    async query(text, params = []) {
        return this.pool.query(text, params);
    }
    async transaction(fn) {
        const client = await this.pool.connect();
        try {
            await client.query("BEGIN");
            const result = await fn(client);
            await client.query("COMMIT");
            return result;
        }
        catch (error) {
            await client.query("ROLLBACK");
            throw error;
        }
        finally {
            client.release();
        }
    }
    async close() {
        await this.pool.end();
    }
}
