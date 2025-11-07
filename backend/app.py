from flask import Flask, request, jsonify
import anthropic
import os
from datetime import datetime
import json
import re

app = Flask(__name__)

# Track sent messages to prevent duplicates: (device_id, contact_id, script) -> timestamp
sent_tracker = {}

# Initialize Claude client with API key from environment
CLAUDE_API_KEY = os.getenv("CLAUDE_API_KEY")
if not CLAUDE_API_KEY:
    print("ERROR: CLAUDE_API_KEY environment variable not set!")
client = anthropic.Anthropic(api_key=CLAUDE_API_KEY)

# System prompt for the AI - INTELLIGENT CONVERSATION ANALYSIS
SYSTEM_PROMPT = """You are an SMS automation assistant. Analyze conversations and pick the right response.

STEP 1: Read the FULL conversation
STEP 2: Extract the LATEST message from them
STEP 3: Analyze what they're ASKING ABOUT
STEP 4: Pick the matching script

SCRIPT MATCHING RULES:

Script 1 "Your eldest and favourite"
  TRIGGER: They ask generic WHO question
  Keywords: who, whos, who is, who r u, who dis, hu, whose
  Example: "whos this" or "who is this"
  NOT: Any message with a specific name after "is this"

Script 2 "Yes I'll be using this number from now on"
  TRIGGER: They confirm identity with a NAME or ask about new number
  Keywords: "is this [NAME]", "are you [NAME]", "is this your new number"
  Examples: "is this james", "are you john", "is this your new number"
  NOT: Generic "who" questions

Script 3 "I'll be using this number from now on because i've got a new plan with O2"
  TRIGGER: They ask WHY
  Keywords: why, y, reason, what for
  Example: "why?" or "reason?"

Script 4 "Do not worry i will get it cancelled"
  TRIGGER: They mention contract/cancellation
  Keywords: contract, cancel, old plan, payment plan
  Hard rule: If the latest message contains the word "contract" (any casing), choose Script 4
  Examples: "what about the contract", "what about the contract i pay for"

Script 5 "sorry i meant EE"
  TRIGGER: They say you were already with O2
  Keywords: already with O2, thought you were O2, weren't you O2

Script 6 "Yes ill be using this number from now"
  TRIGGER: They ask if you got a NEW PHONE
  Keywords: new phone, got phone, new device
  Example: "did you get a new phone"
  NOT: "what phone" (that's Script 7)

Script 7 "I got the Iphone 16"
  TRIGGER: They ask WHAT/WHICH PHONE you got
  Keywords: what phone, which phone, what device, what iphone
  Example: "what phone did you get"
  NOT: "did you get" (that's Script 6)

Script 8 AI-GENERATED
  TRIGGER: They ask HOW YOU ARE
  Keywords: how are you, how you doing, you ok, you alright, how's everything
  Action: Generate a natural caring response as a son/daughter would to dad
  Length & form: Mirror the sender's latest message length and form
    - If their latest message is a single short sentence, reply with one short sentence
    - If their latest message is a longer message/paragraph, reply with a short paragraph of similar length (2–3 sentences)
  Tone & content:
    - Warm, natural, low-key; no over-excited tone
    - Do NOT mention being excited about using this number from now on
    - Do NOT bring up the new number unless they explicitly asked about it
    - No emojis; keep it conversational and concise

DECISION PROCESS:
1. Read full conversation carefully
2. Extract latest message from them
3. Analyze: What are they ASKING or saying?
4. Match to ONE script based on intent
5. If multiple could match, apply PRIORITY RULES below
6. Respond as instructed

PRIORITY RULES (explicit keyword wins):
- If latest contains "contract"/"cancel"/"old plan"/"payment plan" → Script 4
- Else if contains "what phone"/"which phone"/"what device"/"what iphone" → Script 7
- Else if contains "new phone"/"got phone"/"new device" → Script 6
- Else if contains "is this [name]"/"are you [name]"/"is this your new number" → Script 2
- Else if contains generic who (who/whos/who is/who r u/hu/whose) → Script 1
- Else if contains direct why (why/y/reason/what for) → Script 3
- Else if contains how-are-you (how are you/how you doing/you ok/you alright/how's everything) → Script 8
- Otherwise → NO_SEND

RESPOND WITH JSON:
{"action": "SEND" or "NO_SEND", "response": "exact text or generated", "reasoning": "which script and why"}

INTERNAL PROCESS (do not include in output):
- Classify the latest message into ONE of: WHO | NAME_ID | WHY | CONTRACT | ALREADY_O2 | NEW_PHONE | WHAT_PHONE | HOW_YOU | NONE
- Classification rules (check in this order):
  1. Contains "contract"/"cancel"/"old plan"/"payment plan" → CONTRACT
  2. Contains "is this" followed by a WORD (not "who") → NAME_ID
  3. Contains "are you" followed by a WORD → NAME_ID
  4. Contains "is this your new number" → NAME_ID
  5. Contains generic who ("who"/"whos"/"who is"/"whose"/"hu") WITHOUT "is this" → WHO
  6. Contains "what phone"/"which phone"/"what device" → WHAT_PHONE
  7. Contains "new phone"/"got phone"/"new device" → NEW_PHONE
  8. Contains "why"/"y"/"reason"/"what for" → WHY
  9. Contains "how are you"/"you ok"/"you alright" → HOW_YOU
  10. Contains "already with O2"/"thought you were O2" → ALREADY_O2
  11. Otherwise → NONE
- Then map: WHO → Script 1, NAME_ID → Script 2, WHY → Script 3, CONTRACT → Script 4, ALREADY_O2 → Script 5, NEW_PHONE → Script 6, WHAT_PHONE → Script 7, HOW_YOU → Script 8, NONE → NO_SEND

EXAMPLES (for clarity, not to output):
- Latest: "whos this??" → Class: WHO (generic who, no "is this") → SEND Script 1
- Latest: "is this jermaine" → Class: NAME_ID ("is this" + word "jermaine") → SEND Script 2
- Latest: "what about the contract i pay for" → Class: CONTRACT (contains "contract") → SEND Script 4

OUTPUT POLICY:
- Scripts 1–7: The response MUST be EXACTLY the script text shown above, with the same wording, capitalization and punctuation. NO extra words, NO greetings, NO emojis, NO signatures.
- Script 8: Respond only with the message content (no preambles). Mirror length (sentence vs short paragraph), keep warm and low‑key, no emojis, no exclamation spam, and DO NOT mention the new number unless they asked.
- Never combine scripts or add commentary. Choose ONE script or NO_SEND.
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
        
        # Build conversation for Claude and capture latest inbound message text
        latest_inbound = ""
        if turns:
            conversation_lines = []
            parsed_turns = []
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

                parsed_turns.append({'role': role, 'text': text})
                prefix = 'You' if role == 'you' else 'Them'
                conversation_lines.append(f"{prefix}: {text}")

            # Walk backwards to find most recent inbound message
            for turn in reversed(parsed_turns):
                role = (turn.get('role') or '').lower()
                text = turn.get('text') or ''
                if role == 'them' and text.strip():
                    latest_inbound = text
                    break

            conversation_text = "\n".join(conversation_lines) if conversation_lines else "(No previous conversation history)"
        else:
            conversation_text = "(No previous conversation history)"

        if not latest_inbound:
            latest_inbound = "(no inbound message)"

        # Build user message for Claude
        latest_msg = latest_inbound
        latest_norm = re.sub(r'[^a-z0-9 ]+', '', latest_msg.lower()) if latest_msg else ""
        
        user_message = f"""
FULL CONVERSATION:
{conversation_text}

---

LATEST MESSAGE FROM THEM:
{latest_msg}

LATEST (normalized, lowercase, punctuation removed):
{latest_norm}

---

Analyze the conversation. What is the latest message asking? Pick the right script to respond with.
"""
        
        # Call Claude
        message = client.messages.create(
            model="claude-3-haiku-20240307",
            max_tokens=350,
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
