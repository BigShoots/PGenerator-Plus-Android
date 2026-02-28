package com.pgeneratorplus.android.network

import android.util.Log
import com.pgeneratorplus.android.model.AppState
import com.pgeneratorplus.android.model.DrawCommand
import com.pgeneratorplus.android.hdr.HdrController
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * CalMAN UPGCI (Unified Pattern Generator Control Interface) server.
 *
 * Implements the UPGCI v2.0 protocol on TCP port 2100.
 * CalMAN can dynamically control HDR/SDR mode, bit depth, and InfoFrame
 * metadata through this protocol.
 *
 * Protocol:
 * - Commands framed with STX (0x02) prefix and ETX (0x03) suffix
 * - All responses are a single ACK byte (0x06)
 *
 * Command types:
 *   INIT:2.0                                    — Protocol init
 *   STATUS                                      — Status query
 *   RGB_S:rrrr,gggg,bbbb,www                    — Standard pattern (4 params)
 *   RGB_B:rrrr,gggg,bbbb,wwww                   — Bordered pattern (4 params)
 *   RGB_A:rrrr,gggg,bbbb,RRRR,GGGG,BBBB,www     — Full pattern (7 params)
 *   CONF_HDR:...                                — HDR configuration
 *   CONF_FORMAT:...                             — Color format configuration
 *   CONF_LEVEL:...                              — Level/gamma configuration
 *   SPECIALTY:BRIGHTNESS|CONTRAST|...           — Specialty patterns
 *   UPDATE[:...]                                — Display update
 *   SHUTDOWN                                    — Disconnect
 *
 * Values are 10-bit (0-1023), converted to 8-bit: floor(val * 256 / 1024)
 *
 * CRITICAL: ACK must be sent immediately — CalMAN has a short timeout.
 */
