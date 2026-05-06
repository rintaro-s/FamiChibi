package com.nbks.famichibi.vrm

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VrmGlRenderer : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "com.nbks.famichibi"
    }

    private val vertexShaderCode = """
        #version 300 es
        in vec4 aPosition;
        in vec2 aTexCoord;
        uniform mat4 uMVP;
        out vec2 vTexCoord;
        void main() {
            gl_Position = uMVP * aPosition;
            vTexCoord = aTexCoord;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #version 300 es
        precision mediump float;
        in vec2 vTexCoord;
        uniform sampler2D uTexture;
        uniform vec4 uBaseColor;
        uniform float uHasTexture;
        out vec4 fragColor;
        void main() {
            vec4 color = uBaseColor;
            if (uHasTexture > 0.5) {
                color = texture(uTexture, vTexCoord) * uBaseColor;
            }
            fragColor = color;
        }
    """.trimIndent()

    private var program: Int = 0
    private val meshRenderers = mutableListOf<MeshRenderer>()
    private val modelMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    private var surfaceWidth: Int = 1
    private var surfaceHeight: Int = 1

    var rotationY = 0f
    var isWalking = false
    private var walkPhase = 0f
    private var needsMeshUpdate = false
    private var pendingMeshes: List<MeshData>? = null

    fun setMeshes(meshes: List<MeshData>) {
        pendingMeshes = meshes
        needsMeshUpdate = true
    }

    private fun applyPendingMeshes() {
        val meshes = pendingMeshes ?: return
        pendingMeshes = null
        needsMeshUpdate = false

        for (mr in meshRenderers) mr.destroy()
        meshRenderers.clear()

        for (mesh in meshes) {
            try {
                meshRenderers.add(MeshRenderer(mesh))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create MeshRenderer", e)
            }
        }
        computeModelMatrix(meshes)
        Log.d(TAG, "Loaded ${meshRenderers.size} mesh renderers")
    }

    private fun computeModelMatrix(meshes: List<MeshData>) {
        if (meshes.isEmpty()) {
            Matrix.setIdentityM(modelMatrix, 0)
            return
        }
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE

        for (mesh in meshes) {
            val positions = mesh.positions
            for (i in positions.indices step 3) {
                if (i + 2 >= positions.size) break
                val x = positions[i]
                val y = positions[i + 1]
                val z = positions[i + 2]
                minX = kotlin.math.min(minX, x)
                minY = kotlin.math.min(minY, y)
                minZ = kotlin.math.min(minZ, z)
                maxX = kotlin.math.max(maxX, x)
                maxY = kotlin.math.max(maxY, y)
                maxZ = kotlin.math.max(maxZ, z)
            }
        }

        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f
        val centerZ = (minZ + maxZ) / 2f
        val sizeX = maxX - minX
        val sizeY = maxY - minY
        val sizeZ = maxZ - minZ
        val maxSize = kotlin.math.max(sizeX, kotlin.math.max(sizeY, sizeZ))
        val scale = if (maxSize > 0f) 1.5f / maxSize else 1f

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, -centerX, -centerY, -centerZ)
        Matrix.scaleM(modelMatrix, 0, scale, scale, scale)

        Log.d(TAG, "Model bbox: [$minX,$minY,$minZ] - [$maxX,$maxY,$maxZ], scale=$scale")
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        val vs = loadShader(GLES30.GL_VERTEX_SHADER, vertexShaderCode)
        val fs = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode)
        if (vs == 0 || fs == 0) {
            Log.e(TAG, "Shader compilation failed")
            return
        }
        program = createProgram(vs, fs)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (program == 0) {
            Log.e(TAG, "Program linking failed")
            return
        }

        Matrix.setLookAtM(viewMatrix, 0, 0f, 0.5f, 2.5f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        surfaceWidth = width
        surfaceHeight = height
        val ratio = if (height > 0) width.toFloat() / height.toFloat() else 1f
        Matrix.perspectiveM(projMatrix, 0, 45f, ratio, 0.1f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (needsMeshUpdate) {
            applyPendingMeshes()
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        if (meshRenderers.isEmpty()) return

        Matrix.setIdentityM(tempMatrix, 0)

        // Walking bounce
        if (isWalking) {
            walkPhase += 0.2f
            val bounce = kotlin.math.sin(walkPhase) * 0.06f
            Matrix.translateM(tempMatrix, 0, 0f, bounce, 0f)
        }

        // Face movement direction (Y-axis rotation)
        Matrix.rotateM(tempMatrix, 0, rotationY, 0f, 1f, 0f)
        Matrix.multiplyMM(tempMatrix, 0, tempMatrix, 0, modelMatrix, 0)

        Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, tempMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvpMatrix, 0)

        for (mr in meshRenderers) {
            mr.draw(program, mvpMatrix)
        }
    }

    private fun loadShader(type: Int, code: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, code)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            Log.e(TAG, "Shader compile error: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun createProgram(vs: Int, fs: Int): Int {
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e(TAG, "Program link error: ${GLES30.glGetProgramInfoLog(prog)}")
            GLES30.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    fun destroy() {
        for (mr in meshRenderers) mr.destroy()
        meshRenderers.clear()
        if (program != 0) {
            GLES30.glDeleteProgram(program)
            program = 0
        }
    }

    private class MeshRenderer(private val data: MeshData) {
        private var posVbo: Int = 0
        private var uvVbo: Int = 0
        private var normVbo: Int = 0
        private var ibo: Int = 0
        private var texId: Int = 0
        private var indexCount: Int = 0
        private var indexType: Int = 0

        init {
            val bufs = IntArray(4)
            GLES30.glGenBuffers(4, bufs, 0)
            posVbo = bufs[0]
            uvVbo = bufs[1]
            normVbo = bufs[2]
            ibo = bufs[3]

            // Positions
            val posByteBuf = ByteBuffer.allocateDirect(data.positions.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            posByteBuf.put(data.positions).position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, posVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.positions.size * 4, posByteBuf, GLES30.GL_STATIC_DRAW)

            // UVs
            if (data.uvs != null) {
                val uvByteBuf = ByteBuffer.allocateDirect(data.uvs.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                uvByteBuf.put(data.uvs).position(0)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, uvVbo)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.uvs.size * 4, uvByteBuf, GLES30.GL_STATIC_DRAW)
            }

            // Normals
            if (data.normals != null) {
                val normByteBuf = ByteBuffer.allocateDirect(data.normals.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                normByteBuf.put(data.normals).position(0)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, normVbo)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.normals.size * 4, normByteBuf, GLES30.GL_STATIC_DRAW)
            }

            // Indices
            val indexByteSize = when (data.indexType) {
                GLES30.GL_UNSIGNED_BYTE -> 1
                GLES30.GL_UNSIGNED_SHORT -> 2
                else -> 4
            }
            val idxBuf = when (data.indexType) {
                GLES30.GL_UNSIGNED_BYTE -> {
                    val buf = ByteBuffer.allocateDirect(data.indices.size)
                        .order(ByteOrder.nativeOrder())
                    for (i in data.indices) buf.put(i.toByte())
                    buf.position(0)
                    buf
                }
                GLES30.GL_UNSIGNED_SHORT -> {
                    val buf = ByteBuffer.allocateDirect(data.indices.size * 2)
                        .order(ByteOrder.nativeOrder())
                        .asShortBuffer()
                    for (i in data.indices) buf.put(i.toShort())
                    buf.position(0)
                    buf
                }
                else -> {
                    val buf = ByteBuffer.allocateDirect(data.indices.size * 4)
                        .order(ByteOrder.nativeOrder())
                        .asIntBuffer()
                    buf.put(data.indices).position(0)
                    buf
                }
            }
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, data.indices.size * indexByteSize, idxBuf, GLES30.GL_STATIC_DRAW)

            indexCount = data.indices.size
            indexType = data.indexType

            // Texture
            if (data.textureBitmap != null) {
                val tex = IntArray(1)
                GLES30.glGenTextures(1, tex, 0)
                texId = tex[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, data.textureBitmap, 0)
            }
        }

        fun draw(program: Int, mvpMatrix: FloatArray) {
            GLES30.glUseProgram(program)

            val mvpLoc = GLES30.glGetUniformLocation(program, "uMVP")
            GLES30.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)

            val baseColorLoc = GLES30.glGetUniformLocation(program, "uBaseColor")
            GLES30.glUniform4fv(baseColorLoc, 1, data.baseColorFactor, 0)

            val hasTexLoc = GLES30.glGetUniformLocation(program, "uHasTexture")
            GLES30.glUniform1f(hasTexLoc, if (texId != 0) 1f else 0f)

            val posLoc = GLES30.glGetAttribLocation(program, "aPosition")
            GLES30.glEnableVertexAttribArray(posLoc)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, posVbo)
            GLES30.glVertexAttribPointer(posLoc, 3, GLES30.GL_FLOAT, false, 0, 0)

            if (data.uvs != null && texId != 0) {
                val uvLoc = GLES30.glGetAttribLocation(program, "aTexCoord")
                if (uvLoc >= 0) {
                    GLES30.glEnableVertexAttribArray(uvLoc)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, uvVbo)
                    GLES30.glVertexAttribPointer(uvLoc, 2, GLES30.GL_FLOAT, false, 0, 0)
                }
            }

            if (texId != 0) {
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
                val texLoc = GLES30.glGetUniformLocation(program, "uTexture")
                GLES30.glUniform1i(texLoc, 0)
            }

            if (data.doubleSided) {
                GLES30.glDisable(GLES30.GL_CULL_FACE)
            } else {
                GLES30.glEnable(GLES30.GL_CULL_FACE)
                GLES30.glCullFace(GLES30.GL_BACK)
            }

            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, indexType, 0)

            GLES30.glDisableVertexAttribArray(posLoc)
            if (data.uvs != null && texId != 0) {
                val uvLoc = GLES30.glGetAttribLocation(program, "aTexCoord")
                if (uvLoc >= 0) GLES30.glDisableVertexAttribArray(uvLoc)
            }
        }

        fun destroy() {
            val bufs = intArrayOf(posVbo, uvVbo, normVbo, ibo)
            GLES30.glDeleteBuffers(4, bufs, 0)
            if (texId != 0) {
                val tex = intArrayOf(texId)
                GLES30.glDeleteTextures(1, tex, 0)
            }
        }
    }
}
