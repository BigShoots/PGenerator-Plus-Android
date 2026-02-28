package com.pgeneratorplus.android.patterns

import com.pgeneratorplus.android.model.DrawCommand

/**
 * Built-in test pattern generator.
 * Generates standard calibration patterns modeled after AVS HD 709 and
 * common professional pattern specifications (SMPTE, ITU-R BT.814).
 *
 * All patterns use OpenGL NDC coordinates: x[-1,1], y[-1,1].
 * Colors are integer [0-255] converted to normalized floats internally.
 */
object PatternGenerator {

 private const val MAX_V = 255f

 // ───── White Clipping ─────
 /**
  * White Clipping pattern (AVS HD 709 style).
  * Tests display contrast/white level by showing near-white bars that
  * should all be distinguishable from each other and from the background.
  *
  * Layout: Gray background (85%) with a central region containing
  * vertical bars at 230, 234, 238, 242, 246, 250, 253, 254, 255.
  * Correctly set contrast: all bars visible, 255 bar barely distinct from 254.
  *
  * @param fullRange true = 0-255, false = limited 16-235 range
  */
 fun drawWhiteClipping(fullRange: Boolean = true): List<DrawCommand> {
  val commands = mutableListOf<DrawCommand>()
  val minL = if (fullRange) 0 else 16
  val maxL = if (fullRange) 255 else 235
  val range = maxL - minL

  // Background at ~85%
  val bgLevel = minL + (range * 0.85f).toInt()
  commands.add(DrawCommand.fullFieldInt(bgLevel, bgLevel, bgLevel, MAX_V))

  // Bars from ~90% up to 100% in ~2% steps, plus super-white if full range
  val levels = if (fullRange) {
   intArrayOf(230, 234, 238, 242, 246, 250, 253, 254, 255)
  } else {
   // Limited range: 16-235, go from ~210 to 235+
   intArrayOf(210, 214, 218, 222, 226, 230, 233, 234, 235)
  }
  val numBars = levels.size
  val totalBarWidth = 1.6f // 80% of screen width
  val barWidth = totalBarWidth / numBars
  val gap = 0.005f
  val startX = -totalBarWidth / 2f
  val barTop = 0.7f
  val barBottom = -0.7f

  for (i in levels.indices) {
   val level = levels[i]
   val cmd = DrawCommand()
   cmd.setColorsFromRgb(intArrayOf(level, level, level), MAX_V)
   cmd.x1 = startX + i * barWidth + gap
   cmd.y1 = barTop
   cmd.x2 = cmd.x1 + barWidth - gap * 2
   cmd.y2 = barBottom
   commands.add(cmd)
  }

  // Label row at bottom: reference black stripe to help see the white bars
  val refLevel = minL + (range * 0.5f).toInt()
  commands.add(DrawCommand.solidRect(-0.8f, barBottom - 0.02f, 0.8f, barBottom - 0.1f,
   refLevel / MAX_V, refLevel / MAX_V, refLevel / MAX_V))

  return commands
 }

