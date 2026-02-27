package com.pgeneratorplus.android.network

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.pgeneratorplus.android.hdr.HdrController
import com.pgeneratorplus.android.model.AppState
import com.pgeneratorplus.android.model.DrawCommand
import com.pgeneratorplus.android.patterns.PatternGenerator
import fi.iki.elonen.NanoHTTPD
import java.net.NetworkInterface

/**
 * Embedded Web UI HTTP server for PGenerator+ Android.
 *
 * Serves the Pi-style dashboard from assets/webui.html and a JSON API
 * that mirrors the PGenerator+ Pi Web UI endpoints.
 *
 * Routes (Pi-compatible):
 *   GET  /                    → Dashboard (loaded from assets)
 *   GET  /api/config          → Current signal/display config
 *   POST /api/config          → Update signal/display config
 *   GET  /api/info            → Device info (hostname, resolution, temperature, interfaces, etc.)
 *   POST /api/pattern         → Set pattern (white_clipping, black_clipping, color_bars, gray_ramp, overscan, patch, stop)
 *   POST /api/restart         → Apply signal settings
 *
 * Additional Android-specific routes:
 *   GET  /api/status          → Connection & server status
 *   GET  /api/hdr/caps        → HDR capabilities
 */
class WebUIServer(
 private val context: Context,
 port: Int = 8080
) : NanoHTTPD(port) {

 companion object {
  private const val TAG = "WebUIServer"

  @Volatile
  private var instance: WebUIServer? = null

  @Synchronized
  fun startInstance(context: Context, port: Int = 8080): WebUIServer? {
   instance?.let {
    if (it.isAlive) return it
   }
   return try {
    WebUIServer(context.applicationContext, port).also {
     it.start()
     instance = it
     Log.i(TAG, "WebUI server started on port $port")
    }
   } catch (e: Exception) {
    Log.e(TAG, "Failed to start WebUI server", e)
    null
   }
  }

  @Synchronized
  fun stopInstance() {
   try { instance?.stop() } catch (e: Exception) {}
   instance = null
  }

  fun isRunning(): Boolean = instance?.isAlive == true
 }

 private val gson = Gson()
 private var cachedHtml: String? = null

 override fun serve(session: IHTTPSession): Response {
  val uri = session.uri
  val method = session.method

  return try {
   when {
    // Dashboard
    uri == "/" || uri == "/index.html" -> serveDashboard()

    // Pi-compatible API routes
    uri == "/api/config" && method == Method.GET -> serveConfig()
    uri == "/api/config" && method == Method.POST -> handleConfigPost(session)
    uri == "/api/info" && method == Method.GET -> serveDeviceInfo()
    uri == "/api/pattern" && method == Method.POST -> handlePatternPost(session)
    uri == "/api/restart" && method == Method.POST -> handleRestart()
    uri == "/api/modes" && method == Method.GET -> serveModes()

    // Android-specific routes
    uri == "/api/status" && method == Method.GET -> serveStatus()
    uri == "/api/hdr/caps" && method == Method.GET -> serveHdrCaps()
    uri == "/api/settings" && method == Method.GET -> serveConfig()
    uri == "/api/settings" && method == Method.POST -> handleConfigPost(session)
    uri == "/api/hdr" && method == Method.POST -> handleHdrPost(session)

    // CORS preflight
    method == Method.OPTIONS -> corsResponse()

    uri.startsWith("/api/") -> jsonError(404, "Not found")
    else -> serveDashboard()
   }
  } catch (e: Exception) {
   Log.e(TAG, "Error serving $uri", e)
   jsonError(500, "Internal error: ${e.message}")
  }
 }

 // ───── Dashboard ─────

 private fun serveDashboard(): Response {
  val html = cachedHtml ?: try {
   context.assets.open("webui.html").bufferedReader().use { it.readText() }.also {
    cachedHtml = it
   }
  } catch (e: Exception) {
   Log.e(TAG, "Failed to load webui.html from assets", e)
   "<html><body><h1>Error loading dashboard</h1><p>${e.message}</p></body></html>"
  }
  return newFixedLengthResponse(Response.Status.OK, "text/html", html).apply {
   addHeader("Access-Control-Allow-Origin", "*")
  }
 }

 // ───── /api/config (Pi-compatible) ─────

 private fun serveConfig(): Response {
  val config = JsonObject().apply {
   // Signal mode flags (Pi format)
   addProperty("is_sdr", if (!AppState.hdr) "1" else "0")
   addProperty("is_hdr", if (AppState.hdr && AppState.eotf != 3) "1" else "0")
   addProperty("eotf", AppState.eotf.toString())
   addProperty("max_bpc", AppState.bitDepth.toString())
   addProperty("color_format", AppState.colorFormat.toString())
   addProperty("rgb_quant_range", AppState.quantRange.toString())
   addProperty("colorimetry", AppState.colorimetry.toString())

   // HDR metadata
   addProperty("primaries", "0")
   addProperty("max_luma", if (AppState.maxCLL > 0) AppState.maxCLL.toString() else "1000")
   addProperty("min_luma", "0.005")
   addProperty("max_cll", if (AppState.maxCLL > 0) AppState.maxCLL.toString() else "1000")
   addProperty("max_fall", if (AppState.maxFALL > 0) AppState.maxFALL.toString() else "400")

   // DV (not available on Android)
   addProperty("dv_status", "0")
   addProperty("is_ll_dovi", "0")
   addProperty("is_std_dovi", "0")
   addProperty("dv_metadata", "0")
   addProperty("dv_interface", "0")
   addProperty("dv_color_space", "0")

   // Additional Android state
   addProperty("bitDepth", AppState.bitDepth)
   addProperty("hdr", AppState.hdr)
   addProperty("colorFormat", AppState.colorFormat)
   addProperty("quantRange", AppState.quantRange)
  }
  return jsonResponse(config)
 }

 private fun handleConfigPost(session: IHTTPSession): Response {
  val body = readBody(session)
  val json = gson.fromJson(body, JsonObject::class.java)

  // Handle Pi-format config keys
  json.get("max_bpc")?.asString?.toIntOrNull()?.let { AppState.bitDepth = it }
  json.get("eotf")?.asString?.toIntOrNull()?.let { eotf ->
   AppState.eotf = eotf
   AppState.hdr = eotf > 0
  }
  json.get("color_format")?.asString?.toIntOrNull()?.let { AppState.colorFormat = it }
  json.get("rgb_quant_range")?.asString?.toIntOrNull()?.let { AppState.quantRange = it }
  json.get("colorimetry")?.asString?.toIntOrNull()?.let { AppState.colorimetry = it }

  // Handle is_sdr/is_hdr flags
  json.get("is_sdr")?.asString?.let { if (it == "1") { AppState.hdr = false; AppState.eotf = 0 } }
  json.get("is_hdr")?.asString?.let { if (it == "1") AppState.hdr = true }

  // HDR metadata
  json.get("max_luma")?.asString?.toIntOrNull()?.let { AppState.maxDML = it }
  json.get("max_cll")?.asString?.toIntOrNull()?.let { AppState.maxCLL = it }
  json.get("max_fall")?.asString?.toIntOrNull()?.let { AppState.maxFALL = it }

  // Handle Android-format keys too
  json.get("bitDepth")?.asInt?.let { AppState.bitDepth = it }
  json.get("colorFormat")?.asInt?.let { AppState.colorFormat = it }
  json.get("quantRange")?.asInt?.let { AppState.quantRange = it }

  AppState.modeChanged = true

  // Apply signal settings if on Amlogic device
  HdrController.applySignalSettings(
   AppState.eotf, AppState.colorFormat, AppState.colorimetry, AppState.bitDepth
  )
  if (AppState.maxCLL > 0) {
   HdrController.setHdrMetadata(AppState.maxCLL, AppState.maxFALL, AppState.maxDML)
  }

  val result = JsonObject().apply {
   addProperty("status", "ok")
   addProperty("restart", false)
  }
  return jsonResponse(result)
 }

 // ───── /api/info (Pi-compatible) ─────

 private fun serveDeviceInfo(): Response {
  val info = JsonObject().apply {
   addProperty("hostname", Build.MODEL)
   addProperty("version", "2.0.1")
   addProperty("product", "PGenerator+ Android")

   val display = HdrController.getDisplayResolution(context)
   addProperty("resolution", "${display.first}x${display.second}")

   // Uptime in seconds
   addProperty("uptime", (SystemClock.elapsedRealtime() / 1000).toString())

   // Temperature (not available on most Android devices)
   addProperty("temperature", "")

   // Network interfaces (Pi format)
   val interfaces = JsonObject()
   try {
    val netInterfaces = NetworkInterface.getNetworkInterfaces()
    while (netInterfaces.hasMoreElements()) {
     val iface = netInterfaces.nextElement()
     if (iface.isLoopback || !iface.isUp) continue
     val addrs = iface.inetAddresses
     while (addrs.hasMoreElements()) {
      val addr = addrs.nextElement()
      if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
       interfaces.addProperty(iface.name, addr.hostAddress)
      }
     }
    }
   } catch (e: Exception) {}
   add("interfaces", interfaces)

   // Calibration software connection status
   val calibration = JsonObject()
   val connStatus = AppState.connectionStatus.get()
   val isConnected = connStatus != "Idle" && connStatus.contains("Connected", ignoreCase = true)
   calibration.addProperty("connected", isConnected)
   calibration.addProperty("software", if (isConnected) connStatus else "")
   calibration.addProperty("ip", "")
   add("calibration", calibration)

   // Additional Android info
   addProperty("device", "${Build.MANUFACTURER} ${Build.MODEL}")
   addProperty("android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
   addProperty("bitDepth", AppState.bitDepth)
   addProperty("hdr", AppState.hdr)
   addProperty("hdrMode", when (AppState.eotf) {
    0 -> "SDR"
    2 -> "PQ (HDR10)"
    3 -> "HLG"
    else -> "Unknown"
   })
  }
  return jsonResponse(info)
 }

 // ───── /api/pattern (Pi-compatible) ─────

 private fun handlePatternPost(session: IHTTPSession): Response {
  val body = readBody(session)
  val json = gson.fromJson(body, JsonObject::class.java)

  // Support both Pi format (name) and Android format (type)
  val patternName = json.get("name")?.asString ?: json.get("type")?.asString ?: "fullfield"
  val maxV = 255f

  when (patternName) {
   // Pi-compatible pattern names
   "white_clipping" -> {
    // White clipping: near-white bars showing 100%, 101%, 102%, 104%
    val commands = mutableListOf<DrawCommand>()
    commands.add(DrawCommand.fullFieldInt(200, 200, 200, maxV))
    val levels = intArrayOf(250, 252, 254, 255)
    val barWidth = 0.12f
    for (i in levels.indices) {
     val cmd = DrawCommand()
     cmd.setColorsFromRgb(intArrayOf(levels[i], levels[i], levels[i]), maxV)
     cmd.x1 = -0.24f + i * barWidth; cmd.y1 = 0.4f
     cmd.x2 = cmd.x1 + barWidth; cmd.y2 = -0.4f
     commands.add(cmd)
    }
    AppState.setCommands(commands)
   }
   "black_clipping" -> {
    // Black clipping / PLUGE
    AppState.setCommands(PatternGenerator.drawPluge(AppState.hdr))
   }
   "color_bars" -> {
    AppState.setCommands(PatternGenerator.drawBars(AppState.quantRange != 1, AppState.hdr))
   }
   "gray_ramp" -> {
    AppState.setCommands(PatternGenerator.drawGrayscaleRamp(AppState.quantRange != 1))
   }
   "overscan" -> {
    // Overscan: white border lines on black background
    val commands = mutableListOf<DrawCommand>()
    commands.add(DrawCommand.fullFieldInt(0, 0, 0, maxV))
    val t = 0.01f // border thickness
    // Top
    commands.add(DrawCommand.solidRect(-1f, 1f, 1f, 1f - t, 1f, 1f, 1f))
    // Bottom
    commands.add(DrawCommand.solidRect(-1f, -1f + t, 1f, -1f, 1f, 1f, 1f))
    // Left
    commands.add(DrawCommand.solidRect(-1f, 1f, -1f + t, -1f, 1f, 1f, 1f))
    // Right
    commands.add(DrawCommand.solidRect(1f - t, 1f, 1f, -1f, 1f, 1f, 1f))
    // Center cross
    commands.add(DrawCommand.solidRect(-0.005f, 0.15f, 0.005f, -0.15f, 1f, 1f, 1f))
    commands.add(DrawCommand.solidRect(-0.15f, 0.005f, 0.15f, -0.005f, 1f, 1f, 1f))
    AppState.setCommands(commands)
   }
   "patch" -> {
    // Patch with specific R,G,B and window size
    val r = json.get("r")?.asInt ?: 128
    val g = json.get("g")?.asInt ?: 128
    val b = json.get("b")?.asInt ?: 128
    val size = json.get("size")?.asInt ?: 18
    if (size >= 100) {
     AppState.setCommands(listOf(DrawCommand.fullFieldInt(r, g, b, maxV)))
    } else {
     AppState.setCommands(PatternGenerator.drawWindow(size.toFloat(), r, g, b))
    }
   }
   "stop" -> {
    AppState.setCommands(emptyList())
   }

   // Android-compatible pattern names (backward compat)
   "fullfield" -> {
    val r = json.get("r")?.asInt ?: 128
    val g = json.get("g")?.asInt ?: 128
    val b = json.get("b")?.asInt ?: 128
    AppState.setCommands(listOf(DrawCommand.fullFieldInt(r, g, b, maxV)))
   }
   "window" -> {
    val r = json.get("r")?.asInt ?: 128
    val g = json.get("g")?.asInt ?: 128
    val b = json.get("b")?.asInt ?: 128
    val size = json.get("size")?.asFloat ?: 18f
    val bgR = json.get("bgR")?.asInt ?: 0
    val bgG = json.get("bgG")?.asInt ?: 0
    val bgB = json.get("bgB")?.asInt ?: 0
    AppState.setCommands(PatternGenerator.drawWindow(size, r, g, b, bgR, bgG, bgB))
   }
   "black" -> AppState.setCommands(listOf(DrawCommand.fullFieldInt(0, 0, 0, maxV)))
   "white" -> AppState.setCommands(listOf(DrawCommand.fullFieldInt(255, 255, 255, maxV)))
   "red" -> AppState.setCommands(listOf(DrawCommand.fullFieldInt(255, 0, 0, maxV)))
   "green" -> AppState.setCommands(listOf(DrawCommand.fullFieldInt(0, 255, 0, maxV)))
   "blue" -> AppState.setCommands(listOf(DrawCommand.fullFieldInt(0, 0, 255, maxV)))
   "clear" -> AppState.setCommands(emptyList())
   else -> return jsonError(400, "Unknown pattern: $patternName")
  }

  val result = JsonObject().apply { addProperty("status", "ok") }
  return jsonResponse(result)
 }

 // ───── /api/restart ─────

 private fun handleRestart(): Response {
  // Apply signal settings (equivalent of Pi's pattern generator restart)
  HdrController.applySignalSettings(
   AppState.eotf, AppState.colorFormat, AppState.colorimetry, AppState.bitDepth
  )
  if (AppState.maxCLL > 0) {
   HdrController.setHdrMetadata(AppState.maxCLL, AppState.maxFALL, AppState.maxDML)
  }
  AppState.modeChanged = true

  val result = JsonObject().apply {
   addProperty("status", "ok")
   addProperty("message", "Signal settings applied")
  }
  return jsonResponse(result)
 }

 // ───── /api/modes (stub — not available on Android) ─────

 private fun serveModes(): Response {
  return jsonResponse(com.google.gson.JsonArray())
 }

 // ───── /api/status ─────

 private fun serveStatus(): Response {
  val status = JsonObject().apply {
   addProperty("connectionStatus", AppState.connectionStatus.get())
   addProperty("mode", AppState.patternMode.name)
   addProperty("bitDepth", AppState.bitDepth)
   addProperty("hdr", AppState.hdr)
   addProperty("pgenPort", PGenServer.TCP_PORT)
   addProperty("upgciPort", UPGCIServer.TCP_PORT)
   addProperty("discoveryPort", DiscoveryService.UDP_PORT)
   addProperty("webuiPort", listeningPort)
   addProperty("ip", getLocalIpAddress())
  }
  return jsonResponse(status)
 }

 // ───── /api/hdr/caps ─────

 private fun serveHdrCaps(): Response {
  val caps = JsonObject().apply {
   val hdrInfo = HdrController.getHdrInfo(context)
   addProperty("hdr10", hdrInfo.contains("HDR10"))
   addProperty("hlg", hdrInfo.contains("HLG"))
   addProperty("dolbyVision", hdrInfo.contains("Dolby Vision"))
   addProperty("hdr10Plus", hdrInfo.contains("HDR10+"))
   addProperty("amlogic", HdrController.isAmlogicDevice())
   addProperty("raw", hdrInfo)
  }
  return jsonResponse(caps)
 }

 // ───── /api/hdr (legacy Android route) ─────

 private fun handleHdrPost(session: IHTTPSession): Response {
  val body = readBody(session)
  val json = gson.fromJson(body, JsonObject::class.java)

  val hdr = json.get("enabled")?.asBoolean ?: false
  val bits = json.get("bitDepth")?.asInt ?: 8
  val eotf = json.get("eotf")?.asInt ?: if (hdr) 2 else 0

  AppState.setMode(bits, hdr)
  AppState.eotf = eotf
  AppState.modeChanged = true

  val maxCLL = json.get("maxCLL")?.asInt
  val maxFALL = json.get("maxFALL")?.asInt
  val maxDML = json.get("maxDML")?.asInt

  if (maxCLL != null) {
   AppState.maxCLL = maxCLL
   AppState.maxFALL = maxFALL ?: maxCLL
   AppState.maxDML = maxDML ?: maxCLL
   HdrController.setHdrMetadata(AppState.maxCLL, AppState.maxFALL, AppState.maxDML)
  }

  val result = JsonObject().apply { addProperty("status", "ok") }
  return jsonResponse(result)
 }

 // ───── Helpers ─────

 private fun readBody(session: IHTTPSession): String {
  val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
  val buf = ByteArray(contentLength)
  session.inputStream.read(buf)
  return String(buf, Charsets.UTF_8)
 }

 private fun jsonResponse(json: Any): Response {
  val jsonStr = when (json) {
   is JsonObject -> json.toString()
   is com.google.gson.JsonArray -> json.toString()
   else -> gson.toJson(json)
  }
  return newFixedLengthResponse(
   Response.Status.OK,
   "application/json",
   jsonStr
  ).apply {
   addHeader("Access-Control-Allow-Origin", "*")
   addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
   addHeader("Access-Control-Allow-Headers", "Content-Type")
  }
 }

 private fun jsonError(code: Int, message: String): Response {
  val json = JsonObject().apply {
   addProperty("error", message)
  }
  val status = when (code) {
   400 -> Response.Status.BAD_REQUEST
   404 -> Response.Status.NOT_FOUND
   else -> Response.Status.INTERNAL_ERROR
  }
  return newFixedLengthResponse(status, "application/json", json.toString()).apply {
   addHeader("Access-Control-Allow-Origin", "*")
  }
 }

 private fun corsResponse(): Response {
  return newFixedLengthResponse(Response.Status.OK, "text/plain", "").apply {
   addHeader("Access-Control-Allow-Origin", "*")
   addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
   addHeader("Access-Control-Allow-Headers", "Content-Type")
  }
 }

 private fun getLocalIpAddress(): String {
  try {
   val interfaces = NetworkInterface.getNetworkInterfaces()
   while (interfaces.hasMoreElements()) {
    val iface = interfaces.nextElement()
    val addresses = iface.inetAddresses
    while (addresses.hasMoreElements()) {
     val addr = addresses.nextElement()
     if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
      return addr.hostAddress ?: "unknown"
     }
    }
   }
  } catch (e: Exception) {
   Log.e(TAG, "Failed to get IP address", e)
  }
  return "unknown"
 }
}
