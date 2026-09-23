package com.example.a3d_viewer.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.a3d_viewer.assets.BundledModelAsset
import com.example.a3d_viewer.assets.GlbLabelParser
import com.example.a3d_viewer.assets.ModelAssetCatalog
import com.example.a3d_viewer.assets.PartLabel
import com.example.a3d_viewer.model.ActiveModelInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Session state for the single-screen viewer.
 *
 * Holds Compose snapshot state (list of models, focus, add-menu) so drag/pinch
 * can mutate [ActiveModelInstance] without copying a new UiState every frame.
 * Filament [io.github.sceneview.node.ModelNode] / Engine objects stay in the View.
 */
class ViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val assets = application.assets
    private val labelCache = mutableMapOf<String, List<PartLabel>>()

    val activeModels = mutableStateListOf<ActiveModelInstance>()

    var availableAssets by mutableStateOf<List<BundledModelAsset>>(emptyList())
        private set

    var addMenuExpanded by mutableStateOf(false)
    var focusedId by mutableStateOf<String?>(null)

    init {
        availableAssets = ModelAssetCatalog.listBundledModels(assets)
    }

    fun focus(id: String) {
        focusedId = id
    }

    fun addModel(asset: BundledModelAsset, viewport: IntSize) {
        addMenuExpanded = false
        viewModelScope.launch {
            val labels = withContext(Dispatchers.Default) {
                labelCache.getOrPut(asset.assetPath) {
                    GlbLabelParser.parseFromAsset(assets, asset.assetPath)
                }
            }
            val cell = layoutSlot(activeModels.size, viewport)
            val created = ActiveModelInstance(
                asset = asset,
                labels = labels,
                initialCenter = cell.center,
                initialSizePx = cell.sizePx,
            )
            activeModels += created
            focusedId = created.id
        }
    }

    fun removeModel(id: String) {
        activeModels.removeAll { it.id == id }
        if (focusedId == id) {
            focusedId = activeModels.lastOrNull()?.id
        }
    }

    private data class Slot(val center: Offset, val sizePx: Float)

    private fun layoutSlot(index: Int, viewport: IntSize): Slot {
        val w = viewport.width.coerceAtLeast(1).toFloat()
        val h = viewport.height.coerceAtLeast(1).toFloat()
        val cols = 2
        val rows = 3
        val col = index % cols
        val row = (index / cols) % rows
        val cellW = w / cols
        val cellH = h / rows
        val size = minOf(cellW, cellH) * 0.72f
        return Slot(
            center = Offset(cellW * (col + 0.5f), cellH * (row + 0.5f)),
            sizePx = size,
        )
    }
}
