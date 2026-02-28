package com.pgeneratorplus.android.model

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Shared application state for communication between network threads and the renderer.
 * Thread-safe singleton managing all global state.
 */
object AppState {
 @Volatile
 var bitDepth: Int = 8

 @Volatile
 var hdr: Boolean = false

 @Volatile
 var flicker: Int = 0

 val debug = AtomicBoolean(false)

 @Volatile
 var maxCLL: Int = -1
 @Volatile
 var maxFALL: Int = -1
 @Volatile
 var maxDML: Int = -1

 @Volatile
 var eotf: Int = 0 // 0=SDR, 2=PQ, 3=HLG, 4=Dolby Vision

 @Volatile
 var dvMode: Boolean = false

 @Volatile
 var colorFormat: Int = 0 // 0=RGB, 1=YCbCr444, 2=YCbCr422

 @Volatile
 var colorimetry: Int = 0 // 0=BT.709, 1=BT.2020

 @Volatile
 var quantRange: Int = 0 // 0=Auto, 1=Limited, 2=Full

 private val drawCommands = AtomicReference<List<DrawCommand>>(emptyList())
 private val pending = AtomicBoolean(false)
 private val lock = ReentrantLock()
 private val condition = lock.newCondition()

 @Volatile
 var modeChanged: Boolean = false

 val connectionStatus = AtomicReference("Idle")

 @Volatile
 var patternActivityActive: Boolean = false

 @Volatile
 var patternMode: PatternMode = PatternMode.MANUAL

 val maxValue: Float
  get() = ((1 shl bitDepth) - 1).toFloat()

 fun setCommands(commands: List<DrawCommand>) {
  drawCommands.set(commands)
  setPending()
 }

 fun getCommands(): List<DrawCommand> = drawCommands.get()

 fun setPending() {
  lock.withLock {
   pending.set(true)
   condition.signalAll()
  }
 }

 fun waitPending() {
  lock.withLock {
   while (pending.get()) {
    condition.await()
   }
  }
 }

 fun clearPending() {
  lock.withLock {
   pending.set(false)
   condition.signalAll()
  }
 }

 fun isPending(): Boolean = pending.get()

 fun setMode(bits: Int, isHdr: Boolean) {
  bitDepth = bits
  hdr = isHdr
    if (!isHdr) {
     eotf = 0
     dvMode = false
    } else if (eotf == 0) {
     eotf = 2
     dvMode = false
    } else {
     dvMode = (eotf == 4)
    }
  modeChanged = true
 }

 fun applyEotfMode(targetEotf: Int) {
    eotf = targetEotf
    dvMode = (targetEotf == 4)
    hdr = targetEotf > 0
    modeChanged = true
 }

 fun parseModeString(mode: String): Boolean {
  return when (mode.trim().lowercase()) {
   "8" -> { setMode(8, false); true }
   "8_hdr" -> { setMode(8, true); true }
   "10" -> { setMode(10, false); true }
   "10_hdr" -> { setMode(10, true); true }
   else -> false
  }
 }

 fun reset() {
  bitDepth = 8
  hdr = false
  flicker = 0
  maxCLL = -1
  maxFALL = -1
  maxDML = -1
  eotf = 0
    dvMode = false
  colorFormat = 0
  colorimetry = 0
  quantRange = 0
  drawCommands.set(emptyList())
  pending.set(false)
  patternActivityActive = false
  patternMode = PatternMode.MANUAL
  modeChanged = false
  connectionStatus.set("Idle")
 }

 enum class PatternMode {
  MANUAL,
  PGEN,
  RESOLVE_SDR,
  RESOLVE_HDR
 }
}
