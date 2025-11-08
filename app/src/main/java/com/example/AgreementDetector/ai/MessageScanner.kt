package com.example.agreementdetector.ai

import android.content.Context
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log
import com.example.agreementdetector.AutoSendQueue
import com.example.agreementdetector.SmsReceiver
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object MessageScanner {
    private const val TAG = "MessageScanner"
    @Volatile private var isScanning = false
    private val scanLock = Any()
    
    fun scanAllMessages(context: Context) {
        // Prevent multiple scans from running simultaneously
        synchronized(scanLock) {
            if (isScanning) {
                Log.d(TAG, "Scan already in progress, skipping")
                return
            }
            isScanning = true
        }
        
        Thread {
            // Acquire wake lock to keep device awake while scanning (even if screen is off)
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AgreementDetector::MessageScanning"
            )
            wakeLock?.acquire(300000) // Hold for up to 5 minutes for full scan
            
            try {
                // Clear previous logs and start fresh scan
                Log.d(TAG, "=== Starting fresh scan of all SMS messages ===")
                Log.d(TAG, "REFRESHING: Reading ALL messages from database - checking entire chat history")
                Log.d(TAG, "Clearing previous scan state and reading fresh conversation data")
                Log.d(TAG, "Background processing: Wake lock acquired for scanning (works even when screen is off)")
                
                // ALL message scanning requires AI - verify AI is enabled
                val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                if (!settings.getBoolean("ai_response_enabled", false)) {
                    Log.w(TAG, "AI response not enabled - skipping scan. ALL message reading requires AI.")
                    return@Thread
                }
                
                Log.d(TAG, "AI enabled - scanning messages through AI backend")
                
                val inbox = Telephony.Sms.Inbox.CONTENT_URI
                var totalScanned = 0
                var totalUnresponded = 0
                var totalQueued = 0
                
                // Get all inbox messages grouped by address
                val messagesByAddress = mutableMapOf<String, MutableList<Pair<String, Long>>>()
                
                try {
                    context.contentResolver.query(
                        inbox,
                        arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                        null,
                        null,
                        "${Telephony.Sms.DATE} DESC"
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val address = cursor.getString(0) ?: continue
                            val body = cursor.getString(1) ?: continue
                            val date = cursor.getLong(2)
                            
                            if (!messagesByAddress.containsKey(address)) {
                                messagesByAddress[address] = mutableListOf()
                            }
                            messagesByAddress[address]?.add(body to date)
                            totalScanned++
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading messages: ${e.message}", e)
                    return@Thread
                }
                
                Log.d(TAG, "Scanned $totalScanned messages from ${messagesByAddress.size} contacts")
                
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                // CRITICAL: Always read fresh from database - don't rely on cached responded_messages set
                // We'll verify each message against the actual SMS database
                val respondedMessages = mutableSetOf<String>() // Start fresh - will verify against database
                
                // CRITICAL: Read ALL sent messages ONCE before the loop to avoid opening multiple cursors
                // Group sent messages by address for efficient lookup
                val sentMessagesByAddress = mutableMapOf<String, MutableList<Pair<String, Long>>>() // address -> list of (text, date)
                try {
                    context.contentResolver.query(
                        Telephony.Sms.Sent.CONTENT_URI,
                        arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.ADDRESS),
                        null,
                        null,
                        "${Telephony.Sms.DATE} ASC" // Oldest first for chronological checking
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val sentBody = cursor.getString(0) ?: continue
                            val sentDate = cursor.getLong(1)
                            val sentAddress = cursor.getString(2) ?: continue
                            
                            // Group by address (normalize for comparison)
                            var matchedAddress: String? = null
                            for (inboxAddress in messagesByAddress.keys) {
                                if (android.telephony.PhoneNumberUtils.compare(context, inboxAddress, sentAddress)) {
                                    matchedAddress = inboxAddress
                                    break
                                }
                            }
                            
                            if (matchedAddress != null) {
                                if (!sentMessagesByAddress.containsKey(matchedAddress)) {
                                    sentMessagesByAddress[matchedAddress] = mutableListOf()
                                }
                                sentMessagesByAddress[matchedAddress]?.add(sentBody to sentDate)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading sent messages: ${e.message}", e)
                }
                
                Log.d(TAG, "Loaded sent messages for ${sentMessagesByAddress.size} contacts")
                
                // Track conversation states
                var contactsWaitingForReply = 0
                var contactsNeedingResponse = 0
                
                // Process each contact's messages - scan ALL messages, not just the latest
                for ((address, messages) in messagesByAddress) {
                    Log.d(TAG, "Processing contact $address with ${messages.size} messages")
                    
                    // Get sent messages for this contact (already loaded and grouped)
                    val sentMessagesWithDates = sentMessagesByAddress[address] ?: mutableListOf()
                    
                    Log.d(TAG, "Found ${sentMessagesWithDates.size} sent messages to $address in SMS database")
                    
                    // CRITICAL: Determine conversation state - who sent the last message?
                    // Read ALL messages (inbox + sent) to determine the last message in the conversation
                    val allMessagesWithDates = mutableListOf<Triple<Long, String, String>>() // date, role, text
                    
                    // Add inbox messages (from them)
                    for ((messageText, messageDate) in messages) {
                        allMessagesWithDates.add(Triple(messageDate, "them", messageText))
                    }
                    
                    // Add sent messages (from you)
                    for ((sentText, sentDate) in sentMessagesWithDates) {
                        allMessagesWithDates.add(Triple(sentDate, "you", sentText))
                    }
                    
                    // Sort by date to find the last message
                    allMessagesWithDates.sortBy { it.first }
                    
                    val lastMessage = allMessagesWithDates.lastOrNull()
                    val lastMessageRole = lastMessage?.second ?: "unknown"
                    val lastMessageText = lastMessage?.third ?: ""
                    val lastMessageDate = lastMessage?.first ?: 0L
                    
                    Log.d(TAG, "Conversation state: Last message was from '$lastMessageRole' at $lastMessageDate: '$lastMessageText'")
                    
                    // If WE sent the last message, we're WAITING for a reply - don't send anything
                    if (lastMessageRole == "you") {
                        contactsWaitingForReply++
                        Log.d(TAG, "STATUS: We are WAITING for a reply - we sent the last message, skipping all messages")
                        continue // Skip this contact - we're waiting for them to respond
                    }
                    
                    // If THEY sent the last message, we NEED TO RESPOND
                    // Now find ALL messages from them that we haven't responded to
                    contactsNeedingResponse++
                    Log.d(TAG, "STATUS: We NEED TO RESPOND - they sent the last message")
                    
                    // Scan through ALL messages from this contact (newest first)
                    // Process EVERY unresponded message - don't stop after the first one
                    for ((messageText, messageDate) in messages) {
                        
                        Log.d(TAG, "Checking message from $address: $messageText (date: $messageDate)")
                        
                        val messageHash = hashMessage(address, messageText)
                        
                        // CRITICAL: Check if we sent a message AFTER this incoming message using TIMESTAMPS
                        // Read fresh data from SMS database - don't trust cached data
                        var hasResponseAfter = false
                        var responseText = ""
                        var responseDate = 0L
                        
                        // Check all sent messages - find one that came AFTER this incoming message
                        // Since sentMessagesWithDates is sorted ASC (oldest first), we can check if any message came after
                        for ((sentText, sentDate) in sentMessagesWithDates) {
                            // Response must come AFTER the incoming message (with small buffer for timing)
                            // Check if this sent message came after the incoming message
                            if (sentDate > messageDate + 1000) { // 1 second buffer to account for timing differences
                                hasResponseAfter = true
                                responseText = sentText
                                responseDate = sentDate
                                Log.d(TAG, "Found response sent AFTER incoming message: '$sentText' (sent: $sentDate, received: $messageDate, diff: ${sentDate - messageDate}ms)")
                                break // Found a response, no need to check further
                            }
                        }
                        
                        // If no response found, log all sent messages for debugging
                        if (!hasResponseAfter && sentMessagesWithDates.isNotEmpty()) {
                            Log.d(TAG, "No response found after message. Sent messages to this contact:")
                            sentMessagesWithDates.forEach { (text, date) ->
                                val diff = date - messageDate
                                Log.d(TAG, "  Sent: '$text' at $date (diff: ${diff}ms from incoming)")
                            }
                        }
                        
                        // Only mark as responded if we have PROOF: a sent message with timestamp AFTER incoming message
                        if (hasResponseAfter) {
                            Log.d(TAG, "VERIFIED: Found response in SMS database sent AFTER this message (${responseDate - messageDate}ms later), skipping: $messageText")
                            // Mark in responded_messages set for future scans
                            respondedMessages.add(messageHash)
                            continue
                        }
                        
                        // No response found in SMS database - this message is unresponded
                        Log.d(TAG, "NO response found in SMS database sent AFTER this message - treating as unresponded: $messageText")
                        
                        // Don't trust responded_messages set alone - always verify with fresh SMS database
                        if (respondedMessages.contains(messageHash) && !hasResponseAfter) {
                            Log.d(TAG, "WARNING: Message in responded_messages set but NO actual response found in SMS database - treating as unresponded: $messageText")
                            // Remove from set since we can't verify it
                            respondedMessages.remove(messageHash)
                        }
                        
                        // This message hasn't been responded to - check with backend
                        Log.d(TAG, "Found unresponded message from $address: $messageText")
                        
                        // Build FULL conversation history - include ALL messages from entire conversation
                        // This ensures AI reads the complete chat context, not just messages up to this point
                        // CRITICAL: Always refresh and read ALL messages from the entire chat
                        val turnsUpToMessage = buildConversationHistoryUpToMessage(
                            messages, // ALL inbox messages (full conversation - refreshed from database)
                            sentMessagesWithDates, // ALL sent messages (full conversation - refreshed from database)
                            messageText,
                            messageDate
                        )
                        
                        Log.d(TAG, "REFRESHED: Read ALL ${turnsUpToMessage.size} messages from entire chat history for $address")
                        Log.d(TAG, "Checking with backend for message: $messageText")
                        Log.d(TAG, "Conversation history: ${turnsUpToMessage.size} turns (ALL messages from entire conversation)")
                        turnsUpToMessage.forEachIndexed { index, turn ->
                            Log.d(TAG, "  Turn $index: ${turn["role"]} - ${turn["text"]}")
                        }
                        
                        // This is an unresponded message
                        totalUnresponded++
                        Log.d(TAG, "Found unresponded message #$totalUnresponded from $address: $messageText")
                        
                        // Check with backend if we should respond
                        val shouldRespond = checkIfShouldRespond(context, address, turnsUpToMessage, messageText, messageHash)
                        
                        if (shouldRespond) {
                            totalQueued++
                            Log.d(TAG, "Queued response #$totalQueued for $address: $messageText")
                            // Continue to next message - process ALL unresponded messages
                        } else {
                            Log.d(TAG, "Backend said NO_SEND for unresponded message: $messageText")
                            // Continue to next message - process ALL unresponded messages
                        }
                    }
                    
                    // Update the responded_messages set
                    if (respondedMessages.isNotEmpty()) {
                        prefs.edit().putStringSet("responded_messages", respondedMessages).apply()
                    }
                }
                
                // Summary of conversation states
                Log.d(TAG, "Scan complete: $totalScanned messages scanned, $totalUnresponded unresponded messages found, $totalQueued responses queued")
                Log.d(TAG, "Summary: $contactsNeedingResponse contacts need responses, $contactsWaitingForReply contacts waiting for replies")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning messages: ${e.message}", e)
            } finally {
                // Release wake lock when done scanning
                try {
                    wakeLock?.let {
                        if (it.isHeld) {
                            it.release()
                            Log.d(TAG, "Wake lock released after scan")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing wake lock: ${e.message}", e)
                }
                synchronized(scanLock) {
                    isScanning = false
                }
            }
        }.start()
    }
    
    private fun hashMessage(address: String, message: String): String {
        val combined = "$address|${message.trim().lowercase()}"
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(combined.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun buildConversationHistoryUpToMessage(
        inboxMessages: List<Pair<String, Long>>, // text, date
        sentMessages: List<Pair<String, Long>>, // text, date
        targetMessage: String,
        targetMessageDate: Long
    ): List<Map<String, String>> {
        // Build FULL conversation history - include ALL messages from entire conversation
        // CRITICAL: Always refresh and include ALL messages - never use cached data
        // This ensures AI reads the complete chat context for better understanding
        val allMessages = mutableListOf<Triple<Long, String, String>>() // date, role, text
        
        // Add ALL inbox messages (from them) - full conversation (fresh from database)
        for ((text, date) in inboxMessages) {
            allMessages.add(Triple(date, "them", text))
        }
        
        // Add ALL sent messages (from you) - full conversation (fresh from database)
        for ((text, date) in sentMessages) {
            allMessages.add(Triple(date, "you", text))
        }
        
        // Sort by date to get chronological order - include ALL messages
        allMessages.sortBy { it.first }
        val turns = mutableListOf<Map<String, String>>()
        
        // Include ALL messages from the conversation for full context
        // CRITICAL: Every scan reads ALL messages fresh - no caching
        for ((date, role, text) in allMessages) {
            turns.add(mapOf("role" to role, "text" to text))
        }
        
        // Ensure target message is included (it should already be in inboxMessages, but add if missing)
        val targetFound = turns.any { it["role"] == "them" && it["text"] == targetMessage }
        if (!targetFound) {
            turns.add(mapOf("role" to "them", "text" to targetMessage))
        }
        
        Log.d(TAG, "REFRESHED: Built FULL conversation history with ${turns.size} turns (ALL messages from entire conversation - fresh from database)")
        
        return turns
    }
    
    private fun checkIfShouldRespond(
        context: Context,
        address: String,
        turns: List<Map<String, String>>,
        latestMessage: String,
        incomingMessageHash: String
    ): Boolean {
        try {
            val url = "https://agreement-detector-api.onrender.com/respond"
            val client = OkHttpClient.Builder()
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val turnsArray = JSONArray()
            for (turn in turns) {
                turnsArray.put(JSONObject(turn))
            }
            
            // Get payment details from SharedPreferences
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val paymentDetails = prefs.getString("payment_details", "")?.trim() ?: ""
            
            val requestBody = JSONObject().apply {
                put("device_id", android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID))
                put("contact_id", address)
                put("script", "Your eldest and favourite")
                put("turns", turnsArray)
                if (paymentDetails.isNotEmpty()) {
                    put("payment_details", paymentDetails)
                }
            }.toString()
            
            val request = Request.Builder()
                .url(url)
                .post(RequestBody.create("application/json".toMediaType(), requestBody))
                .build()
            
            client.newCall(request).execute().use { response ->
                try {
                if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body == null) {
                            // Body is null, ensure it's closed
                            try {
                                response.body?.close()
                            } catch (ignored: Exception) {}
                            return false
                        }
                    val json = JSONObject(body)
                    val action = json.getString("action")
                    val messageToSend = json.getString("response")
                    val reasoning = json.optString("reasoning", "")
                    
                    Log.d(TAG, "Backend response for $address: action=$action, messageLength=${messageToSend.length}, reasoning=$reasoning")
                    
                    if (action == "SEND" && messageToSend.isNotEmpty()) {
                        Log.d(TAG, "Backend says SEND for $address: $messageToSend")
                        // Queue the response with incoming message hash so it can be marked as responded after successful send
                        AutoSendQueue.enqueue(context, address, messageToSend, AutoSendQueue.Source.AI, incomingMessageHash)
                        return true
                    } else {
                        Log.d(TAG, "Backend says NO_SEND for $address: action=$action, messageEmpty=${messageToSend.isEmpty()}, reasoning=$reasoning")
                            
                            // AI should respond to ALL questions naturally
                            val latestMessageLower = latestMessage.lowercase()
                            
                            if (latestMessageLower.isNotEmpty()) {
                                // Detect ANY question - anything with question mark or question words
                                val isQuestion = latestMessageLower.contains("?") ||
                                                latestMessageLower.contains("what") ||
                                                latestMessageLower.contains("how") ||
                                                latestMessageLower.contains("when") ||
                                                latestMessageLower.contains("where") ||
                                                latestMessageLower.contains("why") ||
                                                latestMessageLower.contains("who") ||
                                                latestMessageLower.contains("which") ||
                                                latestMessageLower.contains("did you") ||
                                                latestMessageLower.contains("do you") ||
                                                latestMessageLower.contains("have you") ||
                                                latestMessageLower.contains("are you") ||
                                                latestMessageLower.contains("will you") ||
                                                latestMessageLower.contains("can you") ||
                                                latestMessageLower.contains("could you") ||
                                                latestMessageLower.contains("would you") ||
                                                latestMessageLower.contains("should you") ||
                                                latestMessageLower.contains("is it") ||
                                                latestMessageLower.contains("are they") ||
                                                latestMessageLower.contains("was it") ||
                                                latestMessageLower.contains("were you")
                                
                                val isCasualStatement = latestMessageLower.contains("i'm") || 
                                                        latestMessageLower.contains("im ") ||
                                                        latestMessageLower.contains("i am") ||
                                                        latestMessageLower.contains("going to") ||
                                                        latestMessageLower.contains("at the") ||
                                                        latestMessageLower.contains("dinner") ||
                                                        latestMessageLower.contains("ready")
                                
                                // Detect serious/urgent messages that need a caring response
                                val isSeriousMessage = latestMessageLower.contains("crashed") ||
                                                       latestMessageLower.contains("crash") ||
                                                       latestMessageLower.contains("accident") ||
                                                       latestMessageLower.contains("hospital") ||
                                                       latestMessageLower.contains("emergency") ||
                                                       latestMessageLower.contains("can't speak") ||
                                                       latestMessageLower.contains("cant speak") ||
                                                       latestMessageLower.contains("can't talk") ||
                                                       latestMessageLower.contains("cant talk") ||
                                                       latestMessageLower.contains("hurt") ||
                                                       latestMessageLower.contains("injured") ||
                                                       latestMessageLower.contains("cancer") ||
                                                       latestMessageLower.contains("sick") ||
                                                       latestMessageLower.contains("ill") ||
                                                       latestMessageLower.contains("died") ||
                                                       latestMessageLower.contains("death") ||
                                                       latestMessageLower.contains("passed away") ||
                                                       latestMessageLower.contains("help") ||
                                                       latestMessageLower.contains("urgent") ||
                                                       latestMessageLower.contains("serious")
                                
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
                                    Log.d(TAG, "Script response already sent - not using fallback. Reasoning: $reasoning")
                                    shouldUseFallback = false
                                }
                                
                                // Only use fallback if not already sent and not inappropriate
                                if (!alreadySent && !isInappropriate) {
                                    if (isSeriousMessage) {
                                        // Serious messages ALWAYS get a response - highest priority
                                        shouldUseFallback = true
                                        Log.d(TAG, "Serious/urgent message detected - using fallback to respond with care")
                                    } else if (isQuestion) {
                                        // ALL questions should get a response (unless inappropriate or already sent)
                                        shouldUseFallback = true
                                        Log.d(TAG, "Question detected - using fallback to respond naturally")
                                    } else if (isCasualStatement) {
                                        // Also respond to casual statements
                                        shouldUseFallback = true
                                        Log.d(TAG, "Casual statement detected - using fallback to respond naturally")
                                    } else {
                                        // Respond to ALL other messages too (unless inappropriate or already sent)
                                        shouldUseFallback = true
                                        Log.d(TAG, "Message detected - using fallback to respond naturally")
                                    }
                                }
                                
                                // Check if this is a payment paragraph question BEFORE fallback logic
                                // Check if payment request was sent earlier in conversation
                                var paymentRequestWasSent = false
                                for (turn in turns) {
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
                                    latestMessageLower.contains(keyword)
                                }
                                
                                // If payment request was sent and they're asking what it's for, respond with macbook explanation
                                if (paymentRequestWasSent && isPaymentParagraphQuestion) {
                                    val macbookResponse = "Its for a macbook i got i just need to pay it back if that's okay, I'll have it sent back to you tomorrow first thing"
                                    AutoSendQueue.enqueue(context, address, macbookResponse, AutoSendQueue.Source.AI, incomingMessageHash)
                                    Log.d(TAG, "Payment paragraph question detected - queued macbook response: $macbookResponse")
                                    return true
                                }
                                
                                if (shouldUseFallback) {
                                    Log.d(TAG, "Generating natural AI response to question/statement")
                                    
                                    // Generate natural, varied responses based on the message
                                    val fallbackResponse = when {
                                        // PAYMENT/PROMISE MESSAGES - respond appropriately (check FIRST - highest priority)
                                        (latestMessageLower.contains("promise") || latestMessageLower.contains("will pay") || latestMessageLower.contains("pay back") || 
                                         latestMessageLower.contains("pay you back") || latestMessageLower.contains("pay me back")) && 
                                        (latestMessageLower.contains("tomorrow") || latestMessageLower.contains("will") || latestMessageLower.contains("promise")) -> {
                                            if (latestMessageLower.contains("not a problem") || latestMessageLower.contains("no problem") || latestMessageLower.contains("that's fine") || latestMessageLower.contains("thats fine")) {
                                                "Yes i will i promise"
                                            } else if (latestMessageLower.contains("promise")) {
                                                "Yes i will i promise"
                                            } else {
                                                "Yes i will i promise"
                                            }
                                        }
                                        latestMessageLower.contains("pay back") || latestMessageLower.contains("pay you back") || latestMessageLower.contains("pay me back") -> 
                                            "Yes i will i promise"
                                        
                                        // SERIOUS/URGENT MESSAGES - respond with care and concern
                                        latestMessageLower.contains("crashed") || latestMessageLower.contains("crash") || latestMessageLower.contains("accident") -> {
                                            if (latestMessageLower.contains("car")) {
                                                "Oh no, is everyone okay? Let me know if you need anything"
                                            } else {
                                                "Oh no, are you okay? Let me know if you need anything"
                                            }
                                        }
                                        latestMessageLower.contains("hospital") -> 
                                            "Hope everything is okay, let me know if you need anything"
                                        latestMessageLower.contains("can't speak") || latestMessageLower.contains("cant speak") || 
                                        latestMessageLower.contains("can't talk") || latestMessageLower.contains("cant talk") -> 
                                            "Okay, let me know when you can talk. Hope everything is okay"
                                        latestMessageLower.contains("hurt") || latestMessageLower.contains("injured") -> 
                                            "Are you okay? Let me know if you need anything"
                                        latestMessageLower.contains("cancer") -> 
                                            "I'm so sorry to hear that. Let me know if you need anything or want to talk"
                                        latestMessageLower.contains("sick") || latestMessageLower.contains("ill") -> 
                                            "Hope you feel better soon, let me know if you need anything"
                                        latestMessageLower.contains("died") || latestMessageLower.contains("death") || latestMessageLower.contains("passed away") -> 
                                            "I'm so sorry. Let me know if you need anything or want to talk"
                                        latestMessageLower.contains("help") && (latestMessageLower.contains("need") || latestMessageLower.contains("urgent")) -> 
                                            "What do you need? I'm here to help"
                                        latestMessageLower.contains("emergency") || latestMessageLower.contains("urgent") -> 
                                            "What's wrong? Let me know if you need anything"
                                        
                                        // Shopping requests / lists - if message contains items (common shopping words)
                                        (latestMessageLower.contains("please") || latestMessageLower.contains("can you get") || latestMessageLower.contains("get me") || 
                                         latestMessageLower.contains("pick up") || latestMessageLower.contains("grab")) && 
                                        (latestMessageLower.contains("and") || latestMessageLower.split(" ").size >= 3) -> {
                                            // Extract items from message - look for common shopping items or just acknowledge
                                            "Yes please"
                                        }
                                        
                                        // WhatsApp setup questions
                                        (latestMessageLower.contains("whatsapp") || latestMessageLower.contains("whats app")) && 
                                        (latestMessageLower.contains("set") || latestMessageLower.contains("setup") || latestMessageLower.contains("set up") || 
                                         latestMessageLower.contains("ready") || latestMessageLower.contains("done") || latestMessageLower.contains("working")) -> 
                                            "Not yet i still need to set it up"
                                        
                                        // Specific greetings/questions
                                        latestMessageLower.contains("what you been up to") || latestMessageLower.contains("what you been") || 
                                        (latestMessageLower.contains("okay") && latestMessageLower.contains("what")) -> 
                                            "Hey, not much just been busy. How about you?"
                                        latestMessageLower.contains("how are you") || latestMessageLower.contains("how you doing") -> 
                                            "I'm good thanks, how are you?"
                                        latestMessageLower.contains("you ok") || latestMessageLower.contains("you alright") -> 
                                            "Yeah I'm fine thanks"
                                        latestMessageLower.contains("dinner") && latestMessageLower.contains("ready") -> 
                                            "Thanks, be there soon"
                                        // Going to shops/store and asking if I need anything
                                        (latestMessageLower.contains("going to") || latestMessageLower.contains("going")) && 
                                        (latestMessageLower.contains("shop") || latestMessageLower.contains("store") || latestMessageLower.contains("supermarket")) &&
                                        (latestMessageLower.contains("need") || latestMessageLower.contains("want") || latestMessageLower.contains("anything")) -> 
                                            "No I'm good thanks"
                                        latestMessageLower.contains("going to") || latestMessageLower.contains("going out") -> 
                                            "Okay thanks"
                                        latestMessageLower.contains("at the") -> 
                                            "Okay thanks"
                                        
                                        // Task/completion questions
                                        latestMessageLower.contains("did you manage") || latestMessageLower.contains("did you sort") || 
                                        latestMessageLower.contains("did you get") || latestMessageLower.contains("have you sorted") ||
                                        latestMessageLower.contains("have you got") -> {
                                            if (latestMessageLower.contains("birthday") || latestMessageLower.contains("present")) {
                                                "Yeah I sorted it thanks"
                                            } else {
                                                "Yeah I did thanks"
                                            }
                                        }
                                        
                                        // Yes/No questions - read the actual question
                                        latestMessageLower.contains("are you") -> {
                                            when {
                                                latestMessageLower.contains("coming") && latestMessageLower.contains("dinner") -> 
                                                    "Yeah I'll be there"
                                                latestMessageLower.contains("coming") -> 
                                                    "Yeah I'll be there"
                                                latestMessageLower.contains("still") && latestMessageLower.contains("coming") -> 
                                                    "Yeah I'll be there"
                                                latestMessageLower.contains("birthday") || latestMessageLower.contains("present") -> 
                                                    "Yeah I sorted it thanks"
                                                else -> 
                                                    "Yeah I'm fine thanks"
                                            }
                                        }
                                        latestMessageLower.contains("did you") || latestMessageLower.contains("have you") -> {
                                            if (latestMessageLower.contains("birthday") || latestMessageLower.contains("present")) {
                                                "Yeah I sorted it thanks"
                                            } else if (latestMessageLower.contains("manage") || latestMessageLower.contains("sort") || latestMessageLower.contains("get")) {
                                                "Yeah I did thanks"
                                            } else {
                                                "Yeah I did thanks"
                                            }
                                        }
                                        latestMessageLower.contains("will you") || latestMessageLower.contains("can you") || 
                                        latestMessageLower.contains("could you") || latestMessageLower.contains("would you") ||
                                        latestMessageLower.contains("should you") || latestMessageLower.contains("is it") || 
                                        latestMessageLower.contains("was it") -> {
                                            if (latestMessageLower.contains("coming") || latestMessageLower.contains("be there")) {
                                                "Yeah I'll be there"
                                            } else {
                                                "Yeah I did thanks"
                                            }
                                        }
                                        
                                        // "What" questions - read the actual question (check most specific first)
                                        latestMessageLower.contains("what") -> {
                                            when {
                                                // Check for weekend question FIRST (more specific)
                                                latestMessageLower.contains("what you doing") && latestMessageLower.contains("weekend") -> 
                                                    "Not much, probably just relaxing"
                                                latestMessageLower.contains("what you doing") -> 
                                                    "Not much really"
                                                latestMessageLower.contains("what you") || latestMessageLower.contains("what have you") -> 
                                                    "Not much, just been busy. How about you?"
                                                else -> 
                                                    "Not sure, I'll check and let you know"
                                            }
                                        }
                                        
                                        // "How" questions
                                        latestMessageLower.contains("how") -> {
                                            if (latestMessageLower.contains("how are") || latestMessageLower.contains("how you")) {
                                                "I'm good thanks, how are you?"
                                            } else {
                                                "It's going okay thanks"
                                            }
                                        }
                                        
                                        // "When" questions
                                        latestMessageLower.contains("when") -> {
                                            "I'll let you know when I know"
                                        }
                                        
                                        // "Where" questions
                                        latestMessageLower.contains("where") -> {
                                            "I'm not sure, I'll check"
                                        }
                                        
                                        // "Why" questions
                                        latestMessageLower.contains("why") -> {
                                            "Not sure why, I'll find out"
                                        }
                                        
                                        // "Who" questions
                                        latestMessageLower.contains("who") -> {
                                            "I'm not sure who"
                                        }
                                        
                                        // Generic questions (has ?) - check this BEFORE the else
                                        latestMessageLower.contains("?") -> {
                                            "I'm not sure, I'll check and get back to you"
                                        }
                                        
                                        // If it's a question but didn't match above, give a generic question response
                                        isQuestion -> {
                                            "I'm not sure, I'll check and let you know"
                                        }
                                        
                                        // Requests (please, can you, get me, etc.) - acknowledge positively
                                        latestMessageLower.contains("please") || latestMessageLower.contains("can you") || 
                                        latestMessageLower.contains("get me") || latestMessageLower.contains("pick up") || 
                                        latestMessageLower.contains("grab") || latestMessageLower.contains("bring") -> {
                                            "Yes please"
                                        }
                                        
                                        // Statements - only use "Okay thanks" for non-questions
                                        else -> {
                                            "Okay thanks"
                                        }
                                    }
                                    
                                    if (fallbackResponse.isNotEmpty()) {
                                        AutoSendQueue.enqueue(context, address, fallbackResponse, AutoSendQueue.Source.AI, incomingMessageHash)
                                        Log.d(TAG, "Fallback response queued: $fallbackResponse")
                                        return true
                                    } else {
                                        // Fallback response was empty, continue
                                    }
                                } else {
                                    // Not using fallback, continue
                                }
                            } else {
                                // Latest message is empty, continue
                            }
                        }
                    } else {
                        // Consume error body to prevent resource leak
                        val errorBody = response.body?.string()
                        Log.e(TAG, "Backend request failed for $address: ${response.code}, error: $errorBody")
                    }
                } catch (e: Exception) {
                    // Ensure response body is consumed even on exception
                    try {
                        response.body?.close()
                    } catch (ignored: Exception) {}
                    throw e
                }
            }
        } catch (e: Exception) {
            // Check if it's a timeout exception - log but don't fail completely
            val isTimeout = e is java.net.SocketTimeoutException || 
                          e is java.net.ConnectException ||
                          e.message?.contains("timeout", ignoreCase = true) == true ||
                          e.message?.contains("timed out", ignoreCase = true) == true
            
            if (isTimeout) {
                Log.w(TAG, "Timeout exception checking if should respond: ${e.message}")
            } else {
            Log.e(TAG, "Error checking if should respond: ${e.message}", e)
            }
        }
        return false
    }
}

