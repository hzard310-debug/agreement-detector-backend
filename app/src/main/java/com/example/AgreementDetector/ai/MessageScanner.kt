package com.example.agreementdetector.ai

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
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
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

object MessageScanner {
    private const val TAG = "MessageScanner"
    @Volatile private var isScanning = false
    @Volatile private var pendingRescan = false
    private val scanLock = Any()
    
    // Status callback for UI updates
    interface StatusListener {
        fun onStatusUpdate(status: String)
        fun onQueueCountUpdate(count: Int)
    }
    
    private val statusListeners = mutableSetOf<StatusListener>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    
    fun addStatusListener(listener: StatusListener) {
        statusListeners.add(listener)
    }
    
    fun removeStatusListener(listener: StatusListener) {
        statusListeners.remove(listener)
    }
    
    private fun notifyStatus(status: String) {
        handler.post {
            statusListeners.forEach { it.onStatusUpdate(status) }
        }
    }
    
    private fun notifyQueueCount(count: Int) {
        handler.post {
            statusListeners.forEach { it.onQueueCountUpdate(count) }
        }
    }
    
    // Data class for unresponded messages to process in parallel
    private data class UnrespondedMessage(
        val address: String,
        val messageText: String,
        val messageHash: String,
        val conversationHistory: List<Map<String, String>>,
        val messageDate: Long = System.currentTimeMillis() // Store message date for sorting
    )
    
