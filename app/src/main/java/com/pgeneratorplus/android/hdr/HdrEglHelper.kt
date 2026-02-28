package com.pgeneratorplus.android.hdr

import android.opengl.EGL14
import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/**
 * EGL helper classes for HDR rendering.
 *
 * On non-rooted devices (e.g. Chromecast with Google TV), we cannot write to
 * Amlogic sysfs to trigger HDR. Instead we use Android's display framework:
 *
 * 1. Create an EGL surface with RGBA16F (half-float) pixel format
 * 2. Set EGL_GL_COLORSPACE_BT2020_PQ_EXT on the window surface
 * 3. Set window.colorMode = COLOR_MODE_HDR
 *
 * This tells SurfaceFlinger the content is BT.2020 PQ HDR, which triggers
 * HDR output negotiation with the display — no root needed.
 *
 * For HLG: use EGL_GL_COLORSPACE_BT2020_HLG_EXT instead.
 */
object HdrEglHelper {

 private const val TAG = "HdrEglHelper"

 // Whether native library was loaded successfully
 private var nativeAvailable = false

 init {
  try {
   System.loadLibrary("pgeneratorplus_native")
   nativeAvailable = true
   Log.i(TAG, "Native HDR library loaded")
  } catch (e: UnsatisfiedLinkError) {
   Log.w(TAG, "Native HDR library not available: ${e.message}")
  }
 }

 // EGL extension constants (not in javax.microedition.khronos.egl.EGL10)
 private const val EGL_OPENGL_ES3_BIT = 0x40
 private const val EGL_GL_COLORSPACE_KHR = 0x309D
 private const val EGL_GL_COLORSPACE_BT2020_PQ_EXT = 0x3340
 private const val EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540
 private const val EGL_GL_COLORSPACE_SRGB_KHR = 0x3089
 private const val EGL_COLOR_COMPONENT_TYPE_EXT = 0x3339
 private const val EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT = 0x333B

 /** Track which HDR mode was successfully applied via EGL */
 var activeHdrColorspace = 0
  private set

 /** Whether HDR dataspace was set via Surface API (fallback for devices without EGL HDR) */
 var hdrDataSpaceSet = false
  private set

 /** Whether HDR is active via either EGL colorspace or Surface dataspace */
 val isHdrActive: Boolean
  get() = activeHdrColorspace != 0 || hdrDataSpaceSet

 // Android DataSpace constants (from android/data_space.h)
 private const val DATASPACE_BT2020_PQ = 0x09C60000   // STANDARD_BT2020 | TRANSFER_ST2084 | RANGE_FULL
 private const val DATASPACE_BT2020_HLG = 0x09460000  // STANDARD_BT2020 | TRANSFER_HLG | RANGE_FULL

 /**
  * Configure a GLSurfaceView for HDR rendering.
  *
  * Attempts to set up RGBA16F + BT2020_PQ (or HLG) EGL surface.
  * Falls back to standard RGBA8 if HDR EGL is not supported.
  *
  * @param glView The GLSurfaceView to configure
 * @param eotf EOTF: 2=PQ (HDR10), 3=HLG, 4=Dolby Vision (PQ transport)
  * @return true if HDR EGL was successfully configured
  */
 fun configureHdrSurface(glView: GLSurfaceView, eotf: Int): Boolean {
  val colorspace = when (eotf) {
   2 -> EGL_GL_COLORSPACE_BT2020_PQ_EXT
    4 -> EGL_GL_COLORSPACE_BT2020_PQ_EXT
   3 -> EGL_GL_COLORSPACE_BT2020_HLG_EXT
   else -> 0
  }

  if (colorspace == 0) {
   Log.w(TAG, "EOTF $eotf is not HDR, skipping HDR EGL setup")
   return false
  }

  // Set RGBA16F config chooser for HDR
  glView.setEGLConfigChooser(HdrConfigChooser())

  // Set window surface factory with HDR colorspace + Surface dataspace fallback
  glView.setEGLWindowSurfaceFactory(HdrWindowSurfaceFactory(colorspace, eotf))

  Log.i(TAG, "Configured GLSurfaceView for HDR (EOTF=$eotf, " +
   "colorspace=0x${colorspace.toString(16)})")
  return true
 }

