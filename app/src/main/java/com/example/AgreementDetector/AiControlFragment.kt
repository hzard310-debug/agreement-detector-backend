package com.example.agreementdetector

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import java.text.SimpleDateFormat
import java.util.*

class AiControlFragment : Fragment() {
    private val statusLogHandler = Handler(Looper.getMainLooper())
    private val statusLog = mutableListOf<String>()
    private val maxLogLines = 50
    private var queueCountText: MaterialTextView? = null
    private var statusLogText: MaterialTextView? = null
    
    // Status listeners
    private val messageScannerListener = object : com.example.agreementdetector.ai.MessageScanner.StatusListener {
        override fun onStatusUpdate(status: String) {
            addStatusLog(status)
        }
        
        override fun onQueueCountUpdate(count: Int) {
            updateQueueCount(count)
        }
    }
    
    private val autoSendQueueListener = object : AutoSendQueue.Listener {
        override fun onQueueProgress(pending: Int) {
            updateQueueCount(pending)
        }
        
        override fun onStatusUpdate(status: String) {
            addStatusLog(status)
        }
    }
    
    private fun updateQueueCount(count: Int) {
        statusLogHandler.post {
            queueCountText?.let { textView ->
                if (count > 0) {
                    textView.text = "Pending Responses: $count"
                    textView.setTextColor(0xFFFFA500.toInt()) // Orange
                } else {
                    textView.text = "No Pending Responses"
                    textView.setTextColor(0xFF00D4FF.toInt()) // Cyan
                }
            }
        }
    }
    
    private fun addStatusLog(message: String) {
        statusLogHandler.post {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val logEntry = "[$timestamp] $message"
            statusLog.add(logEntry)
            
            // Keep only the last maxLogLines entries
            if (statusLog.size > maxLogLines) {
                statusLog.removeAt(0)
            }
            
            // Update the text view
            statusLogText?.text = statusLog.joinToString("\n")
            
            // Auto-scroll to bottom
            statusLogText?.parent?.let { parent ->
                if (parent is android.widget.ScrollView) {
                    parent.post {
                        parent.fullScroll(android.view.View.FOCUS_DOWN)
                    }
                }
            }
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_ai_control, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val enableButton = view.findViewById<MaterialButton>(R.id.enableAiButton)
        val disableButton = view.findViewById<MaterialButton>(R.id.disableAiButton)
        val statusText = view.findViewById<MaterialTextView>(R.id.aiStatusText)
        queueCountText = view.findViewById<MaterialTextView>(R.id.queueCountText)
        statusLogText = view.findViewById<MaterialTextView>(R.id.statusLogText)
        
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        
        // Register status listeners
        com.example.agreementdetector.ai.MessageScanner.addStatusListener(messageScannerListener)
        AutoSendQueue.addListener(autoSendQueueListener)
        
        fun updateStatus() {
            val isEnabled = prefs.getBoolean("ai_response_enabled", false)
            if (isEnabled) {
                statusText.text = "✓ AI is ENABLED"
                statusText.setTextColor(0xFF00D4FF.toInt())
            } else {
                statusText.text = "⚠ AI is DISABLED"
                statusText.setTextColor(0xFFFF6B6B.toInt())
            }
            enableButton.isEnabled = !isEnabled
            disableButton.isEnabled = isEnabled
        }
        
        // Initialize queue count
        updateQueueCount(AutoSendQueue.pendingCount())
        
        // Add initial log entry
        addStatusLog("System ready")
        
        enableButton.setOnClickListener {
            // Check if payment details are saved
            val paymentDetails = prefs.getString("payment_details", "")?.trim() ?: ""
            if (paymentDetails.isEmpty()) {
                // Show error dialog
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Payment Details Required")
                    .setMessage("Please go to the Payment Details tab and enter the payment details before enabling AI auto-responses.")
                    .setPositiveButton("Go to Payment Details") { _, _ ->
                        // Switch to Payment Details tab
                        val viewPager = requireActivity().findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)
                        viewPager?.currentItem = 1 // Payment Details is at position 1
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return@setOnClickListener
            }
            
            prefs.edit().putBoolean("ai_response_enabled", true).apply()
            updateStatus()
            addStatusLog("AI enabled - will process new incoming messages")
            // Start foreground service to keep app alive when screen is locked
            SmsProcessingService.start(requireContext())
            Toast.makeText(requireContext(), "AI auto-responses enabled", Toast.LENGTH_SHORT).show()
            // REMOVED: Full scan feature - only process new incoming messages
        }
        
        disableButton.setOnClickListener {
            prefs.edit().putBoolean("ai_response_enabled", false).apply()
            updateStatus()
            // Stop foreground service when AI is disabled
            SmsProcessingService.stop(requireContext())
            addStatusLog("AI disabled")
            Toast.makeText(requireContext(), "AI auto-responses disabled", Toast.LENGTH_SHORT).show()
        }
        
        updateStatus()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // Unregister listeners to prevent memory leaks
        com.example.agreementdetector.ai.MessageScanner.removeStatusListener(messageScannerListener)
        AutoSendQueue.removeListener(autoSendQueueListener)
    }
}
