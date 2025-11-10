package com.example.agreementdetector.ai

import android.content.Context
import android.provider.Telephony
import android.util.Log
import com.example.agreementdetector.SmsProcessingService
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

object MessageScanner {
    private const val TAG = "MessageScanner"
    private val scanning = AtomicBoolean(false)
    @Volatile private var pendingRescan = false

    fun scanAllMessages(context: Context) {
        val appContext = context.applicationContext
        if (!scanning.compareAndSet(false, true)) {
            pendingRescan = true
            return
        }
        Thread {
            try {
                performScan(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning messages: ${e.message}", e)
            } finally {
                scanning.set(false)
                if (pendingRescan) {
                    pendingRescan = false
                    scanAllMessages(appContext)
                }
            }
        }.start()
    }

    private fun performScan(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val respondedMessages = prefs.getStringSet("responded_messages", emptySet()) ?: emptySet()
        val queuedMessages = prefs.getStringSet("scan_queued_messages", emptySet())?.toMutableSet() ?: mutableSetOf()

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val address = cursor.getString(1) ?: continue
                val body = cursor.getString(2) ?: continue
                val hash = hashMessage(address, body)

                if (respondedMessages.contains(hash) || queuedMessages.contains(hash)) {
                    continue
                }

                queuedMessages.add(hash)
                prefs.edit().putStringSet("scan_queued_messages", HashSet(queuedMessages)).apply()
                SmsProcessingService.startWithSms(context, address, body)
                Log.d(TAG, "Queued message for AI review from $address")
            }
        }
    }

    fun markMessageResponded(context: Context, hash: String) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val queuedMessages = prefs.getStringSet("scan_queued_messages", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (queuedMessages.remove(hash)) {
            prefs.edit().putStringSet("scan_queued_messages", HashSet(queuedMessages)).apply()
            Log.d(TAG, "Marked message hash as processed: $hash")
        }
    }

    private fun hashMessage(address: String, message: String): String {
        val combined = "$address|${message.trim().lowercase()}"
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(combined.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}

