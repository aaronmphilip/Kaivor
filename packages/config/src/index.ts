import dotenv from "dotenv";

dotenv.config();

export interface SmtpConfig {
  host: string;
  port: number;
  user: string;
  pass: string;
  from: string;
}

export interface AppConfig {
  nodeEnv: string;
  port: number;
  databaseUrl: string;
  masterApiKey: string;
  appBaseUrl: string;
  whatsappApiVersion: string;
  whatsappAccessToken: string;
  whatsappWebhookVerifyToken: string;
  whatsappAppSecret: string;
  polarAccessToken?: string;
  polarWebhookSecret?: string;
  polarProductId?: string;
  polarMonthlyPriceInr: number;
  followupPollIntervalMs: number;
  followupMaxRetries: number;
  smtp?: SmtpConfig;
}

function required(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

function optionalNumber(name: string, fallback: number): number {
  const raw = process.env[name];
  if (!raw) {
    return fallback;
  }
  const parsed = Number(raw);
  if (Number.isNaN(parsed)) {
    throw new Error(`Invalid number for environment variable: ${name}`);
  }
  return parsed;
}

function maybeSmtpConfig(): SmtpConfig | undefined {
  const host = process.env.SMTP_HOST;
  const port = process.env.SMTP_PORT;
  const user = process.env.SMTP_USER;
  const pass = process.env.SMTP_PASS;
  const from = process.env.SMTP_FROM;

  if (!host || !port || !user || !pass || !from) {
    return undefined;
  }

  return {
    host,
    port: Number(port),
    user,
    pass,
    from
  };
}

export function loadConfig(): AppConfig {
  return {
    nodeEnv: process.env.NODE_ENV ?? "development",
    port: optionalNumber("PORT", 3000),
    databaseUrl: required("DATABASE_URL"),
    masterApiKey: required("MASTER_API_KEY"),
    appBaseUrl: process.env.APP_BASE_URL ?? "http://localhost:3000",
    whatsappApiVersion: process.env.WHATSAPP_API_VERSION ?? "v20.0",
    whatsappAccessToken: required("WHATSAPP_ACCESS_TOKEN"),
    whatsappWebhookVerifyToken: required("WHATSAPP_WEBHOOK_VERIFY_TOKEN"),
    whatsappAppSecret: required("WHATSAPP_APP_SECRET"),
    polarAccessToken: process.env.POLAR_ACCESS_TOKEN,
    polarWebhookSecret: process.env.POLAR_WEBHOOK_SECRET,
    polarProductId: process.env.POLAR_PRODUCT_ID,
    polarMonthlyPriceInr: optionalNumber("POLAR_MONTHLY_PRICE_INR", 1499),
    followupPollIntervalMs: optionalNumber("FOLLOWUP_POLL_INTERVAL_MS", 60000),
    followupMaxRetries: optionalNumber("FOLLOWUP_MAX_RETRIES", 3),
    smtp: maybeSmtpConfig()
  };
}
