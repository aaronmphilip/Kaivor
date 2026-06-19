import { loadConfig } from "../../../packages/config/src/index.js";
import { FollowupScheduler } from "../../../packages/scheduler/src/index.js";
import { Database, PostgresLeadRepository } from "../../../packages/storage/src/index.js";
import { TelegramClient } from "../../../packages/telegram/src/index.js";
async function bootstrapWorker() {
    const config = loadConfig();
    const db = new Database(config.databaseUrl);
    const repository = new PostgresLeadRepository(db);
    const telegramClient = new TelegramClient();
    const scheduler = new FollowupScheduler({
        repository,
        telegramClient,
        maxRetries: config.followupMaxRetries
    });
    const tick = async () => {
        try {
            await scheduler.run(20);
        }
        catch (error) {
            console.error("Worker tick failed", error);
        }
    };
    await tick();
    const timer = setInterval(() => {
        void tick();
    }, config.followupPollIntervalMs);
    const shutdown = async () => {
        clearInterval(timer);
        await db.close();
        process.exit(0);
    };
    process.on("SIGINT", shutdown);
    process.on("SIGTERM", shutdown);
}
bootstrapWorker().catch((error) => {
    console.error("Worker bootstrap failed", error);
    process.exit(1);
});