 // ───── Black Clipping / PLUGE ─────
 /**
  * Black Clipping / PLUGE pattern (ITU-R BT.814-4 / AVS HD 709 style).
  *
  * On a dark gray background (~5%), shows:
  *   - A below-black bar (should be invisible when brightness is set correctly)
  *   - A near-black reference bar (at reference black level)
  *   - Bars stepping up from black in ~2% increments
  *
  * Correctly set brightness: the below-black bar is invisible, the
  * slightly-above-black bar is barely visible.
  *
  * Layout (left to right, centered):
  *   [below-black | black | +2% | +4% | +6% | +8% | +10%]
  *
  * @param fullRange true = 0-255, false = limited 16-235
  * @param isHdr true = HDR content (shifts levels up slightly)
  */
 fun drawBlackClipping(fullRange: Boolean = true, isHdr: Boolean = false): List<DrawCommand> {
  val commands = mutableListOf<DrawCommand>()
  val minL = if (fullRange) 0 else 16
  val maxL = if (fullRange) 255 else 235
  val range = maxL - minL

  // Background at ~5% above black
  val bgLevel = minL + (range * 0.05f).toInt()
  commands.add(DrawCommand.fullFieldInt(bgLevel, bgLevel, bgLevel, MAX_V))

  // Bar levels
  val belowBlack = (minL - (range * 0.02f).toInt()).coerceAtLeast(0) // Below reference black
  val levels = intArrayOf(
   belowBlack,                              // Below black (should be invisible)
   minL,                                    // Reference black
   minL + (range * 0.02f).toInt(),          // +2%
   minL + (range * 0.04f).toInt(),          // +4%
   minL + (range * 0.06f).toInt(),          // +6%
   minL + (range * 0.08f).toInt(),          // +8%
   minL + (range * 0.10f).toInt()           // +10%
  )

  val numBars = levels.size
  val totalBarWidth = 1.4f
  val barWidth = totalBarWidth / numBars
  val gap = 0.005f
  val startX = -totalBarWidth / 2f
  val barTop = 0.6f
  val barBottom = -0.6f

  for (i in levels.indices) {
   val level = levels[i]
   val cmd = DrawCommand()
   cmd.setColorsFromRgb(intArrayOf(level, level, level), MAX_V)
   cmd.x1 = startX + i * barWidth + gap
   cmd.y1 = barTop
   cmd.x2 = cmd.x1 + barWidth - gap * 2
   cmd.y2 = barBottom
   commands.add(cmd)
  }

  // Small markers above the below-black and reference bars to identify them
  // Below-black marker (left)
  commands.add(DrawCommand.solidRect(
   startX + gap, barTop + 0.06f,
   startX + barWidth - gap, barTop + 0.02f,
   0.2f, 0.0f, 0.0f)) // Red tint = below black
  // Reference black marker
  commands.add(DrawCommand.solidRect(
   startX + barWidth + gap, barTop + 0.06f,
   startX + barWidth * 2 - gap, barTop + 0.02f,
   0.2f, 0.2f, 0.2f)) // Gray = reference black

  return commands
 }

