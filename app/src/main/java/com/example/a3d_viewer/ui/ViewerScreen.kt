package com.example.a3d_viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.a3d_viewer.assets.BundledModelAsset
import com.example.a3d_viewer.model.ActiveModelInstance
import com.example.a3d_viewer.model.ContainerInteractionMode
import com.example.a3d_viewer.scene.SceneController
import com.example.a3d_viewer.viewmodel.ViewerViewModel
import io.github.sceneview.Scene
import io.github.sceneview.SceneView
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import kotlinx.coroutines.delay

/**
 * Single full-screen Filament viewport + lightweight Compose overlays for containers / Add Model.
 *
 * Critical: only ONE [Scene] is created. Each "container" is a 2D bounding box mapped to a
 * [io.github.sceneview.node.ModelNode] transform inside that shared scene.
 */
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel = viewModel(),
) {
    val scene = remember { SceneController() }
    var sceneViewRef by remember { mutableStateOf<SceneView?>(null) }
    var fpsText by remember { mutableStateOf("") }
    val fpsFrames = remember { intArrayOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            if (scene.alive) fpsText = "${fpsFrames[0]} FPS"
            fpsFrames[0] = 0
        }
    }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val childNodes: SnapshotStateList<Node> = rememberNodes()
    val cameraNode = rememberCameraNode(engine) {
        position = Position(x = 0f, y = 0f, z = SceneController.CAMERA_DISTANCE)
        lookAt(Position(x = 0f, y = 0f, z = 0f))
    }

    DisposableEffect(scene) {
        scene.alive = true
        onDispose {
            // Do not destroy ModelNode / FilamentAsset here. SceneView's rememberNodes
            // + rememberModelLoader already tear them down; a second pass SIGSEGVs in gltfio.
            scene.releaseMaps()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewport = IntSize(
            width = with(density) { maxWidth.roundToPx() },
            height = with(density) { maxHeight.roundToPx() },
        )

        LaunchedEffect(viewModel.activeModels.map { it.id }.toList()) {
            scene.syncNodes(viewModel.activeModels, childNodes, modelLoader, viewport)
        }

        Scene(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader,
            isOpaque = true,
            childNodes = childNodes,
            cameraNode = cameraNode,
            cameraManipulator = null,
            onViewCreated = { sceneViewRef = this },
            onFrame = {
                if (scene.alive) {
                    fpsFrames[0] += 1
                    scene.onFrame(viewModel.activeModels, cameraNode, engine, viewport)
                }
            },
        )

        // Runs before Scene's AndroidView.onRelease (later DisposableEffects dispose first).
        // Emptying childNodes first is the SceneView 2.2.1 workaround for libgltfio SIGSEGV 0x2a8.
        DisposableEffect(Unit) {
            onDispose {
                scene.detachScene(sceneViewRef, childNodes)
            }
        }

        PartLabelOverlay(
            projector = scene.labelProjector,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0.5f),
        )

        if (fpsText.isNotEmpty()) {
            Text(
                text = fpsText,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .zIndex(4f)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }

        // ── Virtual container overlays (2D) ─────────────────────────────────
        viewModel.activeModels.forEach { instance ->
            ModelContainerOverlay(
                instance = instance,
                viewport = viewport,
                focused = instance.id == viewModel.focusedId,
                onFocus = { viewModel.focus(instance.id) },
                onClose = { viewModel.removeModel(instance.id) },
            )
        }

        AddModelFab(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            viewModel = viewModel,
            onPick = { asset -> viewModel.addModel(asset, viewport) },
        )
    }
}