    fun scanAllMessages(context: Context, sinceTimestamp: Long? = null) {
        // Prevent multiple scans from running simultaneously
        synchronized(scanLock) {
            if (isScanning) {
                Log.d(TAG, "Scan already in progress, queuing rescan for after current scan completes")
                pendingRescan = true
                return
            }
            isScanning = true
            pendingRescan = false // Clear any pending rescan since we're starting a new one
        }
        
        Thread {
            // Acquire wake lock to keep device awake while scanning (even if screen is off)
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AgreementDetector::MessageScanning"
            )
            wakeLock?.acquire(1800000) // Hold for up to 30 minutes for large scans (2000+ messages)
            
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
                notifyStatus("📱 Reading messages from device...")
                
                val inbox = Telephony.Sms.Inbox.CONTENT_URI
                var totalScanned = 0
                var totalUnresponded = 0
                val totalQueued = AtomicInteger(0)
                
                // Scan messages - if sinceTimestamp is provided, only check new messages after that time
                // Otherwise, scan messages from the last 1 week for better performance
                // Collect all unresponded messages first (fast filtering, no backend calls)
                val unrespondedMessagesToProcess = mutableListOf<UnrespondedMessage>()
                
                // Determine the date filter
                val minDate = if (sinceTimestamp != null) {
                    Log.d(TAG, "Scanning only NEW messages received after ${sinceTimestamp} (since messages were sent)")
                    sinceTimestamp
                } else {
                    // Calculate date 1 week ago for full scan
                    val oneWeekAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
                    Log.d(TAG, "Full scan: Reading messages from last week")
                    oneWeekAgo
                }
                
                // Get inbox messages grouped by address
                val messagesByAddress = mutableMapOf<String, MutableList<Pair<String, Long>>>()
                
                try {
                    // Read inbox messages (filtered by date)
                    context.contentResolver.query(
                        inbox,
                        arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                        "${Telephony.Sms.DATE} >= ?", // Date filter
                        arrayOf(minDate.toString()),
                        "${Telephony.Sms.DATE} DESC"
                    )?.use { cursor ->
                        val scanType = if (sinceTimestamp != null) "NEW messages" else "last week"
                        Log.d(TAG, "Reading inbox messages ($scanType) (total: ${cursor.count})...")
                        while (cursor.moveToNext()) {
                            val address = cursor.getString(0) ?: continue
                            val body = cursor.getString(1) ?: continue
                            val date = cursor.getLong(2)
                            
                            // Add message
                            if (!messagesByAddress.containsKey(address)) {
                                messagesByAddress[address] = mutableListOf()
                            }
                            messagesByAddress[address]?.add(body to date)
                            totalScanned++
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading inbox messages: ${e.message}", e)
                    return@Thread
                }
                
                val scanType = if (sinceTimestamp != null) "NEW messages" else "last week"
                Log.i(TAG, "✓✓✓ Scanned $totalScanned inbox messages ($scanType) from ${messagesByAddress.size} contacts")
                
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                // CRITICAL: Always read fresh from database - don't rely on cached responded_messages set
                // We'll verify each message against the actual SMS database
                val respondedMessages = mutableSetOf<String>() // Start fresh - will verify against database
                
                // Read sent messages for conversation history (always get full history, not just recent)
                // We need full conversation context even when scanning for new messages
                Log.d(TAG, "Reading sent messages for conversation history...")
                val sentMessagesByAddress = mutableMapOf<String, MutableList<Pair<String, Long>>>() // address -> list of (text, date)
                var totalSentScanned = 0
                
                // For conversation history, always get messages from last week (full context)
                // The sinceTimestamp filter is only used for inbox messages (to find new messages to respond to)
                val conversationHistoryMinDate = if (sinceTimestamp != null) {
                    // When scanning for new messages, still get full conversation history from last week
                    System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
                } else {
                    minDate
                }
                
                try {
                    // Query sent messages (full conversation history from last week)
                    context.contentResolver.query(
                        Telephony.Sms.Sent.CONTENT_URI,
                        arrayOf(Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.ADDRESS),
                        "${Telephony.Sms.DATE} >= ?", // Date filter - full conversation history
                        arrayOf(conversationHistoryMinDate.toString()),
                        "${Telephony.Sms.DATE} ASC" // Oldest first for chronological checking
                    )?.use { cursor ->
                        Log.d(TAG, "Reading sent messages ($scanType) (total: ${cursor.count})...")
                        while (cursor.moveToNext()) {
                            val sentBody = cursor.getString(0) ?: continue
                            val sentDate = cursor.getLong(1)
                            val sentAddress = cursor.getString(2) ?: continue
                            
                            // Normalize address for grouping (use first occurrence as canonical)
                            var canonicalAddress: String? = null
                            
                            // Check if this address matches any existing address (inbox or sent)
                            for (existingAddress in messagesByAddress.keys) {
                                if (android.telephony.PhoneNumberUtils.compare(context, existingAddress, sentAddress)) {
                                    canonicalAddress = existingAddress
                                    break
                                }
                            }
                            
                            // If no match found in inbox, check sent addresses
                            if (canonicalAddress == null) {
                                for (existingAddress in sentMessagesByAddress.keys) {
                                    if (android.telephony.PhoneNumberUtils.compare(context, existingAddress, sentAddress)) {
                                        canonicalAddress = existingAddress
                                        break
                                    }
                                }
                            }
                            
                            // Use canonical address or create new entry
                            val addressToUse = canonicalAddress ?: sentAddress
                            
                            if (!sentMessagesByAddress.containsKey(addressToUse)) {
                                sentMessagesByAddress[addressToUse] = mutableListOf()
                            }
                            sentMessagesByAddress[addressToUse]?.add(sentBody to sentDate)
                            totalSentScanned++
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading sent messages: ${e.message}", e)
                }
                
                Log.i(TAG, "✓✓✓ Scanned $totalSentScanned sent messages ($scanType) from ${sentMessagesByAddress.size} contacts")
                
                // OPTIMIZATION: Only process contacts with inbox messages (they need responses)
                // Skip contacts where we only sent messages (we're waiting for their reply)
                val contactsToProcess = mutableSetOf<String>()
                contactsToProcess.addAll(messagesByAddress.keys)
                
                // Normalize addresses efficiently - only for contacts we need to process
                val normalizedAddresses = mutableMapOf<String, String>() // normalized -> canonical
                for (address in contactsToProcess) {
                    var found = false
                    for (existing in normalizedAddresses.keys) {
                        if (android.telephony.PhoneNumberUtils.compare(context, existing, address)) {
                            found = true
                            break
                        }
                    }
                    if (!found) {
                        normalizedAddresses[address] = address
                    }
                }
                
                val totalContacts = normalizedAddresses.size
                val totalMessages = totalScanned + totalSentScanned
                
                Log.d(TAG, "=== SCANNING ALL MESSAGES: $totalMessages total messages (ALL messages) from $totalContacts contacts ===")
                
                // Track conversation states
                var contactsWaitingForReply = 0
                var contactsNeedingResponse = 0
                var contactsProcessed = 0
                
                // Process EVERY contact's conversation - scan ALL conversations on the phone
                // Use normalized addresses to ensure we process every unique conversation
                for (canonicalAddress in normalizedAddresses.keys) {
                    // Get inbox messages for this contact (may be empty if only sent messages)
                    val messages = messagesByAddress[canonicalAddress] ?: mutableListOf()
                    
                    // Get sent messages for this contact (may be empty if only inbox messages)
                    val sentMessagesWithDates = sentMessagesByAddress[canonicalAddress] ?: mutableListOf()
                    
                    Log.d(TAG, "Processing contact $canonicalAddress: ${messages.size} inbox messages, ${sentMessagesWithDates.size} sent messages")
                    
                    // OPTIMIZATION: Check last message FIRST to instantly skip conversations where we're waiting for a reply
                    // This makes scanning instant for conversations that don't need responses
                    
                    // If no inbox messages, we're waiting for a reply - skip instantly
                    if (messages.isEmpty()) {
                        contactsWaitingForReply++
                        Log.d(TAG, "STATUS: No inbox messages - we're waiting for a reply, skipping contact instantly")
                        continue
                    }
                    
                    // Quickly find the last message timestamp from both inbox and sent
                    val lastInboxDate = messages.maxOfOrNull { it.second } ?: 0L
                    val lastSentDate = sentMessagesWithDates.maxOfOrNull { it.second } ?: 0L
                    
                    // If we sent the last message (sent date is more recent), skip instantly - we're waiting for their reply
                    if (lastSentDate > lastInboxDate) {
                        contactsWaitingForReply++
                        Log.d(TAG, "STATUS: We sent the last message (sent: $lastSentDate > inbox: $lastInboxDate) - waiting for reply, skipping contact instantly")
                        continue // Skip this entire conversation - we're waiting for them to respond
                    }
                    
                    // They sent the last message - we need to respond, continue processing
                    Log.d(TAG, "Found ${sentMessagesWithDates.size} sent messages to $canonicalAddress in SMS database")
                    
                    // If THEY sent the last message, we NEED TO RESPOND
                    contactsNeedingResponse++
                    
                    // CRITICAL: Find our most recent sent message to determine what we should respond to
                    val sortedSentMessages = sentMessagesWithDates.sortedByDescending { it.second } // Newest first
                    val ourMostRecentSentDate = sortedSentMessages.firstOrNull()?.second ?: 0L
                    val ourMostRecentSentText = sortedSentMessages.firstOrNull()?.first ?: ""
                    
                    Log.i(TAG, "Our most recent message to $canonicalAddress: '$ourMostRecentSentText' (date: $ourMostRecentSentDate)")
                    
                    // Find the most recent message THEY sent AFTER our most recent message
                    // Sort their messages by date (newest first)
                    val sortedInboxMessages = messages.sortedByDescending { it.second } // Newest first
                    
                    var messageToRespondTo: UnrespondedMessage? = null
                    
                    // Scan through their messages (newest first) to find the first one AFTER our most recent message
                    for ((messageText, messageDate) in sortedInboxMessages) {
                        // Only consider messages that came AFTER our most recent sent message
                        if (ourMostRecentSentDate > 0 && messageDate <= ourMostRecentSentDate) {
                            Log.d(TAG, "Skipping message from $canonicalAddress (date: $messageDate) - it came before or at the same time as our most recent message (date: $ourMostRecentSentDate)")
                            continue
                        }
                        
                        val messageHash = hashMessage(canonicalAddress, messageText)
                        
                        // Check if we already responded to this specific message
                        val maxResponseTime = messageDate + 86400000L // 24 hours
                        var hasResponseAfter = false
                        
                        // Check if we sent a message AFTER this incoming message
                        for ((sentText, sentDate) in sentMessagesWithDates) {
                            if (sentDate < messageDate - 86400000L) continue // Too old
                            if (sentDate > maxResponseTime) break // Too new
                            if (sentDate > messageDate + 1000) { // Response after incoming (1 second buffer)
                                hasResponseAfter = true
                                Log.d(TAG, "Already responded to message from $canonicalAddress (their message: $messageDate, our response: $sentDate)")
                                break
                            }
                        }
                        
                        // If already responded, skip this message
                        if (hasResponseAfter) {
                            respondedMessages.add(messageHash)
                            continue
                        }
                        
                        // Check if we've already queued a response for this contact in this scan
                        // Only process ONE message per contact to prevent sending multiple messages
                        val alreadyQueuedForContact = unrespondedMessagesToProcess.any { 
                            android.telephony.PhoneNumberUtils.compare(context, it.address, canonicalAddress) 
                        }
                        if (alreadyQueuedForContact) {
                            Log.d(TAG, "Already queued a response for $canonicalAddress in this scan - skipping additional messages from this contact")
                            continue
                        }
                        
                        // Let AI backend decide what to ignore and what to send - no client-side filtering
                        // Found the message to respond to - this is the most recent message from them AFTER our last message
                        totalUnresponded++
                        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        Log.i(TAG, "FOUND MESSAGE TO RESPOND TO from $canonicalAddress:")
                        Log.i(TAG, "  Their message: '$messageText' (date: $messageDate)")
                        Log.i(TAG, "  Our last message: '$ourMostRecentSentText' (date: $ourMostRecentSentDate)")
                        Log.i(TAG, "  Time difference: ${messageDate - ourMostRecentSentDate}ms")
                        
                        // Build FULL conversation history (scan whole conversation for clarity)
                        // Always include ALL messages from last week for full context, even when scanning for new messages
                        // Get full conversation history (not just messages since timestamp)
                        val fullInboxMessages = if (sinceTimestamp != null) {
                            // When scanning for new messages, we need full conversation history
                            // Re-query inbox messages from last week for this contact
                            val fullHistoryMinDate = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
                            val fullHistory = mutableListOf<Pair<String, Long>>()
                            try {
                                context.contentResolver.query(
                                    inbox,
                                    arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                                    "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.DATE} >= ?",
                                    arrayOf(canonicalAddress, fullHistoryMinDate.toString()),
                                    "${Telephony.Sms.DATE} ASC"
                                )?.use { cursor ->
                                    while (cursor.moveToNext()) {
                                        val addr = cursor.getString(0) ?: continue
                                        val body = cursor.getString(1) ?: continue
                                        val date = cursor.getLong(2)
                                        if (android.telephony.PhoneNumberUtils.compare(context, addr, canonicalAddress)) {
                                            fullHistory.add(body to date)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error reading full inbox history: ${e.message}", e)
                                messages // Fallback to filtered messages
                            }
                            fullHistory.ifEmpty { messages }
                        } else {
                            messages
                        }
                        
                        val conversationHistory = buildConversationHistoryUpToMessage(
                            fullInboxMessages,
                            sentMessagesWithDates,
                            messageText,
                            messageDate
                        )
                        
                        Log.i(TAG, "  Conversation history: ${conversationHistory.size} turns (full context for AI)")
                        
                        // Create UnrespondedMessage for this message
                        messageToRespondTo = UnrespondedMessage(
                            address = canonicalAddress,
                            messageText = messageText,
                            messageHash = messageHash,
                            conversationHistory = conversationHistory,
                            messageDate = messageDate
                        )
                        
                        // Found the message to respond to - stop checking older messages
                            break
                    }
                    
                    // Add message to processing list if found
                    if (messageToRespondTo != null) {
                        unrespondedMessagesToProcess.add(messageToRespondTo)
                        Log.i(TAG, "✓ Added message from $canonicalAddress to processing queue")
                        Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                        } else {
                        if (ourMostRecentSentDate > 0) {
                            Log.d(TAG, "No messages found from $canonicalAddress AFTER our most recent message (date: $ourMostRecentSentDate)")
                        } else {
                            Log.d(TAG, "No unresponded messages found for $canonicalAddress (all messages have been responded to or skipped)")
                        }
                    }
                    
                    // Update the responded_messages set
                    if (respondedMessages.isNotEmpty()) {
                        prefs.edit().putStringSet("responded_messages", respondedMessages).apply()
                    }
                    
                    contactsProcessed++
                    if (contactsProcessed % 10 == 0) {
                        Log.d(TAG, "Progress: Processed $contactsProcessed/${messagesByAddress.size} contacts...")
                    }
                }
                
                Log.d(TAG, "Finished processing all ${messagesByAddress.size} contacts")
                notifyStatus("✅ Scan complete: ${messagesByAddress.size} contacts processed")
                
                // ALWAYS limit the number of messages processed to prevent sending too many at once
                // Sort by date (most recent first) and take only the most recent messages
                val sortedMessages = unrespondedMessagesToProcess.sortedByDescending { it.messageDate }
                
                // HARD LIMIT: Never process more than 30 messages per scan (one per contact)
                // This prevents sending hundreds of messages at once
                val MAX_MESSAGES_PER_SCAN = 30
                
                val messagesToProcess = if (sinceTimestamp != null) {
                    // When scanning for new messages, filter to only messages received after timestamp
                    // Also ensure we only have one message per contact
                    val uniqueContacts = mutableSetOf<String>()
                    val filtered = sortedMessages.filter { message ->
                        val normalized = android.telephony.PhoneNumberUtils.normalizeNumber(message.address) ?: message.address
                        message.messageDate >= sinceTimestamp && uniqueContacts.add(normalized)
                    }.take(MAX_MESSAGES_PER_SCAN) // HARD LIMIT: 30 contacts max
                    Log.i(TAG, "Scanning for NEW messages: Found ${unrespondedMessagesToProcess.size} total, filtered to ${filtered.size} messages after timestamp (one per contact, max ${MAX_MESSAGES_PER_SCAN} per scan)")
                    if (unrespondedMessagesToProcess.size > MAX_MESSAGES_PER_SCAN) {
                        Log.w(TAG, "⚠️ WARNING: Found ${unrespondedMessagesToProcess.size} unresponded messages but limiting to ${MAX_MESSAGES_PER_SCAN} to prevent sending too many at once")
                    }
                    filtered
                } else {
                    // Full scan: limit to 30 most recent unresponded messages to prevent processing hundreds
                    // Also ensure we only have one message per contact
                    val uniqueContacts = mutableSetOf<String>()
                    val limited = sortedMessages.filter { message ->
                        val normalized = android.telephony.PhoneNumberUtils.normalizeNumber(message.address) ?: message.address
                        if (uniqueContacts.add(normalized)) {
                            true // First message from this contact
                        } else {
                            false // Already have a message from this contact
                        }
                    }.take(MAX_MESSAGES_PER_SCAN) // HARD LIMIT: 30 contacts max
                    Log.i(TAG, "Full scan: Found ${unrespondedMessagesToProcess.size} unresponded messages, limiting to ${limited.size} most recent (one per contact, max ${MAX_MESSAGES_PER_SCAN} per scan)")
                    if (unrespondedMessagesToProcess.size > MAX_MESSAGES_PER_SCAN) {
                        Log.w(TAG, "⚠️ WARNING: Found ${unrespondedMessagesToProcess.size} unresponded messages but limiting to ${MAX_MESSAGES_PER_SCAN} to prevent sending too many at once")
                    }
                    limited
                }
                
                // FINAL SAFETY CHECK: Ensure we never process more than the limit
                val finalMessagesToProcess = messagesToProcess.take(MAX_MESSAGES_PER_SCAN)
                if (finalMessagesToProcess.size < messagesToProcess.size) {
                    Log.w(TAG, "⚠️ SAFETY CHECK: Further limited from ${messagesToProcess.size} to ${finalMessagesToProcess.size} messages to enforce hard limit")
                }
                
                // Process messages ONE AT A TIME (sequential processing)
                if (finalMessagesToProcess.isNotEmpty()) {
                    Log.i(TAG, "Processing ${finalMessagesToProcess.size} unresponded messages ONE AT A TIME (sequential)...")
                    notifyStatus("📤 Processing ${finalMessagesToProcess.size} messages...")
                    notifyQueueCount(finalMessagesToProcess.size)
                    
                    val startTime = System.currentTimeMillis()
                    
                    // Process each message sequentially - one number at a time
                    for ((index, unrespondedMsg) in finalMessagesToProcess.withIndex()) {
                        try {
                            Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                            Log.i(TAG, "Processing message ${index + 1}/${finalMessagesToProcess.size} from ${unrespondedMsg.address}")
                            Log.i(TAG, "Message text: ${unrespondedMsg.messageText.take(100)}...")
                            
                            val shouldRespond = checkIfShouldRespond(
                                context,
                                unrespondedMsg.address,
                                unrespondedMsg.conversationHistory,
                                unrespondedMsg.messageText,
                                unrespondedMsg.messageHash
                            )
                            
                            if (shouldRespond) {
                                totalQueued.incrementAndGet()
                                val remaining = finalMessagesToProcess.size - (index + 1)
                                Log.i(TAG, "✓✓✓ RESPONSE QUEUED for ${unrespondedMsg.address} (${totalQueued.get()} total queued)")
                                notifyStatus("💬 Queued response ${index + 1}/${finalMessagesToProcess.size} (${remaining} remaining)")
                                notifyQueueCount(remaining)
                            } else {
                                Log.d(TAG, "No response needed for ${unrespondedMsg.address}")
                            }
                            
                            // Reduced delay between messages to speed up processing
                            if (index < finalMessagesToProcess.size - 1) {
                                Thread.sleep(500) // 500ms delay between messages (reduced from 1 second)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "✗✗✗ ERROR processing message from ${unrespondedMsg.address}: ${e.message}", e)
                            // Continue with next message even if this one fails
                        }
                    }
                    
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.i(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Log.i(TAG, "Sequential processing complete: ${totalQueued.get()} responses sent in ${elapsed}ms")
                    notifyStatus("✅ Processing complete: ${totalQueued.get()} responses queued")
                    notifyQueueCount(0)
                } else {
                    Log.d(TAG, "No unresponded messages found - all messages have been responded to")
                    notifyStatus("✅ No messages require responses")
                    notifyQueueCount(0)
                }
                
                // Summary of conversation states
                val scanDuration = System.currentTimeMillis() - (wakeLock?.let { 
                    // Approximate duration (wake lock was acquired at start)
                    System.currentTimeMillis() 
                } ?: System.currentTimeMillis())
                Log.d(TAG, "=== SCAN COMPLETE ===")
                Log.d(TAG, "Total messages scanned: $totalScanned")
                Log.d(TAG, "Unresponded messages found: $totalUnresponded")
                Log.d(TAG, "Responses queued: ${totalQueued.get()}")
                Log.d(TAG, "Contacts needing responses: $contactsNeedingResponse")
                Log.d(TAG, "Contacts waiting for replies: $contactsWaitingForReply")
                if (totalScanned > 1000) {
                    Log.d(TAG, "Large scan completed successfully - processed $totalScanned messages")
                }
                
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
                    // If a rescan was requested while we were scanning, trigger it now
                    if (pendingRescan) {
                        pendingRescan = false
                        Log.d(TAG, "Pending rescan detected - triggering new scan after current scan completed")
                        // Trigger rescan on a new thread to avoid blocking
                        Thread {
                            Thread.sleep(500) // Small delay to ensure current scan is fully finished
                            scanAllMessages(context)
                        }.start()
                    }
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
    
    /**
     * Check if message should be skipped (filtering only - no response generation)
     * Returns true if message should be skipped, false if it should be sent to AI
     */
    private fun shouldSkipMessage(messageText: String, address: String): Boolean {
        val lower = messageText.lowercase().trim()
        
        // Skip messages that are just punctuation (like "?")
        if (messageText.trim().matches(Regex("^[?!.,;:]+$"))) {
            Log.d(TAG, "Skipping message that is just punctuation: $messageText")
            return true
        }
        
        // Skip rude/inappropriate messages
        val rudeWords = listOf(
            "fuck", "fuck off", "fuck you", "fucking", "shit", "damn", "bitch", "bastard",
            "piss off", "piss", "crap", "hell", "asshole", "dick", "cock", "pussy",
            "cunt", "wanker", "twat", "tosser", "bellend", "arse", "arsehole"
        )
        val isRude = rudeWords.any { lower.contains(it) }
        if (isRude) {
            Log.d(TAG, "Skipping rude/inappropriate message: $messageText")
            return true
        }
        
        // Skip messages that indicate they don't believe we are their son/daughter or rejection messages
        val disbeliefKeywords = listOf(
            "my dead child", "dead child", "my child is dead", "child is dead",
            "you're not my", "you are not my", "not my son", "not my daughter",
            "not my child", "don't have a son", "don't have a daughter", "don't have a child",
            "i don't have a son", "i don't have a daughter", "i don't have a child",
            "who is this really", "who are you really", "this is a scam", "this is scam",
            "scammer", "scam", "fraud", "fake", "liar", "lying", "you're lying",
            "you are lying", "this is fake", "not my kid", "not my kids",
            "go away im not dad", "go away im not ur dad", "go away im not your dad",
            "go away i'm not dad", "go away i'm not ur dad", "go away i'm not your dad",
            "im not dad", "i'm not dad", "im not ur dad", "i'm not ur dad",
            "im not your dad", "i'm not your dad", "not dad", "not ur dad", "not your dad",
            "go away", "leave me alone", "stop messaging", "stop texting"
        )
        val isDisbelief = disbeliefKeywords.any { lower.contains(it) }
        if (isDisbelief) {
            Log.d(TAG, "Skipping message indicating disbelief in relationship or rejection: $messageText")
            return true
        }
        
        // Skip automated/system messages (like O2UK balance, service provider messages, etc.)
        val isSystemMessage = lower.contains("balance") || lower.contains("top up") || 
                              lower.contains("call 4444") || lower.contains("your balance") ||
                              lower.contains("configure your number") || lower.contains("sms url") ||
                              lower.contains("reply help for help") || lower.contains("reply stop to unsubscribe") ||
                              lower.contains("msg&data rates may apply") || lower.contains("msg and data rates") ||
                              lower.contains("thanks for the message") && lower.contains("configure") ||
                              address.equals("O2UK", ignoreCase = true) ||
                              address.matches(Regex("^[A-Z0-9]+$")) // All caps/numbers = likely system
        if (isSystemMessage) {
            Log.d(TAG, "Skipping system/automated message from $address: $messageText")
            return true
        }
        
        // Don't skip - send to AI for response
        return false
    }
    
    private fun buildConversationHistoryUpToMessage(
        inboxMessages: List<Pair<String, Long>>, // text, date
        sentMessages: List<Pair<String, Long>>, // text, date
        targetMessage: String,
        targetMessageDate: Long
    ): List<Map<String, String>> {
        // Include ALL messages for full context - scan EVERY SINGLE MESSAGE in the conversation
        // No limits, no filtering - AI needs complete context to respond appropriately
        val allMessages = mutableListOf<Triple<Long, String, String>>() // date, role, text
        
        // Add inbox messages (from them)
        for ((text, date) in inboxMessages) {
            allMessages.add(Triple(date, "them", text))
        }
        
        // Add sent messages (from you)
        for ((text, date) in sentMessages) {
            allMessages.add(Triple(date, "you", text))
        }
        
        // Sort by date to get chronological order
        allMessages.sortBy { it.first }
        
        // Include ALL messages for full context (scan whole conversation for clarity)
        // This ensures AI has complete context to respond appropriately
        val turns = mutableListOf<Map<String, String>>()
        for ((date, role, text) in allMessages) {
            turns.add(mapOf("role" to role, "text" to text))
        }
        
        // Ensure target message is included (it should already be in allMessages, but add if missing)
        val targetFound = turns.any { it["role"] == "them" && it["text"] == targetMessage }
        if (!targetFound) {
            turns.add(mapOf("role" to "them", "text" to targetMessage))
        }
        
        Log.d(TAG, "Built FULL conversation history with ${turns.size} turns (scanning whole conversation for clarity)")
        
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
            
            // Log request details for debugging
            val requestStartTime = System.currentTimeMillis()
            Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            Log.d(TAG, "📤 STARTING BACKEND REQUEST for $address")
            Log.d(TAG, "  URL: $url")
            Log.d(TAG, "  Conversation turns: ${turns.size}")
            Log.d(TAG, "  Latest message: ${latestMessage.take(50)}...")
            
            val client = OkHttpClient.Builder()
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS) // Increased to 120s - backend can take 60s+ (Render cold starts)
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS) // 30s for slow connections
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS) // 60s for large payloads
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
            
            // Log request payload size
            val requestBodySize = requestBody.length
            Log.d(TAG, "  Request body size: $requestBodySize bytes (${requestBodySize / 1024} KB)")
            if (requestBodySize > 100000) {
                Log.w(TAG, "  ⚠️ WARNING: Large request payload (${requestBodySize / 1024} KB) - may cause timeout")
            }
            
            val request = Request.Builder()
                .url(url)
                .post(RequestBody.create("application/json".toMediaType(), requestBody))
                .build()
            
            // Check network connectivity
            try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val network = connectivityManager?.activeNetwork
                val capabilities = connectivityManager?.getNetworkCapabilities(network)
                val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val hasWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                val hasCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                Log.d(TAG, "  Network status: Internet=$hasInternet, WiFi=$hasWifi, Cellular=$hasCellular")
                if (!hasInternet) {
                    Log.w(TAG, "  ⚠️ WARNING: No internet connection detected!")
                }
            } catch (e: Exception) {
                Log.w(TAG, "  Could not check network status: ${e.message}")
            }
            
            // Retry logic for 502 Bad Gateway and timeouts (up to 3 retries)
            var lastException: Exception? = null
            var retryCount = 0
            val maxRetries = 3
            
            var result = false
            while (retryCount <= maxRetries) {
                var shouldRetry = false
                val attemptStartTime = System.currentTimeMillis()
                try {
                    Log.d(TAG, "  Attempt ${retryCount + 1}/${maxRetries + 1}: Connecting to backend...")
                    val responseResult = client.newCall(request).execute().use { response ->
                        val connectTime = System.currentTimeMillis() - attemptStartTime
                        Log.d(TAG, "  ✓ Connected in ${connectTime}ms, reading response...")
                        try {
                if (response.isSuccessful) {
                                val readStartTime = System.currentTimeMillis()
                                val body = response.body?.string()
                                val readTime = System.currentTimeMillis() - readStartTime
                                val totalTime = System.currentTimeMillis() - attemptStartTime
                                
                                if (body == null) {
                                    // Body is null, ensure it's closed
                                    try {
                                        response.body?.close()
                                    } catch (ignored: Exception) {}
                                    return@use false
                                }
                    val json = JSONObject(body)
                    val action = json.getString("action")
                    val messageToSend = json.getString("response")
                    val reasoning = json.optString("reasoning", "")
                    
                    Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Log.d(TAG, "✓ BACKEND RESPONSE RECEIVED for $address")
                    Log.d(TAG, "  Connect time: ${connectTime}ms")
                    Log.d(TAG, "  Read time: ${readTime}ms")
                    Log.d(TAG, "  Total time: ${totalTime}ms")
                    Log.d(TAG, "  Action: $action")
                    Log.d(TAG, "  Response length: ${messageToSend.length} chars")
                    Log.d(TAG, "  Reasoning: ${reasoning.take(100)}...")
                    
                    if (action == "SEND" && messageToSend.isNotEmpty()) {
                        Log.d(TAG, "Backend says SEND for $address: $messageToSend")
                                    // AI chose the response - queue it for sending (reliable method)
                                    try {
                        AutoSendQueue.enqueue(context, address, messageToSend, AutoSendQueue.Source.AI, incomingMessageHash)
                                        Log.i(TAG, "✓✓✓ MESSAGE QUEUED FOR SENDING: '$messageToSend' to $address (hash: $incomingMessageHash)")
                                        notifyStatus("📨 Queued message to ${address.takeLast(4)}")
                                        return@use true
                                    } catch (e: Exception) {
                                        Log.e(TAG, "✗ FAILED TO QUEUE MESSAGE: Error queuing '$messageToSend' to $address: ${e.message}", e)
                                        return@use false
                                    }
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
                                    try {
                                        AutoSendQueue.enqueue(context, address, macbookResponse, AutoSendQueue.Source.AI, incomingMessageHash)
                                        Log.i(TAG, "✓✓✓ Payment paragraph question - message queued: $macbookResponse")
                                        return@use true
        } catch (e: Exception) {
                                        Log.e(TAG, "Failed to queue macbook response: ${e.message}", e)
                                        return@use false
                                    }
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
                                        try {
                                            AutoSendQueue.enqueue(context, address, fallbackResponse, AutoSendQueue.Source.AI, incomingMessageHash)
                                            Log.i(TAG, "✓✓✓ Fallback response queued: $fallbackResponse")
                                            return@use true
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to queue fallback response: ${e.message}", e)
                                            return@use false
                                        }
                                    } else {
                                        // Fallback response was empty, continue
                                        return@use false
                                    }
                                    } else {
                                        // Not using fallback, continue
                                        return@use false
                                    }
                                } else {
                                    // Latest message is empty, continue
                                    return@use false
                                }
                                
                                    // Request succeeded (even if NO_SEND), no retry needed
                                    return@use false
                    }
                } else {
                                // Consume error body to prevent resource leak
                                val errorBody = response.body?.string()
                                val is502 = response.code == 502
                                val is503 = response.code == 503
                                val is504 = response.code == 504
                                
                                if ((is502 || is503 || is504) && retryCount < maxRetries) {
                                    // Server error - signal to retry
                                    shouldRetry = true
                                    retryCount++
                                    val delayMs = (1000L * (1 shl retryCount)).coerceAtMost(10000L) // Max 10s delay
                                    Log.w(TAG, "Backend returned ${response.code} for $address - retrying in ${delayMs}ms (attempt $retryCount/$maxRetries)")
                                    Thread.sleep(delayMs)
                                    return@use false
                                } else {
                                    Log.e(TAG, "Backend request failed for $address: ${response.code}, error: ${errorBody?.take(200)}")
                                    return@use false
                }
            }
        } catch (e: Exception) {
                            // Ensure response body is consumed even on exception
                            try {
                                response.body?.close()
                            } catch (ignored: Exception) {}
                            throw e
                        }
                    }
                    
                    // If we got a result (not a retry), return it
                    if (!shouldRetry) {
                        result = responseResult
                        if (result) return true
        return false
                    }
                    // Otherwise continue loop to retry
                } catch (e: Exception) {
                    val attemptDuration = System.currentTimeMillis() - attemptStartTime
                    val totalDuration = System.currentTimeMillis() - requestStartTime
                    
                    // Identify specific timeout type based on exception and duration
                    val timeoutType = when {
                        e is java.net.SocketTimeoutException -> {
                            when {
                                e.message?.contains("connect", ignoreCase = true) == true -> "CONNECT_TIMEOUT (30s)"
                                e.message?.contains("read", ignoreCase = true) == true -> "READ_TIMEOUT (60s)"
                                e.message?.contains("write", ignoreCase = true) == true -> "WRITE_TIMEOUT (60s)"
                                // Use duration to determine timeout type if message is generic
                                attemptDuration >= 115000 && attemptDuration <= 125000 -> "READ_TIMEOUT (120s) - Backend taking too long to respond"
                                attemptDuration >= 55000 && attemptDuration <= 65000 -> "READ_TIMEOUT (60s) - Backend taking too long (old timeout)"
                                attemptDuration >= 25000 && attemptDuration <= 35000 -> "CONNECT_TIMEOUT (30s) - Can't connect to server"
                                attemptDuration < 5000 -> "WRITE_TIMEOUT (60s) - Can't send request"
                                else -> "UNKNOWN_TIMEOUT (duration: ${attemptDuration}ms)"
                            }
                        }
                        e is java.net.ConnectException -> "CONNECTION_ERROR - No internet or server unreachable"
                        e.message?.contains("timeout", ignoreCase = true) == true -> {
                            // Use duration to determine which timeout
                            when {
                                attemptDuration >= 115000 && attemptDuration <= 125000 -> "READ_TIMEOUT (120s) - Backend taking too long"
                                attemptDuration >= 55000 && attemptDuration <= 65000 -> "READ_TIMEOUT (60s) - Backend taking too long (old timeout)"
                                attemptDuration >= 25000 && attemptDuration <= 35000 -> "CONNECT_TIMEOUT (30s) - Can't connect"
                                else -> "TIMEOUT (duration: ${attemptDuration}ms)"
                            }
                        }
                        e.message?.contains("timed out", ignoreCase = true) == true -> "TIMED_OUT"
                        else -> "OTHER_ERROR"
                    }
                    
                    Log.e(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    Log.e(TAG, "✗ REQUEST FAILED for $address")
                    Log.e(TAG, "  Error type: ${e.javaClass.simpleName}")
                    Log.e(TAG, "  Timeout type: $timeoutType")
                    Log.e(TAG, "  Error message: ${e.message}")
                    Log.e(TAG, "  Attempt duration: ${attemptDuration}ms")
                    Log.e(TAG, "  Total duration: ${totalDuration}ms")
                    Log.e(TAG, "  Request body size: $requestBodySize bytes")
                    Log.e(TAG, "  Conversation turns: ${turns.size}")
                    
                    // Diagnostic information based on timeout type
                    if (timeoutType.contains("READ_TIMEOUT")) {
                        Log.w(TAG, "  🔍 DIAGNOSIS: Backend is taking >120s to respond")
                        Log.w(TAG, "     Possible causes:")
                        Log.w(TAG, "     - Render free tier cold start (server sleeping - can take 60s+)")
                        Log.w(TAG, "     - Backend processing slowly (AI API delays)")
                        Log.w(TAG, "     - Backend overloaded")
                        Log.w(TAG, "     - Network latency")
                        Log.w(TAG, "     NOTE: Timeout increased to 120s to handle Render cold starts")
                    } else if (timeoutType.contains("CONNECT_TIMEOUT")) {
                        Log.w(TAG, "  🔍 DIAGNOSIS: Can't connect to backend")
                        Log.w(TAG, "     Possible causes:")
                        Log.w(TAG, "     - No internet connection")
                        Log.w(TAG, "     - Backend server down")
                        Log.w(TAG, "     - Firewall blocking connection")
                    } else if (timeoutType.contains("WRITE_TIMEOUT")) {
                        Log.w(TAG, "  🔍 DIAGNOSIS: Can't send request")
                        Log.w(TAG, "     Possible causes:")
                        Log.w(TAG, "     - Request payload too large")
                        Log.w(TAG, "     - Network upload speed too slow")
                    }
                    
                    // Check if it's a timeout or connection error that we should retry
                    val isRetryable = e is java.net.SocketTimeoutException || 
                                    e is java.net.ConnectException ||
                                    e.message?.contains("timeout", ignoreCase = true) == true ||
                                    e.message?.contains("timed out", ignoreCase = true) == true
                    
                    if (isRetryable && retryCount < maxRetries) {
                        retryCount++
                        val delayMs = (1000L * (1 shl retryCount)).coerceAtMost(10000L) // Max 10s delay
                        Log.w(TAG, "  → Retrying in ${delayMs}ms (attempt $retryCount/$maxRetries)")
                        lastException = e
                        Thread.sleep(delayMs)
                        // Continue loop to retry
                    } else {
                        // Not retryable or max retries reached
                        Log.e(TAG, "  → Max retries reached or non-retryable error - giving up")
                        lastException = e
                        break // Exit retry loop
                    }
                }
            }
            
            // If we exhausted retries, return false
            if (retryCount > maxRetries && lastException != null) {
                Log.e(TAG, "Max retries ($maxRetries) exceeded for $address: ${lastException?.message}")
                return false
            }
        } catch (e: Exception) {
            // Check if it's a timeout exception - log but don't fail completely
            val isTimeout = e is java.net.SocketTimeoutException || 
                          e is java.net.ConnectException ||
                          e.message?.contains("timeout", ignoreCase = true) == true ||
                          e.message?.contains("timed out", ignoreCase = true) == true
            
            if (isTimeout) {
                Log.w(TAG, "Timeout exception checking if should respond for $address: ${e.message}")
                // Don't retry on timeout - backend is likely overloaded, skip this message
            } else {
                Log.e(TAG, "Error checking if should respond for $address: ${e.message}", e)
            }
        }
        return false
    }
    
    /**
     * Send SMS directly (AI chooses response, but we send it directly without queue)
     */
    private fun sendSmsDirectly(context: Context, address: String, text: String, incomingMessageHash: String?) {
        try {
            Log.i(TAG, "→→ PREPARING TO SEND SMS: '$text' to $address (hash: $incomingMessageHash)")
            
            // Get SMS manager
            val smsManager = try {
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val preferred = prefs.getInt("preferred_subid", SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                val subMgr = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val subId = if (preferred != SubscriptionManager.INVALID_SUBSCRIPTION_ID) preferred else SubscriptionManager.getDefaultSmsSubscriptionId()
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    Log.d(TAG, "Using SMS manager for subscription ID: $subId")
                    SmsManager.getSmsManagerForSubscriptionId(subId)
                } else {
                    Log.d(TAG, "Using default SMS manager")
                    SmsManager.getDefault()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error getting SMS manager, using default: ${e.message}")
                SmsManager.getDefault()
            }
            
            // Create unique request code for this message to avoid conflicts
            val requestCode = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            
            // Create sent intent to track delivery
            val sentIntent = PendingIntent.getBroadcast(
                context.applicationContext,
                requestCode,
                Intent("SMS_SENT").apply {
                    putExtra("addr", address)
                    putExtra("text", text)
                    if (incomingMessageHash != null) {
                        putExtra("incoming_message_hash", incomingMessageHash)
                    }
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            val deliveredIntent = PendingIntent.getBroadcast(
                context.applicationContext,
                requestCode + 1000,
                Intent("SMS_DELIVERED"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            Log.d(TAG, "Created PendingIntents for SMS send (requestCode: $requestCode)")
            
            // Send message (with 2 second delay as requested)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    Log.i(TAG, "→→ ATTEMPTING TO SEND SMS NOW: '$text' to $address")
                    
                    val parts = smsManager.divideMessage(text)
                    if (parts != null && parts.size > 1) {
                        // Multipart message
                        Log.d(TAG, "Message will be sent as multipart (${parts.size} parts)")
                        val sentIntents = ArrayList<PendingIntent>(parts.size).apply {
                            repeat(parts.size) { add(sentIntent) }
                        }
                        val deliveredIntents = ArrayList<PendingIntent>(parts.size).apply {
                            repeat(parts.size) { add(deliveredIntent) }
                        }
                        try {
                            smsManager.sendMultipartTextMessage(address, null, parts, sentIntents, deliveredIntents)
                            Log.i(TAG, "✓✓✓ SMS SEND CALLED (multipart ${parts.size} parts): '$text' to $address")
                            Log.i(TAG, "✓✓✓ VERIFICATION: sendMultipartTextMessage() method was successfully called")
                        } catch (e: Exception) {
                            Log.e(TAG, "✗✗✗ EXCEPTION IN sendMultipartTextMessage CALL: ${e.message}", e)
                            throw e
                        }
                    } else {
                        // Single part message
                        Log.d(TAG, "Message will be sent as single part")
                        try {
                            smsManager.sendTextMessage(address, null, text, sentIntent, deliveredIntent)
                            Log.i(TAG, "✓✓✓ SMS SEND CALLED (single part): '$text' to $address")
                            Log.i(TAG, "✓✓✓ VERIFICATION: sendTextMessage() method was successfully called")
                        } catch (e: Exception) {
                            Log.e(TAG, "✗✗✗ EXCEPTION IN sendTextMessage CALL: ${e.message}", e)
                            throw e
                        }
                    }
                    
                    // Mark incoming message as responded immediately (before confirmation)
                    if (incomingMessageHash != null) {
                        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                        val respondedMessages = prefs.getStringSet("responded_messages", null)?.toMutableSet() ?: mutableSetOf()
                        respondedMessages.add(incomingMessageHash)
                        prefs.edit().putStringSet("responded_messages", respondedMessages).apply()
                        Log.d(TAG, "Marked incoming message as responded: $incomingMessageHash")
                    }
                    
                    // Don't trigger rescan here - wait until all messages in AutoSendQueue are sent
                    // The rescan will be triggered in AutoSendQueue.drain() when queue becomes empty
                } catch (e: Exception) {
                    Log.e(TAG, "✗✗✗ EXCEPTION CALLING SMS SEND: ${e.message}", e)
                    Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
                    e.printStackTrace()
                }
            }, 2000L) // 2 second delay before sending
            
            Log.d(TAG, "Scheduled SMS send in 2 seconds")
            
        } catch (e: Exception) {
            Log.e(TAG, "✗✗✗ FAILED TO PREPARE SMS SEND: ${e.message}", e)
            Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
            e.printStackTrace()
            throw e
        }
    }
}

