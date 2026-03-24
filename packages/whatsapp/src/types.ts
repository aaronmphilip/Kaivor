export interface WhatsAppInboundMessage {
  eventId: string;
  fromPhone: string;
  text: string;
  phoneNumberId: string;
  timestamp: number;
}

export interface WhatsAppSendTextInput {
  phoneNumberId: string;
  to: string;
  body: string;
}

export interface WhatsAppSendTemplateInput {
  phoneNumberId: string;
  to: string;
  templateName: string;
  languageCode?: string;
}
