package com.pgeneratorplus.android.hdr

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Window
import android.view.WindowManager
import java.io.File

/**
 * HDR controller for Android devices.
 *
 * Supports two HDR control paths:
 *
 * 1. **Amlogic sysfs** (Chromecast, Shield-like devices with Amlogic SoC):
 *    Uses /sys/class/amhdmitx/amhdmitx0/ to directly control the HDMI output
 *    HDR mode, color space, bit depth, and static metadata.
 *
 * 2. **Android Display API** (API 26+):
 *    Uses Window.colorMode and Display.HdrCapabilities for standard Android
 *    HDR support. Limited compared to sysfs but works across all devices.
 *
 * DRM/AVI InfoFrame:
 *   On Amlogic devices, writing to `hdr_mode` and `attr` controls the
 *   DRM and AVI InfoFrames sent over HDMI. This is critical for display
 *   calibration — the TV must see the correct EOTF and colorimetry flags.
 */
object HdrController {

 private const val TAG = "HdrController"

 // Amlogic sysfs paths
 private const val AMHDMITX_PATH = "/sys/class/amhdmitx/amhdmitx0/"
 private const val HDR_MODE_PATH = "${AMHDMITX_PATH}hdr_mode"
 private const val ATTR_PATH = "${AMHDMITX_PATH}attr"
 private const val CONFIG_PATH = "${AMHDMITX_PATH}config"
 private const val HDR_CAP_PATH = "${AMHDMITX_PATH}hdr_cap"
 private const val HDR_MDATA_PATH = "${AMHDMITX_PATH}hdr_mdata"
 private const val DISP_CAP_PATH = "${AMHDMITX_PATH}disp_cap"
 private const val PHY_SIZE_PATH = "${AMHDMITX_PATH}phy_size"

 /**
  * Check if this is an Amlogic-based device.
  */
 fun isAmlogicDevice(): Boolean {
  if (File(AMHDMITX_PATH).exists()) return true

  try {
   val hardware = System.getProperty("ro.hardware") ?: ""
   val platform = System.getProperty("ro.board.platform") ?: ""
   if (hardware.contains("amlogic", ignoreCase = true) ||
    platform.contains("amlogic", ignoreCase = true) ||
    platform.startsWith("meson", ignoreCase = true)
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
  * Set HDR output mode.
  *
  * On Amlogic: writes to hdr_mode sysfs
  * On other devices: sets window colorMode
  *
  * @param hdr Enable HDR output
  * @param activity Current activity (for window color mode)
  */
 fun setHdrMode(hdr: Boolean, activity: Activity?) {
  if (isAmlogicDevice()) {
   if (hdr) {
    writeSysfs(HDR_MODE_PATH, "1")
    writeSysfs(ATTR_PATH, "444,10bit")
    Log.i(TAG, "Amlogic HDR mode enabled (444, 10bit)")
   } else {
    writeSysfs(HDR_MODE_PATH, "0")
    writeSysfs(ATTR_PATH, "444,8bit")
    Log.i(TAG, "Amlogic HDR mode disabled (444, 8bit)")
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
  * Set HDR static metadata (CTA-861 EOTF + mastering display info).
  *
  * On Amlogic: writes to hdr_mdata sysfs in the format expected by the driver.
  * This controls the DRM InfoFrame metadata sent to the TV.
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

  // BT.2020 primaries for DRM InfoFrame:
  // Red:   (0.708, 0.292)
  // Green: (0.170, 0.797)
  // Blue:  (0.131, 0.046)
  // White: (0.3127, 0.3290)
  val metadata = buildString {
   // EOTF: 2 = SMPTE ST 2084 (PQ)
   append("2,")
   // Display primaries (BT.2020) as 16-bit scaled (x 50000)
   append("35400,14600,")  // Red
   append("8500,39850,")   // Green
   append("6550,2300,")    // Blue
   // White point
   append("15635,16450,")
   // Max/min display mastering luminance (in 1 cd/m2 units for max, 0.0001 for min)
   append("${maxDML},0,")
   // MaxCLL, MaxFALL
   append("$maxCLL,$maxFALL")
  }

  writeSysfs(HDR_MDATA_PATH, metadata)
  Log.i(TAG, "HDR metadata set: MaxCLL=$maxCLL MaxFALL=$maxFALL MaxDML=$maxDML")
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
  */
 fun getDisplayResolution(context: Context): Pair<Int, Int> {
  if (isAmlogicDevice()) {
   val config = readSysfs(CONFIG_PATH)
   val match = Regex("(\\d{3,4})x(\\d{3,4})").find(config)
   if (match != null) {
    return Pair(match.groupValues[1].toInt(), match.groupValues[2].toInt())
   }
  }

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
   File(path).readText().trim()
  } catch (e: Exception) {
   ""
  }
 }

 private fun writeSysfs(path: String, value: String) {
  try {
   File(path).writeText(value)
  } catch (e: Exception) {
   // Try with su
   try {
    Runtime.getRuntime().exec(arrayOf("su", "-c", "echo '$value' > $path"))
   } catch (e2: Exception) {
    Log.e(TAG, "Failed to write '$value' to $path", e2)
   }
  }
 }
}
