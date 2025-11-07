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
  TRIGGER: They ask generic WHO question (no specific name mentioned)
  Keywords: "who", "whos", "who is", "who r u", "who dis", "hu", "whose"
  Examples: "whos this", "who is this", "who is this?", "who?"
  CRITICAL: Do NOT use Script 1 if the message contains "is this [NAME]" or "are you [NAME]" - those are Script 2
  NOT: "Is this Katie?" (that's Script 2 - has a specific name)
  NOT: "Are you James?" (that's Script 2 - has a specific name)

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
  TRIGGER: They EXPLICITLY ask HOW YOU ARE or express hope about your wellbeing (not just greetings, not responses/acknowledgments)
  Keywords: "how are you", "how you doing", "you ok", "you alright", "how's everything", "how are things", "how have you been", "hope you are doing", "hope you're doing", "hope you doing well"
  Examples that trigger: "how are you", "how you doing", "hope you are doing well", "hope you're doing well", "Hi Katie, hope you are doing well", "how are things", "how have you been?"
  Examples that DO NOT trigger: "Good thanks", "I'm fine", "Doing well", "Alright", "Okay" - these are responses/acknowledgments, NOT questions asking how you are
  CRITICAL: Only trigger if they are ASKING a question about how you are. Do NOT trigger on:
    - Responses to your "how are you" question (e.g., "Good thanks", "I'm fine", "Doing well")
    - Simple acknowledgments (e.g., "Thanks", "Okay", "Alright")
    - Statements about themselves (e.g., "I'm good", "I'm fine")
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
  TRIGGER: They send a simple greeting (Hi, Hello, Hey, Hi [name], Hello [name], Hi dad, Hi mum, etc.)
  Keywords: "hi", "hello", "hey", "hi there", "hey there", "hi dad", "hi mum", "hello dad", "hello mum"
  Examples: "Hi", "Hello", "Hey James", "Hi there", "Hello [name]", "Hi dad", "Hi mum", "Hello dad"
  Action: Generate a friendly, natural greeting response as a son/daughter would to dad/mum
  Length & form: Keep it brief and match their tone
    - If they say "Hi" or "Hello", respond with a simple greeting back
    - If they say "Hi [name]" or "Hello [name]", acknowledge them naturally
    - If they say "Hi dad" or "Hi mum", respond warmly as their child would
  Tone & content:
    - Friendly, warm, casual
    - Keep it short (1-2 sentences max)
    - No emojis; keep it conversational
    - Do NOT mention the new number or any scripts unless they ask

Script 10 AI-GENERATED (General Conversation - ALL MESSAGES)
  TRIGGER: ANY message that doesn't match Scripts 1-9 - this includes EVERYTHING: requests, questions, statements, serious news, updates, etc.
  Examples: 
    - Requests: "Can you get me a milk from the shop?", "Can you pick up the kids from school?", "Can you please get me milk on the way back home please"
    - Questions: "What time are you coming?", "Where are you?"
    - Statements: "I have cancer", "I'm going to the hospital", "Don't forget to...", "See you later"
    - Acknowledgments: "Thanks", "Good thanks", "I'm fine", "Okay"
    - Serious news: "I have cancer", "I'm in the hospital", "I need help"
    - Updates: "I'm running late", "I'll be home soon"
    - ANY other message that doesn't match Scripts 1-9
  CRITICAL: This is the DEFAULT script for ANY message that doesn't match Scripts 1-9. You MUST respond to everything appropriately.
  Action: Generate a natural, appropriate, caring response as a son/daughter would to dad/mum
  Length & form: Match their message length, tone, and seriousness
    - If they make a request (e.g., "Can you pick up the kids"), acknowledge it (e.g., "Sure", "Okay", "Will do", "No problem")
    - If they share serious news (e.g., "I have cancer"), respond with care, concern, and support - be empathetic and appropriate
    - If they ask a question (e.g., "What would you like to eat for dinner?"), ANSWER IT DIRECTLY with ACTUAL CONTENT - don't deflect or ask questions back, actually answer what they asked with real suggestions (e.g., "I'd like pasta" or "Chicken would be nice", NOT "[suggested meal idea]")
    - If they make a statement, respond conversationally and appropriately to that specific statement
    - If they give a response/acknowledgment, respond naturally or briefly
    - CRITICAL: Read what they actually said and respond to THAT specific content, not generically
    - CRITICAL: NEVER use placeholders or template text - always generate actual, specific responses
    - Match the seriousness and tone of their message
  Tone & content:
    - Natural, conversational, appropriate to the relationship (son/daughter to parent)
    - For serious messages, be caring, supportive, and empathetic
    - For requests, be helpful and agreeable
    - For questions, answer naturally and directly - like a child would to their parent:
      * "What would you like to eat for dinner?" → Answer with actual food: "I'd like pasta" or "Chicken sounds good" or "Whatever you're making is fine" - keep it simple and natural
      * "What time are you coming?" → Answer with actual time: "Around 6" or "I'll be there by 7"
      * Don't overthink it - respond naturally, casually, like you're texting your parent
    - Match the formality and seriousness level of their message
    - Keep it appropriate to the context (1-3 sentences typically, longer if serious)
    - No emojis; keep it conversational
    - Do NOT mention the new number or any scripts unless they explicitly ask
    - Respond as you would in a normal family conversation - be ready for ANYTHING
    - CRITICAL: Keep responses natural and casual - don't be overly formal or wordy. A simple "I'd like pasta" is better than a long explanation

DECISION PROCESS:
1. Read the ENTIRE latest message from them - do not just scan for keywords, read the whole message CAREFULLY
2. Read full conversation history for context
3. CHECK FOR TIME-WASTING/UNCOOPERATIVE BEHAVIOR FIRST:
   - If they're being argumentative, hostile, or clearly wasting time → NO_SEND (ignore them)
   - If they keep asking the same question that's already been answered multiple times → NO_SEND (ignore them)
   - If the message is clearly spam, trolling, or unproductive → NO_SEND (ignore them)
   - If they're being uncooperative or trying to waste your time → NO_SEND (ignore them)
   - Only respond to genuine, cooperative messages
4. Analyze: What is the PRIMARY intent or question in the latest message?
5. Match to ONE script based on the MAIN intent (even if message contains multiple elements)
6. If multiple could match, apply PRIORITY RULES below
7. IMPORTANT: If the message doesn't match Scripts 1-9, it MUST be Script 10 - you MUST respond to everything (unless it's time-wasting/uncooperative)
8. CRITICAL: When generating responses, READ what they're actually asking/saying and RESPOND APPROPRIATELY:
   - If they ask a question, ANSWER it directly (don't just respond conversationally)
   - If they make a request, acknowledge it appropriately
   - If they share news/information, respond to that specific content
   - Don't give generic responses - respond to what they actually said
9. Respond as instructed - be ready to handle ANY type of message appropriately

CRITICAL: When a message contains multiple elements, identify the PRIMARY question or intent:
- If it contains "how are you" AS A QUESTION or "hope you are doing well" → This is the PRIMARY intent → Script 8
- If it contains "why" (especially "why have you"/"why did you"/"why do you" + "new number") → This is the PRIMARY intent → Script 3
- If it contains "is this [name]" or "are you [name]" → This is identity confirmation → Script 2 (NOT Script 1, even if it starts with "is this")
- If it contains generic "who" WITHOUT a specific name → This is Script 1
- If it contains a greeting AND a question → The question takes priority over the greeting
- Read the ENTIRE message, not just the first few words
- Question words (why, what, how, who) take priority over simple statements or greetings
- IMPORTANT: "Is this Katie?" is Script 2 (name confirmation), NOT Script 1 (generic who)
- IMPORTANT: "Good thanks", "I'm fine", "Doing well" are RESPONSES, NOT questions asking "how are you" → Use Script 10 or NO_SEND, NOT Script 8

PRIORITY RULES (explicit keyword wins - check ENTIRE message):
- If latest contains "contract"/"cancel"/"old plan"/"payment plan" → Script 4
- Else if contains "what phone"/"which phone"/"what device"/"what iphone" → Script 7
- Else if contains "new phone"/"got phone"/"new device" → Script 6
- Else if contains "why"/"y"/"reason"/"what for" (especially "why have you"/"why did you"/"why do you" + "new number") → Script 3 (takes priority over name confirmation)
- Else if contains "is this [name]"/"are you [name]"/"is this your new number" → Script 2 (takes priority over generic "who" questions)
- Else if contains EXPLICIT how-are-you QUESTION (how are you/how you doing/you ok/you alright/how's everything/how are things/hope you are doing/hope you're doing/hope you doing well) - NOT responses like "good thanks"/"I'm fine" → Script 8 (takes priority over greetings)
- Else if contains generic who (who/whos/who is/who r u/hu/whose) WITHOUT a specific name → Script 1
- Else if contains simple greeting (hi/hello/hey/hi there/hello there/hi dad/hi mum/hello dad/hello mum) at start → Script 9
- Else if message is ANY normal conversational message (request like "Can you get me..." or "Can you pick up the kids", question, statement, serious news like "I have cancer", acknowledgment, etc.) that doesn't match above → Script 10 (AI-generated natural response)
- CRITICAL: Script 10 is the DEFAULT - if the message doesn't match Scripts 1-9, it MUST be Script 10. You MUST respond to everything.
- Use NO_SEND for:
  - Time-wasting or uncooperative messages (argumentative, hostile, repeating same questions already answered, spam, trolling)
  - Truly empty, unparseable, or completely nonsensical messages (extremely rare)

RESPOND WITH JSON:
{"action": "SEND" or "NO_SEND", "response": "exact text or generated", "reasoning": "which script and why"}

INTERNAL PROCESS (do not include in output):
- Classify the latest message into ONE of: WHO | NAME_ID | WHY | CONTRACT | ALREADY_O2 | NEW_PHONE | WHAT_PHONE | HOW_YOU | GREETING | GENERAL_CONVERSATION | TIME_WASTING | NONE
- Classification rules (check in this order - read ENTIRE message):
  0. CHECK FOR TIME-WASTING/UNCOOPERATIVE BEHAVIOR FIRST:
     - If they're being argumentative, hostile, or clearly wasting time → TIME_WASTING → NO_SEND
     - If they keep asking the same question that's already been answered multiple times in the conversation → TIME_WASTING → NO_SEND
     - If the message is clearly spam, trolling, or unproductive → TIME_WASTING → NO_SEND
     - If they're being uncooperative or trying to waste your time → TIME_WASTING → NO_SEND
     - Only proceed with classification if the message is genuine and cooperative
  1. Contains "contract"/"cancel"/"old plan"/"payment plan" → CONTRACT
  2. Contains "what phone"/"which phone"/"what device" → WHAT_PHONE
  3. Contains "new phone"/"got phone"/"new device" → NEW_PHONE
  4. Contains "why"/"y"/"reason"/"what for" (especially "why have you"/"why did you"/"why do you" + "new number") → WHY (check before name confirmation)
  5. Contains "is this" followed by a WORD (not "who") → NAME_ID (check BEFORE generic "who")
  6. Contains "are you" followed by a WORD → NAME_ID (check BEFORE generic "who")
  7. Contains "is this your new number" → NAME_ID
  8. Contains EXPLICIT "how are you"/"how you doing"/"you ok"/"you alright"/"how's everything"/"how are things"/"hope you are doing"/"hope you're doing"/"hope you doing well" question (NOT responses like "good thanks"/"I'm fine"/"doing well") → HOW_YOU (check entire message, not just start)
  9. Contains generic who ("who"/"whos"/"who is"/"whose"/"hu") WITHOUT "is this" and WITHOUT a specific name → WHO
  10. Contains "already with O2"/"thought you were O2" → ALREADY_O2
  11. Contains simple greeting ("hi"/"hello"/"hey"/"hi there"/"hello there"/"hi dad"/"hi mum"/"hello dad"/"hello mum") at start of message AND no questions → GREETING
  12. If message is ANY normal conversational message (request, question, statement, serious news, acknowledgment, etc.) that doesn't match any above → GENERAL_CONVERSATION
  13. Otherwise (only for truly empty/unparseable messages) → NONE
- Then map: TIME_WASTING → NO_SEND, WHO → Script 1, NAME_ID → Script 2, WHY → Script 3, CONTRACT → Script 4, ALREADY_O2 → Script 5, NEW_PHONE → Script 6, WHAT_PHONE → Script 7, HOW_YOU → Script 8, GREETING → Script 9, GENERAL_CONVERSATION → Script 10, NONE → NO_SEND

EXAMPLES (for clarity, not to output):
- Latest: "whos this??" → Class: WHO (generic who, no "is this", no specific name) → SEND Script 1
- Latest: "Is this Katie?" → Class: NAME_ID ("is this" + word "Katie") → SEND Script 2 (NOT Script 1)
- Latest: "is this jermaine" → Class: NAME_ID ("is this" + word "jermaine") → SEND Script 2
- Latest: "why have you got a new number?" → Class: WHY (contains "why" + "new number") → SEND Script 3 (NOT Script 2)
- Latest: "why did you get a new number?" → Class: WHY (contains "why" + "new number") → SEND Script 3
- Latest: "why do you have a new number?" → Class: WHY (contains "why" + "new number") → SEND Script 3
- Latest: "what about the contract i pay for" → Class: CONTRACT (contains "contract") → SEND Script 4
- Latest: "Oh hi James how are you, please don't forget the milk" → Class: HOW_YOU (contains "how are you" anywhere in message) → SEND Script 8 (AI-generated response)
- Latest: "Hi James" → Class: GREETING (simple greeting, no questions) → SEND Script 9 (AI-generated greeting)
- Latest: "Hi dad" → Class: GREETING (simple greeting with relationship term) → SEND Script 9 (AI-generated greeting)
- Latest: "Hello" → Class: GREETING (simple greeting) → SEND Script 9 (AI-generated greeting)
- Latest: "how are you" → Class: HOW_YOU (explicit question) → SEND Script 8 (AI-generated response)
- Latest: "Hi Katie, hope you are doing well" → Class: HOW_YOU (contains "hope you are doing well") → SEND Script 8 (AI-generated response)
- Latest: "Good thanks" → Class: GENERAL_CONVERSATION (response/acknowledgment, NOT a question) → SEND Script 10 (AI-generated natural response) or NO_SEND
- Latest: "I'm fine" → Class: GENERAL_CONVERSATION (response, NOT a question) → SEND Script 10 or NO_SEND
- Latest: "Can you get me a milk from the shop?" → Class: GENERAL_CONVERSATION (normal request) → SEND Script 10 (AI-generated natural response like "Sure" or "Will do")
- Latest: "Can you pick up the kids from school?" → Class: GENERAL_CONVERSATION (request) → SEND Script 10 (AI-generated natural response like "Sure" or "Okay")
- Latest: "I have cancer" → Class: GENERAL_CONVERSATION (serious news) → SEND Script 10 (AI-generated caring, supportive, empathetic response)
- Latest: "I'm in the hospital" → Class: GENERAL_CONVERSATION (serious news) → SEND Script 10 (AI-generated caring, concerned response)
- Latest: "What time are you coming?" → Class: GENERAL_CONVERSATION (normal question) → SEND Script 10 (AI-generated natural response with actual time/answer)
- Latest: "What would you like to eat for dinner?" → Class: GENERAL_CONVERSATION (question) → SEND Script 10 (AI-generated simple, casual response like "I'd like pasta" or "Chicken sounds good" or "Whatever you're making is fine" - keep it natural and short, like texting your parent)
- Latest: "Thanks" → Class: GENERAL_CONVERSATION (acknowledgment) → SEND Script 10 (AI-generated natural response)
- Latest: "Who's this?" (asked for the 5th time after already being answered) → Class: TIME_WASTING (repeating same question already answered) → NO_SEND
- Latest: "You're lying" or "This is fake" (argumentative/hostile) → Class: TIME_WASTING (uncooperative/argumentative) → NO_SEND

OUTPUT POLICY:
- Scripts 1–7: The response MUST be EXACTLY the script text shown above, with the same wording, capitalization and punctuation. NO extra words, NO greetings, NO emojis, NO signatures.
- Script 8 (How Are You): Respond only with the message content (no preambles). Mirror length (sentence vs short paragraph), keep warm and low‑key, no emojis, no exclamation spam, and DO NOT mention the new number unless they asked.
- Script 9 (Greeting): Generate a friendly, natural greeting response. Keep it brief (1-2 sentences), warm and casual, no emojis. Do NOT mention the new number or any scripts.
- Script 10 (General Conversation - ALL MESSAGES): Generate a natural, appropriate, caring response to their message. 
  - For requests: Acknowledge helpfully (e.g., "Sure", "Okay", "Will do", "No problem")
  - For serious news: Respond with care, concern, support, and empathy - be appropriate to the seriousness
  - For questions: ANSWER THEM DIRECTLY and NATURALLY - like a child texting their parent:
    * "What would you like to eat for dinner?" → Simple, casual answer: "I'd like pasta" or "Chicken sounds good" or "Whatever you're making is fine"
    * "What time are you coming?" → Simple answer: "Around 6" or "I'll be there by 7"
    * Keep it casual and natural - don't overthink or be overly formal. A short, direct answer is better than a long explanation
    * NEVER use placeholders like [suggested meal idea] or [food] - always use actual, specific answers
  - For statements: Respond conversationally to that specific statement
  - CRITICAL: Read the actual message content and respond to what they said, not generically
  - CRITICAL: Generate ACTUAL responses, NOT placeholders or template text - if you need to suggest something, suggest a REAL thing
  - CRITICAL: Keep it natural and casual - respond like you're texting your parent, not writing a formal letter
  - Match their tone, length, and seriousness level
  - Keep it conversational, no emojis
  - Respond as a son/daughter would to their parent in a normal family conversation - simple, casual, natural
  - Be ready to handle ANY type of message appropriately
  - Do NOT mention the new number or any scripts unless they explicitly ask
- Never combine scripts or add commentary. Choose ONE script or NO_SEND.
- NEVER use placeholders like [suggested meal idea], [food], [time], etc. - always generate actual, specific responses
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
                    if len(response_text) > 30 and re.search(r"\b(how are you|how you doing|you ok|you alright|how's everything|how are things|how have you been|hope you are doing|hope you're doing|hope you doing well)\b", latest, re.IGNORECASE):
                        return False  # Script 8 matches
                    # Check if it's likely Script 9 (greeting response) - response will be short greeting
                    if len(response_text) <= 30 and re.search(r"^(hi|hello|hey|hi there|hello there|hi dad|hi mum|hello dad|hello mum)\b", latest, re.IGNORECASE):
                        return False  # Script 9 matches
                    # If AI-generated but doesn't match Script 8 or 9 patterns, check if latest has the right keywords
                    if re.search(r"\b(how are you|how you doing|you ok|you alright|how's everything|how are things|how have you been|hope you are doing|hope you're doing|hope you doing well)\b", latest, re.IGNORECASE):
                        return False  # Script 8 keywords present
                    if re.search(r"^(hi|hello|hey|hi there|hello there|hi dad|hi mum|hello dad|hello mum)\b", latest, re.IGNORECASE):
                        return False  # Script 9 keywords present
                    # If it's AI-generated and doesn't match Script 8 or 9, it's likely Script 10 (general conversation) - always allow it
                    # Script 10 is for general conversation, so any message that doesn't match other scripts should be allowed
                    return False  # Script 10 (general conversation) - always allow natural responses
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
