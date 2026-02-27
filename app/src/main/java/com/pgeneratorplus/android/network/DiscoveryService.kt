package com.pgeneratorplus.android.network

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP Discovery service for PGenerator protocol.
 * Responds to calibration software discovery broadcasts on port 1977.
 *
 * Protocol: Client sends "Who is a PGenerator" → device replies
 * "I am a PGenerator <hostname>"
 */
class DiscoveryService(
 private val hostname: String = "PGeneratorPlus-Android"
) {
 companion object {
  private const val TAG = "DiscoveryService"
  const val UDP_PORT = 1977
  private const val MAX_BUFFER_SIZE = 1024

  @Volatile
  private var instance: DiscoveryService? = null

  @Synchronized
  fun startInstance(hostname: String = "PGeneratorPlus-Android"): DiscoveryService {
   instance?.let {
    if (it.active.get()) return it
   }
   return DiscoveryService(hostname).also {
    it.start()
    instance = it
   }
  }

  @Synchronized
  fun stopInstance() {
   instance?.stop()
   instance = null
  }

  fun isRunning(): Boolean = instance?.active?.get() == true
 }

 private val active = AtomicBoolean(false)
 private var udpSocket: DatagramSocket? = null
 private var thread: Thread? = null

 fun start() {
  if (active.get()) return

  active.set(true)
  thread = Thread({
   try {
    udpSocket = DatagramSocket(UDP_PORT).apply { reuseAddress = true }
    val buffer = ByteArray(MAX_BUFFER_SIZE)

    Log.i(TAG, "Discovery service listening on UDP port $UDP_PORT")

    while (active.get()) {
     try {
      val packet = DatagramPacket(buffer, buffer.size)
      udpSocket?.receive(packet)

      val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
      if (message == "Who is a PGenerator") {
       val response = "I am a PGenerator $hostname"
       val responseBytes = response.toByteArray(Charsets.UTF_8)
       val responsePacket = DatagramPacket(
        responseBytes, responseBytes.size,
        packet.address, packet.port
       )
       udpSocket?.send(responsePacket)
       Log.i(TAG, "Sent discovery response to ${packet.address}")
      }
     } catch (e: SocketException) {
      if (active.get()) Log.e(TAG, "UDP discovery error", e)
     } catch (e: IOException) {
      if (active.get()) Log.e(TAG, "UDP discovery error", e)
     }
    }
   } catch (e: Exception) {
    if (active.get()) Log.e(TAG, "Discovery service error", e)
   } finally {
    try { udpSocket?.close() } catch (e: IOException) {}
    Log.i(TAG, "Discovery service stopped")
   }
  }, "PGen-Discovery")

  thread!!.isDaemon = true
  thread!!.start()
 }

 fun stop() {
  active.set(false)
  try { udpSocket?.close() } catch (e: IOException) {}
  try { thread?.join(1000) } catch (e: InterruptedException) {}
  thread = null
 }
}
