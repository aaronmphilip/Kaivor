export interface TelegramInboundMessage {
  eventId: string;
  chatId: string;
  fromId: string;
  text: string;
  timestamp: number;
}

export interface TelegramSendMessageInput {
  botToken: string;
  chatId: string;
  text: string;
}
