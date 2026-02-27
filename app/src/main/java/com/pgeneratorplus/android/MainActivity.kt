package com.pgeneratorplus.android

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.pgeneratorplus.android.hdr.HdrController
import com.pgeneratorplus.android.model.AppState
import java.net.NetworkInterface

/**
 * Main configuration activity for PGenerator+ Android.
 *
 * Displays device info, network configuration, and signal settings.
 * Launches PatternActivity to begin pattern generation with all servers active.
 */
class MainActivity : AppCompatActivity() {

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContentView(R.layout.activity_main)

  setupUI()
 }

 private fun setupUI() {
  // Device info
  val tvDeviceInfo = findViewById<TextView>(R.id.tvDeviceInfo)
  val ip = getLocalIpAddress()
  val res = HdrController.getDisplayResolution(this)
  val hdrInfo = HdrController.getHdrInfo(this)
  val isAmlogic = HdrController.isAmlogicDevice()

  tvDeviceInfo.text = buildString {
   appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
   appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
   appendLine("Resolution: ${res.first}x${res.second}")
   appendLine("IP: $ip")
   appendLine("Amlogic: ${if (isAmlogic) "Yes" else "No"}")
   appendLine("HDR: $hdrInfo")
  }

  // Mode spinner
  val spinnerMode = findViewById<Spinner>(R.id.spinnerMode)
  val modes = arrayOf("8-bit SDR", "8-bit HDR", "10-bit SDR", "10-bit HDR")
  spinnerMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
  spinnerMode.setSelection(0)

  spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
   override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
    when (position) {
     0 -> AppState.setMode(8, false)
     1 -> AppState.setMode(8, true)
     2 -> AppState.setMode(10, false)
     3 -> AppState.setMode(10, true)
    }
   }
   override fun onNothingSelected(parent: AdapterView<*>?) {}
  }

  // Web UI info
  val tvWebUI = findViewById<TextView>(R.id.tvWebUI)
  tvWebUI.text = "Web UI: http://$ip:8080"

  // Start button — launches pattern activity with all servers
  val btnStartPGen = findViewById<Button>(R.id.btnStartPGen)
  btnStartPGen.setOnClickListener {
   val intent = Intent(this, PatternActivity::class.java).apply {
    putExtra("mode", "pgen")
    putExtra("hdr", AppState.hdr)
    putExtra("bits", AppState.bitDepth)
   }
   startActivity(intent)
  }

  // Resolve mode
  val btnStartResolve = findViewById<Button>(R.id.btnStartResolve)
  val etResolveIp = findViewById<EditText>(R.id.etResolveIp)
  val etResolvePort = findViewById<EditText>(R.id.etResolvePort)

  btnStartResolve.setOnClickListener {
   val resolveIp = etResolveIp.text.toString().ifEmpty { "192.168.1.100" }
   val resolvePort = etResolvePort.text.toString().toIntOrNull() ?: 20002
   val intent = Intent(this, PatternActivity::class.java).apply {
    putExtra("mode", "resolve_sdr")
    putExtra("resolveIp", resolveIp)
    putExtra("resolvePort", resolvePort)
    putExtra("hdr", AppState.hdr)
    putExtra("bits", AppState.bitDepth)
   }
   startActivity(intent)
  }

  // Built-in patterns
  val btnPluge = findViewById<Button>(R.id.btnPluge)
  val btnBars = findViewById<Button>(R.id.btnBars)
  val btnWindow = findViewById<Button>(R.id.btnWindow)
  val btnRamp = findViewById<Button>(R.id.btnRamp)

  btnPluge.setOnClickListener { launchManualPattern("pluge") }
  btnBars.setOnClickListener { launchManualPattern("bars") }
  btnWindow.setOnClickListener { launchManualPattern("window") }
  btnRamp.setOnClickListener { launchManualPattern("ramp") }
 }

 private fun launchManualPattern(pattern: String) {
  val intent = Intent(this, PatternActivity::class.java).apply {
   putExtra("mode", "manual")
   putExtra("pattern", pattern)
   putExtra("hdr", AppState.hdr)
   putExtra("bits", AppState.bitDepth)
  }
  startActivity(intent)
 }

 private fun getLocalIpAddress(): String {
  try {
   val interfaces = NetworkInterface.getNetworkInterfaces()
   while (interfaces.hasMoreElements()) {
    val iface = interfaces.nextElement()
    val addrs = iface.inetAddresses
    while (addrs.hasMoreElements()) {
     val addr = addrs.nextElement()
     if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
      return addr.hostAddress ?: "unknown"
     }
    }
   }
  } catch (e: Exception) { }
  return "unknown"
 }
}
