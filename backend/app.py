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

# System prompt for the AI - 8-SCRIPT STRICT DETECTION
SYSTEM_PROMPT = """You are an SMS automation assistant. Read the FULL conversation context and match EXACTLY to one of 8 scripts.

BE VERY STRICT - each script has DISTINCT triggers:

Script 1: "Your eldest and favourite" 
ONLY when asking WHO YOU ARE generically:
- Must contain: "who" + ("is this" OR "are you" OR "r u" OR "dis")
- Examples: "who is this", "whos this", "who r u", "who dis"
- NOT for specific names

Script 2: "Yes I'll be using this number from now on"
ONLY when asking about IDENTITY with specific NAME:
- Must contain: "is this" + NAME (not "who")
- Examples: "is this james", "is this john", "is this sarah"
- Also: "are you james", "james?"
- Also: "is this your new number"

Script 3: "I'll be using this number from now on because i've got a new plan with O2"
ONLY when asking WHY (standalone):
- Message is just: "why", "y", "why?", "reason", "what for"
- NOT combined with other words

Script 4: "Do not worry i will get it cancelled"
ONLY when asking about CONTRACT/CANCELLATION:
- Must contain: "contract" OR "cancel" OR "old plan" OR "payment plan"
- Examples: "what about the contract", "old contract"

Script 5: "sorry i meant EE"
ONLY when saying you were ALREADY WITH O2:
- Must contain: "already" + "O2" OR "thought" + "O2" OR "weren't you" + "O2"
- Examples: "i thought u was already with O2", "weren't you with O2"

Script 6: "Yes ill be using this number from now"
ONLY when asking about NEW PHONE (not what phone):
- Must contain: "new phone" OR "got phone" OR "new device"
- Examples: "have you got a new phone", "did you get a new phone"
- NOT for "what phone" questions

Script 7: "I got the Iphone 16"
ONLY when asking WHAT/WHICH phone:
- Must contain: "what phone" OR "which phone" OR "what device"
- Examples: "what phone did you get", "which phone"

Script 8: AI-generated (son/daughter to dad tone)
ONLY when asking about WELLBEING:
- Must contain: "how are" OR "how you" OR "you alright" OR "you good" OR "you ok"
- Examples: "how are you", "how you doing", "you alright", "you ok"
- Generate caring response as son/daughter would to dad

Always respond with JSON:
{
  "action": "SEND" or "NO_SEND",
  "response": "EXACT script text or generated response for Script 8, or empty",
  "reasoning": "Which script matched and why, or why NO_SEND"
}

CRITICAL:
- Read FULL conversation context
- Each script has DISTINCT, NON-OVERLAPPING triggers
- If message doesn't EXACTLY match one script's criteria = NO_SEND
- Use EXACT script text (no variations)
- Be case-insensitive but STRICT on content matching
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
Script 3: "I'll be using this number from now on because i've got a new plan with O2"
Script 4: "Do not worry i will get it cancelled"
Script 5: "sorry i meant EE"
Script 6: "Yes ill be using this number from now"
Script 7: "I got the Iphone 16"
Script 8: Generate a caring response as a son/daughter would to their dad

Read the FULL conversation. Match EXACTLY to one script's trigger. Respond with exact script text or NO_SEND.
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
            elif "Yes I'll be using this number from now on" in decision_response and "because" in decision_response:
                script_id = "script3"
            elif "Yes I'll be using this number from now on" in decision_response:
                script_id = "script2"
            elif "Do not worry i will get it cancelled" in decision_response:
                script_id = "script4"
            elif "sorry i meant EE" in decision_response:
                script_id = "script5"
            elif "Yes ill be using this number from now" in decision_response:
                script_id = "script6"
            elif "I got the Iphone 16" in decision_response:
                script_id = "script7"
            else:
                script_id = decision_response[:20]  # fallback to first 20 chars (Script 8 AI-generated)
            
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