 /**
  * Set HDR data space on a Surface.
  *
  * Tries NDK ANativeWindow_setBuffersDataSpace() first (most reliable),
  * then falls back to Java reflection on Surface.setBuffersDataSpace().
  *
  * This is the primary HDR trigger on devices that don't expose
  * EGL BT.2020 colorspace extensions (e.g. Chromecast with Google TV).
  * SurfaceFlinger reads the buffer data space to decide HDR output.
  */
 fun setBufferDataSpace(surface: android.view.Surface, eotf: Int): Boolean {
  val dataSpace = when (eotf) {
   2, 4 -> DATASPACE_BT2020_PQ
   3 -> DATASPACE_BT2020_HLG
   else -> return false
  }

  // Try 1: NDK native call (most reliable)
  if (nativeAvailable) {
   try {
    val result = nativeSetBuffersDataSpace(surface, dataSpace)
    if (result == 0) {
     hdrDataSpaceSet = true
     Log.i(TAG, "Buffer data space set to 0x${dataSpace.toString(16)} via NDK")
     return true
    }
    Log.w(TAG, "NDK setBuffersDataSpace returned error: $result")
   } catch (e: Exception) {
    Log.w(TAG, "NDK setBuffersDataSpace failed: ${e.message}")
   }
  }

  // Try 2: Java reflection fallback
  return try {
   val method = android.view.Surface::class.java
    .getDeclaredMethod("setBuffersDataSpace", Integer.TYPE)
   method.isAccessible = true
   method.invoke(surface, dataSpace)
   hdrDataSpaceSet = true
   Log.i(TAG, "Buffer data space set to 0x${dataSpace.toString(16)} via reflection")
   true
  } catch (e: Exception) {
   Log.w(TAG, "setBuffersDataSpace not available: ${e.javaClass.simpleName}: ${e.message}")
   false
  }
 }

 /**
  * JNI bridge to ANativeWindow_setBuffersDataSpace() (NDK API 28+).
  */
 private external fun nativeSetBuffersDataSpace(surface: android.view.Surface, dataSpace: Int): Int

 // EGL_EXT_surface_SMPTE2086_metadata constants
 private const val EGL_SMPTE2086_DISPLAY_PRIMARY_RX_EXT = 0x3341
 private const val EGL_SMPTE2086_DISPLAY_PRIMARY_RY_EXT = 0x3342
 private const val EGL_SMPTE2086_DISPLAY_PRIMARY_GX_EXT = 0x3343
 private const val EGL_SMPTE2086_DISPLAY_PRIMARY_GY_EXT = 0x3344
 private const val EGL_SMPTE2086_DISPLAY_PRIMARY_BX_EXT = 0x3345
 private const val EGL_SMPTE2086_DISPLAY_PRIMARY_BY_EXT = 0x3346
 private const val EGL_SMPTE2086_WHITE_POINT_X_EXT = 0x3347
 private const val EGL_SMPTE2086_WHITE_POINT_Y_EXT = 0x3348
 private const val EGL_SMPTE2086_MAX_LUMINANCE_EXT = 0x3349
 private const val EGL_SMPTE2086_MIN_LUMINANCE_EXT = 0x334A
 // EGL_EXT_surface_CTA861_3_metadata constants
 private const val EGL_CTA861_3_MAX_CONTENT_LIGHT_LEVEL_EXT = 0x3360
 private const val EGL_CTA861_3_MAX_FRAME_AVERAGE_LIGHT_LEVEL_EXT = 0x3361

