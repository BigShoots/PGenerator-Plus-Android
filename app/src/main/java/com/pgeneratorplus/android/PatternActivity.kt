package com.pgeneratorplus.android

import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.pgeneratorplus.android.hdr.HdrController
import com.pgeneratorplus.android.hdr.HdrEglHelper
import com.pgeneratorplus.android.hdr.HdrVideoOverlay
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
 private var hdrEglActive = false
 private var suppressSdrRestoreOnPause = false
 private var bitDepth = 8
 private var eotf = 0
 private var colorFormat = 0
 private var colorimetry = 0
 private var quantRange = 0
 private var hdrVideoOverlay: HdrVideoOverlay? = null
 private var hdrDecoderSurfaceView: SurfaceView? = null

 private fun normalizeHdrRequest() {
  if (!isHdr) {
   eotf = 0
   return
  }

  if (eotf != 2 && eotf != 3 && eotf != 4) {
   Log.w(TAG, "HDR requested with invalid EOTF=$eotf, defaulting to PQ (2)")
   eotf = 2
  }

  if (bitDepth < 10) {
   Log.i(TAG, "HDR requested with bitDepth=$bitDepth, promoting to 10-bit")
   bitDepth = 10
  }

  if (colorimetry == 0) {
   colorimetry = 1
  }
 }

 /**
  * Set HDR headroom on the SurfaceView's SurfaceControl via
  * SurfaceControl.Transaction (API 34+).
  *
  * This tells SurfaceFlinger that this layer contains HDR content
  * with brightness extending beyond the SDR white point. On devices
  * where HWC doesn't auto-detect HDR from buffer dataspace
  * (e.g. Chromecast with Google TV), this is needed to trigger
  * the display to switch to HDR output mode.
  */
 private fun setSurfaceControlHdrHeadroom() {
  if (android.os.Build.VERSION.SDK_INT < 34) return
  try {
   val sc = glView.surfaceControl
   if (sc == null || !sc.isValid) {
    Log.w(TAG, "SurfaceControl not available for HDR headroom")
    return
   }
   // setExtendedRangeBrightness (API 34) tells SurfaceFlinger the buffer
   // contains content brighter than SDR white point.
   // currentBufferRatio=10.0 = PQ content up to 10x SDR brightness
   // desiredRatio=10.0 = request 10x HDR headroom from display
   android.view.SurfaceControl.Transaction()
    .setExtendedRangeBrightness(sc, 10.0f, 10.0f)
    .apply()
   Log.i(TAG, "SurfaceControl HDR headroom set via setExtendedRangeBrightness")

   // Also try setDesiredHdrHeadroom (API 35) via reflection for newer devices
   try {
    val txn = android.view.SurfaceControl.Transaction()
    val method = txn.javaClass.getMethod(
     "setDesiredHdrHeadroom",
     android.view.SurfaceControl::class.java,
     Float::class.javaPrimitiveType
    )
    method.invoke(txn, sc, 10.0f)
    txn.apply()
    Log.i(TAG, "SurfaceControl HDR headroom set via setDesiredHdrHeadroom")
   } catch (e: NoSuchMethodException) {
    Log.d(TAG, "setDesiredHdrHeadroom not available (API 35+)")
   }
  } catch (e: Exception) {
   Log.w(TAG, "Failed to set SurfaceControl HDR headroom: ${e.message}")
  }
 }

 /**
  * Start the HDR video overlay to trigger the display pipeline to enter HDR mode.
  * Uses the existing video decoder SurfaceView that's behind our GL view.
  * A MediaCodec HEVC decoder with HDR10 parameters outputs a frame to the
  * SurfaceView, triggering the Amlogic video HAL to switch HDMI output
  * to BT2020 PQ (HDR10).
  */
 private fun startHdrVideoOverlay() {
  if (hdrVideoOverlay != null) return
  val sv = hdrDecoderSurfaceView ?: return
  val surface = sv.holder.surface
  if (surface == null || !surface.isValid) {
   Log.w(TAG, "HDR decoder surface not ready, waiting for callback")
   return
  }
  hdrVideoOverlay = HdrVideoOverlay()
  Thread {
   // Ensure 10-bit decode is enabled (critical for Chromecast HDR)
   val tenBitOk = HdrController.ensure10BitDecodeSupport()
   if (!tenBitOk) {
    Log.w(TAG, "10-bit decode not enabled — HDR may not activate on HDMI output. " +
     "Run: adb shell setprop debug.vendor.media.c2.vdec.support_10bit true")
   }
   val success = hdrVideoOverlay?.start(surface, eotf) ?: false
   if (success) {
    Log.i(TAG, "HDR video overlay started successfully (10bit=$tenBitOk)")
   } else {
    Log.w(TAG, "HDR video overlay failed to start")
   }
  }.start()
 }

 /**
  * Stop the HDR video overlay and release resources.
  */
 private fun stopHdrVideoOverlay() {
  hdrVideoOverlay?.stop()
  hdrVideoOverlay = null
 }

 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)

  // Parse intent early — we need HDR info before setting up EGL
  mode = intent.getStringExtra("mode") ?: "pgen"
  isHdr = intent.getBooleanExtra("hdr", false)
  bitDepth = intent.getIntExtra("bits", 8)
  eotf = intent.getIntExtra("eotf", 0)
  colorFormat = intent.getIntExtra("colorFormat", 0)
  colorimetry = intent.getIntExtra("colorimetry", 0)
  quantRange = intent.getIntExtra("quantRange", 0)
  normalizeHdrRequest()

  // Set HDR color mode BEFORE creating the surface — this tells SurfaceFlinger
  // that this activity renders HDR content, triggering HDR output negotiation.
  if (isHdr && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
   window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_HDR
   Log.i(TAG, "Window colorMode set to HDR (before surface creation)")
  }

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

  // Setup GLSurfaceView — use HDR EGL surface if HDR mode enabled
  glView = GLSurfaceView(this).apply {
   setEGLContextClientVersion(3)

   if (isHdr) {
    // Request 10-bit pixel format first (more likely to get HWC DEVICE composition),
    // falling back to FP16 if not available
    holder.setFormat(PixelFormat.RGBA_1010102)
    // Configure RGBA1010102 or RGBA16F EGL + BT2020_PQ/HLG colorspace
    hdrEglActive = HdrEglHelper.configureHdrSurface(this, eotf)
    if (hdrEglActive) {
     Log.i(TAG, "HDR EGL surface configured (EOTF=$eotf)")
    } else {
     Log.w(TAG, "HDR EGL setup failed, falling back to RGBA8")
     setEGLConfigChooser(8, 8, 8, 8, 0, 0)
    }
   } else {
    setEGLConfigChooser(8, 8, 8, 8, 0, 0) // RGBA8, no depth/stencil
   }

   setRenderer(PatternRenderer())
   renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
  }

  if (isHdr) {
   // Use a FrameLayout with a video decoder SurfaceView behind the GL view.
   // The decoder SurfaceView receives HDR10 HEVC output which triggers the
   // Amlogic video pipeline to switch HDMI to BT2020 PQ mode.
   val frameLayout = FrameLayout(this)

   // Create a full-screen SurfaceView for the video decoder output (behind GL view).
   // Must be full-screen for the Amlogic HWC to trigger HDR mode switch.
   // A tiny (1x1) layer is not sufficient — the HWC needs a visible video
   // overlay layer covering the display to activate BT2020 PQ output.
   hdrDecoderSurfaceView = SurfaceView(this).also { sv ->
    sv.setZOrderMediaOverlay(false)  // Behind main content
    sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
     override fun surfaceCreated(holder: android.view.SurfaceHolder) {
      Log.i(TAG, "HDR decoder surface created, starting overlay...")
      startHdrVideoOverlay()
     }
     override fun surfaceChanged(holder: android.view.SurfaceHolder, f: Int, w: Int, h: Int) {}
     override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
      stopHdrVideoOverlay()
     }
    })
    frameLayout.addView(sv, FrameLayout.LayoutParams(
     FrameLayout.LayoutParams.MATCH_PARENT,
     FrameLayout.LayoutParams.MATCH_PARENT
    ))
   }

   // GL view on top
   glView.setZOrderOnTop(true)
   frameLayout.addView(glView, FrameLayout.LayoutParams(
    FrameLayout.LayoutParams.MATCH_PARENT,
    FrameLayout.LayoutParams.MATCH_PARENT
   ))
   setContentView(frameLayout)
  } else {
   setContentView(glView)
  }

  // Backup HDR setup: if EGL colorspace + factory dataspace both failed,
  // try once more when the surface is created via SurfaceHolder callback.
  // Also set SurfaceControl HDR headroom to signal HDR to the compositor.
  if (isHdr) {
   glView.holder.addCallback(object : android.view.SurfaceHolder.Callback {
    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
     if (!HdrEglHelper.isHdrActive) {
      Log.i(TAG, "SurfaceHolder callback: retrying HDR dataspace setup")
      HdrEglHelper.setBufferDataSpace(holder.surface, eotf)
     }
     // Set HDR headroom on SurfaceControl (API 34+) to tell SurfaceFlinger
     // this layer contains HDR content. This is critical on devices where
     // the HWC doesn't detect HDR from buffer dataspace alone.
     setSurfaceControlHdrHeadroom()
    }
    override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {}
    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
   })
  }

  HdrController.keepScreenOn(this, true)

  Log.i(TAG, "Starting in $mode mode (${bitDepth}-bit " +
   "${if (isHdr) "HDR" else "SDR"} EOTF=$eotf " +
   "color=$colorFormat colorimetry=$colorimetry range=$quantRange" +
   " hdrEgl=$hdrEglActive)")
 }

 override fun onResume() {
  super.onResume()
  glView.onResume()
  AppState.patternActivityActive = true

  // Apply all signal settings to AppState
  AppState.bitDepth = bitDepth
  AppState.applyEotfMode(eotf)
  AppState.colorFormat = colorFormat
  AppState.colorimetry = colorimetry
  AppState.quantRange = quantRange
  AppState.modeChanged = true

  // Apply HDR mode and signal settings via HdrController
  if (isHdr) {
   HdrController.setHdrMode(true, this)
   HdrController.applySignalSettings(eotf, colorFormat, colorimetry, bitDepth)
   // Start HDR video overlay to trigger display pipeline HDR mode
   startHdrVideoOverlay()
  } else {
   HdrController.setHdrMode(false, this)
   HdrController.applySignalSettings(0, colorFormat, colorimetry, bitDepth)
  }

  startServers()
 }

 override fun onPause() {
  AppState.patternActivityActive = false
  stopServers()
  stopHdrVideoOverlay()
  glView.onPause()

  if (isHdr && !suppressSdrRestoreOnPause) {
   HdrController.setHdrMode(false, this)
   // Restore SDR on HDMI output
   HdrController.applySignalSettings(0, colorFormat, colorimetry, bitDepth)
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
  upgciServer = UPGCIServer(isHdr) { hdr, bits, targetEotf ->
   runOnUiThread {
    val eotfForMode = if (hdr) {
     if (targetEotf == 2 || targetEotf == 3 || targetEotf == 4) targetEotf else 2
    } else {
     0
    }
    val needsEglRecreate = !HdrController.hasSysfsHdrControl() &&
     (isHdr != hdr || (hdr && eotf != eotfForMode))

    isHdr = hdr
    bitDepth = if (hdr && bits < 10) 10 else bits
    eotf = eotfForMode
    if (hdr && colorimetry == 0) {
     colorimetry = 1
    }
    AppState.bitDepth = bitDepth
    AppState.applyEotfMode(eotfForMode)

    if (needsEglRecreate) {
     Log.i(TAG, "UPGCI mode change requires EGL recreate: hdr=$hdr eotf=$eotfForMode")
     restartForModeChange()
     return@runOnUiThread
    }

    HdrController.setHdrMode(hdr, this)
    HdrController.applySignalSettings(eotfForMode, AppState.colorFormat, AppState.colorimetry, bits)
   }
  }
  upgciThread = Thread({
   upgciServer?.start()
  }, "UPGCI-Server").also { it.start() }

  AppState.patternMode = AppState.PatternMode.PGEN
  Log.i(TAG, "PGen mode: All servers started")
 }

   private fun restartForModeChange() {
    suppressSdrRestoreOnPause = true
    val restartIntent = Intent(this, PatternActivity::class.java).apply {
     putExtra("mode", mode)
     putExtra("hdr", isHdr)
     putExtra("bits", bitDepth)
     putExtra("eotf", eotf)
     putExtra("colorFormat", colorFormat)
     putExtra("colorimetry", colorimetry)
     putExtra("quantRange", quantRange)
    }
    finish()
    startActivity(restartIntent)
    overridePendingTransition(0, 0)
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
