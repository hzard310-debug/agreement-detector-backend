#!/usr/bin/env python3
"""
Telegram Bot for SMS Automation
Receives payment confirmations from Android app and forwards to Telegram channel
"""

from flask import Flask, request, jsonify
import requests
import os
import threading
import time
from datetime import datetime

app = Flask(__name__)

# Configuration from environment variables
TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "")
TELEGRAM_CHANNEL_ID = os.getenv("TELEGRAM_CHANNEL_ID", "")
KEEP_ALIVE_KEY = os.getenv("KEEP_ALIVE_KEY", "")
KEEP_ALIVE_URL = os.getenv("KEEP_ALIVE_URL", "")  # Optional: external keep-alive service URL

# Telegram API base URL
TELEGRAM_API_URL = f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}"

# Keep-alive thread
keep_alive_running = False
keep_alive_thread = None

def send_to_telegram(text: str, photo_url: str = None, contact_number: str = None):
    """Send message or photo to Telegram channel"""
    if not TELEGRAM_BOT_TOKEN or not TELEGRAM_CHANNEL_ID:
        return {"success": False, "error": "Telegram not configured"}
    
    try:
        if photo_url:
            # Send photo with caption
            caption = f"From: {contact_number}\n\n{text}" if contact_number else text
            url = f"{TELEGRAM_API_URL}/sendPhoto"
            data = {
                "chat_id": TELEGRAM_CHANNEL_ID,
                "caption": caption[:1024] if len(caption) > 1024 else caption,  # Telegram caption limit
            }
            files = {"photo": ("photo.jpg", requests.get(photo_url).content, "image/jpeg")}
            response = requests.post(url, data=data, files=files, timeout=30)
        else:
            # Send text message
            full_message = f"From: {contact_number}\n\n{text}" if contact_number else text
            url = f"{TELEGRAM_API_URL}/sendMessage"
            data = {
                "chat_id": TELEGRAM_CHANNEL_ID,
                "text": full_message,
            }
            response = requests.post(url, json=data, timeout=30)
        
        if response.status_code == 200:
            return {"success": True, "message_id": response.json().get("result", {}).get("message_id")}
        else:
            return {"success": False, "error": f"Telegram API error: {response.status_code}", "details": response.text}
    except Exception as e:
        return {"success": False, "error": str(e)}

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
        except Exception as e:
            print(f"Keep-alive error: {e}")
            time.sleep(60)  # Retry in 1 minute on error

def start_keep_alive():
    """Start the keep-alive thread"""
    global keep_alive_running, keep_alive_thread
    if not keep_alive_running:
        keep_alive_running = True
        keep_alive_thread = threading.Thread(target=keep_alive_worker, daemon=True)
        keep_alive_thread.start()
        print("Keep-alive thread started")

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
    """Receive message from Android app and forward to Telegram"""
    try:
        data = request.get_json()
        
        # Validate required fields
        if not data or "message" not in data:
            return jsonify({"success": False, "error": "Missing 'message' field"}), 400
        
        message = data.get("message", "")
        contact_number = data.get("contact_number")
        photo_url = data.get("photo_url")
        api_key = data.get("api_key")
        
        # Optional: Validate API key if KEEP_ALIVE_KEY is set
        if KEEP_ALIVE_KEY and api_key != KEEP_ALIVE_KEY:
            return jsonify({"success": False, "error": "Invalid API key"}), 401
        
        # Send to Telegram
        result = send_to_telegram(message, photo_url, contact_number)
        
        if result["success"]:
            return jsonify(result), 200
        else:
            return jsonify(result), 500
            
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500

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

