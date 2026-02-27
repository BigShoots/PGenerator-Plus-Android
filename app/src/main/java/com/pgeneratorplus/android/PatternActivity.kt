package com.pgeneratorplus.android

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.pgeneratorplus.android.hdr.HdrController
import com.pgeneratorplus.android.model.AppState
import com.pgeneratorplus.android.model.DrawCommand
import com.pgeneratorplus.android.network.*
import com.pgeneratorplus.android.patterns.PatternGenerator
import com.pgeneratorplus.android.renderer.PatternRenderer

/**
 * Fullscreen pattern display activity.
 *
 * Manages the OpenGL renderer and network servers.
 * Supports three operational modes:
 *
 * 1. **PGen mode**: Starts PGenServer (port 85), UPGCIServer (port 2100),
 *    DiscoveryService (UDP 1977), and WebUIServer (port 8080) simultaneously.
 *    External calibration software connects and controls patterns.
 *
 * 2. **Resolve mode**: Connects to a Resolve/DisplayCAL/Calman XML server
 *    and renders patterns received over TCP.
 *
 * 3. **Manual mode**: Displays a selected built-in test pattern.
 */
class PatternActivity : AppCompatActivity() {

 companion object {
  private const val TAG = "PatternActivity"
 }

 private lateinit var glView: GLSurfaceView
 private var pgenServer: PGenServer? = null
 private var upgciServer: UPGCIServer? = null
 private var resolveClient: ResolveClient? = null

 private var pgenThread: Thread? = null
 private var upgciThread: Thread? = null
 private var resolveThread: Thread? = null

