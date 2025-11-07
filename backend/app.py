from flask import Flask, request, jsonify
import anthropic
import os
from datetime import datetime, timedelta
import json
import re
import hashlib

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
  TRIGGER: They ask WHY you got a new number or WHY you're using this number
  Keywords: "why", "y", "reason", "what for", "why have you", "why did you", "why do you"
  Examples: "why?", "why have you got a new number?", "why did you get a new number?", "what for?", "reason?"
  CRITICAL: If the message contains "why" (especially "why have you got" or "why did you get" or "why do you have" + "new number"), this is Script 3, NOT Script 2

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

Script 8 AI-GENERATED (How Are You)
  TRIGGER: They EXPLICITLY ask HOW YOU ARE (not just greetings)
  Keywords: "how are you", "how you doing", "you ok", "you alright", "how's everything", "how are things", "how have you been"
  Examples that trigger: "how are you", "how you doing", "hope you are doing well", "how are things"
  Action: Generate a natural caring response as a son/daughter would to dad
  Length & form: Mirror the sender's latest message length and form
    - If their latest message is a single short sentence, reply with one short sentence
    - If their latest message is a longer message/paragraph, reply with a short paragraph of similar length (2–3 sentences)
  Tone & content:
    - Warm, natural, low-key; no over-excited tone
    - Do NOT mention being excited about using this number from now on
    - Do NOT bring up the new number unless they explicitly asked about it
    - No emojis; keep it conversational and concise

Script 9 AI-GENERATED (Greeting Response)
  TRIGGER: They send a simple greeting (Hi, Hello, Hey, Hi [name], Hello [name], etc.)
  Keywords: "hi", "hello", "hey", "hi there", "hey there"
  Examples: "Hi", "Hello", "Hey James", "Hi there", "Hello [name]"
  Action: Generate a friendly, natural greeting response as a son/daughter would to dad
  Length & form: Keep it brief and match their tone
    - If they say "Hi" or "Hello", respond with a simple greeting back
    - If they say "Hi [name]" or "Hello [name]", acknowledge them naturally
  Tone & content:
    - Friendly, warm, casual
    - Keep it short (1-2 sentences max)
    - No emojis; keep it conversational
    - Do NOT mention the new number or any scripts unless they ask

DECISION PROCESS:
1. Read the ENTIRE latest message from them - do not just scan for keywords, read the whole message
2. Read full conversation history for context
3. Analyze: What is the PRIMARY intent or question in the latest message?
4. Match to ONE script based on the MAIN intent (even if message contains multiple elements)
5. If multiple could match, apply PRIORITY RULES below
6. Respond as instructed

CRITICAL: When a message contains multiple elements (e.g., "Oh hi James how are you, please don't forget the milk"), identify the PRIMARY question or intent:
- If it contains "how are you" → This is the PRIMARY intent → Script 8
- If it contains a greeting AND a question → The question takes priority
- Read the ENTIRE message, not just the first few words

