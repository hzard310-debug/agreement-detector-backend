package com.example.agreementdetector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType

class SmsProcessingService : Service() {
    companion object {
        private const val TAG = "SmsProcessingService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "sms_processing_channel"
        const val EXTRA_SMS_SENDER = "sms_sender"
        const val EXTRA_SMS_BODY = "sms_body"
        const val ACTION_PROCESS_SMS = "com.example.agreementdetector.PROCESS_SMS"
        
        fun start(context: Context) {
            val intent = Intent(context, SmsProcessingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "Starting foreground service for SMS processing")
        }
        
        fun startWithSms(context: Context, sender: String, body: String) {
            val intent = Intent(context, SmsProcessingService::class.java).apply {
                action = ACTION_PROCESS_SMS
                putExtra(EXTRA_SMS_SENDER, sender)
                putExtra(EXTRA_SMS_BODY, body)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "Starting foreground service with SMS to process: $sender")
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, SmsProcessingService::class.java)
            context.stopService(intent)
            Log.d(TAG, "Stopping foreground service")
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For Android 10+, use startForeground with service type
                startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            Log.d(TAG, "SMS Processing Service created and started in foreground")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
            // Try without service type for older Android versions
            try {
                startForeground(NOTIFICATION_ID, createNotification())
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to start foreground: ${e2.message}", e2)
            }
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started - keeping app alive for SMS processing")
        // Ensure notification is shown (in case service was restarted)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground: ${e.message}", e)
            try {
                startForeground(NOTIFICATION_ID, createNotification())
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to start foreground: ${e2.message}", e2)
            }
        }
        
        // Process SMS if provided
        if (intent?.action == ACTION_PROCESS_SMS) {
            val sender = intent.getStringExtra(EXTRA_SMS_SENDER)
            val body = intent.getStringExtra(EXTRA_SMS_BODY)
            if (sender != null && body != null) {
                Log.d(TAG, "Processing SMS from $sender in service (works even when screen is locked)")
                processSmsInService(sender, body)
            }
        }
        