 private var mode: String = "pgen"
 private var isHdr = false
 private var bitDepth = 8
 private var eotf = 0
 private var colorFormat = 0
 private var colorimetry = 0
 private var quantRange = 0

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)

  // Fullscreen immersive
  window.setFlags(
   WindowManager.LayoutParams.FLAG_FULLSCREEN,
   WindowManager.LayoutParams.FLAG_FULLSCREEN
  )

  @Suppress("DEPRECATION")
  window.decorView.systemUiVisibility = (
   android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
   android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
   android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
   android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
   android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
   android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
  )

  // Setup GLSurfaceView
  glView = GLSurfaceView(this).apply {
   setEGLContextClientVersion(3)
   setEGLConfigChooser(8, 8, 8, 8, 0, 0) // RGBA8, no depth/stencil
   setRenderer(PatternRenderer())
   renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
  }
  setContentView(glView)

  // Parse intent
  mode = intent.getStringExtra("mode") ?: "pgen"
  isHdr = intent.getBooleanExtra("hdr", false)
  bitDepth = intent.getIntExtra("bits", 8)
  eotf = intent.getIntExtra("eotf", 0)
  colorFormat = intent.getIntExtra("colorFormat", 0)
  colorimetry = intent.getIntExtra("colorimetry", 0)
  quantRange = intent.getIntExtra("quantRange", 0)

  HdrController.keepScreenOn(this, true)

  Log.i(TAG, "Starting in $mode mode (${bitDepth}-bit " +
   "${if (isHdr) "HDR" else "SDR"} EOTF=$eotf " +
   "color=$colorFormat colorimetry=$colorimetry range=$quantRange)")
 }

 override fun onResume() {
  super.onResume()
  glView.onResume()
  AppState.patternActivityActive = true

  // Apply all signal settings to AppState
  AppState.bitDepth = bitDepth
  AppState.hdr = isHdr
  AppState.eotf = eotf
  AppState.colorFormat = colorFormat
  AppState.colorimetry = colorimetry
  AppState.quantRange = quantRange
  AppState.modeChanged = true

  // Apply HDR mode and signal settings via HdrController
  if (isHdr) {
   HdrController.setHdrMode(true, this)
   HdrController.applySignalSettings(eotf, colorFormat, colorimetry, bitDepth)
  } else {
   HdrController.setHdrMode(false, this)
   HdrController.applySignalSettings(0, colorFormat, colorimetry, bitDepth)
  }

  startServers()
 }

 override fun onPause() {
  AppState.patternActivityActive = false
  stopServers()
  glView.onPause()

  if (isHdr) {
   HdrController.setHdrMode(false, this)
  }

  HdrController.keepScreenOn(this, false)
  super.onPause()
 }

 override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
  if (keyCode == KeyEvent.KEYCODE_BACK) {
   finish()
   return true
  }
  return super.onKeyDown(keyCode, event)
 }

 private fun startServers() {
  when (mode) {
   "pgen" -> startPGenMode()
   "resolve_sdr" -> startResolveMode(false)
   "resolve_hdr" -> startResolveMode(true)
   "manual" -> startManualMode()
  }
 }

 private fun startPGenMode() {
  // Ensure discovery service is running (uses singleton — safe if already started)
  DiscoveryService.startInstance("PGeneratorPlus-Android")

  // Ensure Web UI server is running (uses singleton — safe if already started)
  WebUIServer.startInstance(this, 8080)

  // Start PGen server
  pgenServer = PGenServer(isHdr)
  pgenThread = Thread({
   pgenServer?.start()
  }, "PGen-Server").also { it.start() }

  // Start UPGCI server
  upgciServer = UPGCIServer(isHdr) { hdr, bits ->
   runOnUiThread {
    isHdr = hdr
    bitDepth = bits
    HdrController.setHdrMode(hdr, this)
    if (hdr) {
     HdrController.applySignalSettings(AppState.eotf, AppState.colorFormat, AppState.colorimetry, bits)
    }
   }
  }
  upgciThread = Thread({
   upgciServer?.start()
  }, "UPGCI-Server").also { it.start() }

  AppState.patternMode = AppState.PatternMode.PGEN
  Log.i(TAG, "PGen mode: All servers started")
 }

 private fun startResolveMode(hdr: Boolean) {
  val ip = intent.getStringExtra("resolveIp") ?: "192.168.1.100"
  val port = intent.getIntExtra("resolvePort", 20002)

  resolveClient = ResolveClient(ip, port, hdr)
  resolveThread = Thread({
   resolveClient?.start()
  }, "Resolve-Client").also { it.start() }

  AppState.patternMode = if (hdr) AppState.PatternMode.RESOLVE_HDR else AppState.PatternMode.RESOLVE_SDR
  Log.i(TAG, "Resolve mode: Connecting to $ip:$port")
 }

 private fun startManualMode() {
  val pattern = intent.getStringExtra("pattern") ?: "pluge"
  val commands = when (pattern) {
   "pluge" -> PatternGenerator.drawPluge(isHdr)
   "bars" -> PatternGenerator.drawBars()
   "bars_limited" -> PatternGenerator.drawBars(fullRange = false)
   "window" -> PatternGenerator.drawWindow()
   "ramp" -> PatternGenerator.drawGrayscaleRamp()
   "black" -> PatternGenerator.drawFullField(0, 0, 0)
   "white" -> PatternGenerator.drawFullField(255, 255, 255)
   else -> PatternGenerator.drawPluge(isHdr)
  }
  AppState.setCommands(commands)
  AppState.patternMode = AppState.PatternMode.MANUAL
  Log.i(TAG, "Manual mode: Displaying $pattern pattern")
 }

 private fun stopServers() {
  pgenServer?.stop()
  upgciServer?.stop()
  resolveClient?.stop()

  // Don't stop WebUI or Discovery — they're singletons managed by MainActivity

  pgenThread?.interrupt()
  upgciThread?.interrupt()
  resolveThread?.interrupt()

  try { pgenThread?.join(2000) } catch (e: InterruptedException) {}
  try { upgciThread?.join(2000) } catch (e: InterruptedException) {}
  try { resolveThread?.join(2000) } catch (e: InterruptedException) {}

  pgenServer = null
  upgciServer = null
  resolveClient = null
  pgenThread = null
  upgciThread = null
  resolveThread = null

  AppState.setCommands(emptyList())
  Log.i(TAG, "Servers stopped")
 }
}
