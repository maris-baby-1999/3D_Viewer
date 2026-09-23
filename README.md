# 3D Model Viewer

Single-activity Jetpack Compose app that loads bundled `.glb` models onto **one** full-screen 3D viewport. Each model sits in a 2D container: drag to move, pinch to resize, toggle 3D interaction, show part labels, or close.

**Min SDK 24.** Models: `Bulb.glb`, `Fiagena.glb`, `Lungs.glb`, `Microscope.glb`, `solarsystem.glb` in `app/src/main/assets/`.

## 3D library (and why)

**[SceneView 2.2.1](https://github.com/SceneView/sceneview-android)** (`io.github.sceneview:sceneview`) on **Google Filament**.

- Compose-native `Scene` + `ModelNode` — no Fragments, one `MainActivity`.
- Loads `.glb` directly; Filament is built for real-time glTF on Android.
- **2.2.1** instead of 4.x: SceneView 4.38 needs compileSdk 37 and AGP 9; this project is AGP 8.13 / compileSdk 36.
- Not ARSceneView — no ARCore, no extra camera/network permissions.

## Performance optimisations

Target: **≥ 30 FPS with 5 models** on 2–3 GB RAM devices.

- **One Filament `Scene` / `SurfaceView`.** Containers are Compose boxes mapped to `ModelNode` transforms, not extra SceneViews (those crash low-RAM devices).
- **Shared GLB templates.** The first load of a file keeps a Filament `Model`; later adds of the same file call `createInstance` instead of another full GPU upload.
- **Templates evict** when the last instance of that asset is closed (and again on screen dispose).
- **`scaleToUnitCube` only when the container size changes** — never overwrite scale with a raw factor (that hid every model except the solar system).
- **Dirty transform writes.** `onFrame` updates position / rotation / scale only when you drag, rotate, or pinch.
- **No raw GLB byte cache.** Labels parse once from the asset (`parseFromAsset`) and stay in a small in-memory label cache. Duplicate adds do not keep ~17 MB of `.glb` bytes around.
- **Shadows and collision off** on each `ModelNode`. SceneView 2.2 already defaults SSAO / bloom / MSAA off.
- **Label projector** uses pre-allocated matrices/arrays (no `Vector3` / `Matrix` in the render loop) and invalidates Compose only if a label moved ≥ 0.5 px.
- **Live part nodes.** Each `extras.prop` label is bound to its Filament entity (by glTF name, then extras text). Every frame the projector takes that node’s world translation and runs it through the real camera, so chips stay on the part while you rotate or zoom. Baked model-space xyz is only a fallback.
- Camera orbit is off (`cameraManipulator = null`) so container gestures own input.
- **On-screen FPS counter** (top-left) updates once per second so you can check the 30 FPS target on a real phone.

## Trade-offs

| Choice | Cost |
|--------|------|
| SceneView 2.2 vs 4.x | Older API (`childNodes` list, not the 4.x node DSL); no `RenderQuality.Performance` preset. |
| Screen↔world via a 45° frustum helper | Can drift vs Filament’s 28 mm lens; labels use the real camera matrices. |
| Solar system display bias (large AABB) | Heuristic scale, not a true “dense mesh” fit. |
| Pinch always resizes the **frame** (model fills it) | 3D-only zoom without moving the box was dropped so zoom-out matches the container. |
| Min frame = size at load (floor 140 px) | You can pinch smaller than a later enlarge, but never smaller than the first slot. |
| Five full-screen Compose overlays | Needed so ✕ / Mv / Lbl stay tappable when a box is near the edge; extra hit-test cost. |

## With more time

- True camera unprojection for placement; drop the solar bias.
- Memory HUD next to the FPS counter for the 2–3 GB RAM test.
- Full material unbind on Close (beyond `node.destroy()` + template eviction).

## Known limitations

- Labels start **off**. They track the live Empty/part node origin; if that node is not on the dense mesh, the chip can still sit slightly off the surface.
- High-end SceneView 4 features (auto-center, quality presets) are unused.
- The FPS overlay is a 1-second frame count, not a GPU profiler. 30 FPS is still device-dependent; interaction and **Lbl** cost extra.
- Interaction vs Normal: pinch always resizes the **container** (model scales to fit). One-finger still only rotates in 3D mode.

## Devices tested

Development and Gradle builds on **Windows 10**, Android Studio, **compileSdk 36 / minSdk 24**. Interactive checks were on a **physical Android phone** (tall gesture-nav device). Add your exact model and API level here if required for submission.

## How to run

1. Open the project in Android Studio and wait for Gradle sync.
2. Run the `app` configuration, or `.\gradlew.bat :app:installDebug`.
3. Tap **+ Model** and pick a `.glb`.
4. **✕** closes, **Mv / 3D** switches mode, **Lbl** toggles part labels.
5. One-finger drag moves (or rotates in 3D mode). Pinch resizes the container; the 3D content scales to fit. The box cannot shrink below its size at load.
6. After about one second, a **FPS** readout appears top-left. Add all five models, sit idle, then drag/pinch to compare.

Parser unit tests:

```
.\gradlew.bat :app:testDebugUnitTest --tests "com.example.a3d_viewer.assets.GlbLabelParserTest"
```
