# Telegram Bot Setup Guide

This Python Telegram bot receives payment confirmations from the Android app and forwards them to your Telegram channel.

## Deployment on Render

1. **Create a new Web Service on Render:**
   - Go to https://render.com
   - Click "New +" → "Web Service"
   - Connect your GitHub repository
   - Select the `backend` directory

2. **Configure the service:**
   - **Name:** `telegram-bot-sms` (or any name you prefer)
   - **Environment:** `Python 3`
   - **Build Command:** `pip install --upgrade pip && pip install -r telegram_requirements.txt`
   - **Start Command:** `gunicorn --bind 0.0.0.0:$PORT --workers 2 --timeout 120 telegram_bot:app`

3. **Set Environment Variables:**
   - `TELEGRAM_BOT_TOKEN` - Your Telegram bot token (get from @BotFather)
   - `TELEGRAM_CHANNEL_ID` - Your Telegram channel ID (e.g., `@yourchannel` or `-1001234567890`)
   - `KEEP_ALIVE_KEY` - (Optional) API key for authentication
   - `KEEP_ALIVE_URL` - (Optional) External keep-alive service URL
   - `PORT` - Automatically set by Render (don't change)

## Getting Your Telegram Bot Token

1. Open Telegram and search for `@BotFather`
2. Send `/newbot` command
3. Follow the instructions to create your bot
4. Copy the bot token (looks like: `123456789:ABCdefGHIjklMNOpqrsTUVwxyz`)

## Getting Your Channel ID

1. Add your bot to your Telegram channel as an administrator
2. Send a message to your channel
3. Visit: `https://api.telegram.org/bot<YOUR_BOT_TOKEN>/getUpdates`
4. Look for `"chat":{"id":-1001234567890}` - that's your channel ID
   - For public channels, use `@yourchannelname`
   - For private channels, use the numeric ID (e.g., `-1001234567890`)

## Android App Configuration

1. Open the SMS Automation app
2. Go to the "Telegram" tab
3. Enter:
   - **Bot Token:** Your Telegram bot token
   - **Channel ID:** Your Telegram channel ID
   - **Bot Server URL:** (Optional) Your Render service URL (e.g., `https://telegram-bot-sms.onrender.com`)
   - **API Key:** (Optional) The same key you set as `KEEP_ALIVE_KEY` on Render
4. Click "Save Telegram Settings"
5. Click "Test Connection" to verify

## How It Works

- **Direct Mode (Default):** If no Bot Server URL is set, the app sends directly to Telegram API
- **Bot Server Mode:** If Bot Server URL is set, the app sends to your bot server, which then forwards to Telegram
  - This keeps your bot token secure on the server
  - Allows for additional processing/logging
  - Keeps the server alive with keep-alive mechanism

## Keep-Alive Mechanism

The bot includes a built-in keep-alive mechanism that:
- Pings the `/health` endpoint every 5 minutes
- Optionally pings an external keep-alive service
- Prevents the Render service from sleeping (on free tier, services sleep after 15 minutes of inactivity)

## API Endpoints

- `GET /` - Service info
- `GET /health` - Health check (used for keep-alive)
- `POST /send` - Receive message from Android app
  ```json
  {
    "message": "Payment sent",
    "contact_number": "+1234567890",
    "api_key": "your-api-key" // Optional
  }
  ```

## Troubleshooting

- **Bot not responding:** Check that bot token and channel ID are correct
- **Messages not forwarding:** Verify bot has admin rights in the channel
- **Server sleeping:** The keep-alive mechanism should prevent this, but you can also use external services like UptimeRobot

