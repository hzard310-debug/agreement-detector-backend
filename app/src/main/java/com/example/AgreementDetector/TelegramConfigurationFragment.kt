package com.example.agreementdetector

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class TelegramConfigurationFragment : Fragment() {

    private val client by lazy {
        OkHttpClient.Builder()
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_telegram_configuration, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val botTokenInput = view.findViewById<TextInputEditText>(R.id.telegramBotTokenEditText)
        val channelIdInput = view.findViewById<TextInputEditText>(R.id.telegramChannelIdEditText)
        val forwardUrlInput = view.findViewById<TextInputEditText>(R.id.telegramForwardUrlEditText)
        val apiKeyInput = view.findViewById<TextInputEditText>(R.id.telegramApiKeyEditText)
        val enableSwitch = view.findViewById<SwitchMaterial>(R.id.telegramEnableSwitch)
        val saveButton = view.findViewById<MaterialButton>(R.id.saveTelegramSettingsButton)
        val testButton = view.findViewById<MaterialButton>(R.id.testTelegramSettingsButton)
        val statusText = view.findViewById<MaterialTextView>(R.id.telegramStatusText)

        val prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)

        fun loadSettings() {
            botTokenInput.setText(prefs.getString("telegram_bot_token", ""))
            channelIdInput.setText(prefs.getString("telegram_channel_id", ""))
            forwardUrlInput.setText(prefs.getString("telegram_forward_url", ""))
            apiKeyInput.setText(prefs.getString("telegram_forward_api_key", ""))
            val enabled = prefs.getBoolean("telegram_forward_enabled", false)
            enableSwitch.isChecked = enabled
            updateStatus(statusText, enabled, forwardUrlInput.text?.isNotBlank() == true || botTokenInput.text?.isNotBlank() == true)
        }

        loadSettings()

        saveButton.setOnClickListener {
            val enabled = enableSwitch.isChecked
            prefs.edit()
                .putString("telegram_bot_token", botTokenInput.text?.toString()?.trim() ?: "")
                .putString("telegram_channel_id", channelIdInput.text?.toString()?.trim() ?: "")
                .putString("telegram_forward_url", forwardUrlInput.text?.toString()?.trim() ?: "")
                .putString("telegram_forward_api_key", apiKeyInput.text?.toString()?.trim() ?: "")
                .putBoolean("telegram_forward_enabled", enabled)
                .apply()
            updateStatus(statusText, enabled, forwardUrlInput.text?.isNotBlank() == true || botTokenInput.text?.isNotBlank() == true)
            Toast.makeText(requireContext(), "Telegram settings saved", Toast.LENGTH_SHORT).show()
        }

        testButton.setOnClickListener {
            val enabled = enableSwitch.isChecked
            if (!enabled) {
                Toast.makeText(requireContext(), "Enable Telegram forwarding first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val forwardUrl = forwardUrlInput.text?.toString()?.trim().orEmpty()
            val botToken = botTokenInput.text?.toString()?.trim().orEmpty()
            val channelId = channelIdInput.text?.toString()?.trim().orEmpty()
            val apiKey = apiKeyInput.text?.toString()?.trim().orEmpty()

            if (forwardUrl.isEmpty() && (botToken.isEmpty() || channelId.isEmpty())) {
                Toast.makeText(requireContext(), "Provide either bot token & channel ID or a forward URL", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            testButton.isEnabled = false
            Toast.makeText(requireContext(), "Sending test message...", Toast.LENGTH_SHORT).show()

            Thread {
                try {
                    val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    val testMessage = "Test message from Agreement Detector app at $timestamp"
                    val result = if (forwardUrl.isNotEmpty()) {
                        sendViaForwardService(forwardUrl, apiKey, testMessage)
                    } else {
                        sendDirect(botToken, channelId, testMessage)
                    }
                    requireActivity().runOnUiThread {
                        testButton.isEnabled = true
                        Toast.makeText(requireContext(), result, Toast.LENGTH_LONG).show()
                    }
                } catch (ex: Exception) {
                    requireActivity().runOnUiThread {
                        testButton.isEnabled = true
                        Toast.makeText(requireContext(), "Test failed: ${ex.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private fun updateStatus(statusText: MaterialTextView, enabled: Boolean, hasConfig: Boolean) {
        if (!enabled) {
            statusText.text = "⚠ Telegram forwarding disabled"
            statusText.setTextColor(0xFFFF6B6B.toInt())
            return
        }
        if (hasConfig) {
            statusText.text = "✓ Telegram forwarding enabled"
            statusText.setTextColor(0xFF00D4FF.toInt())
        } else {
            statusText.text = "⚠ Missing configuration details"
            statusText.setTextColor(0xFFFFA500.toInt())
        }
    }

    private fun sendViaForwardService(forwardUrlRaw: String, apiKey: String, message: String): String {
        val url = if (forwardUrlRaw.endsWith("/send", ignoreCase = true)) forwardUrlRaw else forwardUrlRaw.trimEnd('/') + "/send"
        val payload = JSONObject().apply {
            put("message", message)
            put("contact_number", "")
            if (apiKey.isNotEmpty()) {
                put("api_key", apiKey)
            }
        }
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Forward service error: ${response.code}")
            }
        }
        return "Forward service sent test message successfully"
    }

    private fun sendDirect(botToken: String, channelId: String, message: String): String {
        val apiUrl = "https://api.telegram.org/bot$botToken/sendMessage"
        val payload = JSONObject().apply {
            put("chat_id", channelId)
            put("text", message)
        }
        val request = Request.Builder()
            .url(apiUrl)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Telegram API error: ${response.code}")
            }
        }
        return "Telegram received test message successfully"
    }
}


