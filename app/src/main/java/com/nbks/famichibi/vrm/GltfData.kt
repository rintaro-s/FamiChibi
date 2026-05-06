package com.nbks.famichibi.vrm

import android.graphics.Bitmap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class GltfRoot(
    val meshes: List<GltfMesh>? = null,
    val buffers: List<GltfBuffer>? = null,
    val bufferViews: List<GltfBufferView>? = null,
    val accessors: List<GltfAccessor>? = null,
    val images: List<GltfImage>? = null,
    val textures: List<GltfTexture>? = null,
    val materials: List<GltfMaterial>? = null,
    val nodes: List<GltfNode>? = null,
    val scenes: List<GltfScene>? = null,
    val skins: List<GltfSkin>? = null,
    val animations: List<GltfAnimation>? = null,
)

@Serializable
class GltfScene(val nodes: List<Int>? = null)

@Serializable
class GltfNode(
    val mesh: Int? = null,
    val skin: Int? = null,
    val translation: List<Float>? = null,
    val rotation: List<Float>? = null,
    val scale: List<Float>? = null,
    val children: List<Int>? = null,
    val matrix: List<Float>? = null,
    val name: String? = null,
)

@Serializable
class GltfMesh(val primitives: List<GltfPrimitive>, val name: String? = null)

@Serializable
class GltfPrimitive(
    val attributes: Map<String, Int>,
    val indices: Int? = null,
    val material: Int? = null,
    val mode: Int = 4, // TRIANGLES
    val targets: List<Map<String, Int>>? = null,
)

@Serializable
class GltfBuffer(val uri: String? = null, val byteLength: Int)

@Serializable
class GltfBufferView(
    val buffer: Int,
    val byteOffset: Int = 0,
    val byteLength: Int,
    val byteStride: Int? = null,
    val target: Int? = null,
)

@Serializable
class GltfAccessor(
    val bufferView: Int? = null,
    val byteOffset: Int = 0,
    val componentType: Int,
    val count: Int,
    val type: String,
    val max: List<Float>? = null,
    val min: List<Float>? = null,
)

@Serializable
class GltfImage(
    val uri: String? = null,
    val bufferView: Int? = null,
    val mimeType: String? = null,
    val name: String? = null,
)

@Serializable
class GltfTexture(val source: Int? = null, val name: String? = null)

@Serializable
class GltfMaterial(
    val pbrMetallicRoughness: GltfPbr? = null,
    val alphaMode: String? = null,
    val alphaCutoff: Float = 0.5f,
    val doubleSided: Boolean = false,
    val name: String? = null,
)

@Serializable
class GltfPbr(
    val baseColorFactor: List<Float>? = null,
    val baseColorTexture: GltfTextureInfo? = null,
    val metallicFactor: Float = 1f,
    val roughnessFactor: Float = 1f,
)

@Serializable
class GltfTextureInfo(val index: Int, val texCoord: Int = 0)

@Serializable
class GltfSkin(
    val inverseBindMatrices: Int,
    val joints: List<Int>,
    val name: String? = null,
)

@Serializable
class GltfAnimation(
    val channels: List<GltfAnimationChannel>,
    val samplers: List<GltfAnimationSampler>,
    val name: String? = null,
)

@Serializable
class GltfAnimationChannel(
    val sampler: Int,
    val target: GltfAnimationTarget,
)

@Serializable
class GltfAnimationTarget(
    val node: Int,
    val path: String, // "translation", "rotation", "scale", "weights"
)

@Serializable
class GltfAnimationSampler(
    val input: Int, // accessor index for times
    val output: Int, // accessor index for values
    val interpolation: String = "LINEAR",
)

data class MeshData(
    val positions: FloatArray,
    val normals: FloatArray?,
    val uvs: FloatArray?,
    val joints: FloatArray?, // 4 components per vertex (bone indices)
    val weights: FloatArray?, // 4 components per vertex
    val indices: IntArray,
    val indexType: Int,
    val textureBitmap: Bitmap?,
    val baseColorFactor: FloatArray,
    val doubleSided: Boolean,
    val skin: SkinData?,
)

data class SkinData(
    val joints: IntArray,
    val inverseBindMatrices: FloatArray, // 16 floats per joint
)
