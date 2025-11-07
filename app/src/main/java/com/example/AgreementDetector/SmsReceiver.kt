package com.example.agreementdetector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        
        // Extract SMS messages
        val extras = intent.extras
        if (extras == null) {
            android.util.Log.i("SmsReceiver", "[SKIP] No extras")
            return
        }
        val pdus = extras.get("pdus") as? Array<*>
        if (pdus == null) {
            android.util.Log.i("SmsReceiver", "[SKIP] No pdus")
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

            // Check if AI is enabled before calling Claude
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val aiEnabled = prefs.getBoolean("ai_response_enabled", false)
            android.util.Log.i("SmsReceiver", "[CHECK] AI enabled: $aiEnabled")
            
            if (aiEnabled) {
                val messageBody = body // Store in outer scope for deletion
                val messageSender = sender // Store in outer scope for deletion
                
                Log.d("SmsReceiver", "AI enabled - querying Claude AI for response")
                Thread {
                    try {
                        // Get conversation history
                        val turns = com.example.agreementdetector.ai.TurnsAndState.collectRecentTurns(context, sender, 99999)
                        Log.d("SmsReceiver", "Collected ${turns.size} turns of conversation history")
                        
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
                        
                        // Call Claude backend with retry logic for 502 errors
                        val url = "https://agreement-detector-api.onrender.com/respond"
                        val client = okhttp3.OkHttpClient()
                        val turnsArray = org.json.JSONArray()
                        for (turn in turnsWithCurrent) {
                            turnsArray.put(org.json.JSONObject(turn))
                        }
                        
                        val requestBody = org.json.JSONObject().apply {
                            put("device_id", android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID))
                            put("contact_id", sender)
                            put("script", script)
                            put("turns", turnsArray)
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
                                    Log.d("SmsReceiver", "Backend response code: ${response.code} (attempt ${retryCount + 1})")
                                    
                                    // Retry on 502 Bad Gateway (service temporarily unavailable)
                                    if (response.code == 502 && retryCount < maxRetries - 1) {
                                        retryCount++
                                        val delayMs = (1000 * retryCount).toLong() // Exponential backoff: 1s, 2s, 3s
                                        Log.w("SmsReceiver", "502 error, retrying in ${delayMs}ms...")
                                        Thread.sleep(delayMs)
                                        return@use
                                    }
                                    
                                    if (response.isSuccessful) {
                                        val body = response.body?.string() ?: return@use
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
                                            Log.d("SmsReceiver", "Claude decided NO_SEND or message is empty")
                                        }
                                        success = true
                                    } else {
                                        Log.e("SmsReceiver", "Claude API error: ${response.code}")
                                        val errorBody = response.body?.string()
                                        Log.e("SmsReceiver", "Error details: $errorBody")
                                        success = true // Don't retry on non-502 errors
                                    }
                                }
                            } catch (e: Exception) {
                                if (retryCount < maxRetries - 1) {
                                    retryCount++
                                    val delayMs = (1000 * retryCount).toLong()
                                    Log.w("SmsReceiver", "Exception during request, retrying in ${delayMs}ms: ${e.message}")
                                    Thread.sleep(delayMs)
                                } else {
                                    throw e // Re-throw on final attempt
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SmsReceiver", "Error calling Claude: ${e.message}", e)
                        e.printStackTrace()
                    }
                }.start()
            } else {
                Log.d("SmsReceiver", "AI response disabled")
            }
        }
    }
}
