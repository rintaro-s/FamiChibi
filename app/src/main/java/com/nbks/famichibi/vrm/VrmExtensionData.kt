package com.nbks.famichibi.vrm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class VrmExtension(
    val exporterVersion: String? = null,
    val meta: VrmMeta? = null,
    val humanoid: VrmHumanoid? = null,
    val blendShapeMaster: VrmBlendShapeMaster? = null,
    val secondaryAnimation: VrmSecondaryAnimation? = null,
    val materialProperties: List<VrmMaterialProperty>? = null
)

@Serializable
data class VrmMeta(
    val title: String? = null,
    val version: String? = null,
    val author: String? = null,
    val contactInformation: String? = null,
    val reference: String? = null,
    val texture: Int = -1,
    val allowedUserName: String = "OnlyAuthor",
    val violentUssageName: String = "Disallow",
    val sexualUssageName: String = "Disallow",
    val commercialUssageName: String = "Disallow",
    val otherPermissionUrl: String = "",
    val licenseName: String = "Redistribution_Prohibited",
    val otherLicenseUrl: String = ""
)

@Serializable
data class VrmHumanoid(
    val humanBones: List<VrmHumanBone> = emptyList(),
    val armStretch: Float = 0.05f,
    val legStretch: Float = 0.05f,
    val upperArmTwist: Float = 0.5f,
    val lowerArmTwist: Float = 0.5f,
    val upperLegTwist: Float = 0.5f,
    val lowerLegTwist: Float = 0.5f,
    val feetSpacing: Float = 0f,
    val hasTranslationDoF: Boolean = false
)

@Serializable
data class VrmHumanBone(
    val bone: String,
    val node: Int,
    val useDefaultValues: Boolean = true
)

@Serializable
data class VrmBlendShapeMaster(
    val blendShapeGroups: List<VrmBlendShapeGroup> = emptyList()
)

@Serializable
data class VrmBlendShapeGroup(
    val name: String,
    val presetName: String? = null,
    val binds: List<VrmBlendShapeBind> = emptyList(),
    val materialValues: List<JsonObject> = emptyList(),
    val isBinary: Boolean = false
)

@Serializable
data class VrmBlendShapeBind(
    val mesh: Int,
    val index: Int,
    val weight: Float
)

@Serializable
data class VrmSecondaryAnimation(
    val boneGroups: List<VrmSpringBoneGroup> = emptyList(),
    val colliderGroups: List<VrmColliderGroup> = emptyList()
)

@Serializable
data class VrmSpringBoneGroup(
    val comment: String? = null,
    val stiffiness: Float = 1.0f,
    val gravityPower: Float = 0.0f,
    val gravityDir: VrmVector3 = VrmVector3(0f, -1f, 0f),
    val dragForce: Float = 0.3f,
    val center: Int = -1,
    val hitRadius: Float = 0.02f,
    val bones: List<Int> = emptyList(),
    val colliderGroups: List<Int> = emptyList()
)

@Serializable
data class VrmColliderGroup(
    val node: Int,
    val colliders: List<VrmCollider> = emptyList()
)

@Serializable
data class VrmCollider(
    val offset: VrmVector3 = VrmVector3(0f, 0f, 0f),
    val radius: Float = 0f
)

@Serializable
data class VrmVector3(
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f
)

@Serializable
data class VrmMaterialProperty(
    val name: String,
    val shader: String,
    val renderQueue: Int = 2000,
    val floatProperties: Map<String, Float> = emptyMap(),
    val vectorProperties: Map<String, List<Float>> = emptyMap(),
    val textureProperties: Map<String, Int> = emptyMap(),
    val keywordMap: Map<String, Boolean> = emptyMap(),
    val tagMap: Map<String, String> = emptyMap()
)
