package com.pgeneratorplus.android.hdr

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Window
import android.view.WindowManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * HDR controller for Android devices.
 *
 * Supports two HDR control paths:
 *
 * 1. **Amlogic sysfs** (Android TV boxes, Chromecast with Google TV):
 *    Uses /sys/class/amhdmitx/amhdmitx0/ to directly control the HDMI output
 *    HDR mode, color space, bit depth, and static metadata.
 *
 *    Key discovery: the `config` node is the correct way to trigger HDR/HLG/SDR
 *    on the HDMI output (sets DRM InfoFrame). Writing to the old `hdr_mode` node
 *    under amhdmitx does NOT exist on many devices. The `am_vecm` hdr_mode param
 *    controls internal video processing but not the HDMI InfoFrame.
 *
 *    Config commands:
 *      "hdr,2"  → HDR10 (SMPTE ST.2084 PQ)
 *      "hlg"    → HLG
 *      "sdr"    → SDR (BT.1886)
 *
 *    Attr format: "rgb,10bit" / "444,10bit" / "422,12bit"
 *
 *    Debug node accepts HDR metadata:
 *      "HDR <eotf> <type> <Rx> <Ry> <Gx> <Gy> <Bx> <By> <Wx> <Wy> <maxDML> <minDML> <maxCLL> <maxFALL>"
 *
 * 2. **Android Display API** (API 26+):
 *    Uses Window.colorMode for standard Android HDR support.
 *    Limited compared to sysfs but works across non-rooted devices.
 *
 * Resolution detection:
 *    /sys/class/display/mode returns e.g. "2160p60hz", "1080p60hz"
 *    Falls back to Android DisplayMetrics.
 */
object HdrController {

 private const val TAG = "HdrController"

 // Amlogic sysfs paths
 private const val AMHDMITX_PATH = "/sys/class/amhdmitx/amhdmitx0/"
 private const val ATTR_PATH = "${AMHDMITX_PATH}attr"
 private const val CONFIG_PATH = "${AMHDMITX_PATH}config"
 private const val HDR_CAP_PATH = "${AMHDMITX_PATH}hdr_cap"
 private const val HDR_STATUS_PATH = "${AMHDMITX_PATH}hdmi_hdr_status"
 private const val DEBUG_PATH = "${AMHDMITX_PATH}debug"
 private const val DISP_CAP_PATH = "${AMHDMITX_PATH}disp_cap"
 private const val DISPLAY_MODE_PATH = "/sys/class/display/mode"

 // am_vecm parameters (video processing layer)
 private const val VECM_HDR_MODE_PATH = "/sys/module/am_vecm/parameters/hdr_mode"
 private const val VECM_HDR_POLICY_PATH = "/sys/module/am_vecm/parameters/hdr_policy"

 /**
  * Check if this is an Amlogic-based device.
  */
 fun isAmlogicDevice(): Boolean {
  if (File(AMHDMITX_PATH).exists()) return true

  try {
   val hardware = getSystemProperty("ro.hardware") ?: ""
   val platform = getSystemProperty("ro.board.platform") ?: ""
   if (hardware.contains("amlogic", ignoreCase = true) ||
    platform.contains("amlogic", ignoreCase = true) ||
    platform.startsWith("meson", ignoreCase = true) ||
    platform.startsWith("gx", ignoreCase = true) ||
    platform == "sabrina" || platform == "sm1" || platform == "sc2"
   ) {
    return true
   }
  } catch (e: Exception) { }

  return false
 }

