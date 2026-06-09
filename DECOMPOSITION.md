# MainActivity decomposition roadmap

`MainActivity.kt` is ~4000 lines / ~140 functions / ~95 fields — a god-object that
owns AR, networking, streaming, planning, measurement, UI and telemetry. This is
the ordered plan to break it down. Each step must compile (JBR/JDK21, see
`memory/android-build-setup`) and is independently shippable.

## Target architecture (Clean-ish MVVM)
```
ui/        MainActivity = thin coordinator: inflate views, observe StateFlow, route clicks
viewmodel/ StructureViewModel = single source of truth (StateFlow), already exists — expand it
controller/
  ArController          wraps managers/ARSessionManager + scene/* (session, frames, anchors)
  StreamingController   the stream loop + backpressure + per-frame payload send
  PlanningController    request_scaffold / lock / export-poll / readiness-poll
  MeasurementController thin wrapper over measurement/ARRuler (callbacks already exist)
data/      NetworkClient (done: API key), OfflineQueue (make atomic), prefs
streaming/ FramePayloadBuilder (pure: AR frame + state -> request map)
```

## Ordered steps (each = one compileable commit)

1. **State → ViewModel.** Move the streaming/connection/readiness/export fields
   (`MainActivity.kt:237-339`) into `StructureViewModel` as `StateFlow`s; MainActivity
   observes them. Removes the cross-thread field sharing entirely.
   - ✅ **connection status**: `currentConnStatus` is now a read-only computed view of
     `viewModel.connectionState.value.status` (the `@Volatile` duplicate + 5 redundant
     writes removed; the collector already renders from the flow).
   - ✅ **readiness state**: `ready/score/metrics/reasons` → `StructureViewModel.readiness`
     (`StateFlow<ReadinessState>`, `setReadiness(...)`). 4 MainActivity fields removed; a
     no-arg `updateReadinessUI()` reads the flow. (Throttle fields `lastReadinessHints*`
     and `lastReadinessProfile` stay local — they're bookkeeping, not UI state.)
   - ✅ **currentSessionId**: promoted `StructureViewModel.currentSessionId` to a
     `StateFlow<String?>` (`sessionId`); MainActivity's field is now a read-only
     computed view, its single write routed through `setSessionId` (the duplicate
     `@Volatile` field + a redundant `setSessionId` call removed). All three
     cross-thread fields (conn status, readiness, session id) now live in the
     ViewModel — **no `@Volatile` patches remain**.

4. **PlanningController** (extract polling/lock/export coroutines + state).
   - ✅ **ReadinessPoller** (`controller/ReadinessPoller.kt`): the readiness poll loop,
     backoff (shared `NetworkStateController` tag "readiness") and its 3 fields
     (`readinessPollJob/Failures`, `nextReadinessPollAtMs`) moved out of MainActivity;
     MainActivity injects predicates (`isUiActive`, `isActiveSession`, `serverUrl`) and
     renders via an `onResult(body)` callback. ~80 lines + 3 fields removed.
   - ✅ **ExportPoller** (`controller/ExportPoller.kt`): the export/latest poll loop,
     in-flight guard, fail counters, 409 handling and shared backoff (tag
     "export_latest") moved out; 4 private fields removed (`exportPollJob/InFlight/
     Failures/FailStreak`, `nextExportPollAtMs`). The new-revision state machine
     (origin gate, auto-reload cooldown, GLB load) stays in MainActivity behind the
     `onExportLatestRevision` callback, since that state is shared with the export
     dialog + scene rendering. `exportNotReady409` stays in MainActivity (set via
     callback) — it's read by the status UI and `loadExportLayersInternal`.
   - ✅ **SessionLockClient** (`controller/SessionLockClient.kt`): the lock-revision
     network action (lock + offline-queue fallback + export/latest recovery, under
     `lockMutex`) moved out; `doLockSession` shrank ~35→~18 lines and now gathers AR
     measurements *outside* the network critical section.

## Extracted classes so far
`streaming/StreamTuner`, `controller/ReadinessPoller`, `controller/ExportPoller`,
`controller/SessionLockClient`, `controller/LayerController`. All injected with
explicit deps + callbacks; each compiles and leaves the unit suite (44) green.

- ✅ **LayerController** (`controller/LayerController.kt`): owns the `LayerGlbManager`,
  the parsed layer list/paths and `loadedRevId`; `applyBundle()` does the
  revision-aware clear + parse + default-on rendering. The ~20 scattered
  `layerGlbManager?.…` calls + 4 layer fields collapsed to one owner; MainActivity
  keeps the network fetch and the (Android) layers dialog, delegating all state +
  rendering. **Touches scene rendering — verify layer load/visibility on device.**

## Remaining (dedicated phases)
- Sceneform → SceneView/Filament (rendering migration).
- UI/UX: Material 3, drop blanket `monospace`.
2. **FramePayloadBuilder.** Extract the per-frame payload assembly
   (`MainActivity.kt:~2951-3001` basePayload + flags) into a pure
   `streaming/FramePayloadBuilder` taking intrinsics/pose/points/flags. Heavy encode
   (YUV→JPEG, depth) stays callable; depth already extracted to `DepthUtils.buildDepthPayload`.
3. **StreamingController.** Move `startStreaming/stopStreaming/loop` and the send/backpressure
   logic (`~2460-2620` + the big suspend send fn `~2860-3120`) behind a controller that
   takes `ApiService`, an AR-frame provider, and the ViewModel. Use a `Channel`
   (conflated) for backpressure instead of the scattered `streamPendingTick`/jobs.
4. **PlanningController.** Move request_scaffold/lock/export-poll/readiness-poll
   (`~1080-1200`, `~1460-1560`, the poll jobs) behind a controller; reuse `exportLoadMutex`/
   `lockExportMutex`.
5. **ArController + MeasurementController.** Pull AR session lifecycle + ruler wiring
   (`~660-880`) out; MainActivity keeps only view binding + observation.
6. **OfflineQueue atomicity.** Make `offline/OfflineQueue` read-modify-write atomic
   (single mutex) — flush currently races with enqueue.
7. **Lifecycle hygiene.** Verify teardown (`onDestroy 851-879` is mostly OK), stop the
   hint ticker / YOLO job, add timeout/cancel to `LayerGlbManager` GLB builds.

## Already done (this branch of work)
- `streaming/StreamTuner` — pure adaptive throttle (jpeg quality / point cap / interval
  from an EWMA of send time). Extracted from the inline `MainActivity.tuneStreaming`
  over four mutable fields; unit-tested (`StreamTunerTest`).
- `DepthUtils.buildDepthPayload` — uniform depth downsample (fixed the height-only
  shrink that distorted aspect ratio); MainActivity delegates to it.
- `NetworkClient` — sends `X-API-Key` from `BuildConfig.BACKEND_API_KEY` (empty by default).
- `@Volatile` on `currentSessionId` / `currentConnStatus` (interim until step 1).
- Reconnect/backoff is already centralized in `network/NetworkStateController`
  (`reportResult`/`snapshot`) — no extraction needed there.
- ARRuler formatting split: `formatDistance/formatArea` = display (device locale),
  `formatDistanceMachine/formatAreaMachine` + `machineLabel(m)` = machine ('.' via
  Locale.US), used for the server `MeasurementConstraint` labels. ARRulerUnitTest
  fixed (was locale-flaky) — full unit suite now green (44/44).
- Ruler button now bootstraps AR + shows a hint when not ready, instead of a
  silent no-op (a likely "ruler doesn't work" cause). The 3D markers/lines still
  depend on Sceneform — true AR-render reliability comes with the SceneView migration.

Note: `./gradlew testDebugUnitTest` needs network the first time (some test-runtime
jars — fuel/result/constraintlayout-core — aren't in the offline cache).

## Then (separate efforts)
- Sceneform → `io.github.sceneview:arsceneview` (Filament) behind a `RenderEngine` interface.
- UI/UX: Material 3, drop blanket `monospace`, real hierarchy (`res/layout/*`, styles).
