package com.pgeneratorplus.android.hdr

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Window
import android.view.WindowManager
import com.pgeneratorplus.android.model.AppState
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

 // Cache root access availability (checked once)
 private var rootAccessChecked = false
 private var rootAccessAvailable = false

 /**
  * Check (and cache) whether root (su) access is available.
  * Only runs the actual check once; returns cached result after that.
  */
 fun hasRootAccess(): Boolean {
  if (rootAccessChecked) return rootAccessAvailable
  rootAccessChecked = true
  rootAccessAvailable = try {
   val process = ProcessBuilder("su", "-c", "id")
    .redirectErrorStream(true)
    .start()
   val output = process.inputStream.bufferedReader().readText().trim()
   val exitCode = process.waitFor()
   val hasRoot = exitCode == 0 && output.contains("uid=0")
   Log.i(TAG, "Root access check: ${if (hasRoot) "AVAILABLE" else "NOT AVAILABLE"} (output=$output)")
   hasRoot
  } catch (e: Exception) {
   Log.i(TAG, "Root access check: NOT AVAILABLE (${e.message})")
   false
  }
  return rootAccessAvailable
 }

 /**
  * Check if sysfs HDR control is possible (needs root + Amlogic sysfs).
  */
 fun hasSysfsHdrControl(): Boolean {
  return isAmlogicDevice() && File(AMHDMITX_PATH).exists() && hasRootAccess()
 }

 /**
  * Ensure the Amlogic media codec 10-bit decode support is enabled.
  *
  * This is critical for HDR on Chromecast with Google TV where the
  * `debug.vendor.media.c2.vdec.support_10bit` property defaults to `false`.
  * Without this property, the HEVC decoder only outputs 8-bit even for
  * HDR10 content, and the HWC never triggers HDR output mode on HDMI.
  *
  * The property can be set by the ADB shell user but not by regular apps.
  * This method tries multiple approaches and reports the result.
  *
  * @return true if the property is set to true, false if it couldn't be set
  */
 fun ensure10BitDecodeSupport(): Boolean {
  if (!isAmlogicDevice()) return true // Non-Amlogic doesn't need this

  // Check current value
  val current = getSystemProperty("debug.vendor.media.c2.vdec.support_10bit")
  if (current == "true") {
   Log.i(TAG, "10-bit decode support already enabled")
   return true
  }

  Log.i(TAG, "10-bit decode support is '$current', attempting to enable...")

  // Try 1: Runtime.exec setprop (works if app UID can set debug.* properties)
  try {
   val process = Runtime.getRuntime().exec(
    arrayOf("setprop", "debug.vendor.media.c2.vdec.support_10bit", "true")
   )
   process.waitFor()
   val verify = getSystemProperty("debug.vendor.media.c2.vdec.support_10bit")
   if (verify == "true") {
    Log.i(TAG, "10-bit decode support enabled via setprop")
    return true
   }
  } catch (e: Exception) {
   Log.d(TAG, "setprop attempt failed: ${e.message}")
  }

  // Try 2: android.os.SystemProperties.set() via reflection
  try {
   val clazz = Class.forName("android.os.SystemProperties")
   val setter = clazz.getDeclaredMethod("set", String::class.java, String::class.java)
   setter.invoke(null, "debug.vendor.media.c2.vdec.support_10bit", "true")
   val verify = getSystemProperty("debug.vendor.media.c2.vdec.support_10bit")
   if (verify == "true") {
    Log.i(TAG, "10-bit decode support enabled via SystemProperties.set()")
    return true
   }
  } catch (e: Exception) {
   Log.d(TAG, "SystemProperties.set attempt failed: ${e.message}")
  }

  // Try 3: su setprop (rooted devices)
  if (hasRootAccess()) {
   suExec("setprop debug.vendor.media.c2.vdec.support_10bit true")
   val verify = getSystemProperty("debug.vendor.media.c2.vdec.support_10bit")
   if (verify == "true") {
    Log.i(TAG, "10-bit decode support enabled via su")
    return true
   }
  }

  Log.w(TAG, "Failed to enable 10-bit decode support automatically. " +
   "Run via ADB once per boot: adb shell setprop debug.vendor.media.c2.vdec.support_10bit true")
  return false
 }

 /**
  * Check if 10-bit decode support is currently enabled.
  */
 fun is10BitDecodeEnabled(): Boolean {
  val value = getSystemProperty("debug.vendor.media.c2.vdec.support_10bit")
  return value == "true"
 }

 /**
  * Check if this is an Amlogic-based device.
  */
 fun isAmlogicDevice(): Boolean {
  val sysfsExists = File(AMHDMITX_PATH).exists()
  if (sysfsExists) {
   Log.d(TAG, "isAmlogicDevice: true (sysfs path exists)")
   return true
  }

  try {
   val hardware = getSystemProperty("ro.hardware") ?: ""
   val platform = getSystemProperty("ro.board.platform") ?: ""
   Log.d(TAG, "isAmlogicDevice: hardware='$hardware' platform='$platform'")
   if (hardware.contains("amlogic", ignoreCase = true) ||
    platform.contains("amlogic", ignoreCase = true) ||
    platform.startsWith("meson", ignoreCase = true) ||
    platform.startsWith("gx", ignoreCase = true) ||
    platform == "sabrina" || platform == "sm1" || platform == "sc2"
   ) {
    Log.d(TAG, "isAmlogicDevice: true (property match)")
    return true
   }
  } catch (e: Exception) {
   Log.w(TAG, "isAmlogicDevice: exception checking properties", e)
  }

  Log.d(TAG, "isAmlogicDevice: false")
  return false
 }

 /**
  * Get HDR capabilities information as a human-readable string.
  */
 fun getHdrInfo(context: Context): String {
  val sb = StringBuilder()

  // Try Amlogic sysfs first (only if root + sysfs available)
  if (hasSysfsHdrControl()) {
   val hdrCap = readSysfs(HDR_CAP_PATH)
   if (hdrCap.isNotEmpty()) {
    sb.append("Amlogic HDR capabilities:\n$hdrCap\n")
   }
   val hdrStatus = readSysfs(HDR_STATUS_PATH)
   if (hdrStatus.isNotEmpty()) {
    sb.append("Current HDR status: $hdrStatus\n")
   }
  }

  // Report EGL HDR state if active
  if (HdrEglHelper.activeHdrColorspace != 0) {
   val csName = when (HdrEglHelper.activeHdrColorspace) {
    0x3340 -> "BT2020_PQ (HDR10)"
    0x3540 -> "BT2020_HLG"
    else -> "0x${HdrEglHelper.activeHdrColorspace.toString(16)}"
   }
   sb.append("EGL HDR colorspace: $csName\n")
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
  if (hasSysfsHdrControl()) {
   // Set am_vecm processing mode (rooted Amlogic path)
   if (hdr) {
    writeSysfs(VECM_HDR_MODE_PATH, "1")
    writeSysfs(VECM_HDR_POLICY_PATH, "0")
    Log.i(TAG, "Amlogic vecm HDR processing enabled")
   } else {
    writeSysfs(VECM_HDR_MODE_PATH, "0")
    writeSysfs(VECM_HDR_POLICY_PATH, "0")
    Log.i(TAG, "Amlogic vecm HDR processing disabled")
   }
  } else if (isAmlogicDevice()) {
   Log.i(TAG, "Amlogic device without root — skipping sysfs vecm writes, using EGL path")
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
 * @param eotf EOTF transfer function: 0=SDR, 2=PQ, 3=HLG, 4=Dolby Vision (PQ transport)
  * @param colorFormat Color format: 0=RGB, 1=YCbCr444, 2=YCbCr422
  * @param colorimetry Colorimetry: 0=BT.709, 1=BT.2020
  * @param bitDepth Bit depth: 8, 10, or 12
  */
 fun applySignalSettings(eotf: Int, colorFormat: Int, colorimetry: Int, bitDepth: Int) {
  Log.i(TAG, "Applying signal settings: EOTF=$eotf colorFormat=$colorFormat " +
   "colorimetry=$colorimetry bitDepth=$bitDepth")

  if (hasSysfsHdrControl()) {
   // Rooted Amlogic path — full sysfs control
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
    4 -> { // Dolby Vision request (transported as PQ on generated patterns)
     writeSysfs(CONFIG_PATH, "hdr,2")
     Log.i(TAG, "Amlogic config: Dolby Vision requested, using PQ transport mode")
    }
    3 -> { // HLG
     writeSysfs(CONFIG_PATH, "hlg")
     Log.i(TAG, "Amlogic config: HLG mode")
    }
   }

   // Verify the switch
   val status = readSysfs(HDR_STATUS_PATH)
   Log.i(TAG, "HDMI HDR status after apply: $status")

   // Also verify attr
   val currentAttr = readSysfs(ATTR_PATH)
   Log.i(TAG, "HDMI attr after apply: $currentAttr")
  } else {
   // Non-root path: HDR is handled via EGL surface (HdrEglHelper) +
   // window.colorMode (set in setHdrMode). Sysfs writes are not possible.
     val hdrMethod = when {
        HdrEglHelper.activeHdrColorspace == 0x3540 -> "EGL BT2020_HLG"
        HdrEglHelper.activeHdrColorspace == 0x3340 && eotf == 4 -> "EGL BT2020_PQ (Dolby Vision transport)"
        HdrEglHelper.activeHdrColorspace == 0x3340 -> "EGL BT2020_PQ"
        HdrEglHelper.hdrDataSpaceSet && eotf == 3 -> "Surface BT2020_HLG"
        HdrEglHelper.hdrDataSpaceSet -> "Surface BT2020_PQ"
        else -> "window colorMode only"
     }
     Log.i(TAG, "Non-root device — HDR handled via $hdrMethod (EOTF=$eotf)")
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
  if (!hasSysfsHdrControl()) {
   Log.d(TAG, "HDR metadata via sysfs not available (no root or no Amlogic sysfs)")
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
  val hasSysfs = hasSysfsHdrControl()
  Log.i(TAG, "getDisplayResolution: hasSysfs=$hasSysfs")

  if (hasSysfs) {
   // Primary: /sys/class/display/mode returns e.g. "2160p60hz", "1080p60hz"
   val mode = readSysfs(DISPLAY_MODE_PATH)
   Log.i(TAG, "getDisplayResolution: display/mode='$mode'")
   if (mode.isNotEmpty()) {
    val res = parseDisplayMode(mode)
    if (res != null) {
     Log.i(TAG, "getDisplayResolution: parsed ${res.first}x${res.second} from mode='$mode'")
     return res
    } else {
     Log.w(TAG, "getDisplayResolution: failed to parse mode='$mode'")
    }
   }

   // Fallback: parse VIC from config
   val config = readSysfs(CONFIG_PATH)
   Log.i(TAG, "getDisplayResolution: config='$config'")
   val vicMatch = Regex("cur_VIC:\\s*(\\d+)").find(config)
   if (vicMatch != null) {
    val vic = vicMatch.groupValues[1].toIntOrNull() ?: 0
    val res = vicToResolution(vic)
    if (res != null) {
     Log.i(TAG, "getDisplayResolution: VIC $vic → ${res.first}x${res.second}")
     return res
    }
   }
  }

  // Fallback: Android Display.Mode API (API 23+) — gives actual display output resolution
  // This is more accurate than DisplayMetrics which gives the app rendering resolution
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
   try {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val display = wm.defaultDisplay
    val mode = display.mode
    val w = mode.physicalWidth
    val h = mode.physicalHeight
    if (w > 0 && h > 0) {
     Log.i(TAG, "getDisplayResolution: Display.Mode ${w}x${h}")
     return Pair(w, h)
    }
   } catch (e: Exception) {
    Log.w(TAG, "getDisplayResolution: Display.Mode failed", e)
   }
  }

  // Fallback: Android DisplayMetrics (gives app rendering resolution, not display)
  try {
   val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
   val display = wm.defaultDisplay
   val metrics = android.util.DisplayMetrics()
   display.getRealMetrics(metrics)
   Log.i(TAG, "getDisplayResolution: DisplayMetrics fallback ${metrics.widthPixels}x${metrics.heightPixels}")
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
  // Rooted Amlogic: full sysfs access
  if (hasSysfsHdrControl()) {
   return readSysfs(HDR_STATUS_PATH).ifEmpty { "unknown" }
  }

  // Non-root Amlogic: hdmi_hdr_status is often world-readable
  // (e.g., Chromecast with Google TV). Try direct read first.
  if (isAmlogicDevice()) {
   val sysfsStatus = readSysfs(HDR_STATUS_PATH)
   if (sysfsStatus.isNotEmpty()) {
    return sysfsStatus
   }
  }

  if (AppState.dvMode && HdrEglHelper.isHdrActive) {
   return "DOLBY_VISION-BT2020_PQ"
  }
  // Non-root: report HDR state (EGL colorspace or Surface dataspace)
  return when {
   HdrEglHelper.activeHdrColorspace == 0x3340 -> "HDR10-EGL_BT2020_PQ"
   HdrEglHelper.activeHdrColorspace == 0x3540 -> "HLG-EGL_BT2020_HLG"
   HdrEglHelper.hdrDataSpaceSet && AppState.eotf == 3 -> "HLG-SURFACE_BT2020_HLG"
   HdrEglHelper.hdrDataSpaceSet -> "HDR10-SURFACE_BT2020_PQ"
   else -> "SDR"
  }
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
  return if (hasSysfsHdrControl()) {
   readSysfs(DISP_CAP_PATH)
  } else {
   "Display capabilities not available (no sysfs access)"
  }
 }

 /**
  * Read a sysfs file. Tries direct File read first, then sh, then su.
  */
 private fun readSysfs(path: String): String {
  // Try direct file read
  try {
   val file = File(path)
   if (file.exists() && file.canRead()) {
    val value = file.readText().trim()
    if (value.isNotEmpty()) {
     Log.d(TAG, "Read $path (direct): $value")
     return value
    }
   }
  } catch (_: Exception) { }

  // Try via sh
  try {
   val result = shellExec(arrayOf("sh", "-c", "cat $path"))
   if (result != null) {
    Log.d(TAG, "Read $path (sh): $result")
    return result.trim()
   }
  } catch (_: Exception) { }

  // Try via su (only if root is available)
  if (hasRootAccess()) {
   try {
    val result = suExec("cat $path")
    if (result != null) {
     Log.d(TAG, "Read $path (su): $result")
     return result.trim()
    }
   } catch (_: Exception) { }
  }

  Log.w(TAG, "Failed to read $path")
  return ""
 }

 /**
  * Write a value to a sysfs file. The critical fix: must use su to spawn
  * a root shell that interprets the > redirect, because Runtime.exec()
  * does not use a shell and the app process (untrusted_app SELinux context)
  * cannot write to sysfs directly even on rooted devices.
  */
 private fun writeSysfs(path: String, value: String) {
  // Try su first — app process cannot write to sysfs directly
  // su -c spawns a root shell that interprets the redirect properly
  if (hasRootAccess()) {
   val suResult = suExec("echo '$value' > $path")
   if (suResult != null) {
    Log.i(TAG, "Wrote '$value' to $path (su)")
    return
   }
  }

  // Fallback: try direct file write (unlikely to work from app context)
  try {
   val file = File(path)
   if (file.exists() && file.canWrite()) {
    file.writeText(value)
    Log.i(TAG, "Wrote '$value' to $path (direct)")
    return
   }
  } catch (_: Exception) { }

  // Fallback: try sh (unlikely to work from app context)
  try {
   val result = shellExec(arrayOf("sh", "-c", "echo '$value' > $path"))
   if (result != null) {
    Log.i(TAG, "Wrote '$value' to $path (sh)")
    return
   }
  } catch (_: Exception) { }

  Log.e(TAG, "FAILED to write '$value' to $path — no working write method")
 }

 /**
  * Execute a command via su (root). Uses ProcessBuilder to spawn su as
  * an interactive shell and writes the command to its stdin, which ensures
  * shell redirects (>) are interpreted by the root shell process.
  */
 private fun suExec(command: String): String? {
  return try {
   val process = ProcessBuilder("su")
    .redirectErrorStream(true)
    .start()

   // Write command to su's stdin so the redirect is interpreted by su's shell
   process.outputStream.bufferedWriter().use { writer ->
    writer.write(command)
    writer.newLine()
    writer.write("exit")
    writer.newLine()
    writer.flush()
   }

   val output = process.inputStream.bufferedReader().readText().trim()
   val exitCode = process.waitFor()
   Log.d(TAG, "suExec($command) → exit=$exitCode output='$output'")

   if (exitCode == 0) output else null
  } catch (e: Exception) {
   Log.w(TAG, "suExec failed: ${e.message}")
   null
  }
 }

 /**
  * Execute a shell command (non-root).
  */
 private fun shellExec(cmd: Array<String>): String? {
  return try {
   val process = Runtime.getRuntime().exec(cmd)
   val output = process.inputStream.bufferedReader().readText().trim()
   val exitCode = process.waitFor()
   if (exitCode == 0 && output.isNotEmpty()) output else null
  } catch (e: Exception) {
   null
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
}
