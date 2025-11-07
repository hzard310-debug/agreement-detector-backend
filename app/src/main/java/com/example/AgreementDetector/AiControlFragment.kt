package com.example.agreementdetector

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView

class AiControlFragment : Fragment() {
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
        
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        
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
            // Start foreground service to keep app alive when screen is locked
            SmsProcessingService.start(requireContext())
            Toast.makeText(requireContext(), "AI auto-responses enabled", Toast.LENGTH_SHORT).show()
            // Scan all messages when AI is enabled
            com.example.agreementdetector.ai.MessageScanner.scanAllMessages(requireContext())
        }
        
        disableButton.setOnClickListener {
            prefs.edit().putBoolean("ai_response_enabled", false).apply()
            updateStatus()
            // Stop foreground service when AI is disabled
            SmsProcessingService.stop(requireContext())
            Toast.makeText(requireContext(), "AI auto-responses disabled", Toast.LENGTH_SHORT).show()
        }
        
        updateStatus()
    }
}