@Composable
private fun ModelContainerOverlay(
    instance: ActiveModelInstance,
    viewport: IntSize,
    focused: Boolean,
    onFocus: () -> Unit,
    onClose: () -> Unit,
) {
    val density = LocalDensity.current
    val sizeDp = with(density) { instance.sizePx.toDp() }
    val half = instance.sizePx / 2f
    val mode = instance.interactionMode
    val interacting = mode == ContainerInteractionMode.Interaction
    val button = 36.dp
    val buttonPx = with(density) { button.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }
    val padPx = with(density) { 10.dp.toPx() }
    val barWidth = buttonPx * 3f + gapPx * 2f
    val maxLeft = (viewport.width - barWidth - padPx).coerceAtLeast(padPx)
    val barLeft = (instance.center.x - half + padPx).coerceIn(padPx, maxLeft)
    val barTop = (instance.center.y - half + padPx)
        .coerceIn(padPx, (viewport.height - buttonPx - padPx).coerceAtLeast(padPx))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (focused) 2f else 1f),
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    translationX = instance.center.x - half
                    translationY = instance.center.y - half
                }
                .size(sizeDp)
                .pointerInput(instance.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.any { it.changedToDown() }) {
                                onFocus()
                            }
                        }
                    }
                }
                .pointerInput(instance.id, mode, viewport) {
                    detectTransformGestures(
                        panZoomLock = false,
                    ) { _, pan, zoom, _ ->
                        val pinching = zoom != 1f
                        when (mode) {
                            ContainerInteractionMode.Normal -> {
                                if (pinching) {
                                    instance.resizeBy(zoom, viewport)
                                } else {
                                    instance.moveBy(pan, viewport)
                                }
                            }
                            ContainerInteractionMode.Interaction -> {
                                if (pinching) {
                                    // Zoom the 3D model and its frame together.
                                    instance.resizeBy(zoom, viewport)
                                } else {
                                    instance.modelYawDeg += pan.x * 0.35f
                                    instance.modelPitchDeg =
                                        (instance.modelPitchDeg - pan.y * 0.35f)
                                            .coerceIn(-80f, 80f)
                                }
                            }
                        }
                    }
                }
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = Color.White.copy(alpha = if (focused) 0.9f else 0.55f),
                    shape = RoundedCornerShape(8.dp),
                ),
        ) {
            Text(
                text = instance.asset.displayName,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        Row(
            modifier = Modifier
                .zIndex(3f)
                .graphicsLayer {
                    translationX = barLeft
                    translationY = barTop
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OverlayIconButton(
                label = "✕",
                color = Color(0xFFE53935),
                onClick = onClose,
            )
            OverlayIconButton(
                label = if (interacting) "3D" else "Mv",
                color = if (interacting) Color(0xFF1565C0) else Color(0xE6212121),
                onClick = {
                    onFocus()
                    instance.toggleInteractionMode()
                },
            )
            OverlayIconButton(
                label = "Lbl",
                color = if (instance.labelsVisible) Color(0xFF2E7D32) else Color(0xE6212121),
                onClick = {
                    onFocus()
                    instance.toggleLabels()
                },
            )
        }
    }
}

@Composable
private fun OverlayIconButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = color,
        contentColor = Color.White,
        shadowElevation = 6.dp,
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = label, fontSize = 13.sp, color = Color.White)
        }
    }
}

@Composable
private fun AddModelFab(
    modifier: Modifier = Modifier,
    viewModel: ViewerViewModel,
    onPick: (BundledModelAsset) -> Unit,
) {
    Box(modifier = modifier) {
        FloatingActionButton(
            onClick = { viewModel.addMenuExpanded = true },
            containerColor = MaterialTheme.colorScheme.primary,
        ) {
            Text(text = "+ Model", modifier = Modifier.padding(horizontal = 12.dp))
        }
        DropdownMenu(
            expanded = viewModel.addMenuExpanded,
            onDismissRequest = { viewModel.addMenuExpanded = false },
        ) {
            if (viewModel.availableAssets.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No .glb assets found") },
                    onClick = { viewModel.addMenuExpanded = false },
                )
            } else {
                viewModel.availableAssets.forEach { asset ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(asset.displayName)
                                Text(
                                    text = asset.fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = { onPick(asset) },
                    )
                }
            }
        }
    }
}
