# Sceneform → SceneView/Filament migration guide

**Validated:** `io.github.sceneview:arsceneview:2.2.1` resolves cleanly with
`com.google.android.filament:*:1.52.0` and `com.google.ar:core:1.43.0` (no conflicts).
API below is introspected from the actual 2.2.1 AAR (`javap`), not from memory.

**Why this is a guide, not a finished migration:** it's a big-bang rewrite of the
render layer — 16 files / 400+ call sites — and the scene graph is single-engine, so
the build is red from the dependency swap until *all* files are migrated. AR render
correctness (coordinates, anchors, materials, lighting, lifecycle) can only be
verified on a device, iterating. Do it with a device attached; this guide makes it
mechanical. Recovery point for the working app: commit `8f6306d`.

## Dependency
```kotlin
// remove: com.gorisse.thomas.sceneform:sceneform:1.23.0 + :ux + the forced ARCore 1.39
implementation("io.github.sceneview:arsceneview:2.2.1") // pulls Filament 1.52 + ARCore 1.43
```
Also delete the `resolutionStrategy.force("com.google.ar:core:1.39.0")` block.

## The view (layout + setup)
- Layout: `com.google.ar.sceneform.ArSceneView` → `io.github.sceneview.ar.ARSceneView`
  (id stays `@+id/sceneView`).
- `ARSceneView` needs a lifecycle: `sceneView.lifecycle = lifecycle` in `onCreate`
  (it self-manages the ARCore session — no manual `Session(context)`/`session.configure`).
- Per-frame: replace `sceneView.scene.addOnUpdateListener { … sceneView.arFrame }` with
  `sceneView.onSessionUpdated = { session, frame -> … }` and read `sceneView.frame`.
- Session config: replace ARSessionManager's manual `Config(session).apply{…}` with
  `sceneView.configureSession { session, config -> config.focusMode = …; config.depthMode = …;
  config.planeFindingMode = …; config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR }`.
  (ARCore `Config`/`Session`/`Frame`/`Plane`/`Anchor`/`HitResult` are unchanged — keep that logic.)
- Hit test: `frame.hitTest(x,y)` + manual plane filtering → `sceneView.hitTestAR(x, y, planeTypes=…)`
  returns a `com.google.ar.core.HitResult` (filtering args built in). Keep ARCore `HitResult`.
- Light estimation: delete the gorisse `LightEstimationConfig` + reflection hacks entirely
  (that whole block in ARSessionManager exists to work around the Sceneform fork; SceneView
  handles it via `configureSession`).

## Nodes & rendering — the core rewrite
| Sceneform | SceneView 2.2.1 |
|---|---|
| `com.google.ar.sceneform.Node` | `io.github.sceneview.node.Node(engine)` |
| `AnchorNode(anchor)` + `setParent(sceneView.scene)` | `io.github.sceneview.ar.node.AnchorNode(engine, anchor)` + `sceneView.addChildNode(node)` |
| `node.setParent(parent)` / `setParent(null)` | `parent.addChildNode(node)` / `node.parent = null` (or `parent.removeChildNode`) |
| `node.worldPosition = Vector3(…)` | `node.worldPosition = Position(x,y,z)` (`io.github.sceneview.math.Position` = `Float3`) |
| `node.worldRotation = Quaternion.lookRotation(dir, up)` | `node.quaternion = lookTowards(...)` / set `node.rotation: Rotation` (`dev.romainguy.kotlin.math`) |
| `ModelRenderable.builder().setSource(ctx, Uri).build().thenAccept{ node.renderable = it }` | `val instance = sceneView.modelLoader.loadModelInstance(fileLocation)` (suspend) → `ModelNode(modelInstance = instance)` then `addChildNode` |
| `MaterialFactory.makeOpaqueWithColor(ctx, Color(r,g,b,a)).thenAccept{…}` | `sceneView.materialLoader.createColorInstance(Color(r,g,b,a))` → `MaterialInstance` (sync) |
| `ShapeFactory.makeCube(size, center, material)` + `Node{renderable=…}` | `io.github.sceneview.node.CubeNode(engine, size=Size(…), center=Position(…), materialInstance=…)` |
| `ShapeFactory.makeCylinder(radius, height, center, material)` | `CylinderNode(engine, radius, height, center, materialInstance)` |
| `ShapeFactory.makeSphere(radius, center, material)` | `SphereNode(engine, radius, center, materialInstance)` |
| `ViewRenderable.builder().setView(ctx, view).build().thenAccept{ node.renderable=… }` | `ViewNode(engine, windowManager, materialLoader)` + set its view; needs a `ViewNode2.WindowManager` (see ARSceneView ctor) — used for the AR ruler's floating label |

## Math (`Vector3`/`Quaternion` — 163 sites)
- `com.google.ar.sceneform.math.Vector3` → `dev.romainguy.kotlin.math.Float3`
  (aliased `Position`/`Direction`/`Scale`/`Size` in `io.github.sceneview.math`).
