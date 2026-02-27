package com.pgeneratorplus.android.patterns

import com.pgeneratorplus.android.model.AppState
import com.pgeneratorplus.android.model.DrawCommand

/**
 * Built-in test pattern generator.
 * Generates standard calibration patterns: PLUGE, color bars, windows.
 */
object PatternGenerator {

 /**
  * PLUGE pattern (Picture Line-Up Generation Equipment).
  * ITU-R BT.814-4: near-black bars for brightness/black level setup.
  *
  * Layout:
  *   [+4% | -4% | 0% | +4% | -4%]
  *
  * @param isHdr If true, use wider range values for HDR content.
  */
 fun drawPluge(isHdr: Boolean = false): List<DrawCommand> {
  val commands = mutableListOf<DrawCommand>()
  val maxV = 255f

  val bgLevel = if (isHdr) 26 else 16  // ~6.3% / ~10.2%
  val refLevel = bgLevel
  val plusLevel = bgLevel + 10            // Reference+4%
  val minusLevel = (bgLevel - 10).coerceAtLeast(0) // Reference-4%

  // Background
  commands.add(DrawCommand.fullFieldInt(bgLevel, bgLevel, bgLevel, maxV))

  // 5 vertical bars across the center
  val barWidth = 0.12f
  val barHeight = 0.5f
  val startX = -0.3f

  val levels = intArrayOf(plusLevel, minusLevel, refLevel, plusLevel, minusLevel)

  for (i in levels.indices) {
   val cmd = DrawCommand()
   val level = levels[i]
   cmd.setColorsFromRgb(intArrayOf(level, level, level), maxV)
   cmd.x1 = startX + i * barWidth
   cmd.y1 = barHeight
   cmd.x2 = cmd.x1 + barWidth
   cmd.y2 = -barHeight
   commands.add(cmd)
  }

  return commands
 }

 /**
  * Color bars pattern.
  * ITU-R BT.2111-2 style — RGBCMYW bars.
  *
  * @param fullRange If true, use full range (0-255); else limited (16-235).
  * @param isHdr If true, adjusted for HDR viewing.
  */
 fun drawBars(fullRange: Boolean = true, isHdr: Boolean = false): List<DrawCommand> {
  val commands = mutableListOf<DrawCommand>()
  val maxV = 255f

  val maxLevel = if (fullRange) 255 else 235
  val minLevel = if (fullRange) 0 else 16

  val colors = arrayOf(
   intArrayOf(maxLevel, maxLevel, maxLevel), // White
   intArrayOf(maxLevel, maxLevel, minLevel),  // Yellow
   intArrayOf(minLevel, maxLevel, maxLevel),  // Cyan
   intArrayOf(minLevel, maxLevel, minLevel),  // Green
   intArrayOf(maxLevel, minLevel, maxLevel),  // Magenta
   intArrayOf(maxLevel, minLevel, minLevel),  // Red
   intArrayOf(minLevel, minLevel, maxLevel),  // Blue
   intArrayOf(minLevel, minLevel, minLevel),  // Black
  )

  // Background black
  commands.add(DrawCommand.fullFieldInt(minLevel, minLevel, minLevel, maxV))

  val barWidth = 2.0f / colors.size

  for (i in colors.indices) {
   val cmd = DrawCommand()
   cmd.setColorsFromRgb(colors[i], maxV)
   cmd.x1 = -1.0f + i * barWidth
   cmd.y1 = 1.0f
   cmd.x2 = cmd.x1 + barWidth
   cmd.y2 = -1.0f
   commands.add(cmd)
  }

  return commands
 }

 /**
  * Window pattern.
  *
  * @param sizePct Window size as percentage of screen area (e.g. 18 = 18%).
  * @param r Red value (0-255)
  * @param g Green value (0-255)
  * @param b Blue value (0-255)
  * @param bgR Background red (0-255)
  * @param bgG Background green (0-255)
  * @param bgB Background blue (0-255)
  */
 fun drawWindow(
  sizePct: Float = 18f,
  r: Int = 255, g: Int = 255, b: Int = 255,
  bgR: Int = 0, bgG: Int = 0, bgB: Int = 0
 ): List<DrawCommand> {
  val maxV = 255f
  return listOf(
   DrawCommand.fullFieldInt(bgR, bgG, bgB, maxV),
   DrawCommand.windowPatternInt(sizePct, r, g, b, maxV)
  )
 }

 /**
  * Full field solid color.
  */
 fun drawFullField(r: Int, g: Int, b: Int): List<DrawCommand> {
  return listOf(DrawCommand.fullFieldInt(r, g, b, 255f))
 }

 /**
  * Grayscale ramp — 10 steps from black to white.
  */
 fun drawGrayscaleRamp(fullRange: Boolean = true): List<DrawCommand> {
  val commands = mutableListOf<DrawCommand>()
  val maxV = 255f
  val steps = 10
  val minLevel = if (fullRange) 0 else 16
  val maxLevel = if (fullRange) 255 else 235

  // Background
  commands.add(DrawCommand.fullFieldInt(0, 0, 0, maxV))

  val barWidth = 2.0f / steps

  for (i in 0 until steps) {
   val level = minLevel + (maxLevel - minLevel) * i / (steps - 1)
   val cmd = DrawCommand()
   cmd.setColorsFromRgb(intArrayOf(level, level, level), maxV)
   cmd.x1 = -1.0f + i * barWidth
   cmd.y1 = 1.0f
   cmd.x2 = cmd.x1 + barWidth
   cmd.y2 = -1.0f
   commands.add(cmd)
  }

  return commands
 }

 /**
  * Parse a draw string command.
  *
  * Formats:
  *   "window <size> <r> <g> <b>"         → window pattern
  *   "draw <x1> <y1> <x2> <y2> <r> <g> <b>"  → arbitrary rectangle
  *   "field <r> <g> <b>"                 → full field
  */
 fun parseDrawString(input: String): List<DrawCommand>? {
  val parts = input.trim().split("\\s+".toRegex())
  if (parts.isEmpty()) return null

  val maxV = 255f

  return when (parts[0].lowercase()) {
   "window" -> {
    if (parts.size < 5) return null
    val size = parts[1].toFloatOrNull() ?: return null
    val r = parts[2].toIntOrNull() ?: return null
    val g = parts[3].toIntOrNull() ?: return null
    val b = parts[4].toIntOrNull() ?: return null
    drawWindow(size, r, g, b)
   }
   "draw" -> {
    if (parts.size < 8) return null
    val x1 = parts[1].toFloatOrNull() ?: return null
    val y1 = parts[2].toFloatOrNull() ?: return null
    val x2 = parts[3].toFloatOrNull() ?: return null
    val y2 = parts[4].toFloatOrNull() ?: return null
    val r = parts[5].toIntOrNull() ?: return null
    val g = parts[6].toIntOrNull() ?: return null
    val b = parts[7].toIntOrNull() ?: return null
    val cmd = DrawCommand()
    cmd.setColorsFromRgb(intArrayOf(r, g, b), maxV)
    cmd.x1 = x1; cmd.y1 = y1; cmd.x2 = x2; cmd.y2 = y2
    listOf(cmd)
   }
   "field" -> {
    if (parts.size < 4) return null
    val r = parts[1].toIntOrNull() ?: return null
    val g = parts[2].toIntOrNull() ?: return null
    val b = parts[3].toIntOrNull() ?: return null
    drawFullField(r, g, b)
   }
   else -> null
  }
 }
}
