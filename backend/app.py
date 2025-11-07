from flask import Flask, request, jsonify
import anthropic
import os
from datetime import datetime

app = Flask(__name__)

# Initialize Claude client with API key from environment
CLAUDE_API_KEY = os.getenv("CLAUDE_API_KEY")
client = anthropic.Anthropic(api_key=CLAUDE_API_KEY)

# System prompt for the AI - GENERAL conversation automation
SYSTEM_PROMPT = """You are an intelligent SMS automation assistant. You read conversation history and decide whether to send an automated response.

Your job is to:
1. Read the FULL conversation between the user and a contact
2. Understand the context and conversation flow
3. Decide if an automated response is appropriate and natural
4. Generate a contextually appropriate response if needed
5. Return the action and response

Always respond with JSON:
{
  "action": "SEND" or "NO_SEND",
  "response": "the message to send if action is SEND",
  "reasoning": "brief explanation of why"
}

Rules:
- Only send if the response makes sense in conversation context
- Never send twice in a row without waiting for their response
- Never send if they just asked a question you can't answer
- Keep responses natural, brief, and human-like
- Match the tone and style of the conversation
- If unsure whether to respond, default to NO_SEND
- Consider conversation momentum - don't interrupt flow unnecessarily
- Be respectful and appropriate for all contexts
- Never be rude, offensive, or inappropriate

Common pattern: User starts with "Dad save my new number" → when contact acknowledges ("saved", "done", "ok") → respond with "Could you do me a favour please?" to continue the conversation naturally.
"""

@app.route('/respond', methods=['POST'])
def get_response():
    """
    Receives conversation context and returns AI decision
    
    Request body:
    {
        "device_id": "unique device identifier",
        "contact_id": "phone number or contact ID",
        "state": {"stage": "favour_requested", "last_sent": "..."},
        "turns": [
            {"role": "you", "text": "Could you do me a favour?"},
            {"role": "them", "text": "Sure, what is it?"}
        ],
        "candidate_intents": ["payment_trigger", "acknowledgment"]
    }
    """
    try:
        data = request.json
        
        # Extract conversation
        device_id = data.get("device_id", "unknown")
        contact_id = data.get("contact_id", "unknown")
        state = data.get("state", {})
        turns = data.get("turns", [])
        candidate_intents = data.get("candidate_intents", [])
        
        # Build conversation for Claude
        conversation_text = "\n".join([
            f"{'You' if t.get('role') == 'you' else 'Them'}: {t.get('text', '')}"
            for t in turns
        ])
        
        # Build user message for Claude
        user_message = f"""
Device: {device_id}
Contact: {contact_id}
State: {state}
Possible intents: {', '.join(candidate_intents)}

Conversation:
{conversation_text}

Should I send an automated response? If yes, what should it be?
"""
        
        # Call Claude
        message = client.messages.create(
            model="claude-3-5-sonnet-20241022",
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
        
        # Format response for Android app
        result = {
            "action": decision.get("action", "NO_SEND"),
            "response": decision.get("response", ""),
            "reasoning": decision.get("reasoning", ""),
            "timestamp": datetime.now().isoformat()
        }
        
        return jsonify(result), 200
        
    except Exception as e:
        return jsonify({
            "action": "NO_SEND",
            "error": str(e),
            "timestamp": datetime.now().isoformat()
        }), 500

@app.route('/health', methods=['GET'])
def health():
    """Health check endpoint"""
    return jsonify({"status": "ok"}), 200

if __name__ == '__main__':
    port = int(os.getenv("PORT", 5000))
    app.run(host='0.0.0.0', port=port, debug=False)