 /**
  * Set SMPTE2086 + CTA861.3 HDR static metadata on the current EGL surface.
  *
  * Must be called on the GL thread (e.g. from Renderer.onSurfaceCreated).
  * Uses BT.2020 primaries, D65 white, and 1000/0.001 nit luminance range.
  * This metadata is included in the HDMI InfoFrame and tells the display
  * that the content is mastered for HDR.
  */
 fun setEglHdrMetadata() {
  try {
   val display = EGL14.eglGetCurrentDisplay()
   val surface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
   if (display == EGL14.EGL_NO_DISPLAY || surface == EGL14.EGL_NO_SURFACE) {
    Log.w(TAG, "No current EGL context for HDR metadata")
    return
   }

   // BT.2020 primaries in 0.00002 units: value = chromaticity × 50000
   EGL14.eglSurfaceAttrib(display, surface, EGL_SMPTE2086_DISPLAY_PRIMARY_RX_EXT, 35400) // 0.708
   EGL14.eglSurfaceAttrib(display, surface, EGL_SMPTE2086_DISPLAY_PRIMARY_RY_EXT, 14600) // 0.292
   EGL14.eglSurfaceAttrib(display, surface, EGL_SMPTE2086_DISPLAY_PRIMARY_GX_EXT, 8500)  // 0.170
   EGL14.eglSurfaceAttrib(display, surface, EGL_SMPTE2086_DISPLAY_PRIMARY_GY_EXT, 39850) // 0.797
   EGL14.eglSurfaceAttrib(display, surface, EGL_SMPTE2086_DISPLAY_PRIMARY_BX_EXT, 6550)  // 0.131
   EGL14.eglSurfaceAttrib(display, surface, EGL_SMPTE2086_DISPLAY_PRIMARY_BY_EXT, 2300)  // 0.046
   // D65 white point
   EGL14.eglSurfaceAttrib(display, surface, EGL_SMPTE2086_WHITE_POINT_X_EXT, 15635)      // 0.3127
   EGL14.eglSurfaceAttrib(display, surface, EGL_SMPTE2086_WHITE_POINT_Y_EXT, 16450)      // 0.3290
   // Luminance: max=1000 cd/m², min=0.001 cd/m² (in 0.0001 units = 10)
   EGL14.eglSurfaceAttrib(display, surface, EGL_SMPTE2086_MAX_LUMINANCE_EXT, 1000)
   EGL14.eglSurfaceAttrib(display, surface, EGL_SMPTE2086_MIN_LUMINANCE_EXT, 10)
   // CTA861.3: MaxCLL=1000, MaxFALL=400
   EGL14.eglSurfaceAttrib(display, surface, EGL_CTA861_3_MAX_CONTENT_LIGHT_LEVEL_EXT, 1000)
   EGL14.eglSurfaceAttrib(display, surface, EGL_CTA861_3_MAX_FRAME_AVERAGE_LIGHT_LEVEL_EXT, 400)

   Log.i(TAG, "EGL HDR static metadata set (SMPTE2086 BT.2020 + CTA861.3)")
  } catch (e: Exception) {
   Log.w(TAG, "Failed to set EGL HDR metadata: ${e.message}")
  }
 }

 /**
  * EGL config chooser that selects RGBA16F (half-float) for HDR rendering.
  *
  * Falls back to RGBA8 if no FP16 config is available (should not happen
  * on any device with GLES 3.0+ support).
  */
 class HdrConfigChooser : GLSurfaceView.EGLConfigChooser {

  override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
   // First try: RGBA1010102 (10-bit per channel, most HWC-friendly for HDR)
   // This format is more likely to be accepted for DEVICE composition
   // (hardware scanout) which is needed for HDR output on some devices.
   var config = tryChooseConfig(egl, display, intArrayOf(
    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
    EGL10.EGL_RED_SIZE, 10,
    EGL10.EGL_GREEN_SIZE, 10,
    EGL10.EGL_BLUE_SIZE, 10,
    EGL10.EGL_ALPHA_SIZE, 2,
    EGL10.EGL_DEPTH_SIZE, 0,
    EGL10.EGL_STENCIL_SIZE, 0,
    EGL10.EGL_NONE
   ))
   if (config != null) {
    Log.i(TAG, "Selected RGBA1010102 EGL config for HDR")
    return config
   }

   // Second try: RGBA16F with float component type (wider precision)
   config = tryChooseConfig(egl, display, intArrayOf(
    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
    EGL10.EGL_RED_SIZE, 16,
    EGL10.EGL_GREEN_SIZE, 16,
    EGL10.EGL_BLUE_SIZE, 16,
    EGL10.EGL_ALPHA_SIZE, 16,
    EGL_COLOR_COMPONENT_TYPE_EXT, EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT,
    EGL10.EGL_DEPTH_SIZE, 0,
    EGL10.EGL_STENCIL_SIZE, 0,
    EGL10.EGL_NONE
   ))
   if (config != null) {
    Log.i(TAG, "Selected RGBA16F EGL config for HDR")
    return config
   }

