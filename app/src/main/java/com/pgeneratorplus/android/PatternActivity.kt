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
 private var discoveryService: DiscoveryService? = null
 private var webUIServer: WebUIServer? = null

 private var pgenThread: Thread? = null
 private var upgciThread: Thread? = null
 private var resolveThread: Thread? = null

 private var mode: String = "pgen"
 private var isHdr = false
 private var bitDepth = 8

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

  HdrController.keepScreenOn(this, true)

  Log.i(TAG, "Starting in $mode mode (${bitDepth}-bit ${if (isHdr) "HDR" else "SDR"})")
 }

 override fun onResume() {
  super.onResume()
  glView.onResume()

  // Apply HDR mode
  if (isHdr) {
   HdrController.setHdrMode(true, this)
   AppState.setMode(bitDepth, true)
   AppState.modeChanged = true
  } else {
   AppState.setMode(bitDepth, false)
  }

  startServers()
 }

 override fun onPause() {
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
  // Start discovery service
  discoveryService = DiscoveryService("PGeneratorPlus-Android").also { it.start() }

  // Start Web UI server
  try {
   webUIServer = WebUIServer(this, 8080).also { it.start() }
   Log.i(TAG, "Web UI server started on port 8080")
  } catch (e: Exception) {
   Log.e(TAG, "Failed to start Web UI server", e)
  }

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
  discoveryService?.stop()

  try { webUIServer?.stop() } catch (e: Exception) {}

  pgenThread?.interrupt()
  upgciThread?.interrupt()
  resolveThread?.interrupt()

  try { pgenThread?.join(2000) } catch (e: InterruptedException) {}
  try { upgciThread?.join(2000) } catch (e: InterruptedException) {}
  try { resolveThread?.join(2000) } catch (e: InterruptedException) {}

  pgenServer = null
  upgciServer = null
  resolveClient = null
  discoveryService = null
  webUIServer = null
  pgenThread = null
  upgciThread = null
  resolveThread = null

  AppState.setCommands(emptyList())
  Log.i(TAG, "All servers stopped")
 }
}
