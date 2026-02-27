package com.pgeneratorplus.android.renderer

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.pgeneratorplus.android.model.AppState
import com.pgeneratorplus.android.model.DrawCommand
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3.0 pattern renderer.
 *
 * Bit-perfect rendering:
 *   • RGBA8 framebuffer → 8-bit per channel
 *   • No sRGB conversion (linear writes)
 *   • No depth/stencil (pattern generator only)
 *   • No blending (opaque patterns)
 *   • Quantization in fragment shader for 10-bit→8-bit dithering support
 *
 * Vertex format: position (2) + color (3) + quant (1) = 6 floats per vertex
 */
class PatternRenderer : GLSurfaceView.Renderer {

 companion object {
  private const val TAG = "PatternRenderer"
  private const val FLOATS_PER_VERTEX = 6
  private const val BYTES_PER_FLOAT = 4
  private const val VERTICES_PER_QUAD = 6
  private const val MAX_QUADS = 64

  private const val VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec3 aColor;
layout(location = 2) in float aQuant;

out vec3 vColor;
out float vQuant;

void main() {
 gl_Position = vec4(aPosition, 0.0, 1.0);
 vColor = aColor;
 vQuant = aQuant;
}"""

  private const val FRAGMENT_SHADER = """#version 300 es
precision highp float;

in vec3 vColor;
in float vQuant;

out vec4 fragColor;

void main() {
 vec3 c = vColor;
 if (vQuant > 0.0) {
  c = floor(c / vQuant) * vQuant;
 }
 fragColor = vec4(c, 1.0);
}"""
 }

 private var programId = 0
 private var vboId = 0
 private var currentQuadCount = 0

 private val vertexBuffer: FloatBuffer = ByteBuffer
  .allocateDirect(MAX_QUADS * VERTICES_PER_QUAD * FLOATS_PER_VERTEX * BYTES_PER_FLOAT)
  .order(ByteOrder.nativeOrder())
  .asFloatBuffer()

 override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
  GLES30.glClearColor(0f, 0f, 0f, 1f)
  GLES30.glDisable(GLES30.GL_DEPTH_TEST)
  GLES30.glDisable(GLES30.GL_BLEND)
  GLES30.glDisable(GLES30.GL_STENCIL_TEST)

  programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
  GLES30.glUseProgram(programId)

  val vboBuf = IntArray(1)
  GLES30.glGenBuffers(1, vboBuf, 0)
  vboId = vboBuf[0]

  Log.i(TAG, "Renderer initialized (GLES 3.0)")
 }

 override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
  GLES30.glViewport(0, 0, width, height)
  Log.i(TAG, "Surface changed: ${width}x${height}")
 }

 override fun onDrawFrame(gl: GL10?) {
  GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

  val commands = AppState.getCommands()
  if (commands.isEmpty()) return

  vertexBuffer.clear()
  var quadCount = 0

  for (cmd in commands) {
   if (quadCount >= MAX_QUADS) break
   val vertexData = cmd.toVertexData()
   vertexBuffer.put(vertexData)
   quadCount++
  }

  if (quadCount == 0) return

  vertexBuffer.flip()
  currentQuadCount = quadCount

  GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vboId)
  GLES30.glBufferData(
   GLES30.GL_ARRAY_BUFFER,
   currentQuadCount * VERTICES_PER_QUAD * FLOATS_PER_VERTEX * BYTES_PER_FLOAT,
   vertexBuffer,
   GLES30.GL_DYNAMIC_DRAW
  )

  val stride = FLOATS_PER_VERTEX * BYTES_PER_FLOAT

  // position (location 0): 2 floats at offset 0
  GLES30.glEnableVertexAttribArray(0)
  GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, stride, 0)

  // color (location 1): 3 floats at offset 2
  GLES30.glEnableVertexAttribArray(1)
  GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 2 * BYTES_PER_FLOAT)

  // quant (location 2): 1 float at offset 5
  GLES30.glEnableVertexAttribArray(2)
  GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, stride, 5 * BYTES_PER_FLOAT)

  GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, currentQuadCount * VERTICES_PER_QUAD)

  GLES30.glDisableVertexAttribArray(0)
  GLES30.glDisableVertexAttribArray(1)
  GLES30.glDisableVertexAttribArray(2)
  GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
 }

 private fun createProgram(vertexSource: String, fragmentSource: String): Int {
  val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
  val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)

  val program = GLES30.glCreateProgram()
  GLES30.glAttachShader(program, vertexShader)
  GLES30.glAttachShader(program, fragmentShader)
  GLES30.glLinkProgram(program)

  val linkStatus = IntArray(1)
  GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linkStatus, 0)
  if (linkStatus[0] != GLES30.GL_TRUE) {
   val log = GLES30.glGetProgramInfoLog(program)
   GLES30.glDeleteProgram(program)
   throw RuntimeException("Program link failed: $log")
  }

  GLES30.glDeleteShader(vertexShader)
  GLES30.glDeleteShader(fragmentShader)

  return program
 }

 private fun compileShader(type: Int, source: String): Int {
  val shader = GLES30.glCreateShader(type)
  GLES30.glShaderSource(shader, source)
  GLES30.glCompileShader(shader)

  val compileStatus = IntArray(1)
  GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compileStatus, 0)
  if (compileStatus[0] != GLES30.GL_TRUE) {
   val log = GLES30.glGetShaderInfoLog(shader)
   GLES30.glDeleteShader(shader)
   val typeName = if (type == GLES30.GL_VERTEX_SHADER) "vertex" else "fragment"
   throw RuntimeException("$typeName shader compile failed: $log")
  }

  return shader
 }
}
