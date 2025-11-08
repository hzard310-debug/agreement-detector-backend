package com.example.agreementdetector

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {
    
    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.RECEIVE_SMS] == true &&
                perms[Manifest.permission.READ_SMS] == true &&
                perms[Manifest.permission.SEND_SMS] == true
        Toast.makeText(this, if (granted) "Permissions granted" else "Permissions denied", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Toolbar
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        // Add settings menu for quick access
        try {
            toolbar.inflateMenu(R.menu.main_menu)
            toolbar.setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_settings) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                } else false
            }
        } catch (_: Exception) { /* ignore if menu not available */ }

        // Setup ViewPager2 and TabLayout
        val viewPager = findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)

        viewPager.adapter = TabPagerAdapter(this)

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "AI Control"
                1 -> "Payment Details"
                else -> ""
            }
        }.attach()

        requestPermissionsIfNeeded()
        requestBatteryOptimizationExemption()
        
        // Start foreground service to keep app alive when screen is locked
        SmsProcessingService.start(this)
    }
    
    override fun onResume() {
        super.onResume()
        // No scanning - only process new incoming messages
    }

    private fun requestPermissionsIfNeeded() {
        val perms = listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        )
        val missing = perms.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPerms.launch(missing.toTypedArray())
        }
    }
    
    private fun requestBatteryOptimizationExemption() {
        // Request battery optimization exemption so app works when screen is locked
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                                            }
                                            startActivity(intent)
                    Toast.makeText(this, "Please allow battery optimization exemption for background SMS processing", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                    // Fallback: open battery optimization settings
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    } catch (e2: Exception) {
                        Log.e("MainActivity", "Could not open battery optimization settings: ${e2.message}")
                    }
                }
            }
        }
    }
    
    fun markSentMessagesAsRead() {
        Thread {
            try {
                val sentUri = android.provider.Telephony.Sms.Sent.CONTENT_URI
                val currentTime = System.currentTimeMillis()
                val recentTime = currentTime - (2 * 60 * 1000) // Last 2 minutes
                
                // Find recently sent messages
                contentResolver.query(
                    sentUri,
                    arrayOf(
                        android.provider.Telephony.Sms._ID,
                        android.provider.Telephony.Sms.ADDRESS,
                        android.provider.Telephony.Sms.DATE,
                        android.provider.Telephony.Sms.READ
                    ),
                    "${android.provider.Telephony.Sms.DATE} > ? AND ${android.provider.Telephony.Sms.READ} = 0",
                    arrayOf(recentTime.toString()),
                    null
                )?.use { cursor ->
                    val idsToUpdate = mutableListOf<String>()
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        idsToUpdate.add(id.toString())
                    }
                    
                    // Mark them as read
                    for (id in idsToUpdate) {
                        try {
                            val values = android.content.ContentValues().apply {
                                put(android.provider.Telephony.Sms.READ, 1)
                            }
                            contentResolver.update(
                                sentUri,
                                values,
                                "${android.provider.Telephony.Sms._ID} = ?",
                                arrayOf(id)
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("MarkRead", "Error marking message as read: ${e.message}")
                        }
                    }
                    
                    if (idsToUpdate.isNotEmpty()) {
                        android.util.Log.d("MarkRead", "Marked ${idsToUpdate.size} sent messages as read")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MarkRead", "Error in markSentMessagesAsRead: ${e.message}")
            }
        }.start()
    }
}