PRIORITY RULES (explicit keyword wins - check ENTIRE message):
- If latest contains "contract"/"cancel"/"old plan"/"payment plan" → Script 4
- Else if contains "what phone"/"which phone"/"what device"/"what iphone" → Script 7
- Else if contains "new phone"/"got phone"/"new device" → Script 6
- Else if contains "why"/"y"/"reason"/"what for" (especially "why have you"/"why did you"/"why do you" + "new number") → Script 3 (takes priority over name confirmation)
- Else if contains "is this [name]"/"are you [name]"/"is this your new number" → Script 2
- Else if contains EXPLICIT how-are-you question (how are you/how you doing/you ok/you alright/how's everything/how are things) ANYWHERE in message → Script 8 (takes priority over greetings)
- Else if contains generic who (who/whos/who is/who r u/hu/whose) → Script 1
- Else if contains simple greeting (hi/hello/hey/hi there/hello there) at start → Script 9
- Otherwise → NO_SEND

RESPOND WITH JSON:
{"action": "SEND" or "NO_SEND", "response": "exact text or generated", "reasoning": "which script and why"}

INTERNAL PROCESS (do not include in output):
- Classify the latest message into ONE of: WHO | NAME_ID | WHY | CONTRACT | ALREADY_O2 | NEW_PHONE | WHAT_PHONE | HOW_YOU | GREETING | NONE
- Classification rules (check in this order - read ENTIRE message):
  1. Contains "contract"/"cancel"/"old plan"/"payment plan" → CONTRACT
  2. Contains "what phone"/"which phone"/"what device" → WHAT_PHONE
  3. Contains "new phone"/"got phone"/"new device" → NEW_PHONE
  4. Contains "why"/"y"/"reason"/"what for" (especially "why have you"/"why did you"/"why do you" + "new number") → WHY (check before name confirmation)
  5. Contains "is this" followed by a WORD (not "who") → NAME_ID
  6. Contains "are you" followed by a WORD → NAME_ID
  7. Contains "is this your new number" → NAME_ID
  8. Contains EXPLICIT "how are you"/"how you doing"/"you ok"/"you alright"/"how's everything"/"how are things" ANYWHERE in message → HOW_YOU (check entire message, not just start)
  9. Contains generic who ("who"/"whos"/"who is"/"whose"/"hu") WITHOUT "is this" → WHO
  10. Contains "already with O2"/"thought you were O2" → ALREADY_O2
  11. Contains simple greeting ("hi"/"hello"/"hey"/"hi there"/"hello there") at start of message AND no questions → GREETING
  12. Otherwise → NONE
- Then map: WHO → Script 1, NAME_ID → Script 2, WHY → Script 3, CONTRACT → Script 4, ALREADY_O2 → Script 5, NEW_PHONE → Script 6, WHAT_PHONE → Script 7, HOW_YOU → Script 8, GREETING → Script 9, NONE → NO_SEND

EXAMPLES (for clarity, not to output):
- Latest: "whos this??" → Class: WHO (generic who, no "is this") → SEND Script 1
- Latest: "is this jermaine" → Class: NAME_ID ("is this" + word "jermaine") → SEND Script 2
- Latest: "why have you got a new number?" → Class: WHY (contains "why" + "new number") → SEND Script 3
- Latest: "what about the contract i pay for" → Class: CONTRACT (contains "contract") → SEND Script 4
- Latest: "Oh hi James how are you, please don't forget the milk" → Class: HOW_YOU (contains "how are you" anywhere in message) → SEND Script 8 (AI-generated response)
- Latest: "Hi James" → Class: GREETING (simple greeting, no questions) → SEND Script 9 (AI-generated greeting)
- Latest: "Hello" → Class: GREETING (simple greeting) → SEND Script 9 (AI-generated greeting)
- Latest: "how are you" → Class: HOW_YOU (explicit question) → SEND Script 8 (AI-generated response)

OUTPUT POLICY:
- Scripts 1–7: The response MUST be EXACTLY the script text shown above, with the same wording, capitalization and punctuation. NO extra words, NO greetings, NO emojis, NO signatures.
- Script 8 (How Are You): Respond only with the message content (no preambles). Mirror length (sentence vs short paragraph), keep warm and low‑key, no emojis, no exclamation spam, and DO NOT mention the new number unless they asked.
- Script 9 (Greeting): Generate a friendly, natural greeting response. Keep it brief (1-2 sentences), warm and casual, no emojis. Do NOT mention the new number or any scripts.
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
        
        # Clean out stale duplicate entries (older than 12 hours)
        try:
            cutoff = datetime.now() - timedelta(hours=12)
            stale_keys = []
            for key, ts in list(sent_tracker.items()):
                try:
                    if datetime.fromisoformat(ts) < cutoff:
                        stale_keys.append(key)
                except Exception:
                    stale_keys.append(key)
            for key in stale_keys:
                sent_tracker.pop(key, None)
        except Exception:
            pass

        # Build conversation for Claude and capture latest inbound message text
        latest_inbound = ""
        turn_count = 0
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
                turn_count += 1
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
        
        script_id = ""
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

            def violates_priority(script_key: str, latest: str, response_text: str) -> bool:
                if script_key == "script4":
                    return not re.search(r"\b(contract|cancel|old plan|payment plan)\b", latest, re.IGNORECASE)
                if script_key == "script2":
                    # Match "is this [name]" or "are you [name]" or "is this your new number"
                    # Handle names with any case and optional punctuation at the end
                    # Use [a-zA-Z]+ to match names regardless of case (character classes don't respect IGNORECASE)
                    pattern = r"\b(is this [a-zA-Z]+|are you [a-zA-Z]+|is this your new number)[\s\?\!\.]*"
                    return not re.search(pattern, latest, re.IGNORECASE)
                if script_key == "script1":
                    return not re.search(r"\bwho(['\s]|$)|\bwhos\b|\bwho is\b", latest, re.IGNORECASE)
                if script_key == "script3":
                    return not re.search(r"\b(why|reason|what for)\b", latest, re.IGNORECASE)
                if script_key == "script6":
                    return not re.search(r"\b(new phone|got (a )?new phone|new device)\b", latest, re.IGNORECASE)
                if script_key == "script7":
                    return not re.search(r"\b(what phone|which phone|what device|what iphone)\b", latest, re.IGNORECASE)
                # For AI-generated responses (Script 8 or 9), check if response matches expected pattern
                if len(script_key) > 10:  # AI-generated response (first 20 chars)
                    # Check if it's likely Script 8 (how are you response) - response will be longer, conversational
                    if len(response_text) > 30 and re.search(r"\b(how are you|how you doing|you ok|you alright|how's everything|how are things|how have you been)\b", latest, re.IGNORECASE):
                        return False  # Script 8 matches
                    # Check if it's likely Script 9 (greeting response) - response will be short greeting
                    if len(response_text) <= 30 and re.search(r"^(hi|hello|hey|hi there|hello there)\b", latest, re.IGNORECASE):
                        return False  # Script 9 matches
                    # If AI-generated but doesn't match Script 8 or 9 patterns, check if latest has the right keywords
                    if re.search(r"\b(how are you|how you doing|you ok|you alright|how's everything|how are things|how have you been)\b", latest, re.IGNORECASE):
                        return False  # Script 8 keywords present
                    if re.search(r"^(hi|hello|hey|hi there|hello there)\b", latest, re.IGNORECASE):
                        return False  # Script 9 keywords present
                    return True  # AI-generated but no matching keywords
                return False

            if violates_priority(script_id, latest_msg.lower(), decision_response):
                decision_action = "NO_SEND"
                decision_response = ""
                decision["reasoning"] = "Script keywords not present in latest inbound message"
                script_id = ""

        if decision_action == "SEND" and decision_response:
            # Recompute script_id if necessary (e.g., guard may have cleared it)
            if not script_id:
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
                    script_id = decision_response[:20]

            latest_fingerprint_source = latest_norm or latest_msg.lower().strip() or "(none)"
            latest_hash = hashlib.sha1(latest_fingerprint_source.encode("utf-8")).hexdigest()[:12]
            msg_key = f"{device_id}:{contact_id}:{script_id}:{latest_hash}:{turn_count}"

            # Check if we've already sent this message
            allow_send = True
            prev_ts = sent_tracker.get(msg_key)
            if prev_ts:
                try:
                    prev_dt = datetime.fromisoformat(prev_ts)
                except Exception:
                    prev_dt = None
                if prev_dt and (datetime.now() - prev_dt) <= timedelta(minutes=2):
                    allow_send = False
                    print(f"DUPLICATE: Already sent '{script_id}' to {contact_id} within 2 minutes")
                else:
                    print(f"RE-SEND: Allowing '{script_id}' to {contact_id} (previous send stale or invalid timestamp)")

            if allow_send:
                sent_tracker[msg_key] = datetime.now().isoformat()
                print(f"TRACKED: Sending '{script_id}' to {contact_id}")
            else:
                decision_action = "NO_SEND"
                decision_response = ""
                decision["reasoning"] = "Already sent this message to this contact (duplicate prevention)"

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
