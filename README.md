# BharatClaw MVP

WhatsApp-first AI workflow backend for Indian small businesses.
Goal: never miss a WhatsApp lead again.

## What this MVP does

- WhatsApp QR connection
- Incoming message listener
- Instant auto-reply
- Lead capture (name + requirement)
- Local data storage (JSON)
- Follow-up if user goes silent
- Owner notifications
- Manual takeover support
- Env-based config system

## What this MVP does not do

- No dashboard
- No multi-channel
- No overengineering

## Core architecture

- whatsapp connector
- state machine
- reply engine
- storage
- scheduler
- notifications
- config

## Lead flow

1. New user sends message
2. Greet instantly
3. Ask name
4. Ask requirement
5. Store lead
6. Notify owner
7. Follow up if no response
8. Human takeover anytime

## Owner commands (from OWNER_PHONE)

- /help
- /takeover <phone>
- /resume <phone>
- /lead <phone>

## Setup

1. Install Node.js 18+
2. npm install
3. copy .env.example .env
4. Set OWNER_PHONE in .env
5. npm start
6. Scan terminal QR in WhatsApp
