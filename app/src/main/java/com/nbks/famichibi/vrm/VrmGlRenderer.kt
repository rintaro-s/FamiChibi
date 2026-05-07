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
        private const val MAX_BONES = 256

        fun quaternionToMatrix(q: FloatArray, m: FloatArray) {
            val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
            val xx = x * x; val yy = y * y; val zz = z * z
            val xy = x * y; val xz = x * z; val yz = y * z
            val wx = w * x; val wy = w * y; val wz = w * z
            m[0] = 1 - 2 * (yy + zz); m[4] = 2 * (xy - wz); m[8] = 2 * (xz + wy); m[12] = 0f
            m[1] = 2 * (xy + wz); m[5] = 1 - 2 * (xx + zz); m[9] = 2 * (yz - wx); m[13] = 0f
            m[2] = 2 * (xz - wy); m[6] = 2 * (yz + wx); m[10] = 1 - 2 * (xx + yy); m[14] = 0f
            m[3] = 0f; m[7] = 0f; m[11] = 0f; m[15] = 1f
        }

        fun multiplyQuaternion(a: FloatArray, b: FloatArray): FloatArray {
            return floatArrayOf(
                a[3]*b[0] + a[0]*b[3] + a[1]*b[2] - a[2]*b[1],
                a[3]*b[1] - a[0]*b[2] + a[1]*b[3] + a[2]*b[0],
                a[3]*b[2] + a[0]*b[1] - a[1]*b[0] + a[2]*b[3],
                a[3]*b[3] - a[0]*b[0] - a[1]*b[1] - a[2]*b[2]
            )
        }

        fun eulerToQuaternion(x: Float, y: Float, z: Float): FloatArray {
            val cx = kotlin.math.cos(x/2); val sx = kotlin.math.sin(x/2)
            val cy = kotlin.math.cos(y/2); val sy = kotlin.math.sin(y/2)
            val cz = kotlin.math.cos(z/2); val sz = kotlin.math.sin(z/2)
            return floatArrayOf(
                sx*cy*cz - cx*sy*sz,
                cx*sy*cz + sx*cy*sz,
                cx*cy*sz - sx*sy*cz,
                cx*cy*cz + sx*sy*sz
            )
        }
    }

    private val vertexShaderCode = """
        #version 300 es
        in vec4 aPosition;
        in vec2 aTexCoord;
        in vec4 aJoint;
        in vec4 aWeight;
        uniform mat4 uMVP;
        uniform mat4 uBones[$MAX_BONES];
        out vec2 vTexCoord;
        void main() {
            mat4 skinMatrix =
                aWeight.x * uBones[int(aJoint.x)] +
                aWeight.y * uBones[int(aJoint.y)] +
                aWeight.z * uBones[int(aJoint.z)] +
                aWeight.w * uBones[int(aJoint.w)];
            gl_Position = uMVP * skinMatrix * aPosition;
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
    private var animPhase = 0f
    private var needsMeshUpdate = false
    private var pendingRoot: GltfRoot? = null
    private var pendingMeshes: List<MeshData>? = null

    // Bone animation state
    private var animNodes: MutableList<AnimNode>? = null
    private var skinData: SkinData? = null
    private val boneMatrices = FloatArray(MAX_BONES * 16)
    private var jointNameToIndex = mutableMapOf<String, Int>()
    private val boneAxes = mutableMapOf<String, FloatArray>()
    private val boneSwingDownAxes = mutableMapOf<String, FloatArray>()   // swing limb toward ground (arms down)
    private val boneSwingForwardAxes = mutableMapOf<String, FloatArray>() // swing limb forward/back (walk)

    enum class AnimationState { IDLE, WALK, SKIP, FLAIL, RESIST }
    var animationState = AnimationState.WALK

    private val fingerBones = setOf(
        "J_Bip_L_ThumbMetacarpal", "J_Bip_L_ThumbProximal", "J_Bip_L_ThumbDistal",
        "J_Bip_L_IndexProximal", "J_Bip_L_IndexIntermediate", "J_Bip_L_IndexDistal",
        "J_Bip_L_MiddleProximal", "J_Bip_L_MiddleIntermediate", "J_Bip_L_MiddleDistal",
        "J_Bip_L_RingProximal", "J_Bip_L_RingIntermediate", "J_Bip_L_RingDistal",
        "J_Bip_L_LittleProximal", "J_Bip_L_LittleIntermediate", "J_Bip_L_LittleDistal",
        "J_Bip_R_ThumbMetacarpal", "J_Bip_R_ThumbProximal", "J_Bip_R_ThumbDistal",
        "J_Bip_R_IndexProximal", "J_Bip_R_IndexIntermediate", "J_Bip_R_IndexDistal",
        "J_Bip_R_MiddleProximal", "J_Bip_R_MiddleIntermediate", "J_Bip_R_MiddleDistal",
        "J_Bip_R_RingProximal", "J_Bip_R_RingIntermediate", "J_Bip_R_RingDistal",
        "J_Bip_R_LittleProximal", "J_Bip_R_LittleIntermediate", "J_Bip_R_LittleDistal",
    )

    data class AnimNode(
        val name: String?,
        val baseTranslation: FloatArray = floatArrayOf(0f, 0f, 0f),
        val baseRotation: FloatArray = floatArrayOf(0f, 0f, 0f, 1f),
        val baseScale: FloatArray = floatArrayOf(1f, 1f, 1f),
        val translation: FloatArray = floatArrayOf(0f, 0f, 0f),
        val rotation: FloatArray = floatArrayOf(0f, 0f, 0f, 1f),
        val scale: FloatArray = floatArrayOf(1f, 1f, 1f),
        val children: List<Int> = emptyList(),
    )

    fun loadModel(root: GltfRoot, meshes: List<MeshData>) {
        pendingRoot = root
        pendingMeshes = meshes
        needsMeshUpdate = true
    }

    private fun applyPendingMeshes() {
        val root = pendingRoot ?: return
        val meshes = pendingMeshes ?: return
        pendingRoot = null
        pendingMeshes = null
        needsMeshUpdate = false

        for (mr in meshRenderers) mr.destroy()
        meshRenderers.clear()

        animNodes = root.nodes?.map { node ->
            val t = node.translation?.toFloatArray() ?: floatArrayOf(0f, 0f, 0f)
            val r = node.rotation?.toFloatArray() ?: floatArrayOf(0f, 0f, 0f, 1f)
            val s = node.scale?.toFloatArray() ?: floatArrayOf(1f, 1f, 1f)
            AnimNode(
                name = node.name,
                baseTranslation = t.copyOf(),
                baseRotation = r.copyOf(),
                baseScale = s.copyOf(),
                translation = t.copyOf(),
                rotation = r.copyOf(),
                scale = s.copyOf(),
                children = node.children ?: emptyList(),
            )
        }?.toMutableList()

        skinData = meshes.firstOrNull { it.skin != null }?.skin
        jointNameToIndex.clear()
        skinData?.let { skin ->
            for ((idx, nodeIdx) in skin.joints.withIndex()) {
                val node = root.nodes?.getOrNull(nodeIdx)
                if (node?.name != null) {
                    jointNameToIndex[node.name] = idx
                }
            }
            Log.d(TAG, "Bone names (${skin.joints.size}): ${jointNameToIndex.keys.take(20).joinToString()}")
        }

        for (mesh in meshes) {
            try {
                meshRenderers.add(MeshRenderer(mesh))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create MeshRenderer", e)
            }
        }
        computeModelMatrix(meshes)
        diagnoseBoneAxes()
        Log.d(TAG, "Loaded ${meshRenderers.size} mesh renderers, bones=${skinData?.joints?.size ?: 0}")
    }

    private fun computeModelMatrix(meshes: List<MeshData>) {
        if (meshes.isEmpty()) {
            Matrix.setIdentityM(modelMatrix, 0)
            return
        }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (mesh in meshes) {
            val positions = mesh.positions
            for (i in positions.indices step 3) {
                if (i + 2 >= positions.size) break
                minX = kotlin.math.min(minX, positions[i])
                minY = kotlin.math.min(minY, positions[i + 1])
                minZ = kotlin.math.min(minZ, positions[i + 2])
                maxX = kotlin.math.max(maxX, positions[i])
                maxY = kotlin.math.max(maxY, positions[i + 1])
                maxZ = kotlin.math.max(maxZ, positions[i + 2])
            }
        }
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f
        val centerZ = (minZ + maxZ) / 2f
        val maxSize = kotlin.math.max(maxX - minX, kotlin.math.max(maxY - minY, maxZ - minZ))
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
        if (vs == 0 || fs == 0) { Log.e(TAG, "Shader compilation failed"); return }
        program = createProgram(vs, fs)
        GLES30.glDeleteShader(vs); GLES30.glDeleteShader(fs)
        if (program == 0) { Log.e(TAG, "Program linking failed"); return }

        Matrix.setLookAtM(viewMatrix, 0, 0f, 0.5f, 2.5f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        surfaceWidth = width; surfaceHeight = height
        val ratio = if (height > 0) width.toFloat() / height.toFloat() else 1f
        Matrix.perspectiveM(projMatrix, 0, 45f, ratio, 0.1f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (needsMeshUpdate) applyPendingMeshes()
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (meshRenderers.isEmpty()) return

        if (animationState == AnimationState.WALK) {
            animPhase += 0.15f
        } else if (animationState == AnimationState.SKIP) {
            animPhase += 0.3f
        } else if (animationState == AnimationState.FLAIL) {
            animPhase += 0.8f
        }

        applyAnimation()
        computeBoneMatrices()

        Matrix.setIdentityM(tempMatrix, 0)
        if (animationState == AnimationState.WALK || animationState == AnimationState.SKIP) {
            val bounce = kotlin.math.sin(animPhase) * 0.06f
            Matrix.translateM(tempMatrix, 0, 0f, bounce, 0f)
        }
        Matrix.rotateM(tempMatrix, 0, rotationY, 0f, 1f, 0f)
        Matrix.multiplyMM(tempMatrix, 0, tempMatrix, 0, modelMatrix, 0)

        Matrix.multiplyMM(mvpMatrix, 0, viewMatrix, 0, tempMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, mvpMatrix, 0)

        for (mr in meshRenderers) {
            mr.draw(program, mvpMatrix, boneMatrices, skinData != null)
        }
    }

    private fun computeBoneMatrices() {
        val nodes = animNodes ?: return
        val skin = skinData ?: return
        val count = nodes.size
        val globalMatrices = Array(count) { FloatArray(16) }
        val visited = BooleanArray(count)

        for (i in 0 until count) {
            computeNodeMatrix(i, visited, globalMatrices)
        }

        for ((idx, nodeIdx) in skin.joints.withIndex()) {
            if (idx >= MAX_BONES) break
            val global = globalMatrices.getOrNull(nodeIdx) ?: continue
            val ibmOffset = idx * 16
            Matrix.multiplyMM(boneMatrices, idx * 16, global, 0, skin.inverseBindMatrices, ibmOffset)
        }
    }

    private fun computeNodeMatrix(nodeIdx: Int, visited: BooleanArray, globalMatrices: Array<FloatArray>) {
        if (visited[nodeIdx]) return
        visited[nodeIdx] = true
        val node = animNodes?.getOrNull(nodeIdx) ?: return
        val local = nodeToMatrix(node)
        var parentIdx = -1
        for ((i, n) in (animNodes ?: return).withIndex()) {
            if (n.children.contains(nodeIdx)) { parentIdx = i; break }
        }
        if (parentIdx >= 0) {
            computeNodeMatrix(parentIdx, visited, globalMatrices)
            Matrix.multiplyMM(globalMatrices[nodeIdx], 0, globalMatrices[parentIdx], 0, local, 0)
        } else {
            System.arraycopy(local, 0, globalMatrices[nodeIdx], 0, 16)
        }
    }

    private fun nodeToMatrix(node: AnimNode): FloatArray {
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, node.translation[0], node.translation[1], node.translation[2])
        val rm = FloatArray(16)
        quaternionToMatrix(node.rotation, rm)
        val temp = FloatArray(16)
        System.arraycopy(m, 0, temp, 0, 16)
        Matrix.multiplyMM(m, 0, temp, 0, rm, 0)
        Matrix.scaleM(m, 0, node.scale[0], node.scale[1], node.scale[2])
        return m
    }

    private fun crossProduct(a: FloatArray, b: FloatArray): FloatArray {
        return floatArrayOf(
            a[1]*b[2] - a[2]*b[1],
            a[2]*b[0] - a[0]*b[2],
            a[0]*b[1] - a[1]*b[0]
        )
    }
    private fun normalize(v: FloatArray): FloatArray {
        val len = kotlin.math.sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2])
        return if (len > 0.001f) floatArrayOf(v[0]/len, v[1]/len, v[2]/len) else v.copyOf()
    }

    private fun diagnoseBoneAxes() {
        val nodes = animNodes ?: return
        boneAxes.clear()
        boneSwingDownAxes.clear()
        boneSwingForwardAxes.clear()
        val down = floatArrayOf(0f, -1f, 0f)
        val forward = floatArrayOf(0f, 0f, 1f)
        for ((idx, node) in nodes.withIndex()) {
            if (node.children.isEmpty()) continue
            val childIdx = node.children.first()
            val child = nodes.getOrNull(childIdx) ?: continue
            val dx = child.baseTranslation[0]
            val dy = child.baseTranslation[1]
            val dz = child.baseTranslation[2]
            val len = kotlin.math.sqrt(dx*dx + dy*dy + dz*dz)
            if (len < 0.001f) continue
            val longAxis = floatArrayOf(dx/len, dy/len, dz/len)
            boneAxes[node.name ?: ""] = longAxis

            val swingDown = normalize(crossProduct(longAxis, down))
            val swingForward = normalize(crossProduct(longAxis, forward))
            if (kotlin.math.abs(swingDown[0]) + kotlin.math.abs(swingDown[1]) + kotlin.math.abs(swingDown[2]) > 0.01f) {
                boneSwingDownAxes[node.name ?: ""] = swingDown
            }
            if (kotlin.math.abs(swingForward[0]) + kotlin.math.abs(swingForward[1]) + kotlin.math.abs(swingForward[2]) > 0.01f) {
                boneSwingForwardAxes[node.name ?: ""] = swingForward
            }
            Log.d(TAG, "Bone ${node.name}: long=(${longAxis[0]},${longAxis[1]},${longAxis[2]}), downSwing=(${swingDown[0]},${swingDown[1]},${swingDown[2]}), fwdSwing=(${swingForward[0]},${swingForward[1]},${swingForward[2]})")
        }
    }

    private fun axisAngleToQuaternion(ax: Float, ay: Float, az: Float, angle: Float): FloatArray {
        val half = angle / 2f
        val s = kotlin.math.sin(half)
        val c = kotlin.math.cos(half)
        return floatArrayOf(ax * s, ay * s, az * s, c)
    }

    private fun applyAnimation() {
        when (animationState) {
            AnimationState.IDLE -> applyIdlePose()
            AnimationState.WALK -> applyWalkPose()
            AnimationState.SKIP -> applySkipPose()
            AnimationState.FLAIL -> applyFlailPose()
            AnimationState.RESIST -> applyResistPose()
        }
    }

    private fun applyIdlePose() {
        val nodes = animNodes ?: return
        for (node in nodes) {
            var q = node.baseRotation.copyOf()
            when (node.name) {
                "J_Bip_L_UpperArm", "J_Bip_R_UpperArm" -> {
                    // Swing arm down from T-pose toward body
                    val swing = boneSwingDownAxes[node.name] ?: floatArrayOf(0f, 0f, 1f)
                    q = multiplyQuaternion(q, axisAngleToQuaternion(swing[0], swing[1], swing[2], 0.5f))
                }
                "J_Bip_L_LowerArm", "J_Bip_R_LowerArm" -> {
                    // Slight elbow bend
                    val swing = boneSwingForwardAxes[node.name] ?: floatArrayOf(1f, 0f, 0f)
                    q = multiplyQuaternion(q, axisAngleToQuaternion(swing[0], swing[1], swing[2], 0.35f))
                }
                in fingerBones -> {
                    val swing = boneSwingForwardAxes[node.name] ?: floatArrayOf(1f, 0f, 0f)
                    q = multiplyQuaternion(q, axisAngleToQuaternion(swing[0], swing[1], swing[2], 0.15f))
                }
            }
            System.arraycopy(q, 0, node.rotation, 0, 4)
        }
    }

    private fun applyWalkPose() {
        val nodes = animNodes ?: return
        val legSwing = kotlin.math.sin(animPhase) * 0.35f
        val kneeBendL = if (legSwing > 0) 0.7f else 0.05f
        val kneeBendR = if (-legSwing > 0) 0.7f else 0.05f
        val armSwing = kotlin.math.sin(animPhase + kotlin.math.PI.toFloat()) * 0.25f

        for (node in nodes) {
            var q = node.baseRotation.copyOf()
            when (node.name) {
                "J_Bip_L_UpperLeg" -> {
                    val swing = boneSwingForwardAxes[node.name] ?: floatArrayOf(1f, 0f, 0f)
                    q = multiplyQuaternion(q, axisAngleToQuaternion(swing[0], swing[1], swing[2], -legSwing))
                }
                "J_Bip_R_UpperLeg" -> {
                    val swing = boneSwingForwardAxes[node.name] ?: floatArrayOf(1f, 0f, 0f)
                    q = multiplyQuaternion(q, axisAngleToQuaternion(swing[0], swing[1], swing[2], legSwing))
                }
                "J_Bip_L_LowerLeg" -> {
                    val swing = boneSwingForwardAxes[node.name] ?: floatArrayOf(1f, 0f, 0f)
                    q = multiplyQuaternion(q, axisAngleToQuaternion(swing[0], swing[1], swing[2], -kneeBendL))
                }
                "J_Bip_R_LowerLeg" -> {
                    val swing = boneSwingForwardAxes[node.name] ?: floatArrayOf(1f, 0f, 0f)
                    q = multiplyQuaternion(q, axisAngleToQuaternion(swing[0], swing[1], swing[2], -kneeBendR))
                }
                "J_Bip_L_UpperArm" -> {
                    val downSwing = boneSwingDownAxes[node.name] ?: floatArrayOf(0f, 0f, 1f)
                    val fwdSwing = boneSwingForwardAxes[node.name] ?: floatArrayOf(0f, 1f, 0f)
                    val down = multiplyQuaternion(q, axisAngleToQuaternion(downSwing[0], downSwing[1], downSwing[2], 0.5f))
                    q = multiplyQuaternion(down, axisAngleToQuaternion(fwdSwing[0], fwdSwing[1], fwdSwing[2], armSwing))
                }
                "J_Bip_R_UpperArm" -> {
                    val downSwing = boneSwingDownAxes[node.name] ?: floatArrayOf(0f, 0f, 1f)
                    val fwdSwing = boneSwingForwardAxes[node.name] ?: floatArrayOf(0f, 1f, 0f)
                    val down = multiplyQuaternion(q, axisAngleToQuaternion(downSwing[0], downSwing[1], downSwing[2], 0.5f))
                    q = multiplyQuaternion(down, axisAngleToQuaternion(fwdSwing[0], fwdSwing[1], fwdSwing[2], -armSwing))
                }
                "J_Bip_L_LowerArm", "J_Bip_R_LowerArm" -> {
                    val swing = boneSwingForwardAxes[node.name] ?: floatArrayOf(1f, 0f, 0f)
                    q = multiplyQuaternion(q, axisAngleToQuaternion(swing[0], swing[1], swing[2], 0.35f))
                }
                in fingerBones -> {
                    val swing = boneSwingForwardAxes[node.name] ?: floatArrayOf(1f, 0f, 0f)
                    q = multiplyQuaternion(q, axisAngleToQuaternion(swing[0], swing[1], swing[2], 0.15f))
                }
            }
            System.arraycopy(q, 0, node.rotation, 0, 4)
        }
    }

    private fun applySkipPose() {
        val nodes = animNodes ?: return
        val hop = kotlin.math.sin(animPhase) * 0.5f
        val legBend = kotlin.math.abs(kotlin.math.sin(animPhase)) * 0.6f
        val armSwing = kotlin.math.sin(animPhase + kotlin.math.PI.toFloat()) * 0.4f
        for (node in nodes) {
            var q = node.baseRotation.copyOf()
            when (node.name) {
                "J_Bip_L_UpperLeg", "J_Bip_R_UpperLeg" -> {
                    val swing = boneSwingForwardAxes[node.name] ?: floatArrayOf(1f, 0f, 0f)
                    q = multiplyQuaternion(q, axisAngleToQuaternion(swing[0], swing[1], swing[2], hop))
                }
                "J_Bip_L_LowerLeg", "J_Bip_R_LowerLeg" -> {
                    val swing = boneSwingForwardAxes[node.name] ?: floatArrayOf(1f, 0f, 0f)
                    q = multiplyQuaternion(q, axisAngleToQuaternion(swing[0], swing[1], swing[2], -legBend))
                }
                "J_Bip_L_UpperArm" -> {
                    val swing = boneSwingForwardAxes[node.name] ?: floatArrayOf(0f, 1f, 0f)
                    q = multiplyQuaternion(q, axisAngleToQuaternion(swing[0], swing[1], swing[2], armSwing))
                }
                "J_Bip_R_UpperArm" -> {
                    val swing = boneSwingForwardAxes[node.name] ?: floatArrayOf(0f, 1f, 0f)
                    q = multiplyQuaternion(q, axisAngleToQuaternion(swing[0], swing[1], swing[2], -armSwing))
                }
            }
            System.arraycopy(q, 0, node.rotation, 0, 4)
        }
    }

    private fun applyFlailPose() {
        val nodes = animNodes ?: return
        val shake = kotlin.math.sin(animPhase * 3f) * 0.3f
        for (node in nodes) {
            var q = node.baseRotation.copyOf()
            when (node.name) {
                "J_Bip_L_UpperLeg", "J_Bip_R_UpperLeg" -> {
                    val swing = boneSwingForwardAxes[node.name] ?: floatArrayOf(1f, 0f, 0f)
                    q = multiplyQuaternion(q, axisAngleToQuaternion(swing[0], swing[1], swing[2], shake))
                }
                "J_Bip_L_UpperArm", "J_Bip_R_UpperArm" -> {
                    val swing = boneSwingForwardAxes[node.name] ?: floatArrayOf(0f, 1f, 0f)
                    q = multiplyQuaternion(q, axisAngleToQuaternion(swing[0], swing[1], swing[2], shake))
                }
            }
            System.arraycopy(q, 0, node.rotation, 0, 4)
        }
    }

    private fun applyResistPose() {
        val nodes = animNodes ?: return
        val wiggle = kotlin.math.sin(animPhase * 2f) * 0.15f
        for (node in nodes) {
            var q = node.baseRotation.copyOf()
            when (node.name) {
                "J_Bip_C_Hips" -> {
                    q = multiplyQuaternion(q, axisAngleToQuaternion(0f, 1f, 0f, wiggle))
                }
                "J_Bip_L_UpperArm", "J_Bip_R_UpperArm" -> {
                    val swing = boneSwingDownAxes[node.name] ?: floatArrayOf(0f, 0f, 1f)
                    q = multiplyQuaternion(q, axisAngleToQuaternion(swing[0], swing[1], swing[2], 0.5f + wiggle))
                }
            }
            System.arraycopy(q, 0, node.rotation, 0, 4)
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
        if (program != 0) { GLES30.glDeleteProgram(program); program = 0 }
    }

    private class MeshRenderer(private val data: MeshData) {
        private var posVbo: Int = 0
        private var uvVbo: Int = 0
        private var normVbo: Int = 0
        private var jointVbo: Int = 0
        private var weightVbo: Int = 0
        private var ibo: Int = 0
        private var texId: Int = 0
        private var indexCount: Int = 0
        private var indexType: Int = 0

        init {
            val bufs = IntArray(6)
            GLES30.glGenBuffers(6, bufs, 0)
            posVbo = bufs[0]; uvVbo = bufs[1]; normVbo = bufs[2]
            jointVbo = bufs[3]; weightVbo = bufs[4]; ibo = bufs[5]

            uploadFloatBuffer(posVbo, data.positions)
            if (data.uvs != null) uploadFloatBuffer(uvVbo, data.uvs)
            if (data.normals != null) uploadFloatBuffer(normVbo, data.normals)
            if (data.joints != null) uploadFloatBuffer(jointVbo, data.joints)
            if (data.weights != null) uploadFloatBuffer(weightVbo, data.weights)

            val indexByteSize = when (data.indexType) {
                GLES30.GL_UNSIGNED_BYTE -> 1
                GLES30.GL_UNSIGNED_SHORT -> 2
                else -> 4
            }
            val idxBuf = when (data.indexType) {
                GLES30.GL_UNSIGNED_BYTE -> {
                    val buf = ByteBuffer.allocateDirect(data.indices.size).order(ByteOrder.nativeOrder())
                    for (i in data.indices) buf.put(i.toByte())
                    buf.position(0); buf
                }
                GLES30.GL_UNSIGNED_SHORT -> {
                    val buf = ByteBuffer.allocateDirect(data.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
                    for (i in data.indices) buf.put(i.toShort())
                    buf.position(0); buf
                }
                else -> {
                    val buf = ByteBuffer.allocateDirect(data.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
                    buf.put(data.indices).position(0)
                    buf
                }
            }
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, data.indices.size * indexByteSize, idxBuf, GLES30.GL_STATIC_DRAW)
            indexCount = data.indices.size
            indexType = data.indexType

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

        private fun uploadFloatBuffer(vbo: Int, arr: FloatArray) {
            val buf = ByteBuffer.allocateDirect(arr.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.put(arr).position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, arr.size * 4, buf, GLES30.GL_STATIC_DRAW)
        }

        fun draw(program: Int, mvpMatrix: FloatArray, boneMatrices: FloatArray, hasSkin: Boolean) {
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

            if (hasSkin && data.joints != null && data.weights != null) {
                val jointLoc = GLES30.glGetAttribLocation(program, "aJoint")
                if (jointLoc >= 0) {
                    GLES30.glEnableVertexAttribArray(jointLoc)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, jointVbo)
                    GLES30.glVertexAttribPointer(jointLoc, 4, GLES30.GL_FLOAT, false, 0, 0)
                }
                val weightLoc = GLES30.glGetAttribLocation(program, "aWeight")
                if (weightLoc >= 0) {
                    GLES30.glEnableVertexAttribArray(weightLoc)
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, weightVbo)
                    GLES30.glVertexAttribPointer(weightLoc, 4, GLES30.GL_FLOAT, false, 0, 0)
                }
                val bonesLoc = GLES30.glGetUniformLocation(program, "uBones")
                if (bonesLoc >= 0) {
                    val numBones = kotlin.math.min(data.skin?.joints?.size ?: 0, MAX_BONES)
                    GLES30.glUniformMatrix4fv(bonesLoc, numBones, false, boneMatrices, 0)
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
            if (hasSkin && data.joints != null) {
                val jointLoc = GLES30.glGetAttribLocation(program, "aJoint")
                if (jointLoc >= 0) GLES30.glDisableVertexAttribArray(jointLoc)
                val weightLoc = GLES30.glGetAttribLocation(program, "aWeight")
                if (weightLoc >= 0) GLES30.glDisableVertexAttribArray(weightLoc)
            }
        }

        fun destroy() {
            val bufs = intArrayOf(posVbo, uvVbo, normVbo, jointVbo, weightVbo, ibo)
            GLES30.glDeleteBuffers(6, bufs, 0)
            if (texId != 0) {
                val tex = intArrayOf(texId)
                GLES30.glDeleteTextures(1, tex, 0)
            }
        }
    }
}
