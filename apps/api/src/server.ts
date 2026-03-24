import { PolarBillingService } from "../../../packages/billing/src/index.js";
import { loadConfig } from "../../../packages/config/src/index.js";
import { NotificationService } from "../../../packages/notifications/src/index.js";
import { Database, PostgresLeadRepository } from "../../../packages/storage/src/index.js";
import { WhatsAppClient } from "../../../packages/whatsapp/src/index.js";
import { createApiApp } from "./app.js";

async function bootstrap() {
  const config = loadConfig();
  const db = new Database(config.databaseUrl);
  const repository = new PostgresLeadRepository(db);
  const whatsappClient = new WhatsAppClient({
    accessToken: config.whatsappAccessToken,
    apiVersion: config.whatsappApiVersion
  });
  const notificationService = new NotificationService({
    repository,
    whatsappClient,
    smtp: config.smtp
  });
  const billingService = new PolarBillingService({
    accessToken: config.polarAccessToken,
    webhookSecret: config.polarWebhookSecret,
    productId: config.polarProductId,
    appBaseUrl: config.appBaseUrl
  });

  const app = createApiApp({
    config,
    repository,
    whatsappClient,
    notificationService,
    billingService
  });

  await app.listen({ host: "0.0.0.0", port: config.port });

  const shutdown = async () => {
    await app.close();
    await db.close();
    process.exit(0);
  };

  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}

bootstrap().catch((error) => {
  console.error("API bootstrap failed", error);
  process.exit(1);
});
