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

# System prompt for the AI - 2-SCRIPT INTELLIGENT DETECTION
SYSTEM_PROMPT = """You are an SMS automation assistant with 2 scripts to choose from based on context.

CRITICAL DISTINCTION:

SCRIPT 1: "Your eldest and favourite"
- Use when they ask generic WHO questions (any variation):
  * "who is this" / "whos this" / "who's this" / "who´s this" / "who`s this" / "who dis" / "who this" / "who r u" / "who are you"
  * "whose this" / "whose´ this" / "whos dis" / "hu is this" / "hu dis" / "who tf is this" / "who the fuck is this"
  * "whoa is this" / "who'z this" / "whos that" / "who dat"
  * Any message asking WHO YOU ARE without mentioning a specific name
  * They are asking WHO YOU ARE in general
  * They do NOT mention any specific name
- Examples that trigger Script 1:
  * "whos this?", "who is this", "who's this?", "who´s this", "who`s this", "who dis", "whose this", "who r u", "who are you", "hu is this", "who tf is this"

SCRIPT 2: "Yes I'll be using this number from now on"
- Use when they ask about IDENTITY WITH A SPECIFIC NAME or CONFIRMING NEW NUMBER
- Trigger patterns (VERY DIRECT):
  * Contains "is this" + NOT "your number" = Script 2
    - "is this jermaine?" → Script 2
    - "is this john" → Script 2
    - "is this your new number?" → Script 2 (contains "new number")
  * Contains "are you" + word after = Script 2
    - "are you jermaine?" → Script 2
  * Just a single word with "?" = Script 2
    - "jermaine?" → Script 2
- Do NOT overthink: if message says "is this [anything]" where [anything] is not "this", send Script 2

Always respond with JSON:
{
  "action": "SEND" or "NO_SEND",
  "response": "Script 1 or Script 2 text, or empty if NO_SEND",
  "reasoning": "which script was selected and why, or why no send"
}

Rules - VERY SIMPLE (case-insensitive matching):
1. If message contains WHO words (who, whos, whose, hu, etc.) = Script 1
   - Works with: "WHO is this", "who is this", "Whos this", "WHO'S THIS", etc.
2. If message contains "is this" (case-insensitive, but not "is this this") = Script 2
   - Works with: "is this jermaine", "IS THIS JERMAINE", "Is This John", etc.
3. If message contains "are you" (case-insensitive) = Script 2
   - Works with: "are you john", "ARE YOU JERMAINE", etc.
4. If message is JUST a name with "?" (like "john?", "JERMAINE?") = Script 2
5. If message contains "is this your new number" = Script 2
6. Everything else = NO_SEND
- Match PATTERNS case-insensitively
- Use EXACT script text when sending
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
