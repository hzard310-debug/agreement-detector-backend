package com.example.agreementdetector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import java.security.MessageDigest
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType

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

            // Mark message as queued so the scanner doesn't enqueue it again before processing finishes
            try {
                val msgHash = hashMessage(sender, body)
                val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val queued = prefs.getStringSet("scan_queued_messages", null)?.toMutableSet() ?: mutableSetOf()
                if (!queued.contains(msgHash)) {
                    queued.add(msgHash)
                    prefs.edit().putStringSet("scan_queued_messages", queued).apply()
                    android.util.Log.d("SmsReceiver", "Marked message as queued to prevent duplicate scan: $msgHash")
                }
            } catch (e: Exception) {
                android.util.Log.w("SmsReceiver", "Unable to mark message as queued: ${e.message}")
            }

            // REMOVED: AI enabled check - always process messages automatically
            android.util.Log.i("SmsReceiver", "[OK] Processing message automatically")
            
            // CRITICAL: Pass SMS to foreground service for processing (service will handle everything)
            // This ensures processing continues even when screen is locked
            try {
                SmsProcessingService.startWithSms(context.applicationContext, sender, body)
                Log.d("SmsReceiver", "Passed SMS to foreground service for processing (works even when screen is locked)")
                // Finish receiver immediately - service will handle processing
                pendingResult.finish()
                return
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Failed to start service with SMS: ${e.message}", e)
                // Fallback to processing in receiver if service fails
            }
            
            // FALLBACK: Process in receiver if service fails (shouldn't normally happen)
            val messageBody = body
            val messageSender = sender
                
            // Acquire wake lock to keep device awake while processing (even if screen is off)
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AgreementDetector::SmsProcessing"
            )
            wakeLock?.acquire(60000) // Hold for up to 60 seconds
                
            Log.d("SmsReceiver", "FALLBACK: Processing SMS in receiver (service failed to start)")
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
                        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
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

                                                // Old number / delete old number questions
                                                val oldNumberKeywords = listOf(
                                                    "old number",
                                                    "previous number",
                                                    "old mobile",
                                                    "old phone",
                                                    "old sim",
                                                    "your old"
                                                )
                                                val asksAboutOldNumber = oldNumberKeywords.any { lowerBody.contains(it) } ||
                                                        (lowerBody.contains("delete") && lowerBody.contains("old") && (lowerBody.contains("number") || lowerBody.contains("num")))
                                                if (asksAboutOldNumber) {
                                                    val response = "Yes ill be using this number from now on, you can delete the old one."
                                                    val incomingMessageHash = hashMessage(sender, messageBody)
                                                    AutoSendQueue.enqueue(context, sender, response, AutoSendQueue.Source.AI, incomingMessageHash)
                                                    Log.d("SmsReceiver", "Old number question detected - queued response: $response")
                                                    return@use
                                                }
                                                
                                                fun selectNameFromOrQuestion(text: String): String? {
                                                    val lower = text.lowercase()
                                                    if (
                                                        !lower.contains("is this") &&
                                                        !lower.contains("is it") &&
                                                        !lower.contains("is that") &&
                                                        !lower.contains("are you")
                                                    ) return null
                                                    val regex = Regex("([A-Za-z][A-Za-z]+)\\s+or\\s+([A-Za-z][A-Za-z]+)")
                                                    val match = regex.find(text)
                                                    val femaleNames = setOf(
                                                        "amy", "anna", "beth", "charlotte", "chloe", "danielle", "emily", "emma",
                                                        "georgia", "grace", "hannah", "isla", "jessica", "karen", "kate", "katie",
                                                        "lauren", "lily", "lucy", "megan", "nicole", "olivia", "rachel", "sarah",
                                                        "sophie", "victoria", "zoe"
                                                    )
                                                    if (match != null) {
                                                        fun prettyName(raw: String): String {
                                                            val cleaned = raw.trim().trimEnd('.', ',', '!', '?')
                                                            return if (cleaned.isNotEmpty()) {
                                                                cleaned.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                                                            } else cleaned
                                                        }
                                                        val first = prettyName(match.groupValues[1])
                                                        val second = prettyName(match.groupValues[2])
                                                        val options = listOf(first, second).filter { it.isNotEmpty() }
                                                        if (options.size == 2) {
                                                            val feminine = options.firstOrNull { femaleNames.contains(it.lowercase()) }
                                                            return feminine ?: options.first()
                                                        }
                                                    }
                                                    return null
                                                }
                                                
                                                shouldUseFallback = false // Defer special-case handling to backend

                                                if (shouldUseFallback) {
                                                    val nameChoice = selectNameFromOrQuestion(messageBody)
                                                    fun detectSingleWordName(original: String): Boolean {
                                                        val cleaned = original.replace(Regex("[^A-Za-z]"), " ").trim()
                                                        val words = cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }
                                                        return words.size == 1 && words[0].length >= 2 && words[0][0].isLetter()
                                                    }
                                                    fun containsPoliceKeywords(lower: String): Boolean {
                                                        val keywords = listOf("police", "fraud squad", "cyber crime", "cybercrime", "scam unit", "authorities", "law enforcement")
                                                        return keywords.any { lower.contains(it) }
                                                    }
                                                    fun isWhichChildMessage(lower: String): Boolean {
                                                        return lower.contains("which child is this") ||
                                                               (lower.contains("which child") && lower.contains("number")) ||
                                                               lower.contains("which kid is this") ||
                                                               lower.contains("child of mine") ||
                                                               lower.contains("children's number") ||
                                                               lower.contains("childrens number")
                                                    }
                                                    fun needsHelpDecline(lower: String): Boolean {
                                                        return (lower.contains("need any help") || lower.contains("need help with it") || lower.contains("help with it") ||
                                                                lower.contains("need help with anything") || lower.contains("want any help")) &&
                                                                (lower.contains("kit") || lower.contains("device") || lower.contains("phone") || lower.contains("number") || lower.contains("new"))
                                                    }
                                                    fun isReactionToSaveNumber(lower: String): Boolean {
                                                        return (lower.contains("liked") || lower.contains("reacted") || lower.contains("loved") || lower.contains("thumbs up")) &&
                                                               lower.contains("dad save my new number")
                                                    }
                                                    fun isMissedCallAlert(lower: String): Boolean {
                                                        return lower.contains("missed a call from me") &&
                                                               (lower.contains("call alert") || lower.contains("dial 901") || lower.contains("o2"))
                                                    }
                                                    fun mentionsNoChildren(lower: String): Boolean {
                                                        return lower.contains("no sons") ||
                                                               lower.contains("no daughters") ||
                                                               lower.contains("no children") ||
                                                               lower.contains("no kids") ||
                                                               lower.contains("not anyone's dad") ||
                                                               lower.contains("no one's dad")
                                                    }
                                                    fun mentionsScam(lower: String): Boolean {
                                                        return lower.contains("goodbye scam") ||
                                                               (lower.contains("scam") && (lower.contains("goodbye") || lower.contains("bye") || lower.contains("lol")))
                                                    }
                                                    fun paymentRefusal(lower: String): Boolean {
                                                        return lower.contains("lol no") || lower.contains("nah no") || lower.contains("no chance") || lower.contains("not paying")
                                                    }
                                                    fun wrongNumberDad(lower: String): Boolean {
                                                        return lower.contains("wrong number pal") ||
                                                               lower.contains("wrong number mate") ||
                                                               (lower.contains("wrong number") && lower.contains("dad"))
                                                    }
                                                    fun asksBankDetailsButLow(lower: String): Boolean {
                                                        return (lower.contains("what are your bank details") || lower.contains("bank details") || lower.contains("account details")) &&
                                                               (lower.contains("dont have that much") || lower.contains("don't have that much") || lower.contains("not that much") || lower.contains("don't have much"))
                                                    }
                                                    fun acknowledgesSaveAnythingElse(lower: String): Boolean {
                                                        return (lower.contains("i'll save it") || lower.contains("ill save it") || lower.contains("saved it")) &&
                                                               (lower.contains("anything else you need") || lower.contains("need anything else"))
                                                    }
                                                    fun acknowledgesNoWorries(lower: String): Boolean {
                                                        return lower.contains("ok no worries") ||
                                                               lower.contains("okay no worries") ||
                                                               lower.contains("no worries") ||
                                                               lower.contains("ok thats fine") ||
                                                               lower.contains("okay thats fine") ||
                                                               lower.contains("all good") ||
                                                               lower.contains("fine no problem")
                                                    }
                                                    fun paymentConfirmed(lower: String): Boolean {
                                                        return (lower.contains("payment sent") || lower.contains("money sent") || lower.contains("paid") || lower.contains("transfer done") || lower.contains("sent it")) &&
                                                               (lower.contains("payment") || lower.contains("transfer") || lower.contains("bank") || lower.contains("money"))
                                                    }
                                                    fun noMoneyAvailable(lower: String): Boolean {
                                                        return lower.contains("don't have any money") ||
                                                               lower.contains("dont have any money") ||
                                                               (lower.contains("have £") && lower.contains("left")) ||
                                                               lower.contains("have no money") ||
                                                               (lower.contains("only have") && lower.contains("£"))
                                                    }
                                                    fun asksYourName(lower: String): Boolean {
                                                        return lower.contains("your name") && lower.contains("?")
                                                    }
                                                    fun isQuestionMarksOnly(text: String): Boolean {
                                                        val trimmed = text.trim()
                                                        return trimmed.isNotEmpty() && trimmed.all { it == '?' }
                                                    }
                                                    fun mentionsRefusalToSave(lower: String): Boolean {
                                                        return lower.contains("i don't want to") && lower.contains("save")
                                                    }
                                                    fun asksWhichTwin(lower: String): Boolean {
                                                        return lower.contains("which twin are you") || lower.contains("which twin")
                                                    }
                                                    fun asksNeedMoneyAgain(lower: String): Boolean {
                                                        return lower.contains("need money again")
                                                    }
                                                    fun asksHowDoIKnow(lower: String): Boolean {
                                                        return lower.contains("how do i know") && lower.contains("really you")
                                                    }
                                                    fun asksHowOldNow(lower: String): Boolean {
                                                        return lower.contains("how old are you now") || (lower.contains("how old are you") && lower.contains("now"))
                                                    }
                                                    fun asksWhatsMyNameThen(lower: String): Boolean {
                                                        return lower.contains("what's my name then") || lower.contains("whats my name then") || lower.contains("what is my name then")
                                                    }
                                                    fun asksPickUp(lower: String): Boolean {
                                                        return (lower.contains("pick me up") || lower.contains("pick us up")) && lower.contains("after work")
                                                    }
                                                    fun asksWhereHaveYouBeen(lower: String): Boolean {
                                                        return lower.contains("where have you been") || lower.contains("where've you been")
                                                    }
                                                    fun asksFoodToday(lower: String): Boolean {
                                                        return lower.contains("what have you had to eat") || lower.contains("what did you eat today") || lower.contains("what've you eaten")
                                                    }
                                                    fun asksCarProblemAfterFavour(lower: String): Boolean {
                                                        return (lower.contains("car problem") || lower.contains("car problems") || lower.contains("car issue") || lower.contains("car again")) ||
                                                               lower.contains("what's wrong") || lower.contains("whats wrong") || lower.contains("what happened")
                                                    }
                                                    fun mentionsStrangeParentScenario(lower: String): Boolean {
                                                        return (lower.contains("spare undies") || (lower.contains("mum") && lower.contains("says hi"))) && lower.contains("son")
                                                    }
                                                    fun pickFavourMessage(): String {
                                                        val variants = listOf(
                                                            "Could you do me a favour please?",
                                                            "Do you think you could do me a small favour?",
                                                            "Could you do me a quick favour when you get a sec?",
                                                            "Any chance you could do me a little favour?"
                                                        )
                                                        val idx = (System.currentTimeMillis() / 1000L % variants.size).toInt()
                                                        return variants[idx]
                                                    }
                                                    
                                                    if (containsPoliceKeywords(lowerBody) ||
                                                        mentionsNoChildren(lowerBody) ||
                                                        mentionsScam(lowerBody) ||
                                                        paymentRefusal(lowerBody) ||
                                                        wrongNumberDad(lowerBody) ||
                                                        isQuestionMarksOnly(messageBody) ||
                                                        mentionsRefusalToSave(lowerBody) ||
                                                        asksWhichTwin(lowerBody) ||
                                                        asksNeedMoneyAgain(lowerBody) ||
                                                        asksHowDoIKnow(lowerBody) ||
                                                        asksHowOldNow(lowerBody) ||
                                                        asksWhatsMyNameThen(lowerBody) ||
                                                        mentionsStrangeParentScenario(lowerBody)) {
                                                        Log.d("SmsReceiver", "Detected message to ignore based on safety rules")
                                                        return@use
                                                    }
                                                    
                                                    Log.d("SmsReceiver", "Generating natural AI response to question/statement")
                                                    
                                                    val fallbackResponse = when {
                                                        isWhichChildMessage(lowerBody) ->
                                                            "it is your eldest and favourite, i will be using this number from now on. Everything's all good though, how are you getting on?"
                                                        asksYourName(lowerBody) ->
                                                            "Your eldest and favourite"
                                                        isReactionToSaveNumber(lowerBody) ->
                                                            pickFavourMessage()
                                                        acknowledgesSaveAnythingElse(lowerBody) ->
                                                            pickFavourMessage()
                                                        acknowledgesNoWorries(lowerBody) ->
                                                            pickFavourMessage()
                                                        asksPickUp(lowerBody) ->
                                                            "I'm not sure yet, I might be tied up. If anything changes I'll let you know."
                                                        asksWhereHaveYouBeen(lowerBody) ->
                                                            "Sorry, I've just been busy lately."
                                                        asksFoodToday(lowerBody) ->
                                                            "I just grabbed a quick sandwich earlier, nothing too exciting. What about you?"
                                                        asksCarProblemAfterFavour(lowerBody) ->
                                                            "No it's not, I need to make a payment today but can't for 24h until my new number is added to my online banking. Would you be able to make the transfer on my behalf if I send you the details and I'll pay you back tomorrow please? Total is £1,785 if that's okay?"
                                                        detectSingleWordName(messageBody) ->
                                                            "Yes ill be using this number from now on"
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
                                                        nameChoice != null ->
                                                            "Its $nameChoice"
                                                        needsHelpDecline(lowerBody) ->
                                                            "Thanks, I'm all sorted with it now but really appreciate you offering."
                                                        lowerBody.contains("everything ok") || lowerBody.contains("everything okay") ||
                                                        lowerBody.contains("everything alright") || lowerBody.contains("everything all right") ||
                                                        lowerBody.contains("everything good") || lowerBody.contains("all ok") ||
                                                        lowerBody.contains("all okay") || lowerBody.contains("all right") ->
                                                            "Yeah everything's okay, how about you?"
                                                        asksBankDetailsButLow(lowerBody) ->
                                                            "Payment details are okay, would you be able to help with half of the payment?"
                                                        paymentConfirmed(lowerBody) ->
                                                            "Thank you so much honestly, could you please send over your bank details so I can pay you back first thing in the morning and also if possible can you please send over a confirmation of the payment so I can send it over."
                                                        noMoneyAvailable(lowerBody) ->
                                                            "Is there anyone you can ask? It's urgent, I really don't want to get hit with late fees and I promise I'll get it back to you first thing in the morning."
                                                        isMissedCallAlert(lowerBody) ->
                                                            "I can't call right now, could you drop me a message instead?"
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
                                                        (lowerBody.contains("please") || lowerBody.contains("can you get") || lowerBody.contains("get me") ||
                                                         lowerBody.contains("pick up") || lowerBody.contains("grab")) &&
                                                         (lowerBody.contains("and") || lowerBody.split(" ").size >= 3) ->
                                                            "Yes please"
                                                        lowerBody.contains("what you been up to") || lowerBody.contains("what you been") ||
                                                         (lowerBody.contains("okay") && lowerBody.contains("what")) ->
                                                            "Hey, not much just been busy. How about you?"
                                                        lowerBody.contains("how are you") || lowerBody.contains("how you doing") ->
                                                            "I'm good thanks, how are you?"
                                                        lowerBody.contains("you ok") || lowerBody.contains("you alright") ->
                                                            "Yeah I'm fine thanks"
                                                        lowerBody.contains("dinner") && lowerBody.contains("ready") ->
                                                            "Thanks, be there soon"
                                                        (lowerBody.contains("going to") || lowerBody.contains("going")) &&
                                                         (lowerBody.contains("shop") || lowerBody.contains("store") || lowerBody.contains("supermarket")) &&
                                                         (lowerBody.contains("need") || lowerBody.contains("want") || lowerBody.contains("anything")) ->
                                                            "No I'm good thanks"
                                                        lowerBody.contains("going out") || lowerBody.contains("going to") ->
                                                            "Okay thanks"
                                                        lowerBody.contains("at the") ->
                                                            "Okay thanks"
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
                                                        lowerBody.contains("did you manage") || lowerBody.contains("did you sort") ||
                                                         lowerBody.contains("did you get") || lowerBody.contains("have you sorted") ||
                                                         lowerBody.contains("have you got") -> {
                                                            if (lowerBody.contains("birthday") || lowerBody.contains("present")) {
                                                                "Yeah I sorted it thanks"
                                                            } else {
                                                                "Yeah I did thanks"
                                                            }
                                                        }
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
                                                        lowerBody.contains("what") -> {
                                                            when {
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
                                                        lowerBody.contains("how") -> {
                                                            if (lowerBody.contains("how are") || lowerBody.contains("how you")) {
                                                                "I'm good thanks, how are you?"
                                                            } else {
                                                                "It's going okay thanks"
                                                            }
                                                        }
                                                        lowerBody.contains("when") ->
                                                            "I'll let you know when I know"
                                                        lowerBody.contains("where") ->
                                                            "I'm not sure, I'll check"
                                                        lowerBody.contains("why") ->
                                                            "Not sure why, I'll find out"
                                                        lowerBody.contains("who") ->
                                                            "I'm not sure who"
                                                        lowerBody.contains("?") ->
                                                            "I'm not sure, I'll check and get back to you"
                                                        isQuestion ->
                                                            "I'm not sure, I'll check and let you know"
                                                        lowerBody.contains("please") || lowerBody.contains("can you") ||
                                                         lowerBody.contains("get me") || lowerBody.contains("pick up") ||
                                                         lowerBody.contains("grab") || lowerBody.contains("bring") ->
                                                            "Yes please"
                                                        else ->
                                                            "Okay thanks"
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