- `Vector3.subtract(a,b)`→`a - b`; `Vector3.add`→`a + b`; `v.scaled(s)`→`v * s`;
  `v.length()`→`length(v)`; `v.normalized()`→`normalize(v)` (functions from `dev.romainguy.kotlin.math`).
- `Quaternion.lookRotation(dir, up)` → `lookTowards(eye, target, up)` / `Quaternion` helpers in kotlin-math.
- `pose.tx()/ty()/tz()` (ARCore Pose) are unchanged — only the Sceneform wrapper types change.

## File order (leaf → core; compile after each is impossible until the view is migrated,
## so migrate the view first, then everything in one red→green sweep)
1. `app/build.gradle.kts` (dep), `res/layout/activity_main.xml` (view tag).
2. `managers/ARSessionManager.kt` — collapse to `configureSession`/`onSessionUpdated`; drop light hacks.
3. `MainActivity.kt` — `sceneView` type, lifecycle, frame access, hit tests, anchor add.
4. `scene/LayerGlbManager.kt` — `modelLoader.loadModelInstance` + `ModelNode`.
5. `measurement/ARRuler.kt` — `CubeNode`/`CylinderNode` for points/segments, `ViewNode` for labels,
   all `Vector3`→`Float3`.
6. `scaffold/ScaffoldCylinderRenderer.kt`, `visualization/VoxelVisualizer.kt`,
   `scene/SceneBuilder.kt`, `scene/LODManager.kt`, `scene/PhysicsAnimator.kt`,
   `scene/LightingSetup.kt`, `materials/MaterialManager.kt`, `effects/ParticleSystem.kt`,
   `models/LayherModels.kt`, `assets/ModelAssets.kt`, `logic/OfflineScaffolder.kt`, `SmartReticleView.kt`.
7. Remove the `selectSafeLightEstimationMode`/reflection fallbacks.

## Exact signatures (introspected from 2.2.1 AAR — copy-paste accurate)
```kotlin
// scene ops (on ARSceneView / SceneView base):
sceneView.engine            // com.google.android.filament.Engine
sceneView.modelLoader       // io.github.sceneview.loaders.ModelLoader
sceneView.materialLoader    // io.github.sceneview.loaders.MaterialLoader
sceneView.addChildNode(node); sceneView.removeChildNode(node)
sceneView.configureSession { session, config -> /* ARCore Config */ }
sceneView.onSessionUpdated = { session, frame -> /* per-frame */ }
sceneView.frame             // com.google.ar.core.Frame?
sceneView.cameraNode        // ARCameraNode (has worldPosition: Float3)
sceneView.hitTestAR(xPx, yPx, planeTypes=..., ...) // -> com.google.ar.core.HitResult?

// nodes:
val n = io.github.sceneview.node.Node(engine)
n.position = Float3(x,y,z); n.worldPosition = Float3(...)
n.quaternion = Quaternion(...); n.rotation = Float3(...) ; n.scale = Float3(...)
n.parent = parent  // or parent.addChildNode(n) / parent.removeChildNode(n)

val anchorNode = io.github.sceneview.ar.node.AnchorNode(engine, anchor)  // ar.node

// material (sync): android color int + PBR (metallic, roughness, reflectance)
val mat = materialLoader.createColorInstance(android.graphics.Color.argb(a,r,g,b), 0f, 0.6f, 0f)

// primitives (geometry builder + material):
val cube = io.github.sceneview.geometries.Cube.Builder().size(Float3(w,h,d)).center(Float3(0f,0f,0f)).build(engine)
val cubeNode = io.github.sceneview.node.CubeNode(engine, cube, mat)
val cyl = io.github.sceneview.geometries.Cylinder.Builder().radius(r).height(h).center(Float3(0f,h/2,0f)).build(engine)
val cylNode = io.github.sceneview.node.CylinderNode(engine, cyl, mat)

// model (suspend): file path under assets, e.g. "models/x.glb" or a cached file path
val instance = modelLoader.loadModelInstance(fileLocation)   // FilamentInstance?
val modelNode = io.github.sceneview.node.ModelNode(instance!!)  // then addChildNode

// math: dev.romainguy.kotlin.math.{Float3,Quaternion,Float4}; functions length(v),
// normalize(v), cross(a,b), dot(a,b); operators a-b, a+b, v*s. lookTowards(eye,target,up).
```

## Gotchas confirmed from the AAR
- Model loading is **suspend** (`modelLoader.loadModelInstance`) — already inside coroutines here, good.
- `ARSceneView` constructor has many optionals; in XML you only get `(Context, AttributeSet, defStyleAttr)`
  — configure via property setters (`lifecycle`, `onSessionUpdated`, `sessionConfiguration`) after inflation.
- Coordinate handedness and units match ARCore (metres), so geometry math should transfer 1:1; the risk is
  node-attachment/anchor lifecycle and material/lighting, which need on-device checks.
