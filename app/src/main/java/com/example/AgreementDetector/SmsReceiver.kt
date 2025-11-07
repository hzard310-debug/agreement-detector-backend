package com.example.agreementdetector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import java.security.MessageDigest

class SmsReceiver : BroadcastReceiver() {
    private fun hashMessage(address: String, message: String): String {
        val combined = "$address|${message.trim().lowercase()}"
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(combined.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun markMessageAsResponded(context: Context, address: String, message: String) {
        val messageHash = hashMessage(address, message)
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val respondedMessages = prefs.getStringSet("responded_messages", null)?.toMutableSet() ?: mutableSetOf()
        respondedMessages.add(messageHash)
        prefs.edit().putStringSet("responded_messages", respondedMessages).apply()
        Log.d("SmsReceiver", "Marked message as responded: $messageHash")
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.i("SmsReceiver", "[ENTRY] onReceive called with action: ${intent.action}")
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION != intent.action) {
            android.util.Log.i("SmsReceiver", "[SKIP] Action mismatch, returning")
            return
        }
        android.util.Log.i("SmsReceiver", "[OK] SMS_RECEIVED_ACTION matched")
        
        // CRITICAL: Use goAsync() to extend receiver lifetime - allows processing to continue when screen is locked
        val pendingResult = goAsync()
        
        // Extract SMS messages
        val extras = intent.extras
        if (extras == null) {
            android.util.Log.i("SmsReceiver", "[SKIP] No extras")
            pendingResult.finish()
            return
        }
        val pdus = extras.get("pdus") as? Array<*>
        if (pdus == null) {
            android.util.Log.i("SmsReceiver", "[SKIP] No pdus")
            pendingResult.finish()
            return
        }
        android.util.Log.i("SmsReceiver", "[OK] Processing ${pdus.size} PDUs")
        
        for ((idx, pdu) in pdus.withIndex()) {
            android.util.Log.i("SmsReceiver", "[PDU] Processing PDU $idx")
            val smsMessage = SmsMessage.createFromPdu(pdu as ByteArray)
            val sender = smsMessage.originatingAddress
            if (sender == null) {
                android.util.Log.i("SmsReceiver", "[SKIP] No sender")
                continue
            }
            val body = smsMessage.messageBody
            if (body == null) {
                android.util.Log.i("SmsReceiver", "[SKIP] No body")
                continue
            }
            android.util.Log.i("SmsReceiver", "[MSG] From $sender: $body")

            // Popup when any SMS is detected
            try {
                android.widget.Toast.makeText(context.applicationContext, "Message detected", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                android.util.Log.e("SmsReceiver", "Toast error", e)
            }

            // ALL message processing requires AI - check if AI is enabled
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val aiEnabled = prefs.getBoolean("ai_response_enabled", false)
            android.util.Log.i("SmsReceiver", "[CHECK] AI enabled: $aiEnabled")
            
            // Only process messages if AI is enabled - ALL messages must go through AI backend
            if (!aiEnabled) {
                android.util.Log.w("SmsReceiver", "AI not enabled - ignoring message. Enable AI to process messages.")
                pendingResult.finish()
                return
            }
            
            // AI is enabled - process message through AI backend
                val messageBody = body // Store in outer scope for deletion
                val messageSender = sender // Store in outer scope for deletion
                
                // Acquire wake lock to keep device awake while processing (even if screen is off)
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AgreementDetector::SmsProcessing"
                )
                wakeLock?.acquire(60000) // Hold for up to 60 seconds
                
                Log.d("SmsReceiver", "AI enabled - querying Claude AI for response (wake lock acquired for background processing, goAsync() extends receiver lifetime)")
                Thread {
                    try {
                        // Get FULL conversation history - collect ALL messages (no limit)
                        // This ensures AI reads the complete chat context - ALL messages from entire conversation
                        val turns = com.example.agreementdetector.ai.TurnsAndState.collectRecentTurns(context, sender, 10000)
                        Log.d("SmsReceiver", "Collected ${turns.size} turns of FULL conversation history (AI will read entire chat - all messages)")
                        
                        // Ensure the current incoming message is included (it might not be in DB yet)
                        val currentMessage = mapOf("role" to "them", "text" to messageBody)
                        val turnsWithCurrent = if (turns.isEmpty() || turns.lastOrNull()?.get("text") != messageBody) {
                            turns + currentMessage
                        } else {
                            turns
                        }
                        Log.d("SmsReceiver", "Turns with current message: ${turnsWithCurrent.size}")
                        
                        // Prepare script for Claude
                        val script = "Your eldest and favourite"
                        
                        // Call Claude backend with retry logic for 502 errors and timeouts
                        val url = "https://agreement-detector-api.onrender.com/respond"
                        val client = okhttp3.OkHttpClient.Builder()
                            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val turnsArray = org.json.JSONArray()
                        for (turn in turnsWithCurrent) {
                            turnsArray.put(org.json.JSONObject(turn))
                        }
                        
                        // Get payment details from SharedPreferences
                        val paymentDetails = prefs.getString("payment_details", "")?.trim() ?: ""
                        
                        val requestBody = org.json.JSONObject().apply {
                            put("device_id", android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID))
                            put("contact_id", sender)
                            put("script", script)
                            put("turns", turnsArray)
                            if (paymentDetails.isNotEmpty()) {
                                put("payment_details", paymentDetails)
                            }
                        }.toString()
                        
                        Log.d("SmsReceiver", "Sending request to backend: $url")
                        
                        var success = false
                        var retryCount = 0
                        val maxRetries = 3
                        
                        while (!success && retryCount < maxRetries) {
                            try {
                                val request = okhttp3.Request.Builder()
                                    .url(url)
                                    .post(okhttp3.RequestBody.create("application/json".toMediaType(), requestBody))
                                    .build()
                                
                                client.newCall(request).execute().use { response ->
                                    try {
                                    Log.d("SmsReceiver", "Backend response code: ${response.code} (attempt ${retryCount + 1})")
                                    
                                    // Retry on 502 Bad Gateway (service temporarily unavailable)
                                    if (response.code == 502 && retryCount < maxRetries - 1) {
                                            // Consume response body before retrying to prevent resource leak
                                            try {
                                                response.body?.close()
                                            } catch (ignored: Exception) {}
                                        retryCount++
                                        val delayMs = (1000 * retryCount).toLong() // Exponential backoff: 1s, 2s, 3s
                                        Log.w("SmsReceiver", "502 error, retrying in ${delayMs}ms...")
                                        Thread.sleep(delayMs)
                                        return@use
                                    }
                                    
                                    success = true // Mark success if we got a response (even if not successful)
                                    
                                    if (response.isSuccessful) {
                                            val body = response.body?.string() ?: run {
                                                // Body is null, ensure it's closed
                                                try {
                                                    response.body?.close()
                                                } catch (ignored: Exception) {}
                                                return@use
                                            }
                                        Log.d("SmsReceiver", "Backend response: $body")
                                        val json = org.json.JSONObject(body)
                                        val action = json.getString("action")
                                        val messageToSend = json.getString("response")
                                        val reasoning = json.optString("reasoning", "")
                                        
                                        Log.d("SmsReceiver", "Claude decision: action=$action, message=$messageToSend")
                                        
                                        // Check if message is inappropriate and should be deleted
                                        if (action == "NO_SEND" && (reasoning.contains("inappropriate", ignoreCase = true) || 
                                                reasoning.contains("swear", ignoreCase = true) || 
                                                reasoning.contains("profanity", ignoreCase = true) || 
                                                reasoning.contains("sexual", ignoreCase = true) || 
                                                reasoning.contains("ignore and delete", ignoreCase = true))) {
                                            // Delete the inappropriate message - try multiple methods
                                            try {
                                                val inboxUri = android.provider.Telephony.Sms.Inbox.CONTENT_URI
                                                
                                                // Method 1: Delete by address and body (exact match)
                                                var deleted = context.contentResolver.delete(
                                                    inboxUri,
                                                    "${android.provider.Telephony.Sms.ADDRESS} = ? AND ${android.provider.Telephony.Sms.BODY} = ?",
                                                    arrayOf(messageSender, messageBody)
                                                )
                                                
                                                // Method 2: If not found, try deleting by address only (most recent message from this sender)
                                                if (deleted == 0) {
                                                    // Get the most recent message from this sender and delete it
                                                    context.contentResolver.query(
                                                        inboxUri,
                                                        arrayOf(android.provider.Telephony.Sms._ID, android.provider.Telephony.Sms.BODY),
                                                        "${android.provider.Telephony.Sms.ADDRESS} = ?",
                                                        arrayOf(messageSender),
                                                        "${android.provider.Telephony.Sms.DATE} DESC"
                                                    )?.use { cursor ->
                                                        if (cursor.moveToFirst()) {
                                                            val msgId = cursor.getLong(0)
                                                            val msgBody = cursor.getString(1)
                                                            // If body matches or is similar, delete it
                                                            if (msgBody == messageBody || msgBody.contains(messageBody) || messageBody.contains(msgBody)) {
                                                                deleted = context.contentResolver.delete(
                                                                    android.net.Uri.parse("${inboxUri}/$msgId"),
                                                                    null,
                                                                    null
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                
                                                Log.d("SmsReceiver", "Deleted inappropriate message from $messageSender (deleted: $deleted)")
                                                
                                                // Show notification that message was deleted
                                                android.widget.Toast.makeText(
                                                    context.applicationContext,
                                                    "Inappropriate message deleted",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            } catch (e: Exception) {
                                                Log.e("SmsReceiver", "Error deleting inappropriate message: ${e.message}", e)
                                            }
                                        } else if (action == "SEND" && messageToSend.isNotEmpty()) {
                                            // Store the incoming message hash so we can mark it as responded to after successful send
                                            val incomingMessageHash = hashMessage(sender, messageBody)
                                            
                                            // Use AutoSendQueue with AI source to ensure proper sending
                                            // Pass the incoming message hash so it can be marked as responded after successful send
                                            AutoSendQueue.enqueue(context, sender, messageToSend, AutoSendQueue.Source.AI, incomingMessageHash)
                                            Log.d("SmsReceiver", "SMS queued for sending to $sender: $messageToSend (incoming hash: $incomingMessageHash)")
                                        } else {
                                                Log.d("SmsReceiver", "Claude decided NO_SEND or message is empty. Reasoning: $reasoning")
                                                
                                                // AI should respond to ALL questions naturally - check if this is a question
                                                val lowerBody = messageBody.lowercase()
                                                
                                                // Detect ANY question - anything with question mark or question words
                                                val isQuestion = lowerBody.contains("?") ||
                                                                lowerBody.contains("what") ||
                                                                lowerBody.contains("how") ||
                                                                lowerBody.contains("when") ||
                                                                lowerBody.contains("where") ||
                                                                lowerBody.contains("why") ||
                                                                lowerBody.contains("who") ||
                                                                lowerBody.contains("which") ||
                                                                lowerBody.contains("did you") ||
                                                                lowerBody.contains("do you") ||
                                                                lowerBody.contains("have you") ||
                                                                lowerBody.contains("are you") ||
                                                                lowerBody.contains("will you") ||
                                                                lowerBody.contains("can you") ||
                                                                lowerBody.contains("could you") ||
                                                                lowerBody.contains("would you") ||
                                                                lowerBody.contains("should you") ||
                                                                lowerBody.contains("is it") ||
                                                                lowerBody.contains("are they") ||
                                                                lowerBody.contains("was it") ||
                                                                lowerBody.contains("were you")
                                                
                                                val isCasualStatement = lowerBody.contains("i'm") || 
                                                                        lowerBody.contains("im ") ||
                                                                        lowerBody.contains("i am") ||
                                                                        lowerBody.contains("going to") ||
                                                                        lowerBody.contains("going out") ||
                                                                        lowerBody.contains("at the") ||
                                                                        lowerBody.contains("dinner") ||
                                                                        lowerBody.contains("ready")
                                                
                                                // Detect serious/urgent messages that need a caring response
                                                val isSeriousMessage = lowerBody.contains("crashed") ||
                                                                       lowerBody.contains("crash") ||
                                                                       lowerBody.contains("accident") ||
                                                                       lowerBody.contains("hospital") ||
                                                                       lowerBody.contains("emergency") ||
                                                                       lowerBody.contains("can't speak") ||
                                                                       lowerBody.contains("cant speak") ||
                                                                       lowerBody.contains("can't talk") ||
                                                                       lowerBody.contains("cant talk") ||
                                                                       lowerBody.contains("hurt") ||
                                                                       lowerBody.contains("injured") ||
                                                                       lowerBody.contains("cancer") ||
                                                                       lowerBody.contains("sick") ||
                                                                       lowerBody.contains("ill") ||
                                                                       lowerBody.contains("died") ||
                                                                       lowerBody.contains("death") ||
                                                                       lowerBody.contains("passed away") ||
                                                                       lowerBody.contains("help") ||
                                                                       lowerBody.contains("urgent") ||
                                                                       lowerBody.contains("serious")
                                                
                                                // Check if message was rejected for being inappropriate
                                                val isInappropriate = reasoning.contains("inappropriate", ignoreCase = true) ||
                                                                     reasoning.contains("swear", ignoreCase = true) ||
                                                                     reasoning.contains("profanity", ignoreCase = true) ||
                                                                     reasoning.contains("sexual", ignoreCase = true) ||
                                                                     reasoning.contains("time-wasting", ignoreCase = true) ||
                                                                     reasoning.contains("uncooperative", ignoreCase = true)
                                                
                                                // Check if a script response was already sent (don't use fallback in this case)
                                                val alreadySent = reasoning.contains("already sent", ignoreCase = true) ||
                                                                 reasoning.contains("duplicate", ignoreCase = true) ||
                                                                 reasoning.contains("waiting for reply", ignoreCase = true)
                                                
                                                // Use fallback ONLY when:
                                                // - Message doesn't match any script (NO_SEND)
                                                // - Script response was NOT already sent (not a duplicate)
                                                // - Message is not inappropriate
                                                // - Message has content
                                                var shouldUseFallback = false
                                                
                                                // DON'T use fallback if a script response was already sent
                                                if (alreadySent) {
                                                    Log.d("SmsReceiver", "Script response already sent - not using fallback. Reasoning: $reasoning")
                                                    shouldUseFallback = false
                                                }
                                                
                                                // Only use fallback if not already sent and not inappropriate
                                                if (!alreadySent && !isInappropriate) {
                                                    if (isSeriousMessage) {
                                                        // Serious messages ALWAYS get a response - highest priority
                                                        shouldUseFallback = true
                                                        Log.d("SmsReceiver", "Serious/urgent message detected - using fallback to respond with care")
                                                    } else if (isQuestion) {
                                                        // ALL questions should get a response (unless inappropriate or already sent)
                                                        shouldUseFallback = true
                                                        Log.d("SmsReceiver", "Question detected - using fallback to respond naturally")
                                                    } else if (isCasualStatement) {
                                                        // Also respond to casual statements
                                                        shouldUseFallback = true
                                                        Log.d("SmsReceiver", "Casual statement detected - using fallback to respond naturally")
                                                    } else {
                                                        // Respond to ALL other messages too (unless inappropriate or already sent)
                                                        shouldUseFallback = true
                                                        Log.d("SmsReceiver", "Message detected - using fallback to respond naturally")
                                                    }
                                                }
                                                
                                                // Check if this is a payment paragraph question BEFORE fallback logic
                                                // Check if payment request was sent earlier in conversation
                                                var paymentRequestWasSent = false
                                                for (turn in turnsWithCurrent) {
                                                    val role = turn["role"]?.lowercase() ?: ""
                                                    val text = turn["text"]?.lowercase() ?: ""
                                                    if (role == "you" && text.contains("i need to make a payment today but can't for 24h")) {
                                                        paymentRequestWasSent = true
                                                        break
                                                    }
                                                }
                                                
                                                // Check if current message asks what the payment is for
                                                val paymentParagraphKeywords = listOf(
                                                    "what is it for", "what's it for", "what for", "what payment",
                                                    "what's the payment for", "what is the payment for", "payment for what",
                                                    "what's this for", "what is this for", "for what",
                                                    "what the payment paragraph is for", "what's the payment paragraph for",
                                                    "what is the payment paragraph for", "payment paragraph for what",
                                                    "what paragraph", "paragraph for what"
                                                )
                                                val isPaymentParagraphQuestion = paymentParagraphKeywords.any { keyword -> 
                                                    lowerBody.contains(keyword)
                                                }
                                                
                                                // If payment request was sent and they're asking what it's for, respond with macbook explanation
                                                if (paymentRequestWasSent && isPaymentParagraphQuestion) {
                                                    val macbookResponse = "Its for a macbook i got i just need to pay it back if that's okay, I'll have it sent back to you tomorrow first thing"
                                                    val incomingMessageHash = hashMessage(sender, messageBody)
                                                    AutoSendQueue.enqueue(context, sender, macbookResponse, AutoSendQueue.Source.AI, incomingMessageHash)
                                                    Log.d("SmsReceiver", "Payment paragraph question detected - queued macbook response: $macbookResponse")
                                                    return@use
                                                }
                                                
                                                if (shouldUseFallback) {
                                                    Log.d("SmsReceiver", "Generating natural AI response to question/statement")
                                                    
                                                    // Generate natural, varied responses based on the message
                                                    // IMPORTANT: Check most specific patterns first, then generic patterns, then else
                                                    val fallbackResponse = when {
                                                        // PAYMENT/PROMISE MESSAGES - respond appropriately (check FIRST - highest priority)
                                                        (lowerBody.contains("promise") || lowerBody.contains("will pay") || lowerBody.contains("pay back") || 
                                                         lowerBody.contains("pay you back") || lowerBody.contains("pay me back")) && 
                                                        (lowerBody.contains("tomorrow") || lowerBody.contains("will") || lowerBody.contains("promise")) -> {
                                                            if (lowerBody.contains("not a problem") || lowerBody.contains("no problem") || lowerBody.contains("that's fine") || lowerBody.contains("thats fine")) {
                                                                "Yes i will i promise"
                                                            } else if (lowerBody.contains("promise")) {
                                                                "Yes i will i promise"
                                                            } else {
                                                                "Yes i will i promise"
                                                            }
                                                        }
                                                        lowerBody.contains("pay back") || lowerBody.contains("pay you back") || lowerBody.contains("pay me back") -> 
                                                            "Yes i will i promise"
                                                        
                                                        // SERIOUS/URGENT MESSAGES - respond with care and concern
                                                        lowerBody.contains("crashed") || lowerBody.contains("crash") || lowerBody.contains("accident") -> {
                                                            if (lowerBody.contains("car")) {
                                                                "Oh no, is everyone okay? Let me know if you need anything"
                                                            } else {
                                                                "Oh no, are you okay? Let me know if you need anything"
                                                            }
                                                        }
                                                        lowerBody.contains("hospital") -> 
                                                            "Hope everything is okay, let me know if you need anything"
                                                        lowerBody.contains("can't speak") || lowerBody.contains("cant speak") || 
                                                        lowerBody.contains("can't talk") || lowerBody.contains("cant talk") -> 
                                                            "Okay, let me know when you can talk. Hope everything is okay"
                                                        lowerBody.contains("hurt") || lowerBody.contains("injured") -> 
                                                            "Are you okay? Let me know if you need anything"
                                                        lowerBody.contains("cancer") -> 
                                                            "I'm so sorry to hear that. Let me know if you need anything or want to talk"
                                                        lowerBody.contains("sick") || lowerBody.contains("ill") -> 
                                                            "Hope you feel better soon, let me know if you need anything"
                                                        lowerBody.contains("died") || lowerBody.contains("death") || lowerBody.contains("passed away") -> 
                                                            "I'm so sorry. Let me know if you need anything or want to talk"
                                                        lowerBody.contains("help") && (lowerBody.contains("need") || lowerBody.contains("urgent")) -> 
                                                            "What do you need? I'm here to help"
                                                        lowerBody.contains("emergency") || lowerBody.contains("urgent") -> 
                                                            "What's wrong? Let me know if you need anything"
                                                        
                                                        // Shopping requests / lists - if message contains items (common shopping words)
                                                        (lowerBody.contains("please") || lowerBody.contains("can you get") || lowerBody.contains("get me") || 
                                                         lowerBody.contains("pick up") || lowerBody.contains("grab")) && 
                                                        (lowerBody.contains("and") || lowerBody.split(" ").size >= 3) -> {
                                                            // Extract items from message - look for common shopping items or just acknowledge
                                                            "Yes please"
                                                        }
                                                        
                                                        // WhatsApp setup questions
                                                        (lowerBody.contains("whatsapp") || lowerBody.contains("whats app")) && 
                                                        (lowerBody.contains("set") || lowerBody.contains("setup") || lowerBody.contains("set up") || 
                                                         lowerBody.contains("ready") || lowerBody.contains("done") || lowerBody.contains("working")) -> 
                                                            "Not yet i still need to set it up"
                                                        
                                                        // Specific greetings/questions
                                                        lowerBody.contains("what you been up to") || lowerBody.contains("what you been") || 
                                                        (lowerBody.contains("okay") && lowerBody.contains("what")) -> 
                                                            "Hey, not much just been busy. How about you?"
                                                        lowerBody.contains("how are you") || lowerBody.contains("how you doing") -> 
                                                            "I'm good thanks, how are you?"
                                                        lowerBody.contains("you ok") || lowerBody.contains("you alright") -> 
                                                            "Yeah I'm fine thanks"
                                                        lowerBody.contains("dinner") && lowerBody.contains("ready") -> 
                                                            "Thanks, be there soon"
                                                        // Going to shops/store and asking if I need anything
                                                        (lowerBody.contains("going to") || lowerBody.contains("going")) && 
                                                        (lowerBody.contains("shop") || lowerBody.contains("store") || lowerBody.contains("supermarket")) &&
                                                        (lowerBody.contains("need") || lowerBody.contains("want") || lowerBody.contains("anything")) -> 
                                                            "No I'm good thanks"
                                                        lowerBody.contains("going out") || lowerBody.contains("going to") -> 
                                                            "Okay thanks"
                                                        lowerBody.contains("at the") -> 
                                                            "Okay thanks"
                                                        // Statements about what they're doing
                                                        lowerBody.contains("i'm") || lowerBody.contains("im ") || lowerBody.contains("i am") -> {
                                                            when {
                                                                (lowerBody.contains("going") && (lowerBody.contains("shop") || lowerBody.contains("store"))) && 
                                                                (lowerBody.contains("need") || lowerBody.contains("want") || lowerBody.contains("anything")) -> 
                                                                    "No I'm good thanks"
                                                                lowerBody.contains("going") || lowerBody.contains("out") -> 
                                                                    "Okay thanks"
                                                                else -> 
                                                                    "Okay thanks"
                                                            }
                                                        }
                                                        
                                                        // Task/completion questions
                                                        lowerBody.contains("did you manage") || lowerBody.contains("did you sort") || 
                                                        lowerBody.contains("did you get") || lowerBody.contains("have you sorted") ||
                                                        lowerBody.contains("have you got") -> {
                                                            if (lowerBody.contains("birthday") || lowerBody.contains("present")) {
                                                                "Yeah I sorted it thanks"
                                                            } else {
                                                                "Yeah I did thanks"
                                                            }
                                                        }
                                                        
                                                        // Yes/No questions - read the actual question
                                                        lowerBody.contains("are you") -> {
                                                            when {
                                                                lowerBody.contains("coming") && lowerBody.contains("dinner") -> 
                                                                    "Yeah I'll be there"
                                                                lowerBody.contains("coming") -> 
                                                                    "Yeah I'll be there"
                                                                lowerBody.contains("still") && lowerBody.contains("coming") -> 
                                                                    "Yeah I'll be there"
                                                                lowerBody.contains("birthday") || lowerBody.contains("present") -> 
                                                                    "Yeah I sorted it thanks"
                                                                else -> 
                                                                    "Yeah I'm fine thanks"
                                                            }
                                                        }
                                                        lowerBody.contains("did you") || lowerBody.contains("have you") -> {
                                                            if (lowerBody.contains("birthday") || lowerBody.contains("present")) {
                                                                "Yeah I sorted it thanks"
                                                            } else if (lowerBody.contains("manage") || lowerBody.contains("sort") || lowerBody.contains("get")) {
                                                                "Yeah I did thanks"
                                                            } else {
                                                                "Yeah I did thanks"
                                                            }
                                                        }
                                                        lowerBody.contains("will you") || lowerBody.contains("can you") || 
                                                        lowerBody.contains("could you") || lowerBody.contains("would you") ||
                                                        lowerBody.contains("should you") || lowerBody.contains("is it") || 
                                                        lowerBody.contains("was it") -> {
                                                            if (lowerBody.contains("coming") || lowerBody.contains("be there")) {
                                                                "Yeah I'll be there"
                                                            } else {
                                                                "Yeah I did thanks"
                                                            }
                                                        }
                                                        
                                                        // "What" questions - read the actual question (check most specific first)
                                                        lowerBody.contains("what") -> {
                                                            when {
                                                                // Check for weekend question FIRST (more specific)
                                                                lowerBody.contains("what you doing") && lowerBody.contains("weekend") -> 
                                                                    "Not much, probably just relaxing"
                                                                lowerBody.contains("what you doing") -> 
                                                                    "Not much really"
                                                                lowerBody.contains("what you") || lowerBody.contains("what have you") -> 
                                                                    "Not much, just been busy. How about you?"
                                                                else -> 
                                                                    "Not sure, I'll check and let you know"
                                                            }
                                                        }
                                                        
                                                        // "How" questions
                                                        lowerBody.contains("how") -> {
                                                            if (lowerBody.contains("how are") || lowerBody.contains("how you")) {
                                                                "I'm good thanks, how are you?"
                                                            } else {
                                                                "It's going okay thanks"
                                                            }
                                                        }
                                                        
                                                        // "When" questions
                                                        lowerBody.contains("when") -> {
                                                            "I'll let you know when I know"
                                                        }
                                                        
                                                        // "Where" questions
                                                        lowerBody.contains("where") -> {
                                                            "I'm not sure, I'll check"
                                                        }
                                                        
                                                        // "Why" questions
                                                        lowerBody.contains("why") -> {
                                                            "Not sure why, I'll find out"
                                                        }
                                                        
                                                        // "Who" questions
                                                        lowerBody.contains("who") -> {
                                                            "I'm not sure who"
                                                        }
                                                        
                                                        // Generic questions (has ?) - check this BEFORE the else
                                                        lowerBody.contains("?") -> {
                                                            "I'm not sure, I'll check and get back to you"
                                                        }
                                                        
                                                        // If it's a question but didn't match above, give a generic question response
                                                        isQuestion -> {
                                                            "I'm not sure, I'll check and let you know"
                                                        }
                                                        
                                                        // Requests (please, can you, get me, etc.) - acknowledge positively
                                                        lowerBody.contains("please") || lowerBody.contains("can you") || 
                                                        lowerBody.contains("get me") || lowerBody.contains("pick up") || 
                                                        lowerBody.contains("grab") || lowerBody.contains("bring") -> {
                                                            "Yes please"
                                                        }
                                                        
                                                        // Statements - only use "Okay thanks" for non-questions
                                                        else -> {
                                                            "Okay thanks"
                                                        }
                                                    }
                                                    
                                                    if (fallbackResponse.isNotEmpty()) {
                                                        val incomingMessageHash = hashMessage(sender, messageBody)
                                                        AutoSendQueue.enqueue(context, sender, fallbackResponse, AutoSendQueue.Source.AI, incomingMessageHash)
                                                        Log.d("SmsReceiver", "Fallback response queued: $fallbackResponse")
                                                    }
                                                } else {
                                                    Log.d("SmsReceiver", "Not using fallback - message doesn't appear to be casual conversation or was rejected for valid reasons")
                                                }
                                        }
                                        success = true
                                    } else {
                                            // Consume error body to prevent resource leak
                                            val errorBody = response.body?.string()
                                        Log.e("SmsReceiver", "Claude API error: ${response.code}")
                                        Log.e("SmsReceiver", "Error details: $errorBody")
                                        success = true // Don't retry on non-502 errors
                                        }
                                    } catch (e: Exception) {
                                        // Ensure response body is closed even on exception
                                        try {
                                            response.body?.close()
                                        } catch (ignored: Exception) {}
                                        throw e
                                    }
                                }
                            } catch (e: Exception) {
                                // Check if it's a timeout exception - retry these
                                val isTimeout = e is java.net.SocketTimeoutException || 
                                              e is java.net.ConnectException ||
                                              e.message?.contains("timeout", ignoreCase = true) == true ||
                                              e.message?.contains("timed out", ignoreCase = true) == true
                                
                                if (isTimeout && retryCount < maxRetries - 1) {
                                    retryCount++
                                    val delayMs = (2000 * retryCount).toLong() // Longer delay for timeouts: 2s, 4s, 6s
                                    Log.w("SmsReceiver", "Timeout exception, retrying in ${delayMs}ms (attempt ${retryCount + 1}/$maxRetries): ${e.message}")
                                    Thread.sleep(delayMs)
                                } else if (retryCount < maxRetries - 1) {
                                    retryCount++
                                    val delayMs = (1000 * retryCount).toLong()
                                    Log.w("SmsReceiver", "Exception during request, retrying in ${delayMs}ms: ${e.message}")
                                    Thread.sleep(delayMs)
                                } else {
                                    Log.e("SmsReceiver", "Failed after $maxRetries attempts: ${e.message}", e)
                                    // Don't throw - just log the error and continue
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SmsReceiver", "Error calling Claude: ${e.message}", e)
                        e.printStackTrace()
                    } finally {
                        // CRITICAL: Release wake lock and finish receiver when done
                        try {
                            wakeLock?.let {
                                if (it.isHeld) {
                                    it.release()
                                    Log.d("SmsReceiver", "Wake lock released after SMS processing")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("SmsReceiver", "Error releasing wake lock: ${e.message}", e)
                        }
                        // Finish the receiver - this tells Android we're done processing
                        pendingResult.finish()
                        Log.d("SmsReceiver", "Receiver finished - processing complete")
                    }
                }.start()
        }
    }
}
