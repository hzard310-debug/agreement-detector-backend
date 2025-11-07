package com.example.agreementdetector.ai

import android.content.Context
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
            try {
                Log.d(TAG, "Starting scan of all SMS messages")
                val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                if (!settings.getBoolean("ai_response_enabled", false)) {
                    Log.d(TAG, "AI response not enabled, skipping scan")
                    return@Thread
                }
                
                val inbox = Telephony.Sms.Inbox.CONTENT_URI
                val processedContacts = mutableSetOf<String>()
                var totalScanned = 0
                var totalResponded = 0
                
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
                
                // Process each contact's messages
                for ((address, messages) in messagesByAddress) {
                    if (processedContacts.contains(address)) continue
                    
                    // Get conversation history for this contact
                    val turns = TurnsAndState.collectRecentTurns(context, address, 99999)
                    
                    // Check if we've already responded to their latest message
                    // If the last message in the conversation is from "you", we've already responded
                    val lastTurn = turns.lastOrNull()
                    val alreadyResponded = lastTurn?.get("role") == "you"
                    
                    if (alreadyResponded) {
                        Log.d(TAG, "Already responded to $address, skipping")
                        continue
                    }
                    
                    // Get the most recent message from this contact that we haven't responded to
                    val latestMessage = messages.firstOrNull()?.first ?: continue
                    
                    // Check if we've already responded to this specific message
                    val messageHash = hashMessage(address, latestMessage)
                    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    val respondedMessages = prefs.getStringSet("responded_messages", null)?.toMutableSet() ?: mutableSetOf()
                    
                    if (respondedMessages.contains(messageHash)) {
                        Log.d(TAG, "Already responded to this message from $address: $latestMessage")
                        continue
                    }
                    
                    // Check if this message is already in the turns (meaning we've seen it)
                    val turnsWithCurrent = if (turns.isEmpty() || turns.lastOrNull()?.get("text") != latestMessage) {
                        turns + mapOf("role" to "them", "text" to latestMessage)
                    } else {
                        turns
                    }
                    
                    // Check with backend if we should respond (pass message hash so it can be marked after successful send)
                    val shouldRespond = checkIfShouldRespond(context, address, turnsWithCurrent, latestMessage, messageHash)
                    
                    if (shouldRespond) {
                        processedContacts.add(address)
                        totalResponded++
                        Log.d(TAG, "Will respond to $address: $latestMessage")
                    }
                }
                
                Log.d(TAG, "Scan complete: $totalScanned messages scanned, $totalResponded contacts to respond to")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning messages: ${e.message}", e)
            } finally {
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
    
    private fun checkIfShouldRespond(
        context: Context,
        address: String,
        turns: List<Map<String, String>>,
        latestMessage: String,
        incomingMessageHash: String
    ): Boolean {
        try {
            val url = "https://agreement-detector-api.onrender.com/respond"
            val client = OkHttpClient()
            val turnsArray = JSONArray()
            for (turn in turns) {
                turnsArray.put(JSONObject(turn))
            }
            
            val requestBody = JSONObject().apply {
                put("device_id", android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID))
                put("contact_id", address)
                put("script", "Your eldest and favourite")
                put("turns", turnsArray)
            }.toString()
            
            val request = Request.Builder()
                .url(url)
                .post(RequestBody.create("application/json".toMediaType(), requestBody))
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return false
                    val json = JSONObject(body)
                    val action = json.getString("action")
                    val messageToSend = json.getString("response")
                    
                    if (action == "SEND" && messageToSend.isNotEmpty()) {
                        // Queue the response with incoming message hash so it can be marked as responded after successful send
                        AutoSendQueue.enqueue(context, address, messageToSend, AutoSendQueue.Source.AI, incomingMessageHash)
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if should respond: ${e.message}", e)
        }
        return false
    }
}