        // Return START_STICKY to restart service if killed
        return START_STICKY
    }
    
    private fun processSmsInService(sender: String, body: String) {
        // Acquire wake lock to keep device awake while processing (even if screen is off)
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AgreementDetector::SmsProcessingService"
        )
        wakeLock?.acquire(60000) // Hold for up to 60 seconds
        
        Thread {
            try {
                // Get FULL conversation history - collect ALL messages (no limit)
                val turns = com.example.agreementdetector.ai.TurnsAndState.collectRecentTurns(this, sender, 10000)
                Log.d(TAG, "Collected ${turns.size} turns of FULL conversation history")
                
                // Ensure the current incoming message is included
                val currentMessage = mapOf("role" to "them", "text" to body)
                val turnsWithCurrent = if (turns.isEmpty() || turns.lastOrNull()?.get("text") != body) {
                    turns + currentMessage
                } else {
                    turns
                }
                Log.d(TAG, "Turns with current message: ${turnsWithCurrent.size}")
                
                // Prepare script for Claude
                val script = "Your eldest and favourite"
                
                // Call Claude backend with retry logic
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
                val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                val paymentDetails = prefs.getString("payment_details", "")?.trim() ?: ""
                
                val requestBody = org.json.JSONObject().apply {
                    put("device_id", android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID))
                    put("contact_id", sender)
                    put("script", script)
                    put("turns", turnsArray)
                    if (paymentDetails.isNotEmpty()) {
                        put("payment_details", paymentDetails)
                    }
                }.toString()
                
                Log.d(TAG, "Sending request to backend: $url")
                
                var success = false
                var retryCount = 0
                val maxRetries = 3
                
                while (!success && retryCount < maxRetries) {
                    try {
                        val request = okhttp3.Request.Builder()
                            .url(url)
                            .post(okhttp3.RequestBody.create("application/json".toMediaType(), requestBody))
                            .build()
                        
                        val response = client.newCall(request).execute()
                        val responseBody = response.body?.string() ?: ""
                        
                        if (response.isSuccessful && responseBody.isNotEmpty()) {
                            val jsonResponse = org.json.JSONObject(responseBody)
                            val action = jsonResponse.getString("action")
                            val responseText = jsonResponse.optString("response", "")
                            val reasoning = jsonResponse.optString("reasoning", "")
                            
                            Log.d(TAG, "Backend response code: ${response.code} (attempt ${retryCount + 1})")
                            Log.d(TAG, "Backend response: $responseBody")
                            
                            if (action == "SEND" && responseText.isNotEmpty()) {
                                val incomingMessageHash = hashMessage(sender, body)
                                AutoSendQueue.enqueue(this, sender, responseText, AutoSendQueue.Source.AI, incomingMessageHash)
                                Log.d(TAG, "SMS queued for sending to $sender: $responseText")
                                success = true
                            } else {
                                // Handle NO_SEND with fallback logic (same as SmsReceiver)
                                Log.d(TAG, "Backend said NO_SEND - checking for fallback response")
                                handleNoSendFallback(sender, body, reasoning, turnsWithCurrent, prefs)
                                success = true
                            }
                        } else {
                            Log.e(TAG, "Backend error: ${response.code}")
                            success = true // Don't retry on non-502 errors
                        }
                    } catch (e: Exception) {
                        val isTimeout = e is java.net.SocketTimeoutException || 
                                      e is java.net.ConnectException ||
                                      e.message?.contains("timeout", ignoreCase = true) == true
                        
                        if (isTimeout && retryCount < maxRetries - 1) {
                            retryCount++
                            val delayMs = (2000 * retryCount).toLong()
                            Log.w(TAG, "Timeout, retrying in ${delayMs}ms (attempt ${retryCount + 1}/$maxRetries)")
                            Thread.sleep(delayMs)
                        } else {
                            Log.e(TAG, "Failed after $maxRetries attempts: ${e.message}", e)
                            // On final timeout failure, use fallback response
                            Log.w(TAG, "Backend timeout after all retries - using fallback response")
                            handleNoSendFallback(sender, body, "Backend timeout", turnsWithCurrent, prefs)
                            success = true
                        }
                    }
                }
                
                // If we still haven't sent anything after all retries, use fallback
                if (!success) {
                    Log.w(TAG, "No response sent after all attempts - using fallback")
                    handleNoSendFallback(sender, body, "No response from backend", turnsWithCurrent, prefs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing SMS in service: ${e.message}", e)
                // On exception, try to send fallback response
                try {
                    val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                    handleNoSendFallback(sender, body, "Exception occurred", emptyList(), prefs)
                } catch (e2: Exception) {
                    Log.e(TAG, "Error in fallback: ${e2.message}", e2)
                }
            } finally {
                // Release wake lock
                try {
                    wakeLock?.let {
                        if (it.isHeld) {
                            it.release()
                            Log.d(TAG, "Wake lock released after SMS processing")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing wake lock: ${e.message}", e)
                }
            }
        }.start()
    }
    
    private fun hashMessage(address: String, message: String): String {
        val combined = "$address|${message.trim().lowercase()}"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(combined.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    private fun handleNoSendFallback(sender: String, body: String, reasoning: String, turnsWithCurrent: List<Map<String, String>>, prefs: android.content.SharedPreferences) {
        val lowerBody = body.lowercase().trim()
        
        // CRITICAL: Check for "Who's this" or "Who is this" - should trigger Script 1
        val isWhoQuestion = (lowerBody.contains("who") && (lowerBody.contains("this") || lowerBody.contains("is") || lowerBody.contains("'s"))) &&
                           !lowerBody.contains("is this") && // Exclude "is this [name]" which is Script 2
                           !lowerBody.matches(Regex(".*is this [a-z]+.*", RegexOption.IGNORE_CASE)) // Exclude "is this [name]"
        
        if (isWhoQuestion) {
            // Script 1: "Your eldest and favourite"
            val response = "Your eldest and favourite"
            val incomingMessageHash = hashMessage(sender, body)
            AutoSendQueue.enqueue(this, sender, response, AutoSendQueue.Source.AI, incomingMessageHash)
            android.util.Log.d(TAG, "Fallback: Script 1 response queued for 'Who's this' question: $response")
            return
        }
        
        // Detect ANY question
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
        
        val isInappropriate = reasoning.contains("inappropriate", ignoreCase = true) ||
                             reasoning.contains("swear", ignoreCase = true) ||
                             reasoning.contains("profanity", ignoreCase = true) ||
                             reasoning.contains("sexual", ignoreCase = true) ||
                             reasoning.contains("time-wasting", ignoreCase = true) ||
                             reasoning.contains("uncooperative", ignoreCase = true)
        
        val alreadySent = reasoning.contains("already sent", ignoreCase = true) ||
                         reasoning.contains("duplicate", ignoreCase = true) ||
                         reasoning.contains("waiting for reply", ignoreCase = true)
        
        var shouldUseFallback = false
        
        if (alreadySent) {
            Log.d(TAG, "Script response already sent - not using fallback")
            return
        }
        
        if (!alreadySent && !isInappropriate) {
            if (isSeriousMessage) {
                shouldUseFallback = true
                Log.d(TAG, "Serious/urgent message - using fallback")
            } else if (isQuestion) {
                shouldUseFallback = true
                Log.d(TAG, "Question detected - using fallback")
            } else if (isCasualStatement) {
                shouldUseFallback = true
                Log.d(TAG, "Casual statement - using fallback")
            } else {
                shouldUseFallback = true
                Log.d(TAG, "Message detected - using fallback")
            }
        }
        
        // Check payment paragraph question
        var paymentRequestWasSent = false
        for (turn in turnsWithCurrent) {
            val role = turn["role"]?.lowercase() ?: ""
            val text = turn["text"]?.lowercase() ?: ""
            if (role == "you" && text.contains("i need to make a payment today but can't for 24h")) {
                paymentRequestWasSent = true
                break
            }
        }
        
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
        
        if (paymentRequestWasSent && isPaymentParagraphQuestion) {
            val macbookResponse = "Its for a macbook i got i just need to pay it back if that's okay, I'll have it sent back to you tomorrow first thing"
            val incomingMessageHash = hashMessage(sender, body)
            AutoSendQueue.enqueue(this, sender, macbookResponse, AutoSendQueue.Source.AI, incomingMessageHash)
            Log.d(TAG, "Payment paragraph question - queued macbook response")
            return
        }
        
        if (shouldUseFallback) {
            val fallbackResponse = when {
                (lowerBody.contains("promise") || lowerBody.contains("will pay") || lowerBody.contains("pay back") || 
                 lowerBody.contains("pay you back") || lowerBody.contains("pay me back")) && 
                (lowerBody.contains("tomorrow") || lowerBody.contains("will") || lowerBody.contains("promise")) -> 
                    "Yes i will i promise"
                lowerBody.contains("pay back") || lowerBody.contains("pay you back") || lowerBody.contains("pay me back") -> 
                    "Yes i will i promise"
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
                (lowerBody.contains("whatsapp") || lowerBody.contains("whats app")) && 
                (lowerBody.contains("set") || lowerBody.contains("setup") || lowerBody.contains("set up") || 
                 lowerBody.contains("ready") || lowerBody.contains("done") || lowerBody.contains("working")) -> 
                    "Not yet i still need to set it up"
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
                val incomingMessageHash = hashMessage(sender, body)
                AutoSendQueue.enqueue(this, sender, fallbackResponse, AutoSendQueue.Source.AI, incomingMessageHash)
                Log.d(TAG, "Fallback response queued: $fallbackResponse")
            }
        } else {
            Log.d(TAG, "Not using fallback - message inappropriate or already sent")
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SMS Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the app running to process SMS messages"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("SMS Processing Active")
                .setContentText("App is processing SMS messages in the background")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("SMS Processing Active")
                .setContentText("App is processing SMS messages in the background")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
    }
}