 /**
  * Get HDR capabilities information as a human-readable string.
  */
 fun getHdrInfo(context: Context): String {
  val sb = StringBuilder()

  // Try Amlogic sysfs first
  if (isAmlogicDevice()) {
   val hdrCap = readSysfs(HDR_CAP_PATH)
   if (hdrCap.isNotEmpty()) {
    sb.append("Amlogic HDR capabilities:\n$hdrCap\n")
   }
   val hdrStatus = readSysfs(HDR_STATUS_PATH)
   if (hdrStatus.isNotEmpty()) {
    sb.append("Current HDR status: $hdrStatus\n")
   }
  }

  // Android Display API (API 26+)
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
   try {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val display = wm.defaultDisplay
    val hdrCaps = display.hdrCapabilities
    if (hdrCaps != null) {
     val types = hdrCaps.supportedHdrTypes
     val typeNames = types.map { type ->
      when (type) {
       Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
       Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
       Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
       Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
       else -> "Unknown($type)"
      }
     }
     sb.append("Android HDR types: ${typeNames.joinToString(", ")}\n")
     sb.append("Max luminance: ${hdrCaps.desiredMaxLuminance} nits\n")
     sb.append("Max avg luminance: ${hdrCaps.desiredMaxAverageLuminance} nits\n")
     sb.append("Min luminance: ${hdrCaps.desiredMinLuminance} nits\n")
    }
   } catch (e: Exception) {
    Log.e(TAG, "Failed to read Android HDR caps", e)
   }
  }

