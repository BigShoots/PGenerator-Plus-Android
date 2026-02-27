package com.pgeneratorplus.android.model

/**
 * Represents a single draw command for the OpenGL ES pattern renderer.
 *
 * Coordinates use OpenGL NDC:
 *   x1,y1 = top-left corner (-1,1 for screen top-left)
 *   x2,y2 = bottom-right corner (1,-1 for screen bottom-right)
 *
 * Colors are normalized floats [0.0, 1.0].
 * Each corner can have its own color for gradient support.
 * quant controls quantization stepping (0 = no quantization).
 */
data class DrawCommand(
 var x1: Float = -1f,
 var y1: Float = 1f,
 var x2: Float = 1f,
 var y2: Float = -1f,
 var color1: FloatArray = floatArrayOf(0f, 0f, 0f), // top-left
 var color2: FloatArray = floatArrayOf(0f, 0f, 0f), // top-right
 var color3: FloatArray = floatArrayOf(0f, 0f, 0f), // bottom-left
 var color4: FloatArray = floatArrayOf(0f, 0f, 0f), // bottom-right
 var quant: Float = 0f
) {
 companion object {
  fun solidRect(x1: Float, y1: Float, x2: Float, y2: Float, r: Float, g: Float, b: Float): DrawCommand {
   val color = floatArrayOf(r, g, b)
   return DrawCommand(x1, y1, x2, y2, color.copyOf(), color.copyOf(), color.copyOf(), color.copyOf(), 0f)
  }

  fun windowPattern(windowPercent: Float, r: Float, g: Float, b: Float): DrawCommand {
   val num = Math.sqrt((windowPercent / 100.0).toDouble()).toFloat()
   val color = floatArrayOf(r, g, b)
   return DrawCommand(
    x1 = -num, y1 = num, x2 = num, y2 = -num,
    color1 = color.copyOf(), color2 = color.copyOf(),
    color3 = color.copyOf(), color4 = color.copyOf(),
    quant = 0f
   )
  }

  fun windowPatternInt(windowPercent: Float, r: Int, g: Int, b: Int, maxV: Float): DrawCommand {
   return windowPattern(windowPercent, r / maxV, g / maxV, b / maxV)
  }

  fun fullFieldInt(r: Int, g: Int, b: Int, maxV: Float): DrawCommand {
   return windowPatternInt(100f, r, g, b, maxV)
  }
 }

 fun setColorsFromRgb(color: IntArray, maxV: Float) {
  for (i in 0..2) {
   val v = color[i] / maxV
   color1[i] = v
   color2[i] = v
   color3[i] = v
   color4[i] = v
  }
  quant = 0f
 }

 fun setColorsFromRgb(color: FloatArray) {
  for (i in 0..2) {
   color1[i] = color[i]
   color2[i] = color[i]
   color3[i] = color[i]
   color4[i] = color[i]
  }
  quant = 0f
 }

 fun setCoordsFromWindow(windowPercent: Float) {
  val num = Math.sqrt((windowPercent / 100.0).toDouble()).toFloat()
  x1 = -num
  y1 = num
  x2 = num
  y2 = -num
 }

 fun toVertexData(): FloatArray {
  return floatArrayOf(
   x1, y1, color1[0], color1[1], color1[2], quant,
   x2, y1, color2[0], color2[1], color2[2], quant,
   x1, y2, color3[0], color3[1], color3[2], quant,
   x2, y2, color4[0], color4[1], color4[2], quant
  )
 }

 override fun equals(other: Any?): Boolean {
  if (this === other) return true
  if (other !is DrawCommand) return false
  return x1 == other.x1 && y1 == other.y1 && x2 == other.x2 && y2 == other.y2 &&
   color1.contentEquals(other.color1) && color2.contentEquals(other.color2) &&
   color3.contentEquals(other.color3) && color4.contentEquals(other.color4) &&
   quant == other.quant
 }

 override fun hashCode(): Int {
  var result = x1.hashCode()
  result = 31 * result + y1.hashCode()
  result = 31 * result + x2.hashCode()
  result = 31 * result + y2.hashCode()
  result = 31 * result + color1.contentHashCode()
  result = 31 * result + color2.contentHashCode()
  result = 31 * result + color3.contentHashCode()
  result = 31 * result + color4.contentHashCode()
  result = 31 * result + quant.hashCode()
  return result
 }
}