 // ───── Color Bars ─────
 /**
  * Color Bars pattern (Rec. 709 SMPTE-style with PLUGE).
  *
  * Top 75%: SMPTE order — Gray, Yellow, Cyan, Green, Magenta, Red, Blue
  * Bottom 25%: Reverse order — Blue, Black, Magenta, Black, Cyan, Black, Gray
  *   plus PLUGE section (below-black, black, above-black bars)
  *
  * @param fullRange true = 0-255, false = limited 16-235
  * @param isHdr true = HDR content
  */
 fun drawColorBars(fullRange: Boolean = true, isHdr: Boolean = false): List<DrawCommand> {
  val commands = mutableListOf<DrawCommand>()
  val maxL = if (fullRange) 255 else 235
  val minL = if (fullRange) 0 else 16

  // 75% intensity bars (Rec. 709) — top portion (~75% of screen)
  val level75 = minL + ((maxL - minL) * 0.75f).toInt()
  val topColors = arrayOf(
   intArrayOf(level75, level75, level75),     // 75% White/Gray
   intArrayOf(level75, level75, minL),        // Yellow
   intArrayOf(minL, level75, level75),        // Cyan
   intArrayOf(minL, level75, minL),           // Green
   intArrayOf(level75, minL, level75),        // Magenta
   intArrayOf(level75, minL, minL),           // Red
   intArrayOf(minL, minL, level75),           // Blue
  )

  // Background
  commands.add(DrawCommand.fullFieldInt(minL, minL, minL, MAX_V))

  val barWidth = 2.0f / topColors.size
  val topY = 1.0f
  val splitY = -0.5f  // Top bars take 75% of screen

  // Top color bars
  for (i in topColors.indices) {
   val cmd = DrawCommand()
   cmd.setColorsFromRgb(topColors[i], MAX_V)
   cmd.x1 = -1.0f + i * barWidth
   cmd.y1 = topY
   cmd.x2 = cmd.x1 + barWidth
   cmd.y2 = splitY
   commands.add(cmd)
  }

  // Bottom section — PLUGE + reverse bars
  val midY = splitY
  val midSplitY = splitY - 0.15f // Thin reverse-color strip
  val botY = -1.0f

  // Reverse-order color strip (middle thin band)
  val revColors = arrayOf(
   intArrayOf(minL, minL, level75),           // Blue
   intArrayOf(minL, minL, minL),              // Black
   intArrayOf(level75, minL, level75),        // Magenta
   intArrayOf(minL, minL, minL),              // Black
   intArrayOf(minL, level75, level75),        // Cyan
   intArrayOf(minL, minL, minL),              // Black
   intArrayOf(level75, level75, level75),     // Gray
  )

  for (i in revColors.indices) {
   val cmd = DrawCommand()
   cmd.setColorsFromRgb(revColors[i], MAX_V)
   cmd.x1 = -1.0f + i * barWidth
   cmd.y1 = midY
   cmd.x2 = cmd.x1 + barWidth
   cmd.y2 = midSplitY
   commands.add(cmd)
  }

  // PLUGE section (bottom area) — 3 sections across full width
  val range = maxL - minL
  val belowBlack = (minL - (range * 0.04f).toInt()).coerceAtLeast(0)
  val refBlack = minL
  val aboveBlack = minL + (range * 0.04f).toInt()

  // Left third: below-black, reference black, above-black bars
  val plugeBarWidth = (2.0f / 3f) / 3f
  val plugeStartX = -1.0f
  val plugeLevels = intArrayOf(belowBlack, refBlack, aboveBlack)
  for (i in plugeLevels.indices) {
   val cmd = DrawCommand()
   cmd.setColorsFromRgb(intArrayOf(plugeLevels[i], plugeLevels[i], plugeLevels[i]), MAX_V)
   cmd.x1 = plugeStartX + i * plugeBarWidth
   cmd.y1 = midSplitY
   cmd.x2 = cmd.x1 + plugeBarWidth
   cmd.y2 = botY
   commands.add(cmd)
  }

  // Middle third: 100% white bar
  commands.add(DrawCommand.solidRect(
   -1.0f / 3f, midSplitY, 1.0f / 3f, botY,
   maxL / MAX_V, maxL / MAX_V, maxL / MAX_V))

  // Right third: black
  commands.add(DrawCommand.solidRect(
   1.0f / 3f, midSplitY, 1.0f, botY,
   minL / MAX_V, minL / MAX_V, minL / MAX_V))

  return commands
 }

 // ───── Gray Ramp ─────
 /**
  * Gray Ramp pattern (AVS HD 709 style).
  *
  * Top ~60%: Smooth continuous gradient from black to white (using
  * per-vertex gradient interpolation in the OpenGL shader).
  * Bottom ~40%: 11 stepped bars from 0% to 100% in 10% increments,
  * matching the standard IRE step pattern.
  *
  * @param fullRange true = 0-255, false = limited 16-235
  */
 fun drawGrayscaleRamp(fullRange: Boolean = true): List<DrawCommand> {
  val commands = mutableListOf<DrawCommand>()
  val minL = if (fullRange) 0 else 16
  val maxL = if (fullRange) 255 else 235

  // Background
  commands.add(DrawCommand.fullFieldInt(0, 0, 0, MAX_V))

  // Top section: continuous gradient ramp (left=black, right=white)
  val rampTop = 1.0f
  val rampBottom = -0.2f
  val rampCmd = DrawCommand().apply {
   x1 = -1.0f; y1 = rampTop; x2 = 1.0f; y2 = rampBottom
   val minC = minL / MAX_V
   val maxC = maxL / MAX_V
   color1 = floatArrayOf(minC, minC, minC)  // top-left = black
   color2 = floatArrayOf(maxC, maxC, maxC)  // top-right = white
   color3 = floatArrayOf(minC, minC, minC)  // bottom-left = black
   color4 = floatArrayOf(maxC, maxC, maxC)  // bottom-right = white
  }
  commands.add(rampCmd)

  // Bottom section: stepped bars (11 bars: 0%, 10%, 20% ... 100%)
  val steps = 11
  val barTop = -0.3f
  val barBottom = -1.0f
  val barWidth = 2.0f / steps

  for (i in 0 until steps) {
   val pct = i / (steps - 1).toFloat()
   val level = minL + ((maxL - minL) * pct).toInt()
   val cmd = DrawCommand()
   cmd.setColorsFromRgb(intArrayOf(level, level, level), MAX_V)
   cmd.x1 = -1.0f + i * barWidth
   cmd.y1 = barTop
   cmd.x2 = cmd.x1 + barWidth
   cmd.y2 = barBottom
   commands.add(cmd)
  }

  return commands
 }

