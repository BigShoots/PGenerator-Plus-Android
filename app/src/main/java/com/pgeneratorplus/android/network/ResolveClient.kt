package com.pgeneratorplus.android.network

import android.util.Log
import com.pgeneratorplus.android.model.AppState
import com.pgeneratorplus.android.model.DrawCommand
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Resolve XML protocol client.
 * Connects to DisplayCAL, Calman, or ColourSpace as a TPG.
 */
class ResolveClient(
 private val ip: String,
 private val port: Int = 20002,
 private val isHdr: Boolean,
 private val windowOverride: Float = 0f
) {
 companion object {
  private const val TAG = "ResolveClient"
  private const val CONNECT_TIMEOUT = 5000
  private const val READ_TIMEOUT = 0
 }

 @Volatile
 private var running = false
 private var socket: Socket? = null

 fun start() {
  running = true
  var firstPattern = true

  try {
   AppState.connectionStatus.set("Connecting to $ip:$port...")
   Log.i(TAG, "Attempting to connect to $ip:$port")

   socket = Socket()
   socket!!.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT)
   socket!!.soTimeout = READ_TIMEOUT

   AppState.connectionStatus.set("Connected to $ip:$port")
   Log.i(TAG, "Connection established!")

   AppState.setCommands(emptyList())

   val inputStream = socket!!.getInputStream()

   while (running && !Thread.currentThread().isInterrupted) {
    AppState.waitPending()

    val lenBuf = ByteArray(4)
    if (!readFully(inputStream, lenBuf)) {
     Log.i(TAG, "Server closed connection")
     break
    }

    val dataLen = ByteBuffer.wrap(lenBuf).order(ByteOrder.BIG_ENDIAN).int
    if (dataLen <= 0) {
     Log.i(TAG, "Server indicated connection close (len=$dataLen)")
     break
    }

    val xmlBuf = ByteArray(dataLen)
    if (!readFully(inputStream, xmlBuf)) {
     Log.e(TAG, "Failed to receive XML data")
     break
    }

    val xmlData = String(xmlBuf, Charsets.UTF_8)
    if (AppState.debug.get()) {
     Log.d(TAG, "Received XML: $xmlData")
    }

    val calibData = XmlParser.parse(xmlData)
    if (calibData == null) {
     Log.e(TAG, "Failed to parse calibration XML")
     continue
    }

    if (calibData.targetBits != 8 && calibData.targetBits != 10) {
     Log.e(TAG, "Unsupported bit depth: ${calibData.targetBits}")
     continue
    }

    val commands = mutableListOf<DrawCommand>()

    if (!calibData.isFullField &&
     !(calibData.backgroundRed == 0f && calibData.backgroundGreen == 0f && calibData.backgroundBlue == 0f)
    ) {
     val bg = DrawCommand()
     bg.setColorsFromRgb(floatArrayOf(calibData.backgroundRed, calibData.backgroundGreen, calibData.backgroundBlue))
     bg.setCoordsFromWindow(100f)
     commands.add(bg)
    }

    val draw = DrawCommand()
    draw.setColorsFromRgb(floatArrayOf(calibData.colorRed, calibData.colorGreen, calibData.colorBlue))

    if (windowOverride == 0f || calibData.isFullField) {
     draw.x1 = -1f + 2f * calibData.geometryX
     draw.y1 = 1f - 2f * calibData.geometryY
     draw.x2 = draw.x1 + 2f * calibData.geometryCX
     draw.y2 = draw.y1 - 2f * calibData.geometryCY
    } else {
     draw.setCoordsFromWindow(windowOverride)
    }

    commands.add(draw)

    AppState.setCommands(commands)

    val bitMatches = (calibData.targetBits == 10 && AppState.bitDepth == 10) ||
     (calibData.targetBits == 8 && AppState.bitDepth == 8)

    if (firstPattern || !bitMatches) {
     AppState.setMode(calibData.targetBits, isHdr)
     Log.i(TAG, "Switching to ${calibData.targetBits} bit ${if (isHdr) "HDR" else "SDR"} output")
     firstPattern = false
    }
   }
  } catch (e: IOException) {
   if (running) {
    Log.e(TAG, "Connection error", e)
    AppState.connectionStatus.set("Connection error: ${e.message}")
   }
  } catch (e: InterruptedException) {
   Log.i(TAG, "Client thread interrupted")
  } finally {
   cleanup()
  }
 }

 fun stop() {
  running = false
  try { socket?.close() } catch (e: IOException) {}
  AppState.clearPending()
 }

 private fun cleanup() {
  try { socket?.close() } catch (e: IOException) {}
  socket = null
  AppState.setCommands(emptyList())
  AppState.connectionStatus.set("Disconnected")
  Log.i(TAG, "Connection closed")
 }

 private fun readFully(stream: InputStream, buffer: ByteArray): Boolean {
  var offset = 0
  while (offset < buffer.size) {
   val read = stream.read(buffer, offset, buffer.size - offset)
   if (read == -1) return false
   offset += read
  }
  return true
 }
}
