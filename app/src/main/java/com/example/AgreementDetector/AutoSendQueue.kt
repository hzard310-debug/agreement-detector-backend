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
import com.example.agreementdetector.ai.MessageScanner

// Sends SMS one-by-one with a 5 second gap between messages.
// ALL messages must come from AI - no manual sending allowed.
object AutoSendQueue {
    enum class Source { AI } // Only AI source - manual removed
    interface Listener { 
        fun onQueueProgress(pending: Int)
        fun onStatusUpdate(status: String)
    }

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
    
    private fun notifyStatus(status: String) {
        listeners.forEach { l -> handler.post { l.onStatusUpdate(status) } }
    }

    private fun ensureReceivers(ctx: Context) {
        if (receiversRegistered) return
        receiversRegistered = true
        val appCtx = ctx.applicationContext
        // SENT result receiver
        val sentReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val rc = resultCode
                val addr = intent.getStringExtra("addr")
                val text = intent.getStringExtra("text")
                val msg = when (rc) {
                    android.app.Activity.RESULT_OK -> {
                        android.util.Log.i("AutoSendQueue", "✓✓✓✓✓ SMS SENT SUCCESSFULLY (CONFIRMED BY SYSTEM): '$text' to $addr")
                        android.util.Log.i("AutoSendQueue", "✓✓✓✓✓ SYSTEM CONFIRMATION: Message was successfully sent to $addr")
                        notifyStatus("✅ Message sent successfully")
                        notifyProgress() // Update queue count after sending
                        // Track unique contacts
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
                            
                            // Don't trigger rescan here - wait until all messages in queue are sent
                            // The rescan will be triggered in drain() when queue becomes empty
                            
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
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
                        android.util.Log.e("AutoSendQueue", "✗✗ SMS SEND FAILED: Generic failure for '$text' to $addr")
                        "SMS error: generic failure"
                    }
                    SmsManager.RESULT_ERROR_NO_SERVICE -> {
                        android.util.Log.e("AutoSendQueue", "✗✗ SMS SEND FAILED: No service for '$text' to $addr")
                        "SMS error: no service"
                    }
                    SmsManager.RESULT_ERROR_NULL_PDU -> {
                        android.util.Log.e("AutoSendQueue", "✗✗ SMS SEND FAILED: Null PDU for '$text' to $addr")
                        "SMS error: null PDU"
                    }
                    SmsManager.RESULT_ERROR_RADIO_OFF -> {
                        android.util.Log.e("AutoSendQueue", "✗✗ SMS SEND FAILED: Radio off for '$text' to $addr")
                        "SMS error: radio off"
                    }
                    else -> {
                        android.util.Log.w("AutoSendQueue", "⚠ SMS SEND UNKNOWN RESULT: $rc for '$text' to $addr")
                        "SMS send result: $rc"
                    }
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
        
        // REMOVED: AI check - no longer blocking messages
        // REMOVED: Duplicate check - no longer blocking messages
        // All messages will be sent immediately without any blocking checks
        
        val t = text.ifBlank { "." } // avoid empty
        
        android.util.Log.d("AutoSendQueue", "Queuing message to $address: $t (all blocking checks removed)")
        
        ensureReceivers(appCtx)
        synchronized(this) {
            queue.add(Triple(address, t, incomingMessageHash))
            android.util.Log.i("AutoSendQueue", "✓ Message added to queue: '$t' to $address (queue size: ${queue.size})")
        }
        notifyStatus("📤 Message queued (${queue.size} in queue)")
        notifyProgress()
        synchronized(this) {
            if (!running) {
                running = true
                android.util.Log.i("AutoSendQueue", "Starting queue processor (queue size: ${queue.size})")
                // Send immediately (no delay) to ensure messages are sent
                handler.post { 
                    android.util.Log.d("AutoSendQueue", "Handler posting drain() call - starting queue processor")
                    drain(appCtx) 
                }
            } else {
                android.util.Log.d("AutoSendQueue", "Queue processor already running (queue size: ${queue.size}) - ensuring drain will continue")
                // Even if running, ensure drain is called to process new messages
                handler.post { 
                    android.util.Log.d("AutoSendQueue", "Handler posting drain() call - queue processor already running, continuing")
                    drain(appCtx) 
                }
            }
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
        android.util.Log.i("AutoSendQueue", "=== DRAIN CALLED ===")
        val item = synchronized(this) { 
            android.util.Log.d("AutoSendQueue", "Checking queue in drain() - size: ${queue.size}")
            if (queue.isEmpty()) {
                android.util.Log.d("AutoSendQueue", "Queue is empty in drain()")
                null 
            } else {
                val next = queue.removeFirst()
                android.util.Log.i("AutoSendQueue", "Removed item from queue, ${queue.size} remaining")
                next
            }
        }
        if (item == null) {
            synchronized(this) { 
                running = false
                android.util.Log.i("AutoSendQueue", "Queue empty - stopping drain, all messages processed")
            }
            notifyProgress()
            // All messages have been sent - now trigger a rescan for NEW messages only
            // Record the timestamp when messages finished sending
            val sendCompleteTimestamp = System.currentTimeMillis()
            // Delay by 2 seconds to allow SMS to be saved to database first
            Handler(Looper.getMainLooper()).postDelayed({
                android.util.Log.d("AutoSendQueue", "All messages sent successfully - scanning for NEW messages received after send completion")
                // Only scan for messages received AFTER we finished sending
                MessageScanner.scanAllMessages(context.applicationContext, sinceTimestamp = sendCompleteTimestamp)
            }, 2000L)
            return
        }
        val (address, text, incomingMessageHash) = item
        android.util.Log.i("AutoSendQueue", "Processing message from queue: '$text' to $address (queue size: ${queue.size})")
        android.util.Log.i("AutoSendQueue", "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        android.util.Log.i("AutoSendQueue", "→→→ SENDING MESSAGE NOW: '$text' to $address (queue remaining: ${queue.size})")
        android.util.Log.i("AutoSendQueue", "→→→ Message hash: $incomingMessageHash")
        try {
            // REMOVED: always_send_via_composer check - always send directly now
            // Direct SMS send using preferred subscription
            android.util.Log.i("AutoSendQueue", "→→→ Obtaining SMS manager...")
            val sms = obtainSmsManager(context)
            android.util.Log.i("AutoSendQueue", "→→→ SMS manager obtained, creating PendingIntents...")
            
            val requestCode = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
            val sentIntent = PendingIntent.getBroadcast(context, requestCode, Intent("SMS_SENT").apply {
                putExtra("addr", address)
                putExtra("text", text)
                if (incomingMessageHash != null) {
                    putExtra("incoming_message_hash", incomingMessageHash)
                }
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val deliveredIntent = PendingIntent.getBroadcast(context, requestCode + 1000, Intent("SMS_DELIVERED"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            android.util.Log.i("AutoSendQueue", "→→→ PendingIntents created (requestCode: $requestCode)")
            
            try {
                // Check if message needs to be split into multiple parts
                android.util.Log.i("AutoSendQueue", "→→→ Dividing message into parts...")
                val parts = sms.divideMessage(text)
                android.util.Log.i("AutoSendQueue", "→→→ Message divided: ${parts?.size ?: 1} part(s)")
                if (parts != null && parts.size > 1) {
                    // Send as multipart message
                    val sentIntents = ArrayList<PendingIntent>(parts.size).apply { 
                        repeat(parts.size) { add(sentIntent) } 
                    }
                    val deliveredIntents = ArrayList<PendingIntent>(parts.size).apply { 
                        repeat(parts.size) { add(deliveredIntent) } 
                    }
                    try {
                        android.util.Log.i("AutoSendQueue", "→→→ CALLING sendMultipartTextMessage() NOW...")
                        sms.sendMultipartTextMessage(address, null, parts, sentIntents, deliveredIntents)
                        android.util.Log.i("AutoSendQueue", "✓✓✓✓✓ SMS SEND CALLED (multipart ${parts.size} parts): '$text' to $address")
                        android.util.Log.i("AutoSendQueue", "✓✓✓✓✓ VERIFICATION: sendMultipartTextMessage() method was successfully called")
                        android.util.Log.i("AutoSendQueue", "✓✓✓✓✓ Waiting for system confirmation via SMS_SENT broadcast...")
                        notifyStatus("📤 Sending message (${parts.size} parts)...")
                    } catch (e: Exception) {
                        android.util.Log.e("AutoSendQueue", "✗✗✗✗✗ EXCEPTION IN sendMultipartTextMessage CALL: ${e.message}", e)
                        android.util.Log.e("AutoSendQueue", "Exception type: ${e.javaClass.simpleName}")
                        e.printStackTrace()
                        throw e
                    }
                } else {
                    // Single part message
                    try {
                        android.util.Log.i("AutoSendQueue", "→→→ CALLING sendTextMessage() NOW...")
                        sms.sendTextMessage(address, null, text, sentIntent, deliveredIntent)
                        android.util.Log.i("AutoSendQueue", "✓✓✓✓✓ SMS SEND CALLED: '$text' to $address")
                        android.util.Log.i("AutoSendQueue", "✓✓✓✓✓ VERIFICATION: sendTextMessage() method was successfully called")
                        android.util.Log.i("AutoSendQueue", "✓✓✓✓✓ Waiting for system confirmation via SMS_SENT broadcast...")
                        notifyStatus("📤 Sending message...")
                    } catch (e: Exception) {
                        android.util.Log.e("AutoSendQueue", "✗✗✗✗✗ EXCEPTION IN sendTextMessage CALL: ${e.message}", e)
                        android.util.Log.e("AutoSendQueue", "Exception type: ${e.javaClass.simpleName}")
                        e.printStackTrace()
                        throw e
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("AutoSendQueue", "✗✗ EXCEPTION SENDING SMS: Failed to send '$text' to $address: ${e.message}", e)
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
        // Continue processing immediately (no delay) to ensure all messages are sent
        android.util.Log.d("AutoSendQueue", "Message processing complete, continuing to drain queue (queue size: ${queue.size})...")
        // Use postDelayed with 0ms to ensure it runs after current handler tasks
        handler.postDelayed({ 
            android.util.Log.d("AutoSendQueue", "Handler posting next drain() call to continue processing")
            drain(context) 
        }, 0L)
    }
}