 // ───── Overscan ─────
 /**
  * Overscan/geometry pattern (AVS HD 709 style).
  *
  * Shows:
  *   - 1-pixel white border at 0% (screen edge)
  *   - Dashed lines at 2.5% and 5% from each edge
  *   - Center crosshair
  *   - Corner markers
  *   - Aspect ratio guide rectangles
  *
  * If any of these are cut off, the display has overscan enabled.
  */
 fun drawOverscan(): List<DrawCommand> {
  val commands = mutableListOf<DrawCommand>()
  val w = 1f // white
  val g = 0.3f // dark gray for inner lines

  // Black background
  commands.add(DrawCommand.fullFieldInt(0, 0, 0, MAX_V))

  val t = 0.005f // line thickness (thin)

  // ── 0% border (outer edge) ──
  commands.add(DrawCommand.solidRect(-1f, 1f, 1f, 1f - t, w, w, w))  // Top
  commands.add(DrawCommand.solidRect(-1f, -1f + t, 1f, -1f, w, w, w))  // Bottom
  commands.add(DrawCommand.solidRect(-1f, 1f, -1f + t, -1f, w, w, w))  // Left
  commands.add(DrawCommand.solidRect(1f - t, 1f, 1f, -1f, w, w, w))  // Right

  // ── 5% lines ──
  val m5 = 0.05f
  commands.add(DrawCommand.solidRect(-1f + m5, 1f - m5, 1f - m5, 1f - m5 - t, g, g, g)) // Top
  commands.add(DrawCommand.solidRect(-1f + m5, -1f + m5 + t, 1f - m5, -1f + m5, g, g, g)) // Bottom
  commands.add(DrawCommand.solidRect(-1f + m5, 1f - m5, -1f + m5 + t, -1f + m5, g, g, g)) // Left
  commands.add(DrawCommand.solidRect(1f - m5 - t, 1f - m5, 1f - m5, -1f + m5, g, g, g)) // Right

  // ── 2.5% lines ──
  val m25 = 0.025f
  val g2 = 0.5f
  commands.add(DrawCommand.solidRect(-1f + m25, 1f - m25, 1f - m25, 1f - m25 - t, g2, g2, g2)) // Top
  commands.add(DrawCommand.solidRect(-1f + m25, -1f + m25 + t, 1f - m25, -1f + m25, g2, g2, g2)) // Bottom
  commands.add(DrawCommand.solidRect(-1f + m25, 1f - m25, -1f + m25 + t, -1f + m25, g2, g2, g2)) // Left
  commands.add(DrawCommand.solidRect(1f - m25 - t, 1f - m25, 1f - m25, -1f + m25, g2, g2, g2)) // Right

  // ── Center crosshair ──
  val crossLen = 0.15f
  val crossT = 0.003f
  commands.add(DrawCommand.solidRect(-crossLen, crossT, crossLen, -crossT, w, w, w))  // Horizontal
  commands.add(DrawCommand.solidRect(-crossT, crossLen, crossT, -crossLen, w, w, w))  // Vertical

  // ── Corner markers (small L-shaped brackets) ──
  val cm = 0.08f // marker length
  val ct = 0.006f // marker thickness
  // Top-left
  commands.add(DrawCommand.solidRect(-1f + m5, 1f - m5, -1f + m5 + cm, 1f - m5 - ct, w, w, w))
  commands.add(DrawCommand.solidRect(-1f + m5, 1f - m5, -1f + m5 + ct, 1f - m5 - cm, w, w, w))
  // Top-right
  commands.add(DrawCommand.solidRect(1f - m5 - cm, 1f - m5, 1f - m5, 1f - m5 - ct, w, w, w))
  commands.add(DrawCommand.solidRect(1f - m5 - ct, 1f - m5, 1f - m5, 1f - m5 - cm, w, w, w))
  // Bottom-left
  commands.add(DrawCommand.solidRect(-1f + m5, -1f + m5 + ct, -1f + m5 + cm, -1f + m5, w, w, w))
  commands.add(DrawCommand.solidRect(-1f + m5, -1f + m5 + cm, -1f + m5 + ct, -1f + m5, w, w, w))
  // Bottom-right
  commands.add(DrawCommand.solidRect(1f - m5 - cm, -1f + m5 + ct, 1f - m5, -1f + m5, w, w, w))
  commands.add(DrawCommand.solidRect(1f - m5 - ct, -1f + m5 + cm, 1f - m5, -1f + m5, w, w, w))

  // ── Aspect ratio guide: 16:9 centered rectangle ──
  val arW = 0.6f
  val arH = arW * 9f / 16f
  val arT = 0.003f
  commands.add(DrawCommand.solidRect(-arW, arH, arW, arH - arT, g, g, g))   // Top
  commands.add(DrawCommand.solidRect(-arW, -arH + arT, arW, -arH, g, g, g)) // Bottom
  commands.add(DrawCommand.solidRect(-arW, arH, -arW + arT, -arH, g, g, g)) // Left
  commands.add(DrawCommand.solidRect(arW - arT, arH, arW, -arH, g, g, g))  // Right

  return commands
 }

