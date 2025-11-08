package com.example.agreementdetector

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import android.app.NotificationManager
import android.app.NotificationChannel
import android.os.Build
import androidx.core.app.NotificationCompat
import android.net.Uri

// Sends SMS one-by-one with a 5 second gap between messages.
// ALL messages must come from AI - no manual sending allowed.
object AutoSendQueue {
    enum class Source { AI } // Only AI source - manual removed
    interface Listener { fun onQueueProgress(pending: Int) }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Triple<String, String, String?>>() // address, text, incomingMessageHash (optional)
    private var running = false
    private val listeners = CopyOnWriteArraySet<Listener>()
    @Volatile private var receiversRegistered = false

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    @Synchronized fun pendingCount(): Int = queue.size

    private fun notifyProgress() {
        val pending = pendingCount()
        listeners.forEach { l -> handler.post { l.onQueueProgress(pending) } }
    }

    private fun ensureReceivers(ctx: Context) {
        if (receiversRegistered) return
        receiversRegistered = true
        val appCtx = ctx.applicationContext
        // SENT result receiver
        val sentReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val rc = resultCode
                val msg = when (rc) {
                    android.app.Activity.RESULT_OK -> {
                        // Track unique contacts
                        val addr = intent.getStringExtra("addr")
                        if (addr != null) {
                            val prefs = appCtx.getSharedPreferences("settings", Context.MODE_PRIVATE)
                            val sentContacts = prefs.getStringSet("sent_contacts", null)?.toMutableSet() ?: mutableSetOf()
                            sentContacts.add(addr)
                            prefs.edit().putStringSet("sent_contacts", sentContacts).apply()
                            
                            // Mark incoming message as responded to if hash was provided
                            val incomingMessageHash = intent.getStringExtra("incoming_message_hash")
                            if (incomingMessageHash != null) {
                                val respondedMessages = prefs.getStringSet("responded_messages", null)?.toMutableSet() ?: mutableSetOf()
                                respondedMessages.add(incomingMessageHash)
                                prefs.edit().putStringSet("responded_messages", respondedMessages).apply()
                                android.util.Log.d("AutoSendQueue", "Marked incoming message as responded: $incomingMessageHash")
                            }
                            
                            // Update notification with count
                            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            val channelId = "sms_sent"
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                if (nm.getNotificationChannel(channelId) == null) {
                                    val ch = NotificationChannel(channelId, "SMS Sent", NotificationManager.IMPORTANCE_LOW)
                                    nm.createNotificationChannel(ch)
                                }
                            }
                            val notif = NotificationCompat.Builder(appCtx, channelId)
                                .setSmallIcon(R.mipmap.ic_launcher)
                                .setContentTitle("SMS Sent")
                                .setContentText("Sent to ${sentContacts.size} ${if (sentContacts.size == 1) "person" else "people"}")
                                .setPriority(NotificationCompat.PRIORITY_LOW)
                                .setAutoCancel(true)
                                .build()
                            nm.notify(1, notif)
                        }
                        "SMS sent"
                    }
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "SMS error: generic failure"
                    SmsManager.RESULT_ERROR_NO_SERVICE -> "SMS error: no service"
                    SmsManager.RESULT_ERROR_NULL_PDU -> "SMS error: null PDU"
                    SmsManager.RESULT_ERROR_RADIO_OFF -> "SMS error: radio off"
                    else -> "SMS send result: $rc"
                }
                android.widget.Toast.makeText(appCtx, msg, android.widget.Toast.LENGTH_SHORT).show()
                // Retry once with alternate SIM on generic failure
                if (rc == SmsManager.RESULT_ERROR_GENERIC_FAILURE) {
                    try {
                        val addr = intent.getStringExtra("addr") ?: return
                        val text = intent.getStringExtra("text") ?: return
                        val subMgr = appCtx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                        val list = subMgr.activeSubscriptionInfoList ?: emptyList()
                        val prefs = appCtx.getSharedPreferences("settings", Context.MODE_PRIVATE)
                        val preferred = prefs.getInt("preferred_subid", SubscriptionManager.INVALID_SUBSCRIPTION_ID)
                        val alt = list.map { it.subscriptionId }.firstOrNull { it != preferred }
                        if (alt != null) {
                            val altSms = SmsManager.getSmsManagerForSubscriptionId(alt)
                            val incomingMessageHash = intent.getStringExtra("incoming_message_hash")
                            val altSent = PendingIntent.getBroadcast(appCtx, 0, Intent("SMS_SENT").apply {
                                putExtra("addr", addr)
                                putExtra("text", text)
                                if (incomingMessageHash != null) {
                                    putExtra("incoming_message_hash", incomingMessageHash)
                                }
                            }, PendingIntent.FLAG_IMMUTABLE)
                            val altDelivered = PendingIntent.getBroadcast(appCtx, 0, Intent("SMS_DELIVERED"), PendingIntent.FLAG_IMMUTABLE)
                            val parts = altSms.divideMessage(text)
                            if (parts != null && parts.size > 1) {
                                val sents = ArrayList<PendingIntent>(parts.size).apply { repeat(parts.size) { add(altSent) } }
                                val deliveredList = ArrayList<PendingIntent>(parts.size).apply { repeat(parts.size) { add(altDelivered) } }
                                altSms.sendMultipartTextMessage(addr, null, parts, sents, deliveredList)
                            } else {
                                altSms.sendTextMessage(addr, null, text, altSent, altDelivered)
                            }
                            android.widget.Toast.makeText(appCtx, "Retrying via alternate SIM", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            // No alternate SIM: open composer for manual send
                            val uri = android.net.Uri.parse("smsto:" + addr)
                            val compose = Intent(Intent.ACTION_SENDTO, uri).apply {
                                putExtra("sms_body", text)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try { appCtx.startActivity(compose) } catch (_: Exception) {}
                            android.widget.Toast.makeText(appCtx, "Manual send fallback opened", android.widget.Toast.LENGTH_LONG).show()
                        }
                    } catch (_: Throwable) { }
                }
            }
        }
        appCtx.registerReceiver(sentReceiver, android.content.IntentFilter("SMS_SENT"))
    }

    @Synchronized
    fun enqueue(context: Context, address: String, text: String, source: Source = Source.AI, incomingMessageHash: String? = null) {
        val appCtx = context.applicationContext
        
        // ALL messages must come from AI - verify AI is enabled
        val settings = appCtx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!settings.getBoolean("ai_response_enabled", false)) {
            android.util.Log.w("AutoSendQueue", "AI response not enabled - blocking message send")
            handler.post {
                android.widget.Toast.makeText(appCtx, "AI must be enabled to send messages", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        // Only accept AI source
        if (source != Source.AI) {
            android.util.Log.w("AutoSendQueue", "Only AI source allowed - blocking non-AI message")
            return
        }
        
        // Check for duplicate messages - check the actual SMS database to see if this exact text was already sent to this contact
        // CRITICAL: Only check recent messages (last 24 hours) to avoid blocking legitimate responses
        // Different incoming messages should be able to get the same response
        val t = text.ifBlank { "." } // avoid empty
        
        // Query the SMS database to check if this exact message was already sent to this contact RECENTLY
        var alreadySent = false
        try {
            val recentTime = System.currentTimeMillis() - 86400000L // Last 24 hours only
            appCtx.contentResolver.query(
                android.provider.Telephony.Sms.Sent.CONTENT_URI,
                arrayOf(android.provider.Telephony.Sms.BODY, android.provider.Telephony.Sms.ADDRESS, android.provider.Telephony.Sms.DATE),
                "${android.provider.Telephony.Sms.DATE} > ?",
                arrayOf(recentTime.toString()),
                "${android.provider.Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val sentBody = cursor.getString(0) ?: continue
                    val sentAddress = cursor.getString(1) ?: continue
                    
                    // Check if this is the same contact and same message text (only within last 24 hours)
                    if (android.telephony.PhoneNumberUtils.compare(appCtx, address, sentAddress) && 
                        sentBody.trim() == t.trim()) {
                        alreadySent = true
                        android.util.Log.d("AutoSendQueue", "Found duplicate in recent chat history (last 24h): '$t' already sent to $address")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AutoSendQueue", "Error checking for duplicates: ${e.message}", e)
        }
        
        if (alreadySent) {
            android.util.Log.w("AutoSendQueue", "Duplicate message detected in recent chat history (last 24h) - not sending again: $t")
            handler.post {
                android.widget.Toast.makeText(appCtx, "Message already sent recently", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        android.util.Log.d("AutoSendQueue", "No duplicate found - queuing message to $address: $t")
        
        ensureReceivers(appCtx)
        queue.add(Triple(address, t, incomingMessageHash))
        notifyProgress()
        if (!running) {
            running = true
            // Temporary: 2 second delay before first send
            handler.postDelayed({ drain(appCtx) }, 2000L)
        }
    }

    private fun obtainSmsManager(ctx: Context): SmsManager {
        // Use preferred subId if set, else default
        return try {
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val preferred = prefs.getInt("preferred_subid", SubscriptionManager.INVALID_SUBSCRIPTION_ID)
            val subMgr = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val subId = if (preferred != SubscriptionManager.INVALID_SUBSCRIPTION_ID) preferred else SubscriptionManager.getDefaultSmsSubscriptionId()
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) SmsManager.getSmsManagerForSubscriptionId(subId)
            else SmsManager.getDefault()
        } catch (_: Throwable) {
            SmsManager.getDefault()
        }
    }

    private fun drain(context: Context) {
        val item = synchronized(this) { if (queue.isEmpty()) null else queue.removeFirst() }
        if (item == null) {
            synchronized(this) { running = false }
            notifyProgress()
            return
        }
        val (address, text, incomingMessageHash) = item
        try {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (prefs.getBoolean("always_send_via_composer", false)) {
                // Show heads-up notification with a tap-to-send action that opens the composer.
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channelId = "send_compose"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (nm.getNotificationChannel(channelId) == null) {
                        val ch = NotificationChannel(channelId, "Send via composer", NotificationManager.IMPORTANCE_HIGH)
                        nm.createNotificationChannel(ch)
                    }
                }
                val composeIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + address)).apply {
                    putExtra("sms_body", text)
                }
                val requestCode = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
                val contentPi = PendingIntent.getActivity(context, requestCode, composeIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val notif = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Tap to send")
                    .setContentText("Open composer to send to $address")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(contentPi)
                    .addAction(android.R.drawable.ic_menu_send, "Send", contentPi)
                    .build()
                nm.notify(requestCode, notif)
            } else {
                // Direct SMS send using preferred subscription
                val sms = obtainSmsManager(context)
                val sentIntent = PendingIntent.getBroadcast(context, 0, Intent("SMS_SENT").apply {
                    putExtra("addr", address)
                    putExtra("text", text)
                    if (incomingMessageHash != null) {
                        putExtra("incoming_message_hash", incomingMessageHash)
                    }
                }, PendingIntent.FLAG_IMMUTABLE)
                val deliveredIntent = PendingIntent.getBroadcast(context, 0, Intent("SMS_DELIVERED"), PendingIntent.FLAG_IMMUTABLE)
                try {
                    // Check if message needs to be split into multiple parts
                    val parts = sms.divideMessage(text)
                    if (parts != null && parts.size > 1) {
                        // Send as multipart message
                        val sentIntents = ArrayList<PendingIntent>(parts.size).apply { 
                            repeat(parts.size) { add(sentIntent) } 
                        }
                        val deliveredIntents = ArrayList<PendingIntent>(parts.size).apply { 
                            repeat(parts.size) { add(deliveredIntent) } 
                        }
                        sms.sendMultipartTextMessage(address, null, parts, sentIntents, deliveredIntents)
                        android.util.Log.d("AutoSendQueue", "Multipart SMS sent to $address (${parts.size} parts)")
                    } else {
                        // Single part message
                        sms.sendTextMessage(address, null, text, sentIntent, deliveredIntent)
                        android.util.Log.d("AutoSendQueue", "SMS sent to $address: $text")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AutoSendQueue", "Failed to send: ${e.message}", e)
                }
            }
        } catch (se: SecurityException) {
            android.util.Log.e("AutoSendQueue", "SecurityException: ${se.message}", se)
            handler.post {
                android.widget.Toast.makeText(context, "SMS permission denied — cannot send", android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("AutoSendQueue", "Exception sending SMS: ${e.message}", e)
        }
        notifyProgress()
        // Temporary: 2 second delay between messages
        handler.postDelayed({ drain(context) }, 2000L)
    }
}
