package com.pgeneratorplus.android

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AlertDialog
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
 * Uses AlertDialog selectors instead of Spinners for Android TV D-pad support.
 */
class MainActivity : AppCompatActivity() {

 companion object {
  private const val TAG = "MainActivity"
 }

 // Current selection indices for each setting
 private var eotfIndex = 0
 private var bitDepthIndex = 0
 private var colorFormatIndex = 0
 private var colorimetryIndex = 0
 private var quantRangeIndex = 0

 // Options arrays
 private val eotfOptions = arrayOf("SDR (BT.1886)", "PQ (HDR10)", "HLG", "Dolby Vision")
 private val bitDepthOptions = arrayOf("8-bit", "10-bit")
 private val colorFormatOptions = arrayOf("RGB", "YCbCr 4:4:4", "YCbCr 4:2:2")
 private val colorimetryOptions = arrayOf("BT.709", "BT.2020")
 private val quantRangeOptions = arrayOf("Auto", "Limited (16-235)", "Full (0-255)")

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

 /**
  * Show a single-choice AlertDialog that works with D-pad navigation.
  * Uses a custom layout with focus-state background so the currently
  * highlighted item is clearly visible when navigating with D-pad.
  * On selection, calls the callback and updates the button text.
  */
 private fun showSelector(title: String, options: Array<String>, currentIndex: Int,
  button: TextView, onSelected: (Int) -> Unit) {
  val adapter = android.widget.ArrayAdapter(this,
   R.layout.dialog_singlechoice_item, android.R.id.text1, options)

  val dlg = AlertDialog.Builder(this, R.style.SelectorDialog)
   .setTitle(title)
   .setSingleChoiceItems(adapter, currentIndex) { dialog, which ->
    button.text = options[which]
    onSelected(which)
    dialog.dismiss()
   }
   .setNegativeButton("Cancel", null)
   .create()

  dlg.setOnShowListener {
   dlg.listView?.let { lv ->
    // Ensure single-choice mode and D-pad focus handling
    lv.choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
    lv.setItemChecked(currentIndex, true)
    lv.setSelection(currentIndex)
    // Let the ListView handle focus — items must NOT be focusable
    lv.itemsCanFocus = false
    lv.isFocusable = true
    lv.isFocusableInTouchMode = true
    lv.post { lv.requestFocus() }
   }
  }
  dlg.show()
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

  // --- Signal Settings (AlertDialog selectors for D-pad) ---
  val btnEotf = findViewById<TextView>(R.id.spinnerEotf)
  val btnBitDepth = findViewById<TextView>(R.id.spinnerBitDepth)
  val btnColorFormat = findViewById<TextView>(R.id.spinnerColorFormat)
  val btnColorimetry = findViewById<TextView>(R.id.spinnerColorimetry)
  val btnQuantRange = findViewById<TextView>(R.id.spinnerQuantRange)

  // EOTF selector
  btnEotf.setOnClickListener {
   showSelector("Select EOTF", eotfOptions, eotfIndex, btnEotf) { pos ->
    eotfIndex = pos
    when (pos) {
     0 -> { // SDR
      AppState.applyEotfMode(0)
     }
     1 -> { // PQ (HDR10)
      AppState.applyEotfMode(2)
      // Auto-switch to BT.2020 + 10-bit for HDR
      AppState.colorimetry = 1
      colorimetryIndex = 1
      btnColorimetry.text = colorimetryOptions[1]
      if (AppState.bitDepth < 10) {
       AppState.bitDepth = 10
       bitDepthIndex = 1
       btnBitDepth.text = bitDepthOptions[1]
      }
     }
     2 -> { // HLG
      AppState.applyEotfMode(3)
      AppState.colorimetry = 1
      colorimetryIndex = 1
      btnColorimetry.text = colorimetryOptions[1]
      if (AppState.bitDepth < 10) {
       AppState.bitDepth = 10
       bitDepthIndex = 1
       btnBitDepth.text = bitDepthOptions[1]
      }
         }
         3 -> { // Dolby Vision
      AppState.applyEotfMode(4)
      AppState.colorimetry = 1
      colorimetryIndex = 1
      btnColorimetry.text = colorimetryOptions[1]
      if (AppState.bitDepth < 10) {
       AppState.bitDepth = 10
       bitDepthIndex = 1
       btnBitDepth.text = bitDepthOptions[1]
      }
     }
    }
    AppState.modeChanged = true
    Log.i(TAG, "EOTF changed: ${eotfOptions[pos]} (hdr=${AppState.hdr})")
   }
  }

  // Bit Depth selector
  btnBitDepth.setOnClickListener {
   showSelector("Select Bit Depth", bitDepthOptions, bitDepthIndex, btnBitDepth) { pos ->
    bitDepthIndex = pos
    AppState.bitDepth = if (pos == 0) 8 else 10
    AppState.modeChanged = true
    Log.i(TAG, "Bit depth changed: ${AppState.bitDepth}")
   }
  }

  // Color Format selector
  btnColorFormat.setOnClickListener {
   showSelector("Select Color Format", colorFormatOptions, colorFormatIndex, btnColorFormat) { pos ->
    colorFormatIndex = pos
    AppState.colorFormat = pos
    AppState.modeChanged = true
    Log.i(TAG, "Color format changed: ${colorFormatOptions[pos]}")
   }
  }

  // Colorimetry selector
  btnColorimetry.setOnClickListener {
   showSelector("Select Colorimetry", colorimetryOptions, colorimetryIndex, btnColorimetry) { pos ->
    colorimetryIndex = pos
    AppState.colorimetry = pos
    AppState.modeChanged = true
    Log.i(TAG, "Colorimetry changed: ${colorimetryOptions[pos]}")
   }
  }

  // Quantization Range selector
  btnQuantRange.setOnClickListener {
   showSelector("Select Quantization Range", quantRangeOptions, quantRangeIndex, btnQuantRange) { pos ->
    quantRangeIndex = pos
    AppState.quantRange = pos
    AppState.modeChanged = true
    Log.i(TAG, "Quant range changed: ${quantRangeOptions[pos]}")
   }
  }

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

    // HDR / DV probe mode (diagnostic)
    val btnHdrProbe = findViewById<Button>(R.id.btnHdrProbe)
    btnHdrProbe.setOnClickListener {
     startActivity(Intent(this, HdrProbeActivity::class.java))
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