class UPGCIServer(
 private var isHdr: Boolean,
 private val onModeChange: ((hdr: Boolean, bitDepth: Int, eotf: Int) -> Unit)? = null
) {
 companion object {
  private const val TAG = "UPGCIServer"
  const val TCP_PORT = 2100
  private const val MAX_BUFFER_SIZE = 4096

  private const val STX: Byte = 0x02
  private const val ETX: Byte = 0x03
  private const val ACK: Byte = 0x06
 }

 @Volatile
 private var running = false
 private var serverSocket: ServerSocket? = null
 private var clientSocket: Socket? = null

 private val maxV = 255f

 fun start() {
  running = true
  var switchedMode = false

  try {
   while (running) {
    serverSocket = ServerSocket(TCP_PORT).apply {
     reuseAddress = true
     soTimeout = 2000
    }

    if (!switchedMode) {
     Log.i(TAG, "UPGCI server ready (${if (isHdr) "HDR" else "SDR"})")
     switchedMode = true
    }

    AppState.setCommands(emptyList())
    AppState.connectionStatus.set("UPGCI: Waiting on port $TCP_PORT...")

    var accepted = false
    while (running && !accepted) {
     try {
      clientSocket = serverSocket!!.accept()
      accepted = true
     } catch (e: java.net.SocketTimeoutException) { }
    }

    if (!accepted || clientSocket == null) continue

    Log.i(TAG, "CalMAN client connected from ${clientSocket!!.inetAddress}")
    AppState.connectionStatus.set("UPGCI: CalMAN connected")
    serverSocket?.close()
    serverSocket = null

    handleClient(clientSocket!!)

    Log.i(TAG, "CalMAN client disconnected. Reopening server socket.")
    clientSocket?.close()
    clientSocket = null
   }
  } catch (e: IOException) {
   if (running) {
    Log.e(TAG, "Server error", e)
    AppState.connectionStatus.set("UPGCI error: ${e.message}")
   }
  } finally {
   cleanup(switchedMode)
  }
 }

 /**
  * Handle a connected CalMAN client.
  * No waitPending() — ACK must be sent immediately to avoid CalMAN Error 10.
  */
 private fun handleClient(client: Socket) {
  val inputStream = client.getInputStream()
  val outputStream = client.getOutputStream()

  try {
   while (running && !client.isClosed) {
    val message = readUPGCIMessage(inputStream) ?: break
    Log.d(TAG, "Received: $message")
    processCommand(message)
    sendAck(outputStream)
   }
  } catch (e: IOException) {
   if (running) {
    Log.e(TAG, "Client communication error", e)
   }
  }
 }

 private fun processCommand(message: String) {
  try {
   val colonIndex = message.indexOf(':')
   if (colonIndex < 0) {
    handleSimpleCommand(message)
    return
   }

   val type = message.substring(0, colonIndex)
   val params = message.substring(colonIndex + 1)

   when {
    type.startsWith("RGB") -> handleRgbCommand(type, params)
    type == "INIT" -> Log.i(TAG, "CalMAN INIT v$params")
    type == "CONF_HDR" -> handleConfHdr(params)
    type == "CONF_FORMAT" -> handleConfFormat(params)
    type == "CONF_LEVEL" -> handleConfLevel(params)
    type == "SPECIALTY" -> handleSpecialty(params)
    type == "UPDATE" -> Log.d(TAG, "UPDATE: $params")
    else -> Log.d(TAG, "Unhandled command: $type:$params")
   }
  } catch (e: Exception) {
   Log.e(TAG, "Command processing error: $message", e)
  }
 }

 private fun handleSimpleCommand(message: String) {
  when {
   message.startsWith("INIT") -> Log.i(TAG, "CalMAN INIT: $message")
   message == "STATUS" || message == "GETSTATUS" -> Log.d(TAG, "Status check")
   message == "IS_ALIVE" || message == "ISALIVE" -> Log.d(TAG, "Alive check")
   message == "UPDATE" -> Log.d(TAG, "Update display")
   message == "SHUTDOWN" || message == "QUIT" -> {
    Log.i(TAG, "$message received, closing connection")
    try { clientSocket?.close() } catch (e: IOException) {}
   }
   else -> Log.d(TAG, "Unknown command: $message")
  }
 }

 /**
  * Handle CONF_HDR command from CalMAN.
  * CalMAN controls the AVI and DRM InfoFrame metadata through this.
  *
  * Format: CONF_HDR:type,Rx,Ry,Gx,Gy,Bx,By,Wx,Wy,?,MaxCLL,MaxFALL,MaxDML
  */
 private fun handleConfHdr(params: String) {
  Log.i(TAG, "CONF_HDR: $params")
  try {
   val parts = params.split(",")
   val hdrType = parts[0].trim().uppercase()

   if (hdrType == "OFF" || hdrType == "SDR" || hdrType == "NONE") {
    isHdr = false
    AppState.setMode(AppState.bitDepth, false)
    AppState.applyEotfMode(0)
    Log.i(TAG, "Switching to SDR mode (CalMAN request)")
    onModeChange?.invoke(false, AppState.bitDepth, 0)
   } else {
    isHdr = true
    val eotf = mapHdrTypeToEotf(hdrType)
    val isDolbyVision = hdrType.contains("DOLBY") || hdrType.contains("DOVI")

    if (isDolbyVision) {
     Log.i(TAG, "Dolby Vision requested by CalMAN. Entering Dolby Vision mode (EOTF=4).")
    }

    AppState.applyEotfMode(eotf)
    AppState.colorimetry = 1 // BT.2020 for HDR modes
    if (AppState.bitDepth < 10) {
      AppState.bitDepth = 10
    }
    AppState.setMode(AppState.bitDepth, true)

    if (parts.size >= 13) {
     AppState.maxCLL = parts[10].trim().toIntOrNull() ?: -1
     AppState.maxFALL = parts[11].trim().toIntOrNull() ?: -1
     AppState.maxDML = parts[12].trim().toIntOrNull() ?: -1
     Log.i(TAG, "HDR metadata: MaxCLL=${AppState.maxCLL} MaxFALL=${AppState.maxFALL} MaxDML=${AppState.maxDML}")

     if (AppState.maxCLL > 0) {
      HdrController.setHdrMetadata(AppState.maxCLL,
       if (AppState.maxFALL > 0) AppState.maxFALL else AppState.maxCLL,
       if (AppState.maxDML > 0) AppState.maxDML else AppState.maxCLL)
     }
    }

    Log.i(TAG, "Switching to HDR mode: $hdrType (CalMAN request, EOTF=$eotf)")
    onModeChange?.invoke(true, AppState.bitDepth, eotf)
   }
  } catch (e: Exception) {
   Log.e(TAG, "Failed to parse CONF_HDR: $params", e)
  }
 }

 /**
  * Handle CONF_LEVEL command from CalMAN.
  * Controls bit depth, color format, range, and gamma.
  */
 private fun handleConfLevel(params: String) {
  Log.i(TAG, "CONF_LEVEL: $params")
  try {
   val p = params.trim()
   when {
    p.startsWith("Bits ", ignoreCase = true) -> {
     val bits = p.substringAfter(" ").trim().toIntOrNull()
     if (bits != null && (bits == 8 || bits == 10 || bits == 12)) {
      AppState.setMode(bits, AppState.hdr)
      Log.i(TAG, "Bit depth set to $bits by CalMAN")
      onModeChange?.invoke(AppState.hdr, bits, AppState.eotf)
     }
    }
    p.startsWith("Range ", ignoreCase = true) -> {
     val range = p.substringAfter(" ").trim()
     Log.i(TAG, "Video range set to: $range")
    }
    p.startsWith("Format ", ignoreCase = true) -> {
     val format = p.substringAfter(" ").trim()
     Log.i(TAG, "Color format set to: $format")
    }
    p.equals("Gamma-HDR", ignoreCase = true) -> {
     if (!isHdr) {
      isHdr = true
        AppState.applyEotfMode(2) // Default HDR path is PQ
      AppState.colorimetry = 1 // BT.2020
      if (AppState.bitDepth < 10) {
       AppState.bitDepth = 10
      }
      AppState.setMode(AppState.bitDepth, true)
      Log.i(TAG, "Switching to HDR mode (Gamma-HDR)")
      onModeChange?.invoke(true, AppState.bitDepth, AppState.eotf)
     }
    }
    p.equals("Gamma-SDR", ignoreCase = true) -> {
     if (isHdr) {
      isHdr = false
      AppState.applyEotfMode(0)
      AppState.setMode(AppState.bitDepth, false)
      Log.i(TAG, "Switching to SDR mode (Gamma-SDR)")
      onModeChange?.invoke(false, AppState.bitDepth, 0)
     }
    }
    else -> Log.d(TAG, "Unknown CONF_LEVEL param: $p")
   }
  } catch (e: Exception) {
   Log.e(TAG, "Failed to parse CONF_LEVEL: $params", e)
  }
 }

 private fun mapHdrTypeToEotf(hdrType: String): Int {
  val t = hdrType.uppercase()
  return when {
   t.contains("HLG") -> 3
     t.contains("DOLBY") || t.contains("DOVI") -> 4
     t.contains("PQ") || t.contains("ST2084") || t.contains("HDR10") -> 2
   else -> 2
  }
 }

 private fun handleConfFormat(params: String) {
  Log.i(TAG, "CONF_FORMAT: $params")
 }

 private fun handleSpecialty(params: String) {
  Log.i(TAG, "SPECIALTY pattern: $params")
  when (params.uppercase()) {
   "BRIGHTNESS" -> AppState.setCommands(listOf(DrawCommand.fullFieldInt(20, 20, 20, maxV)))
   "CONTRAST" -> AppState.setCommands(listOf(DrawCommand.fullFieldInt(235, 235, 235, maxV)))
   else -> Log.d(TAG, "Unknown specialty: $params")
  }
 }

 /**
  * Handle RGB pattern commands.
  * All values are 10-bit (0-1023). Converted to 8-bit: floor(val * 256 / 1024).
  */
 private fun handleRgbCommand(type: String, params: String) {
  try {
   val parts = params.split(",")
   if (parts.size < 3) {
    Log.e(TAG, "Invalid RGB command: not enough values in '$params'")
    return
   }

   val r10 = parts[0].trim().toInt()
   val g10 = parts[1].trim().toInt()
   val b10 = parts[2].trim().toInt()

   val r = (r10 * 256) / 1024
   val g = (g10 * 256) / 1024
   val b = (b10 * 256) / 1024

   val windowPct: Int
   var bgR = 0
   var bgG = 0
   var bgB = 0

   when (type) {
    "RGB_A" -> {
     if (parts.size >= 7) {
      val bgR10 = parts[3].trim().toInt()
      val bgG10 = parts[4].trim().toInt()
      val bgB10 = parts[5].trim().toInt()
      bgR = (bgR10 * 256) / 1024
      bgG = (bgG10 * 256) / 1024
      bgB = (bgB10 * 256) / 1024
      windowPct = parts[6].trim().toInt()
     } else if (parts.size >= 4) {
      windowPct = parts[3].trim().toInt()
     } else {
      windowPct = 100
     }
    }
    else -> {
     windowPct = if (parts.size >= 4) parts[3].trim().toInt() else 100
    }
   }

   Log.d(TAG, "$type: 10-bit($r10,$g10,$b10) -> 8-bit($r,$g,$b) window=$windowPct bg=($bgR,$bgG,$bgB)")

   val commands = mutableListOf<DrawCommand>()

   if (windowPct >= 100) {
    commands.add(DrawCommand.fullFieldInt(r, g, b, maxV))
   } else {
    commands.add(DrawCommand.fullFieldInt(bgR, bgG, bgB, maxV))
    val winPct = if (windowPct > 0) windowPct.toFloat() else 18f
    commands.add(DrawCommand.windowPatternInt(winPct, r, g, b, maxV))
   }

   AppState.setCommands(commands)
  } catch (e: Exception) {
   Log.e(TAG, "Failed to parse RGB command: $type:$params", e)
  }
 }

 private fun readUPGCIMessage(input: InputStream): String? {
  val buffer = ByteArray(MAX_BUFFER_SIZE)
  var pos = 0

  while (pos < MAX_BUFFER_SIZE - 1) {
   val b = input.read()
   if (b == -1) return null

   val byte = b.toByte()

   if (byte == ETX) {
    val start = if (pos > 0 && buffer[0] == STX) 1 else 0
    return if (pos > start) {
     String(buffer, start, pos - start, Charsets.UTF_8).trim()
    } else {
     ""
    }
   }

   buffer[pos] = byte
   pos++
  }

  val start = if (pos > 0 && buffer[0] == STX) 1 else 0
  return if (pos > start) {
   String(buffer, start, pos - start, Charsets.UTF_8).trim()
  } else {
   null
  }
 }

 private fun sendAck(output: OutputStream) {
  output.write(byteArrayOf(ACK))
  output.flush()
 }

 fun stop() {
  running = false
  try { clientSocket?.close() } catch (e: IOException) {}
  try { serverSocket?.close() } catch (e: IOException) {}
  AppState.clearPending()
 }

 private fun cleanup(switchedMode: Boolean) {
  try { serverSocket?.close() } catch (e: IOException) {}
  try { clientSocket?.close() } catch (e: IOException) {}
  AppState.connectionStatus.set("UPGCI: Stopped")
  Log.i(TAG, "Server stopped")
 }
}
