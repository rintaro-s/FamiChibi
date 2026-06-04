package com.nbks.famichibi.vrm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AssetVrmScanner {

    private const val TAG = "com.nbks.famichibi"
    private const val ASSET_DIR = "vrm-viewer"

    suspend fun listAssetVrms(context: Context): List<String> = withContext(Dispatchers.IO) {
        try {
            context.assets.list(ASSET_DIR)
                ?.filter { it.endsWith(".vrm", ignoreCase = true) }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list asset VRMs", e)
            emptyList()
        }
    }

    /**
     * Copies the chosen asset VRM into app files so the OpenGL renderer can read it as a File.
     */
    suspend fun copyAssetVrmToInternal(context: Context, assetName: String): File? = withContext(Dispatchers.IO) {
        try {
            val destFile = File(context.filesDir, assetName)
            context.assets.open("$ASSET_DIR/$assetName").use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset VRM: $assetName", e)
            null
        }
    }
}