   // Fallback: standard RGBA8
   config = tryChooseConfig(egl, display, intArrayOf(
    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
    EGL10.EGL_RED_SIZE, 8,
    EGL10.EGL_GREEN_SIZE, 8,
    EGL10.EGL_BLUE_SIZE, 8,
    EGL10.EGL_ALPHA_SIZE, 8,
    EGL10.EGL_DEPTH_SIZE, 0,
    EGL10.EGL_STENCIL_SIZE, 0,
    EGL10.EGL_NONE
   ))
   if (config != null) {
    Log.w(TAG, "Falling back to RGBA8 EGL config (no HDR pixel format available)")
    return config
   }

   throw RuntimeException("No suitable EGL config found")
  }

  private fun tryChooseConfig(egl: EGL10, display: EGLDisplay, attribs: IntArray): EGLConfig? {
   val numConfigs = IntArray(1)
   if (!egl.eglChooseConfig(display, attribs, null, 0, numConfigs)) {
    return null
   }
   if (numConfigs[0] <= 0) return null

   val configs = arrayOfNulls<EGLConfig>(numConfigs[0])
   if (!egl.eglChooseConfig(display, attribs, configs, numConfigs[0], numConfigs)) {
    return null
   }
   return configs[0]
  }
 }

 /**
  * EGL window surface factory that sets BT.2020 PQ or HLG colorspace
  * on the surface. This is the key to triggering HDR output on non-rooted
  * devices — SurfaceFlinger sees the HDR dataspace and negotiates HDR
  * with the display.
  */
 class HdrWindowSurfaceFactory(
  private val colorspace: Int,
  private val eotf: Int
 ) : GLSurfaceView.EGLWindowSurfaceFactory {

  override fun createWindowSurface(
   egl: EGL10,
   display: EGLDisplay,
   config: EGLConfig,
   nativeWindow: Any
  ): EGLSurface {
   // Step 1: Try creating surface with EGL HDR colorspace
   val hdrAttribs = intArrayOf(
    EGL_GL_COLORSPACE_KHR, colorspace,
    EGL10.EGL_NONE
   )

   try {
    val surface = egl.eglCreateWindowSurface(display, config, nativeWindow, hdrAttribs)
    if (surface != null && surface != EGL10.EGL_NO_SURFACE) {
     val error = egl.eglGetError()
     if (error == EGL10.EGL_SUCCESS) {
      activeHdrColorspace = colorspace
      Log.i(TAG, "Created EGL surface with HDR colorspace 0x${colorspace.toString(16)}")
      return surface
     }
     Log.w(TAG, "EGL surface created but with error: 0x${error.toString(16)}")
    }
   } catch (e: Exception) {
    Log.w(TAG, "EGL HDR colorspace failed: ${e.message}")
   }

   // Step 2: EGL HDR colorspace not supported — set data space on native window
   Log.i(TAG, "EGL HDR colorspace not available, trying Surface data space API")
   activeHdrColorspace = 0
   val nativeSurface = when (nativeWindow) {
    is android.view.SurfaceHolder -> nativeWindow.surface
    is android.view.Surface -> nativeWindow
    else -> null
   }
   if (nativeSurface != null) {
    setBufferDataSpace(nativeSurface, eotf)
   }

   // Step 3: Create standard EGL surface (HDR via data space + window colorMode)
   val surface = egl.eglCreateWindowSurface(display, config, nativeWindow, null)
   if (surface == null || surface == EGL10.EGL_NO_SURFACE) {
    val error = egl.eglGetError()
    throw RuntimeException("Failed to create EGL surface: 0x${error.toString(16)}")
   }
   Log.i(TAG, "Created EGL surface (HDR via data space: $hdrDataSpaceSet)")
   return surface
  }

  override fun destroySurface(egl: EGL10, display: EGLDisplay, surface: EGLSurface) {
   activeHdrColorspace = 0
   hdrDataSpaceSet = false
   egl.eglDestroySurface(display, surface)
  }
 }
}
