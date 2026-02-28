package com.pgeneratorplus.android

import android.content.Intent
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic activity for HDR / Dolby Vision capability probing.
 *
 * This checks:
 * - Display HDR capabilities (Android Display API)
 * - Available decoder profiles (HEVC Main10, VP9 Profile2, AV1 Main10, Dolby Vision)
 *
 * Purpose: distinguish app-path issues from platform/firmware limitations.
 */
class HdrProbeActivity : AppCompatActivity() {

 companion object {
  private const val TAG = "HdrProbeActivity"
 }

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  setContentView(R.layout.activity_hdr_probe)

  val btnRefresh = findViewById<Button>(R.id.btnRefreshProbe)
  btnRefresh.setOnClickListener { runProbe() }

  val btnOpenVideoProbe = findViewById<Button>(R.id.btnOpenVideoProbe)
  btnOpenVideoProbe.setOnClickListener {
   startActivity(Intent(this, VideoProbeActivity::class.java))
  }

  runProbe()
 }

 private fun runProbe() {
  val tvSummary = findViewById<TextView>(R.id.tvProbeSummary)
  val tvInfo = findViewById<TextView>(R.id.tvProbeInfo)

  val displayInfo = probeDisplay()
  val codecInfo = probeCodecs()

  val supportsHdr10 = displayInfo.supportedHdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_HDR10)
  val supportsHlg = displayInfo.supportedHdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_HLG)
  val supportsDvDisplay = displayInfo.supportedHdrTypes.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)

  val summary = buildString {
   append("Display: ")
   append(if (supportsHdr10) "HDR10 " else "")
   append(if (supportsHlg) "HLG " else "")
   append(if (supportsDvDisplay) "DolbyVision" else "")
   if (!supportsHdr10 && !supportsHlg && !supportsDvDisplay) append("No HDR types reported")

   append("  |  Decoder: ")
   append(if (codecInfo.hevcMain10) "HEVC Main10 " else "")
   append(if (codecInfo.vp9p2) "VP9-P2 " else "")
   append(if (codecInfo.av1Main10) "AV1-10bit " else "")
   append(if (codecInfo.dolbyVision) "DolbyVision" else "")
   if (!codecInfo.hevcMain10 && !codecInfo.vp9p2 && !codecInfo.av1Main10 && !codecInfo.dolbyVision) {
    append("No HDR-capable decoder profiles found")
   }
  }
  val reportText = buildString {
   appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
   appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
   appendLine()

   appendLine("=== Display HDR Capabilities ===")
   appendLine("Supported HDR types: ${displayInfo.supportedTypeNames.ifEmpty { listOf("none") }.joinToString(", ")}")
   appendLine("Desired max luminance: ${displayInfo.maxLuminance}")
   appendLine("Desired max avg luminance: ${displayInfo.maxAvgLuminance}")
   appendLine("Desired min luminance: ${displayInfo.minLuminance}")
   appendLine("Active mode: ${displayInfo.activeMode}")
  appendLine("Current color mode: ${displayInfo.colorMode}")
   appendLine()

   appendLine("=== Decoder Capability Probe ===")
   appendLine("HEVC Main10: ${codecInfo.hevcMain10}")
   appendLine("VP9 Profile2: ${codecInfo.vp9p2}")
   appendLine("AV1 Main10: ${codecInfo.av1Main10}")
   appendLine("Dolby Vision decoder/profile: ${codecInfo.dolbyVision}")
   appendLine()

   appendLine("=== Matching Decoder Names ===")
   if (codecInfo.matches.isEmpty()) {
    appendLine("None")
   } else {
    codecInfo.matches.forEach { appendLine(it) }
   }
   appendLine()

   appendLine("=== Interpretation ===")
   appendLine("- If display has HDR10/HLG but color modes stay limited, GL-app HDR may be firmware-limited.")
   appendLine("- If Dolby Vision is false above, true app-generated DV output is not available on this build.")
  }

  val savedPath = saveProbeLog(summary, reportText)
  tvSummary.text = if (savedPath.isNotEmpty()) {
   "$summary\nSaved: $savedPath"
  } else {
   "$summary\nSaved: failed"
  }
  tvInfo.text = reportText
 }

 private fun saveProbeLog(summary: String, reportText: String): String {
  return try {
   val logDir = File(filesDir, "hdr_probe")
   if (!logDir.exists()) {
    logDir.mkdirs()
   }

   val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
   val latestFile = File(logDir, "latest.txt")
   val historyFile = File(logDir, "probe_$ts.txt")

   val payload = buildString {
    appendLine("=== HDR / DV Probe ===")
    appendLine("Timestamp: $ts")
    appendLine()
    appendLine(summary)
    appendLine()
    append(reportText)
   }

   latestFile.writeText(payload)
   historyFile.writeText(payload)
   latestFile.absolutePath
  } catch (e: Exception) {
   Log.e(TAG, "Failed to save probe log", e)
   ""
  }
 }

 private data class DisplayProbe(
  val supportedHdrTypes: IntArray,
  val supportedTypeNames: List<String>,
  val maxLuminance: Float,
  val maxAvgLuminance: Float,
  val minLuminance: Float,
  val activeMode: String,
    val colorMode: String
 )

 private fun probeDisplay(): DisplayProbe {
  val d = windowManager.defaultDisplay
  val hdrCaps = d.hdrCapabilities
  val types = hdrCaps?.supportedHdrTypes ?: intArrayOf()
  val names = types.map { t ->
   when (t) {
    Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
    Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
    else -> "Unknown($t)"
   }
  }

  val mode = try {
   val m = d.mode
   "${m.physicalWidth}x${m.physicalHeight}@${"%.3f".format(m.refreshRate)}"
  } catch (_: Exception) {
   "unknown"
  }

    val colorMode = "unknown"

  return DisplayProbe(
   supportedHdrTypes = types,
   supportedTypeNames = names,
   maxLuminance = hdrCaps?.desiredMaxLuminance ?: -1f,
   maxAvgLuminance = hdrCaps?.desiredMaxAverageLuminance ?: -1f,
   minLuminance = hdrCaps?.desiredMinLuminance ?: -1f,
   activeMode = mode,
    colorMode = colorMode
  )
 }

 private data class CodecProbe(
  val hevcMain10: Boolean,
  val vp9p2: Boolean,
  val av1Main10: Boolean,
  val dolbyVision: Boolean,
  val matches: List<String>
 )

 private fun probeCodecs(): CodecProbe {
  var hevcMain10 = false
  var vp9p2 = false
  var av1Main10 = false
  var dolbyVision = false
  val matches = mutableListOf<String>()

  val codecInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
  for (codec in codecInfos) {
   if (codec.isEncoder) continue

   for (mime in codec.supportedTypes) {
    val m = mime.lowercase()
    if (!m.startsWith("video/")) continue

    try {
     val caps = codec.getCapabilitiesForType(mime)
     for (pl in caps.profileLevels) {
      if (m == "video/hevc" && pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) {
       hevcMain10 = true
       matches.add("${codec.name} -> HEVC Main10")
      }
      if (m == "video/x-vnd.on2.vp9" && pl.profile == MediaCodecInfo.CodecProfileLevel.VP9Profile2) {
       vp9p2 = true
       matches.add("${codec.name} -> VP9 Profile2")
      }
      if (m == "video/av01" && pl.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10) {
       av1Main10 = true
       matches.add("${codec.name} -> AV1 Main10")
      }
      if (m == "video/dolby-vision") {
       dolbyVision = true
       matches.add("${codec.name} -> Dolby Vision profile ${pl.profile}")
      }
     }
    } catch (_: Exception) { }
   }
  }

  return CodecProbe(
   hevcMain10 = hevcMain10,
   vp9p2 = vp9p2,
   av1Main10 = av1Main10,
   dolbyVision = dolbyVision,
   matches = matches.distinct().sorted()
  )
 }
}
