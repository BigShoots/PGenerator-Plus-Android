package com.pgeneratorplus.android

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.pgeneratorplus.android.hdr.HdrController
import com.pgeneratorplus.android.model.AppState
import com.pgeneratorplus.android.network.DiscoveryService
import com.pgeneratorplus.android.network.WebUIServer
import java.net.NetworkInterface

/**
 * Main configuration activity for PGenerator+ Android.
 *
 * Displays device info, network configuration, and signal settings.
 * Starts WebUI server and discovery service immediately on launch.
 * Launches PatternActivity to begin pattern generation with all servers active.
 */
class MainActivity : AppCompatActivity() {

 companion object {
  private const val TAG = "MainActivity"
 }

 private var suppressSpinnerEvents = false

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContentView(R.layout.activity_main)

  startServices()
  setupUI()
 }

 /**
  * Start WebUI and Discovery services immediately so the WebUI
  * is accessible from the main screen without launching PatternActivity.
  */
 private fun startServices() {
  try {
   WebUIServer.startInstance(this, 8080)
  } catch (e: Exception) {
   Log.e(TAG, "Failed to start WebUI server", e)
  }

  try {
   DiscoveryService.startInstance("PGeneratorPlus-Android")
  } catch (e: Exception) {
   Log.e(TAG, "Failed to start Discovery service", e)
  }
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

  // Web UI info
  val tvWebUI = findViewById<TextView>(R.id.tvWebUI)
  tvWebUI.text = "Web UI: http://$ip:8080"

  // --- Signal Settings Spinners ---
  suppressSpinnerEvents = true

  // EOTF
  val spinnerEotf = findViewById<Spinner>(R.id.spinnerEotf)
  val eotfOptions = arrayOf("SDR (BT.1886)", "PQ (HDR10)", "HLG")
  spinnerEotf.adapter = ArrayAdapter(this, R.layout.spinner_item, eotfOptions).also {
   it.setDropDownViewResource(R.layout.spinner_dropdown_item)
  }
  spinnerEotf.setSelection(0)
  spinnerEotf.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
   override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
    if (suppressSpinnerEvents) return
    when (position) {
     0 -> { // SDR
      AppState.eotf = 0
      AppState.hdr = false
     }
     1 -> { // PQ (HDR10)
      AppState.eotf = 2
      AppState.hdr = true
      // Auto-switch to BT.2020 + 10-bit for HDR
      AppState.colorimetry = 1
      val spinnerColorimetry = findViewById<Spinner>(R.id.spinnerColorimetry)
      spinnerColorimetry.setSelection(1)
      if (AppState.bitDepth < 10) {
       AppState.bitDepth = 10
       val spinnerBitDepth = findViewById<Spinner>(R.id.spinnerBitDepth)
       spinnerBitDepth.setSelection(1)
      }
     }
     2 -> { // HLG
      AppState.eotf = 3
      AppState.hdr = true
      AppState.colorimetry = 1
      val spinnerColorimetry = findViewById<Spinner>(R.id.spinnerColorimetry)
      spinnerColorimetry.setSelection(1)
      if (AppState.bitDepth < 10) {
       AppState.bitDepth = 10
       val spinnerBitDepth = findViewById<Spinner>(R.id.spinnerBitDepth)
       spinnerBitDepth.setSelection(1)
      }
     }
    }
    AppState.modeChanged = true
    Log.i(TAG, "EOTF changed: ${eotfOptions[position]} (hdr=${AppState.hdr})")
   }
   override fun onNothingSelected(parent: AdapterView<*>?) {}
  }

  // Bit Depth
  val spinnerBitDepth = findViewById<Spinner>(R.id.spinnerBitDepth)
  val bitDepthOptions = arrayOf("8-bit", "10-bit")
  spinnerBitDepth.adapter = ArrayAdapter(this, R.layout.spinner_item, bitDepthOptions).also {
   it.setDropDownViewResource(R.layout.spinner_dropdown_item)
  }
  spinnerBitDepth.setSelection(0)
  spinnerBitDepth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
   override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
    if (suppressSpinnerEvents) return
    AppState.bitDepth = if (position == 0) 8 else 10
    AppState.modeChanged = true
    Log.i(TAG, "Bit depth changed: ${AppState.bitDepth}")
   }
   override fun onNothingSelected(parent: AdapterView<*>?) {}
  }

  // Color Format
  val spinnerColorFormat = findViewById<Spinner>(R.id.spinnerColorFormat)
  val colorFormatOptions = arrayOf("RGB", "YCbCr 4:4:4", "YCbCr 4:2:2")
  spinnerColorFormat.adapter = ArrayAdapter(this, R.layout.spinner_item, colorFormatOptions).also {
   it.setDropDownViewResource(R.layout.spinner_dropdown_item)
  }
  spinnerColorFormat.setSelection(0)
  spinnerColorFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
   override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
    if (suppressSpinnerEvents) return
    AppState.colorFormat = position
    AppState.modeChanged = true
    Log.i(TAG, "Color format changed: ${colorFormatOptions[position]}")
   }
   override fun onNothingSelected(parent: AdapterView<*>?) {}
  }

  // Colorimetry
  val spinnerColorimetry = findViewById<Spinner>(R.id.spinnerColorimetry)
  val colorimetryOptions = arrayOf("BT.709", "BT.2020")
  spinnerColorimetry.adapter = ArrayAdapter(this, R.layout.spinner_item, colorimetryOptions).also {
   it.setDropDownViewResource(R.layout.spinner_dropdown_item)
  }
  spinnerColorimetry.setSelection(0)
  spinnerColorimetry.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
   override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
    if (suppressSpinnerEvents) return
    AppState.colorimetry = position
    AppState.modeChanged = true
    Log.i(TAG, "Colorimetry changed: ${colorimetryOptions[position]}")
   }
   override fun onNothingSelected(parent: AdapterView<*>?) {}
  }

  // Quantization Range
  val spinnerQuantRange = findViewById<Spinner>(R.id.spinnerQuantRange)
  val quantRangeOptions = arrayOf("Auto", "Limited (16-235)", "Full (0-255)")
  spinnerQuantRange.adapter = ArrayAdapter(this, R.layout.spinner_item, quantRangeOptions).also {
   it.setDropDownViewResource(R.layout.spinner_dropdown_item)
  }
  spinnerQuantRange.setSelection(0)
  spinnerQuantRange.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
   override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
    if (suppressSpinnerEvents) return
    AppState.quantRange = position
    AppState.modeChanged = true
    Log.i(TAG, "Quant range changed: ${quantRangeOptions[position]}")
   }
   override fun onNothingSelected(parent: AdapterView<*>?) {}
  }

  // Allow spinner events after initial setup
  spinnerEotf.post { suppressSpinnerEvents = false }

  // Start button — launches pattern activity with all servers
  val btnStartPGen = findViewById<Button>(R.id.btnStartPGen)
  btnStartPGen.setOnClickListener {
   val intent = Intent(this, PatternActivity::class.java).apply {
    putExtra("mode", "pgen")
    putExtra("hdr", AppState.hdr)
    putExtra("bits", AppState.bitDepth)
    putExtra("eotf", AppState.eotf)
    putExtra("colorFormat", AppState.colorFormat)
    putExtra("colorimetry", AppState.colorimetry)
    putExtra("quantRange", AppState.quantRange)
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
    putExtra("mode", if (AppState.hdr) "resolve_hdr" else "resolve_sdr")
    putExtra("resolveIp", resolveIp)
    putExtra("resolvePort", resolvePort)
    putExtra("hdr", AppState.hdr)
    putExtra("bits", AppState.bitDepth)
    putExtra("eotf", AppState.eotf)
    putExtra("colorFormat", AppState.colorFormat)
    putExtra("colorimetry", AppState.colorimetry)
    putExtra("quantRange", AppState.quantRange)
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
   putExtra("eotf", AppState.eotf)
   putExtra("colorFormat", AppState.colorFormat)
   putExtra("colorimetry", AppState.colorimetry)
   putExtra("quantRange", AppState.quantRange)
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