  if (sb.isEmpty()) sb.append("No HDR information available")
  return sb.toString()
 }

 /**
  * Set HDR output mode via HDMI InfoFrame.
  *
  * On Amlogic: writes to config sysfs to set the DRM InfoFrame EOTF.
  *   "hdr,2" → HDR10 (PQ), "hlg" → HLG, "sdr" → SDR
  * On other devices: sets window colorMode via Android API.
  *
  * @param hdr Enable HDR output
  * @param activity Current activity (for window color mode)
  */
 fun setHdrMode(hdr: Boolean, activity: Activity?) {
  if (isAmlogicDevice()) {
   // Don't set config here — applySignalSettings handles the full
   // HDR/SDR switch with the correct EOTF. Just set am_vecm processing.
   if (hdr) {
    writeSysfs(VECM_HDR_MODE_PATH, "1")
    writeSysfs(VECM_HDR_POLICY_PATH, "0")
    Log.i(TAG, "Amlogic vecm HDR processing enabled")
   } else {
    writeSysfs(VECM_HDR_MODE_PATH, "0")
    writeSysfs(VECM_HDR_POLICY_PATH, "0")
    Log.i(TAG, "Amlogic vecm HDR processing disabled")
   }
  }

  // Android Display API
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
   activity.runOnUiThread {
    try {
     val window = activity.window
     window.colorMode = if (hdr) {
      android.content.pm.ActivityInfo.COLOR_MODE_HDR
     } else {
      android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
     }
     Log.i(TAG, "Window color mode set to ${if (hdr) "HDR" else "Default"}")
    } catch (e: Exception) {
     Log.e(TAG, "Failed to set window color mode", e)
    }
   }
  }
 }

 /**
  * Apply comprehensive signal settings for HDMI output.
  *
  * On Amlogic devices, writes to sysfs to configure:
  * - Color format and bit depth via `attr` ("rgb,10bit", "422,12bit", etc.)
  * - HDR/SDR mode via `config` ("hdr,2" for PQ, "hlg" for HLG, "sdr" for SDR)
  *   This is what actually sets the DRM InfoFrame and triggers the TV's HDR mode.
  *
  * @param eotf EOTF transfer function: 0=SDR, 2=PQ, 3=HLG
  * @param colorFormat Color format: 0=RGB, 1=YCbCr444, 2=YCbCr422
  * @param colorimetry Colorimetry: 0=BT.709, 1=BT.2020
  * @param bitDepth Bit depth: 8, 10, or 12
  */
 fun applySignalSettings(eotf: Int, colorFormat: Int, colorimetry: Int, bitDepth: Int) {
  Log.i(TAG, "Applying signal settings: EOTF=$eotf colorFormat=$colorFormat " +
   "colorimetry=$colorimetry bitDepth=$bitDepth")

  if (isAmlogicDevice()) {
   // Step 1: Set color format and bit depth via attr
   val formatStr = when (colorFormat) {
    0 -> "rgb"
    1 -> "444"
    2 -> "422"
    else -> "rgb"
   }
   val attr = "$formatStr,${bitDepth}bit"
   writeSysfs(ATTR_PATH, attr)
   Log.i(TAG, "Amlogic attr set to: $attr")

   // Step 2: Set HDR/SDR mode via config — this triggers the HDMI DRM InfoFrame
   when (eotf) {
    0 -> { // SDR
     writeSysfs(CONFIG_PATH, "sdr")
     Log.i(TAG, "Amlogic config: SDR mode")
    }
    2 -> { // PQ (HDR10)
     writeSysfs(CONFIG_PATH, "hdr,2")
     Log.i(TAG, "Amlogic config: HDR10 (PQ) mode")
    }
    3 -> { // HLG
     writeSysfs(CONFIG_PATH, "hlg")
     Log.i(TAG, "Amlogic config: HLG mode")
    }
   }

   // Verify the switch
   val status = readSysfs(HDR_STATUS_PATH)
   Log.i(TAG, "HDMI HDR status after apply: $status")
  }
 }

 /**
  * Set HDR static metadata (DRM InfoFrame).
  *
  * On Amlogic: writes via the debug node in the format:
  *   HDR <eotf> <smd_type> <Rx> <Ry> <Gx> <Gy> <Bx> <By> <Wx> <Wy> <maxDML> <minDML> <maxCLL> <maxFALL>
  *
  * Primaries are BT.2020 scaled by 50000 (CTA-861 format).
  *
  * @param maxCLL Maximum content light level (nits)
  * @param maxFALL Maximum frame-average light level (nits)
  * @param maxDML Maximum display mastering luminance (nits)
  */
 fun setHdrMetadata(maxCLL: Int, maxFALL: Int, maxDML: Int) {
  if (!isAmlogicDevice()) {
   Log.w(TAG, "HDR metadata requires Amlogic device")
   return
  }

  // BT.2020 primaries for DRM InfoFrame (CTA-861, scaled x50000):
  // Red:   (0.708, 0.292)  → 35400, 14600
  // Green: (0.170, 0.797)  → 8500, 39850
  // Blue:  (0.131, 0.046)  → 6550, 2300
  // White: (0.3127, 0.3290) → 15635, 16450
  val metadata = "HDR 2 0 35400 14600 8500 39850 6550 2300 15635 16450 $maxDML 1 $maxCLL $maxFALL"

  writeSysfs(DEBUG_PATH, metadata)
  Log.i(TAG, "HDR metadata set via debug: MaxCLL=$maxCLL MaxFALL=$maxFALL MaxDML=$maxDML")
 }

 /**
  * Set Amlogic color format and bit depth.
  *
  * @param colorFormat "rgb", "444", "422", "420"
  * @param bitDepth 8, 10, or 12
  */
 fun setColorFormat(colorFormat: String, bitDepth: Int) {
  if (!isAmlogicDevice()) return
  val attr = "$colorFormat,${bitDepth}bit"
  writeSysfs(ATTR_PATH, attr)
  Log.i(TAG, "Amlogic color format set to $attr")
 }

 /**
  * Get display resolution.
  *
  * On Amlogic: reads /sys/class/display/mode (e.g. "2160p60hz" → 3840x2160)
  * Falls back to Android DisplayMetrics.
  */
 fun getDisplayResolution(context: Context): Pair<Int, Int> {
  if (isAmlogicDevice()) {
   // Primary: /sys/class/display/mode returns e.g. "2160p60hz", "1080p60hz"
   val mode = readSysfs(DISPLAY_MODE_PATH)
   if (mode.isNotEmpty()) {
    val res = parseDisplayMode(mode)
    if (res != null) return res
   }

   // Fallback: parse VIC from config
   val config = readSysfs(CONFIG_PATH)
   val vicMatch = Regex("cur_VIC:\\s*(\\d+)").find(config)
   if (vicMatch != null) {
    val vic = vicMatch.groupValues[1].toIntOrNull() ?: 0
    val res = vicToResolution(vic)
    if (res != null) return res
   }
  }

  // Fallback: Android Display API
  try {
   val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
   val display = wm.defaultDisplay
   val metrics = android.util.DisplayMetrics()
   display.getRealMetrics(metrics)
   return Pair(metrics.widthPixels, metrics.heightPixels)
  } catch (e: Exception) {
   Log.e(TAG, "Failed to get display resolution", e)
  }

  return Pair(1920, 1080)
 }

 /**
  * Parse Amlogic display mode string to resolution.
  * e.g. "2160p60hz" → 3840x2160, "1080p60hz" → 1920x1080
  */
 private fun parseDisplayMode(mode: String): Pair<Int, Int>? {
  val m = mode.trim().lowercase()
  return when {
   m.startsWith("2160") || m.startsWith("4k2k") -> Pair(3840, 2160)
   m.startsWith("smpte") -> Pair(4096, 2160)
   m.startsWith("1080") -> Pair(1920, 1080)
   m.startsWith("720") -> Pair(1280, 720)
   m.startsWith("576") -> Pair(720, 576)
   m.startsWith("480") -> Pair(720, 480)
   else -> null
  }
 }

 /**
  * Map HDMI VIC to resolution.
  */
 private fun vicToResolution(vic: Int): Pair<Int, Int>? {
  return when (vic) {
   in 93..107 -> Pair(3840, 2160) // 4K VICs
   in 195..200 -> Pair(3840, 2160)
   in 210..219 -> Pair(4096, 2160)
   in 353..380 -> Pair(3840, 2160) // Extended 4K VICs
   16, 31, 32, 33, 34 -> Pair(1920, 1080) // 1080p VICs
   4, 19 -> Pair(1280, 720) // 720p
   else -> null
  }
 }

 /**
  * Get current HDMI HDR status from the device.
  * Returns e.g. "SDR", "HDR10-GAMMA_ST2084", "HDR10-GAMMA_HLG"
  */
 fun getHdrStatus(): String {
  if (isAmlogicDevice()) {
   return readSysfs(HDR_STATUS_PATH).ifEmpty { "unknown" }
  }
  return "unknown"
 }

 /**
  * Keep screen on / prevent sleep.
  */
 fun keepScreenOn(activity: Activity, keepOn: Boolean) {
  activity.runOnUiThread {
   if (keepOn) {
    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
   } else {
    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
   }
  }
 }

 /**
  * Get Amlogic display capabilities (supported resolutions/refresh rates).
  */
 fun getDisplayCapabilities(): String {
  return if (isAmlogicDevice()) {
   readSysfs(DISP_CAP_PATH)
  } else {
   "Display capabilities not available on non-Amlogic devices"
  }
 }

 private fun readSysfs(path: String): String {
  return try {
   val file = File(path)
   if (file.exists() && file.canRead()) {
    file.readText().trim()
   } else {
    execCommand("cat $path")?.trim() ?: ""
   }
  } catch (e: Exception) {
   ""
  }
 }

 private fun writeSysfs(path: String, value: String) {
  try {
   val file = File(path)
   if (file.exists() && file.canWrite()) {
    file.writeText(value)
    Log.d(TAG, "Wrote '$value' to $path (direct)")
   } else {
    // Try with su for rooted devices
    val result = execCommand("echo '$value' > $path")
    if (result != null) {
     Log.d(TAG, "Wrote '$value' to $path (via su)")
    } else {
     Log.w(TAG, "Failed to write '$value' to $path (no root?)")
    }
   }
  } catch (e: Exception) {
   // Try with su as fallback
   try {
    execCommand("echo '$value' > $path")
   } catch (e2: Exception) {
    Log.e(TAG, "Failed to write '$value' to $path", e2)
   }
  }
 }

 private fun getSystemProperty(key: String): String? {
  return try {
   val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
   val reader = BufferedReader(InputStreamReader(process.inputStream))
   val result = reader.readLine()
   process.waitFor()
   result
  } catch (e: Exception) { null }
 }

 private fun execCommand(command: String): String? {
  // Try direct shell first, then su
  return try {
   val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
   val reader = BufferedReader(InputStreamReader(process.inputStream))
   val result = reader.readText()
   process.waitFor()
   if (process.exitValue() == 0 && result.isNotEmpty()) result
   else {
    // Try with su
    val suProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
    val suReader = BufferedReader(InputStreamReader(suProcess.inputStream))
    val suResult = suReader.readText()
    suProcess.waitFor()
    if (suProcess.exitValue() == 0) suResult else null
   }
  } catch (e: Exception) { null }
 }
}
