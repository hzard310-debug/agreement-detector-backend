package com.example.agreementdetector

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView

class PaymentDetailsFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_payment_details, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val paymentDetailsEditText = view.findViewById<TextInputEditText>(R.id.paymentDetailsEditText)
        val saveButton = view.findViewById<MaterialButton>(R.id.savePaymentDetailsButton)
        val statusText = view.findViewById<MaterialTextView>(R.id.paymentDetailsStatusText)
        
        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)
        
        // Load existing payment details
        val existingDetails = prefs.getString("payment_details", "")
        if (existingDetails != null && existingDetails.isNotEmpty()) {
            paymentDetailsEditText.setText(existingDetails)
            statusText.text = "✓ Payment details saved"
            statusText.setTextColor(0xFF00D4FF.toInt())
        } else {
            statusText.text = "⚠ No payment details saved"
            statusText.setTextColor(0xFFFF6B6B.toInt())
        }
        
        saveButton.setOnClickListener {
            val paymentDetails = paymentDetailsEditText.text?.toString()?.trim() ?: ""
            
            if (paymentDetails.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter payment details", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // Save payment details
            prefs.edit().putString("payment_details", paymentDetails).apply()
            
            statusText.text = "✓ Payment details saved successfully"
            statusText.setTextColor(0xFF00D4FF.toInt())
            Toast.makeText(requireContext(), "Payment details saved", Toast.LENGTH_SHORT).show()
        }
    }
}

