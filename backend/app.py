from flask import Flask, request, jsonify
import anthropic
import os
from datetime import datetime
import json

app = Flask(__name__)

# Track sent messages to prevent duplicates: (device_id, contact_id, script) -> timestamp
sent_tracker = {}

# Initialize Claude client with API key from environment
CLAUDE_API_KEY = os.getenv("CLAUDE_API_KEY")
if not CLAUDE_API_KEY:
    print("ERROR: CLAUDE_API_KEY environment variable not set!")
client = anthropic.Anthropic(api_key=CLAUDE_API_KEY)

# System prompt for the AI - 2-SCRIPT DETECTION
SYSTEM_PROMPT = """You are an SMS automation assistant with 2 scripts.

READ THE LAST MESSAGE AND MATCH IT:

MATCH Script 1: "Your eldest and favourite"
When the message is asking WHO YOU ARE (generic who questions):
- "whos this" or "who is this" or "who's this" → Script 1
- "whose this" or "who dis" → Script 1
- "who r u" or "who are you" → Script 1
- "hu is this" or "who tf is this" → Script 1
KEY: They are asking WHO in general, no specific name mentioned

MATCH Script 2: "Yes I'll be using this number from now on"
When the message is asking about SPECIFIC NAME or NEW NUMBER:
- "is this jermaine" or "is this john" → Script 2
- "are you jermaine" or "are you john" → Script 2
- "jermaine?" or "john?" (just name with ?) → Script 2
- "is this your new number" → Script 2
KEY: They mention a specific name OR ask about the new number

Always respond with JSON:
{
  "action": "SEND" or "NO_SEND",
  "response": "Copy the exact script text here, or empty string",
  "reasoning": "Why you chose this script or NO_SEND"
}

IMPORTANT:
- If it matches Script 1 patterns → send Script 1
- If it matches Script 2 patterns → send Script 2
- If it doesn't match either → NO_SEND
- Use EXACT script text
- Be case-insensitive
"""

@app.route('/respond', methods=['POST'])
def get_response():
    """
    Receives conversation context and script, decides when to send it
    
    Request body:
    {
        "device_id": "unique device identifier",
        "contact_id": "phone number or contact ID",
        "script": "the pre-written message to send if timing is right",
        "turns": [
            {"role": "you", "text": "Dad save my new number"},
            {"role": "them", "text": "ok saved"}
        ]
    }
    """
    try:
        data = request.json
        
        # Extract conversation and script
        device_id = data.get("device_id", "unknown")
        contact_id = data.get("contact_id", "unknown")
        script = data.get("script", "")
        turns = data.get("turns", [])
        state = data.get("state", {})
        
        # Build conversation for Claude
        if turns:
            conversation_lines = []
            for t in turns:
                # Handle both dict and string formats
                if isinstance(t, dict):
                    role = t.get('role', 'them')
                    text = t.get('text', '')
                elif isinstance(t, str):
                    # If it's a string, try to parse it as JSON
                    try:
                        import json as json_module
                        t = json_module.loads(t)
                        role = t.get('role', 'them')
                        text = t.get('text', '')
                    except:
                        continue
                else:
                    continue
                prefix = 'You' if role == 'you' else 'Them'
                conversation_lines.append(f"{prefix}: {text}")
            conversation_text = "\n".join(conversation_lines) if conversation_lines else "(No previous conversation history)"
        else:
            conversation_text = "(No previous conversation history)"
        
        # Build user message for Claude
        user_message = f"""
Device: {device_id}
Contact: {contact_id}

Conversation:
{conversation_text}

Available Scripts:
Script 1: "Your eldest and favourite"
Script 2: "Yes I'll be using this number from now on"

Should I send one of these scripts NOW based on the conversation context? If yes, respond with the exact script text.
"""
        
        # Call Claude
        message = client.messages.create(
            model="claude-3-haiku-20240307",
            max_tokens=200,
            system=SYSTEM_PROMPT,
            messages=[
                {"role": "user", "content": user_message}
            ]
        )
        
        response_text = message.content[0].text
        
        # Parse Claude's response
        import json
        try:
            # Try to extract JSON from response
            if "{" in response_text and "}" in response_text:
                json_start = response_text.find("{")
                json_end = response_text.rfind("}") + 1
                json_str = response_text[json_start:json_end]
                decision = json.loads(json_str)
            else:
                decision = json.loads(response_text)
        except:
            # Fallback if Claude doesn't return valid JSON
            decision = {
                "action": "NO_SEND",
                "response": "",
                "reasoning": "Could not parse Claude response"
            }
        
        # Check for duplicates - prevent sending the same message to the same contact twice
        decision_action = decision.get("action", "NO_SEND")
        decision_response = decision.get("response", "")
        
        if decision_action == "SEND" and decision_response:
            # Create unique key for this message/script combination
            # Normalize the response to identify which script it is
            if "Your eldest and favourite" in decision_response:
                script_id = "script1"
            elif "Yes I'll be using this number from now on" in decision_response:
                script_id = "script2"
            else:
                script_id = decision_response[:20]  # fallback to first 20 chars
            
            msg_key = f"{device_id}:{contact_id}:{script_id}"
            
            # Check if we've already sent this message
            if msg_key in sent_tracker:
                # Already sent before - don't send again per user rules
                print(f"DUPLICATE: Already sent '{script_id}' to {contact_id}")
                decision_action = "NO_SEND"
                decision_response = ""
                decision["reasoning"] = "Already sent this message to this contact (duplicate prevention)"
            else:
                # First time - record it
                sent_tracker[msg_key] = datetime.now().isoformat()
                print(f"TRACKED: Sending '{script_id}' to {contact_id}")
        
        # Format response for Android app
        result = {
            "action": decision_action,
            "response": decision_response,
            "reasoning": decision.get("reasoning", ""),
            "timestamp": datetime.now().isoformat()
        }
        
        return jsonify(result), 200
        
    except Exception as e:
        import traceback
        error_details = traceback.format_exc()
        print(f"ERROR in /respond: {str(e)}")
        print(error_details)
        return jsonify({
            "action": "NO_SEND",
            "error": str(e),
            "details": error_details,
            "timestamp": datetime.now().isoformat()
        }), 500

@app.route('/health', methods=['GET'])
def health():
    """Health check endpoint"""
    return jsonify({"status": "ok"}), 200

if __name__ == '__main__':
    port = int(os.getenv("PORT", 5000))
    app.run(host='0.0.0.0', port=port, debug=False)
