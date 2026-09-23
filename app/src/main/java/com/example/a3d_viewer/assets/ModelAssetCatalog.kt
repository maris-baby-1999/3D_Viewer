package com.example.a3d_viewer.assets

import android.content.res.AssetManager

/**
 * Scans `assets/` for bundled `.glb` files and exposes a stable catalog for the Add Model UI.
 */
data class BundledModelAsset(
    /** Filename including `.glb` extension (also the asset path). */
    val fileName: String,
    /** Display name without extension. */
    val displayName: String,
) {
    val assetPath: String get() = fileName
}

object ModelAssetCatalog {

    private const val GLB_EXTENSION = ".glb"

    fun listBundledModels(assetManager: AssetManager): List<BundledModelAsset> {
        return assetManager.list("")
            ?.asSequence()
            ?.filter { it.endsWith(GLB_EXTENSION, ignoreCase = true) }
            ?.sorted()
            ?.map { fileName ->
                BundledModelAsset(
                    fileName = fileName,
                    displayName = fileName.substringBeforeLast('.'),
                )
            }
            ?.toList()
            .orEmpty()
    }
}