 // ───── Color Bars (Simple legacy) ─────
 /**
  * Simple RGBCMYW color bars (legacy, for backward compat).
  */
 fun drawBars(fullRange: Boolean = true, isHdr: Boolean = false): List<DrawCommand> {
  return drawColorBars(fullRange, isHdr)
 }

 // ───── PLUGE (legacy alias) ─────
 /**
  * PLUGE pattern (legacy alias for drawBlackClipping).
  */
 fun drawPluge(isHdr: Boolean = false): List<DrawCommand> {
  return drawBlackClipping(isHdr = isHdr)
 }

 // ───── Window Pattern ─────
 /**
  * Window pattern — colored rectangle on black background.
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
  return listOf(
   DrawCommand.fullFieldInt(bgR, bgG, bgB, MAX_V),
   DrawCommand.windowPatternInt(sizePct, r, g, b, MAX_V)
  )
 }

 /**
  * Full field solid color.
  */
 fun drawFullField(r: Int, g: Int, b: Int): List<DrawCommand> {
  return listOf(DrawCommand.fullFieldInt(r, g, b, MAX_V))
 }

 /**
  * Parse a draw string command.
  *
  * Formats:
  *   "window <size> <r> <g> <b>"              → window pattern
  *   "draw <x1> <y1> <x2> <y2> <r> <g> <b>"  → arbitrary rectangle
  *   "field <r> <g> <b>"                      → full field
  */
 fun parseDrawString(input: String): List<DrawCommand>? {
  val parts = input.trim().split("\\s+".toRegex())
  if (parts.isEmpty()) return null

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
    cmd.setColorsFromRgb(intArrayOf(r, g, b), MAX_V)
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
