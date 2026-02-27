package com.pgeneratorplus.android.network

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.pgeneratorplus.android.hdr.HdrController
import com.pgeneratorplus.android.model.AppState
import com.pgeneratorplus.android.model.DrawCommand
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.NetworkInterface

/**
 * Embedded Web UI HTTP server for PGenerator+ Android.
 *
 * Serves a responsive single-page dashboard (HTML/CSS/JS) and a JSON API.
 * Mirrors the PGenerator+ Pi Web UI functionality, adapted for Android.
 *
 * Routes:
 *   GET  /                    → Dashboard SPA
 *   GET  /api/info            → Device info (model, IP, resolution, HDR caps)
 *   GET  /api/status          → Connection & pattern status
 *   POST /api/pattern         → Set pattern (JSON body)
 *   POST /api/hdr             → Set HDR mode (JSON body)
 *   GET  /api/hdr/caps        → HDR capabilities
 *   POST /api/settings        → Update settings
 */
class WebUIServer(
 private val context: Context,
 port: Int = 8080
) : NanoHTTPD(port) {

 companion object {
  private const val TAG = "WebUIServer"
 }

 private val gson = Gson()

 override fun serve(session: IHTTPSession): Response {
  val uri = session.uri
  val method = session.method

  return try {
   when {
    uri == "/" || uri == "/index.html" -> serveDashboard()
    uri == "/api/info" && method == Method.GET -> serveDeviceInfo()
    uri == "/api/status" && method == Method.GET -> serveStatus()
    uri == "/api/pattern" && method == Method.POST -> handlePatternPost(session)
    uri == "/api/hdr" && method == Method.POST -> handleHdrPost(session)
    uri == "/api/hdr/caps" && method == Method.GET -> serveHdrCaps()
    uri == "/api/settings" && method == Method.POST -> handleSettingsPost(session)
    uri == "/api/settings" && method == Method.GET -> serveSettings()
    uri.startsWith("/api/") -> jsonError(404, "Not found")
    else -> serveDashboard()
   }
  } catch (e: Exception) {
   Log.e(TAG, "Error serving $uri", e)
   jsonError(500, "Internal error: ${e.message}")
  }
 }

 private fun serveDashboard(): Response {
  return newFixedLengthResponse(Response.Status.OK, "text/html", DASHBOARD_HTML)
 }

 private fun serveDeviceInfo(): Response {
  val info = JsonObject().apply {
   addProperty("device", "${Build.MANUFACTURER} ${Build.MODEL}")
   addProperty("android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
   addProperty("hostname", Build.MODEL)
   addProperty("ip", getLocalIpAddress())
   addProperty("version", "2.0.1")
   addProperty("product", "PGenerator+ Android")

   val display = HdrController.getDisplayResolution(context)
   addProperty("resolution", "${display.first}x${display.second}")

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

 private fun serveSettings(): Response {
  val settings = JsonObject().apply {
   addProperty("bitDepth", AppState.bitDepth)
   addProperty("hdr", AppState.hdr)
   addProperty("eotf", AppState.eotf)
   addProperty("colorFormat", AppState.colorFormat)
   addProperty("colorimetry", AppState.colorimetry)
   addProperty("quantRange", AppState.quantRange)
   addProperty("maxCLL", AppState.maxCLL)
   addProperty("maxFALL", AppState.maxFALL)
   addProperty("maxDML", AppState.maxDML)
  }

  return jsonResponse(settings)
 }

 private fun handlePatternPost(session: IHTTPSession): Response {
  val body = readBody(session)
  val json = gson.fromJson(body, JsonObject::class.java)
  val type = json.get("type")?.asString ?: "fullfield"

  val r = json.get("r")?.asInt ?: 128
  val g = json.get("g")?.asInt ?: 128
  val b = json.get("b")?.asInt ?: 128
  val maxV = 255f

  when (type) {
   "fullfield" -> {
    AppState.setCommands(listOf(DrawCommand.fullFieldInt(r, g, b, maxV)))
   }
   "window" -> {
    val windowPct = json.get("size")?.asFloat ?: 18f
    val bgR = json.get("bgR")?.asInt ?: 0
    val bgG = json.get("bgG")?.asInt ?: 0
    val bgB = json.get("bgB")?.asInt ?: 0
    AppState.setCommands(listOf(
     DrawCommand.fullFieldInt(bgR, bgG, bgB, maxV),
     DrawCommand.windowPatternInt(windowPct, r, g, b, maxV)
    ))
   }
   "black" -> AppState.setCommands(listOf(DrawCommand.fullFieldInt(0, 0, 0, maxV)))
   "white" -> AppState.setCommands(listOf(DrawCommand.fullFieldInt(255, 255, 255, maxV)))
   "red" -> AppState.setCommands(listOf(DrawCommand.fullFieldInt(255, 0, 0, maxV)))
   "green" -> AppState.setCommands(listOf(DrawCommand.fullFieldInt(0, 255, 0, maxV)))
   "blue" -> AppState.setCommands(listOf(DrawCommand.fullFieldInt(0, 0, 255, maxV)))
   "clear" -> AppState.setCommands(emptyList())
   else -> return jsonError(400, "Unknown pattern type: $type")
  }

  val result = JsonObject().apply { addProperty("status", "ok") }
  return jsonResponse(result)
 }

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

 private fun handleSettingsPost(session: IHTTPSession): Response {
  val body = readBody(session)
  val json = gson.fromJson(body, JsonObject::class.java)

  json.get("bitDepth")?.asInt?.let { AppState.bitDepth = it }
  json.get("eotf")?.asInt?.let { AppState.eotf = it }
  json.get("colorFormat")?.asInt?.let { AppState.colorFormat = it }
  json.get("colorimetry")?.asInt?.let { AppState.colorimetry = it }
  json.get("quantRange")?.asInt?.let { AppState.quantRange = it }

  val result = JsonObject().apply { addProperty("status", "ok") }
  return jsonResponse(result)
 }

 private fun readBody(session: IHTTPSession): String {
  val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
  val buf = ByteArray(contentLength)
  session.inputStream.read(buf)
  return String(buf, Charsets.UTF_8)
 }

 private fun jsonResponse(json: JsonObject): Response {
  return newFixedLengthResponse(
   Response.Status.OK,
   "application/json",
   json.toString()
  ).apply {
   addHeader("Access-Control-Allow-Origin", "*")
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

/**
 * Dashboard SPA HTML — embedded as a constant to avoid asset file dependency.
 * Styled to match PGenerator+ branding (dark theme, blue accents).
 */
private const val DASHBOARD_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>PGenerator+ Android</title>
<style>
:root {
 --bg-primary: #0d1117;
 --bg-secondary: #161b22;
 --bg-card: #21262d;
 --text-primary: #e6edf3;
 --text-secondary: #8b949e;
 --accent: #58a6ff;
 --accent-hover: #79c0ff;
 --success: #3fb950;
 --warning: #d29922;
 --danger: #f85149;
 --border: #30363d;
 --radius: 8px;
}
* { margin:0; padding:0; box-sizing:border-box; }
body {
 font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
 background: var(--bg-primary);
 color: var(--text-primary);
 line-height: 1.5;
}
.header {
 background: var(--bg-secondary);
 border-bottom: 1px solid var(--border);
 padding: 16px 24px;
 display: flex;
 align-items: center;
 justify-content: space-between;
}
.header h1 {
 font-size: 20px;
 font-weight: 600;
}
.header h1 span { color: var(--accent); }
.status-badge {
 display: inline-block;
 padding: 4px 12px;
 border-radius: 12px;
 font-size: 12px;
 font-weight: 500;
 background: rgba(63,185,80,0.15);
 color: var(--success);
}
.container {
 max-width: 1200px;
 margin: 0 auto;
 padding: 24px;
}
.grid {
 display: grid;
 grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
 gap: 20px;
}
.card {
 background: var(--bg-card);
 border: 1px solid var(--border);
 border-radius: var(--radius);
 padding: 20px;
}
.card h2 {
 font-size: 14px;
 font-weight: 600;
 text-transform: uppercase;
 letter-spacing: 0.5px;
 color: var(--text-secondary);
 margin-bottom: 16px;
 padding-bottom: 8px;
 border-bottom: 1px solid var(--border);
}
.info-row {
 display: flex;
 justify-content: space-between;
 padding: 6px 0;
}
.info-row .label { color: var(--text-secondary); font-size: 14px; }
.info-row .value { color: var(--text-primary); font-size: 14px; font-weight: 500; }
.btn-grid {
 display: grid;
 grid-template-columns: repeat(3, 1fr);
 gap: 8px;
}
.btn {
 padding: 10px 16px;
 border: 1px solid var(--border);
 border-radius: var(--radius);
 background: var(--bg-secondary);
 color: var(--text-primary);
 cursor: pointer;
 font-size: 13px;
 font-weight: 500;
 transition: all 0.15s;
 text-align: center;
}
.btn:hover { border-color: var(--accent); color: var(--accent); }
.btn:active { transform: scale(0.97); }
.btn.color-black { background: #000; border-color: #333; }
.btn.color-white { background: #fff; color: #000; border-color: #ccc; }
.btn.color-red { background: #c00; border-color: #f33; }
.btn.color-green { background: #0a0; border-color: #3f3; }
.btn.color-blue { background: #00c; border-color: #33f; }
.btn.color-clear { background: var(--bg-primary); border-color: var(--danger); color: var(--danger); }
.rgb-input {
 display: flex;
 gap: 8px;
 margin-top: 12px;
}
.rgb-input input {
 flex: 1;
 padding: 8px;
 border: 1px solid var(--border);
 border-radius: var(--radius);
 background: var(--bg-secondary);
 color: var(--text-primary);
 text-align: center;
 font-size: 14px;
}
.rgb-input input:focus { outline: none; border-color: var(--accent); }
.btn-send {
 width: 100%;
 margin-top: 8px;
 padding: 10px;
 border: none;
 border-radius: var(--radius);
 background: var(--accent);
 color: #fff;
 cursor: pointer;
 font-size: 14px;
 font-weight: 500;
}
.btn-send:hover { background: var(--accent-hover); }
select {
 width: 100%;
 padding: 8px;
 border: 1px solid var(--border);
 border-radius: var(--radius);
 background: var(--bg-secondary);
 color: var(--text-primary);
 font-size: 14px;
 margin-bottom: 8px;
}
select:focus { outline: none; border-color: var(--accent); }
.toggle-row {
 display: flex;
 justify-content: space-between;
 align-items: center;
 padding: 8px 0;
}
.toggle {
 position: relative;
 width: 44px;
 height: 24px;
}
.toggle input { opacity: 0; width: 0; height: 0; }
.toggle .slider {
 position: absolute;
 inset: 0;
 background: var(--border);
 border-radius: 12px;
 cursor: pointer;
 transition: 0.2s;
}
.toggle .slider:before {
 content: "";
 position: absolute;
 width: 18px;
 height: 18px;
 left: 3px;
 bottom: 3px;
 background: #fff;
 border-radius: 50%;
 transition: 0.2s;
}
.toggle input:checked + .slider { background: var(--accent); }
.toggle input:checked + .slider:before { transform: translateX(20px); }
.footer {
 text-align: center;
 padding: 20px;
 font-size: 12px;
 color: var(--text-secondary);
}
@media (max-width: 720px) {
 .container { padding: 12px; }
 .grid { grid-template-columns: 1fr; }
 .btn-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
</head>
<body>
<div class="header">
 <h1>PGenerator<span>+</span> Android</h1>
 <span class="status-badge" id="statusBadge">Initializing...</span>
</div>

<div class="container">
 <div class="grid">

  <div class="card">
   <h2>Device Information</h2>
   <div id="deviceInfo">
    <div class="info-row"><span class="label">Loading...</span></div>
   </div>
  </div>

  <div class="card">
   <h2>Connection Status</h2>
   <div id="connectionInfo">
    <div class="info-row"><span class="label">Loading...</span></div>
   </div>
  </div>

  <div class="card">
   <h2>Quick Patterns</h2>
   <div class="btn-grid">
    <button class="btn color-black" onclick="sendPattern('black')">Black</button>
    <button class="btn color-white" onclick="sendPattern('white')">White</button>
    <button class="btn color-red" onclick="sendPattern('red')">Red</button>
    <button class="btn color-green" onclick="sendPattern('green')">Green</button>
    <button class="btn color-blue" onclick="sendPattern('blue')">Blue</button>
    <button class="btn color-clear" onclick="sendPattern('clear')">Clear</button>
   </div>
   <div class="rgb-input">
    <input type="number" id="r" placeholder="R" min="0" max="255" value="128">
    <input type="number" id="g" placeholder="G" min="0" max="255" value="128">
    <input type="number" id="b" placeholder="B" min="0" max="255" value="128">
   </div>
   <button class="btn-send" onclick="sendCustomRgb()">Send Full Field</button>
   <div style="display:flex;gap:8px;margin-top:4px">
    <select id="windowSize" style="margin-bottom:0">
     <option value="10">10% Window</option>
     <option value="18" selected>18% Window</option>
     <option value="25">25% Window</option>
     <option value="50">50% Window</option>
     <option value="75">75% Window</option>
    </select>
    <button class="btn-send" style="flex:1" onclick="sendWindow()">Window</button>
   </div>
  </div>

  <div class="card">
   <h2>Signal Configuration</h2>
   <div class="toggle-row">
    <span>HDR Mode</span>
    <label class="toggle">
     <input type="checkbox" id="hdrToggle" onchange="updateHdr()">
     <span class="slider"></span>
    </label>
   </div>
   <select id="bitDepth" onchange="updateSettings()">
    <option value="8">8-bit</option>
    <option value="10">10-bit</option>
   </select>
   <select id="eotf" onchange="updateSettings()">
    <option value="0">SDR (BT.1886)</option>
    <option value="2">PQ (HDR10)</option>
    <option value="3">HLG</option>
   </select>
   <select id="colorFormat" onchange="updateSettings()">
    <option value="0">RGB</option>
    <option value="1">YCbCr 4:4:4</option>
    <option value="2">YCbCr 4:2:2</option>
   </select>
   <select id="colorimetry" onchange="updateSettings()">
    <option value="0">BT.709</option>
    <option value="1">BT.2020</option>
   </select>
   <select id="quantRange" onchange="updateSettings()">
    <option value="0">Auto</option>
    <option value="1">Limited (16-235)</option>
    <option value="2">Full (0-255)</option>
   </select>
  </div>

 </div>
</div>

<div class="footer">PGenerator+ Android v2.0.1</div>

<script>
const API = '';
let refreshTimer;

async function fetchJson(url, opts) {
 try {
  const r = await fetch(API + url, opts);
  return await r.json();
 } catch(e) { console.error(url, e); return null; }
}

async function refreshInfo() {
 const info = await fetchJson('/api/info');
 if (info) {
  document.getElementById('deviceInfo').innerHTML = [
   row('Device', info.device),
   row('Android', info.android),
   row('Resolution', info.resolution),
   row('IP Address', info.ip),
   row('Signal', (info.hdr ? info.hdrMode : 'SDR') + ' / ' + info.bitDepth + '-bit'),
   row('Version', info.version),
  ].join('');
 }

 const status = await fetchJson('/api/status');
 if (status) {
  document.getElementById('statusBadge').textContent = status.connectionStatus || 'Ready';
  document.getElementById('connectionInfo').innerHTML = [
   row('Status', status.connectionStatus),
   row('Mode', status.mode),
   row('PGen Port', status.pgenPort),
   row('UPGCI Port', status.upgciPort),
   row('Discovery', 'UDP ' + status.discoveryPort),
   row('Web UI', 'http://' + status.ip + ':' + status.webuiPort),
  ].join('');
 }

 const settings = await fetchJson('/api/settings');
 if (settings) {
  document.getElementById('hdrToggle').checked = settings.hdr;
  document.getElementById('bitDepth').value = settings.bitDepth;
  document.getElementById('eotf').value = settings.eotf;
  document.getElementById('colorFormat').value = settings.colorFormat;
  document.getElementById('colorimetry').value = settings.colorimetry;
  document.getElementById('quantRange').value = settings.quantRange;
 }
}

function row(label, value) {
 return '<div class="info-row"><span class="label">'+label+'</span><span class="value">'+(value||'-')+'</span></div>';
}

async function sendPattern(type) {
 await fetchJson('/api/pattern', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({type: type})
 });
}

async function sendCustomRgb() {
 const r = parseInt(document.getElementById('r').value) || 0;
 const g = parseInt(document.getElementById('g').value) || 0;
 const b = parseInt(document.getElementById('b').value) || 0;
 await fetchJson('/api/pattern', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({type:'fullfield', r:r, g:g, b:b})
 });
}

async function sendWindow() {
 const r = parseInt(document.getElementById('r').value) || 0;
 const g = parseInt(document.getElementById('g').value) || 0;
 const b = parseInt(document.getElementById('b').value) || 0;
 const size = parseInt(document.getElementById('windowSize').value) || 18;
 await fetchJson('/api/pattern', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({type:'window', r:r, g:g, b:b, size:size, bgR:0, bgG:0, bgB:0})
 });
}

async function updateHdr() {
 const hdr = document.getElementById('hdrToggle').checked;
 const bits = parseInt(document.getElementById('bitDepth').value);
 const eotf = parseInt(document.getElementById('eotf').value);
 await fetchJson('/api/hdr', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({enabled: hdr, bitDepth: bits, eotf: eotf})
 });
}

async function updateSettings() {
 await fetchJson('/api/settings', {
  method: 'POST',
  headers: {'Content-Type': 'application/json'},
  body: JSON.stringify({
   bitDepth: parseInt(document.getElementById('bitDepth').value),
   eotf: parseInt(document.getElementById('eotf').value),
   colorFormat: parseInt(document.getElementById('colorFormat').value),
   colorimetry: parseInt(document.getElementById('colorimetry').value),
   quantRange: parseInt(document.getElementById('quantRange').value)
  })
 });
}

refreshInfo();
refreshTimer = setInterval(refreshInfo, 3000);
</script>
</body>
</html>"""
