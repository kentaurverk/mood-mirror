# PRD: Mood Mirror Android offline app

**Status:** Reviewed, revised, and implemented; device qualification pending
**Last updated:** 2026-09-20  
**Product:** Mood Mirror  
**Target:** Android tablet, standalone and offline  
**Qualification device:** Samsung Galaxy Tab S6 Lite Wi‑Fi, model SM‑P613  
**Application ID / namespace:** `com.kentaurverk.moodmirror`  
**Source proposal:** [`src/mood-mirror-android/ANDROID-SOLUTION.md`](../../src/mood-mirror-android/ANDROID-SOLUTION.md)  
**Reference implementation:** [`src/mood-mirror-flask/`](../../src/mood-mirror-flask/)

Implementation now lives in [`src/mood-mirror-android/`](../../src/mood-mirror-android/). The debug and unsigned release variants assemble successfully, the verified model hashes are enforced by Gradle, and the merged APK manifest contains only camera access plus the AndroidX-generated non-exported receiver permission. Remaining release work is physical qualification on the SM-P613, performance measurement, and creating the signed artifact from the external local keystore.

## 1. Product decision

Build Mood Mirror Android as a native Kotlin application. The release APK will contain the face-landmark model, HSEmotion ONNX model, dog images, translations, and all runtime UI resources. Once installed, the app will work without internet, Wi-Fi, a Flask server, or another computer.

The Android app will recreate the current Flask application's user-visible experience while replacing HTTP frame uploads with on-device inference. It will describe visible expression cues only and must not claim to infer a person's feelings, intent, personality, truthfulness, or mental health.

### 1.1 Qualification device profile

The first release is qualified against the supplied Samsung Galaxy Tab S6 Lite Wi‑Fi (SM‑P613), not against an abstract Android tablet. Samsung lists this model with a 10.4-inch 2000 × 1200 display and an octa-core processor rated at 2.3/1.8 GHz. The supplied qualification unit is recorded as **Android 14**, **One UI 6.1**, firmware/build **P613XXS8CYE1 / P613OXM8CYE1**, with the **2025-05-01** security patch (last successful update: 2025-06-07). ADB reports `arm64-v8a,armeabi-v7a,armeabi`, so the release target is `arm64-v8a`. See the [Samsung product specification](https://www.samsung.com/pt/tablets/galaxy-tab-s/galaxy-tab-s6-lite-gray-64gb-sm-p613nzaaphe/) and [Samsung SM‑P613 update history](https://doc.samsungmobile.com/SM-P613/023502220809/eng.html).

The serial number is intentionally not stored in project documentation. The release report may identify the test unit by model and Android build only.

## 2. Problem and user outcome

The current Flask version requires a Python process and sends sampled camera frames to `/api/analyze`. That prevents it from operating independently on a tablet.

The Android version succeeds when a user can sideload the app, enable airplane mode, launch it, grant camera access, and receive responsive expression and Dog Mode updates entirely on the tablet.

## 3. Goals

1. Run camera capture, face localization, expression classification, smoothing, and rendering entirely on the tablet.
2. Work after a cold start in airplane mode with no runtime model download.
3. Preserve the recognizable Mood Mirror design and core Flask features.
4. Keep camera frames ephemeral and avoid network permissions, telemetry, or remote configuration.
5. Provide complete English and Italian UI coverage.
6. Remain responsive during sustained use on the declared target tablet.
7. Produce a reproducible, signed release APK suitable for USB or SD-card installation.

## 4. Non-goals

- Inferring a person's actual mood, intent, personality, health, or truthfulness.
- Training or fine-tuning a new expression model.
- Cloud inference, accounts, analytics, advertising, remote configuration, or synchronization.
- Capturing photos, recording video, exporting sessions, or persisting prediction history.
- Supporting multiple people simultaneously.
- Supporting Android devices below API 24.
- Play Store publication, automated updates, or Play Asset Delivery in the first release.
- Exact HTML/CSS code sharing with the Flask version.
- Guaranteed parity of floating-point scores across every Android chipset; semantic parity is required as defined in §15.

## 5. Target users and primary flows

### 5.1 Primary user

A tablet owner who wants a playful, private expression visualization that continues to work where no network is available.

### 5.2 Live camera flow

1. User opens the app.
2. App is idle with the camera off.
3. User taps **Start camera**.
4. App requests camera permission if needed.
5. App initializes the models and front camera.
6. Preview starts and the status moves through **Warming up**, **Finding face**, and **Signal locked**.
7. Expression scores, strongest cue, confidence, and recent-pulse history update.
8. User can enable Dog Mode, sound, or Italian at any time.
9. User taps **Stop camera**, backgrounds the app, or closes it; camera and inference resources are released.

### 5.3 Demo flow

1. User taps **Try demo** while the camera is stopped or unavailable.
2. The app cycles through deterministic synthetic expression scores without opening the camera.
3. Dog Mode, history, sound, and localization behave as they do for live results.
4. Starting the camera stops demo mode. Starting demo mode stops and releases the camera.

### 5.4 Permission-denied flow

1. If permission is denied once, show a localized explanation and keep **Try demo** available.
2. If Android reports that permission should no longer be requested, provide a localized **Open settings** action.
3. The app must not loop permission prompts or fall back to the rear camera without an explicit user action.

## 6. Functional requirements

### 6.1 Camera and lifecycle

- **FR-CAM-01:** The front-facing camera is the default and only automatic camera selection.
- **FR-CAM-02:** The preview is mirrored like a mirror. Model input is transformed to the correct sensor rotation independently of preview mirroring.
- **FR-CAM-03:** Use a direct Camera2 `TextureView` preview and bounded `ImageReader` with one active analyzer. On the SM-P613, select the verified front sensor (camera ID 1) instead of allowing CameraX to enumerate the tablet's virtual camera IDs.
- **FR-CAM-04:** Every acquired `Image` is closed in a `finally` path, including inference errors.
- **FR-CAM-05:** The camera is off on cold launch and never starts without a user action.
- **FR-CAM-06:** When the app leaves the foreground, stop analysis and release the camera. Returning to the app returns to the ready state and does not restart the camera automatically.
- **FR-CAM-07:** Keep the display awake only while the live camera is active.
- **FR-CAM-08:** If no front camera is available, show an unsupported-camera state and leave demo mode usable.
- **FR-CAM-09:** Copy and rotate each accepted analysis frame into an app-owned pixel buffer before closing its camera-owned `Image`. Asynchronous MediaPipe or ONNX work must never retain a camera-owned buffer after the analyzer callback returns.

### 6.2 Face detection and expression inference

- **FR-ML-01:** Bundle `face_landmarker.task` and `enet_b2_7.onnx` with the installed app.
- **FR-ML-02:** Configure Face Landmarker for one face and live-stream processing.
- **FR-ML-03:** Port the existing expanded landmark crop exactly:
  - left: minimum x minus `0.50 * face width`
  - right: maximum x plus `0.50 * face width`
  - top: minimum y minus `0.50 * face height`
  - bottom: maximum y plus `0.35 * face height`
  - clamp each bound to the image.
- **FR-ML-04:** Convert the crop to RGB, bilinearly resize to `260 x 260`, divide by 255, apply ImageNet normalization, and arrange a float32 tensor in `NCHW` order.
- **FR-ML-05:** Use normalization mean `[0.485, 0.456, 0.406]` and standard deviation `[0.229, 0.224, 0.225]`.
- **FR-ML-06:** Interpret logits in this exact model order: `angry`, `disgusted`, `fearful`, `happy`, `neutral`, `sad`, `surprised`.
- **FR-ML-07:** Apply numerically stable softmax and expose scores to the UI in this exact order: `happy`, `surprised`, `sad`, `angry`, `disgusted`, `fearful`, `neutral`.
- **FR-ML-08:** Process one inference at a time. A new frame may replace a queued frame but must not create concurrent ONNX sessions or an unbounded queue. The face-landmark result, crop, and ONNX run must use the same owned frame, matched by monotonic timestamp.
- **FR-ML-09:** Start with ONNX Runtime CPU/XNNPACK. NNAPI may be enabled only after target-device benchmarks show equal output acceptance and a material performance benefit.
- **FR-ML-10:** Model initialization or inference failure produces a recoverable localized error state; demo mode remains available.

### 6.3 Temporal behavior

- **FR-TIME-01:** Initialize smoothed scores to neutral `1.0` and every other label `0.0`, matching the Flask app.
- **FR-TIME-02:** For every detected-face result, update each score with `smoothed = previous * 0.52 + current * 0.48`.
- **FR-TIME-03:** Append at most 42 detected-face samples to the in-memory recent-pulse history.
- **FR-TIME-03A:** One history sample contains the displayed top label and its smoothed confidence. Sample count increases once per detected-face result.
- **FR-TIME-04:** Clear smoothing, sample count, and recent-pulse history when a new camera or demo session starts.
- **FR-TIME-05:** Do not append samples when no face is detected.
- **FR-TIME-06:** On loss of face, immediately show **Finding face**. After 1.5 seconds without a face, clear the active confidence and mark the readout as awaiting a signal so a stale label is not presented as current.
- **FR-TIME-07:** Dog Mode changes image only after the same new top label has appeared in three consecutive detected-face results. Returning to the currently displayed label cancels a pending change.

### 6.4 User interface and feature parity

- **FR-UI-01:** Recreate the current two-panel camera/readout experience using Jetpack Compose and responsive tablet layouts.
- **FR-UI-02:** Support landscape and portrait. Landscape shows camera and readout side by side when space permits; portrait stacks them. The app must not force orientation.
- **FR-UI-03:** Show the seven score meters, top expression, confidence, live status, sample count, and recent-pulse graph.
- **FR-UI-04:** Keep the disclaimer visible in the readout: this is an approximation of visible expressions and is not mind reading.
- **FR-UI-05:** Dog Mode replaces the main preview with the matching local dog image. All seven images are decoded before they can be selected; keep the current image until the target is ready and do not crossfade, pulse, or reveal a loading placeholder during a swap.
- **FR-UI-06:** During live Dog Mode, show a small mirrored camera thumbnail in the upper-left of the image with a localized **Your face / source** label. Reuse the same Camera2 `TextureView` rather than opening a second camera or preview stream.
- **FR-UI-07:** In demo mode, do not show a blank or fake camera thumbnail; show a localized demo indicator instead.
- **FR-UI-08:** Camera, demo, Dog Mode, language, and sound controls remain operable without navigating away from the main screen.
- **FR-UI-09:** Sound is off by default. When enabled, play a roughly 170 ms sine tone only when the top label changes: happy 659 Hz, surprised 784 Hz, sad 294 Hz, angry 196 Hz, disgusted 247 Hz, fearful 370 Hz, and neutral 440 Hz.
- **FR-UI-10:** Do not play tones in the background or after the camera/demo session stops.
- **FR-UI-11:** All interactive controls have localized accessibility labels, a minimum 48 dp target, visible focus state, and sufficient contrast. Live predictions must not trigger continuous screen-reader announcements; announce status changes and explicit errors only.
- **FR-UI-12:** Provide a localized **About** panel containing the app version, privacy statement, model names, and third-party notices required by §9.

### 6.5 Localization and persistence

- **FR-L10N-01:** Provide complete English and Italian string resources, including statuses, errors, permissions, mood labels, descriptions, Dog Mode, demo mode, accessibility labels, and the disclaimer.
- **FR-L10N-02:** The upper-right EN/IT control changes language without requiring an app restart.
- **FR-L10N-03:** Persist only the selected language across launches.
- **FR-L10N-04:** Camera, Dog Mode, demo mode, sound, history, and scores reset on a new process launch. Camera and sound default to off.
- **FR-L10N-05:** No user-visible string may be hardcoded in Kotlin or Compose code.

### 6.6 Demo signal contract

- **FR-DEMO-01:** Demo mode cycles in this order: happy, surprised, sad, angry, disgusted, fearful, neutral.
- **FR-DEMO-02:** Emit one demo result every 1,450 ms, with the selected class at `0.72` and the remaining `0.28` distributed equally across the other six classes before normal smoothing.
- **FR-DEMO-03:** Demo results are generated locally and deterministically; demo mode does not require or invoke the camera or either ML model.

## 7. State model

The UI is driven by one observable state owned by a lifecycle-aware view model.

| State | Camera | Inference | Primary UI behavior |
| --- | --- | --- | --- |
| `Ready` | Off | Idle/loaded | Start camera and demo available |
| `RequestingPermission` | Off | Idle | Permission explanation/progress |
| `Initializing` | Starting | Loading | Warming-up status; stop remains available |
| `FindingFace` | On | Active | Preview visible; no current confidence |
| `SignalLocked` | On | Active | Scores, label, history, Dog Mode update |
| `Demo` | Off | Synthetic | Deterministic demo signal and demo badge |
| `PermissionDenied` | Off | Idle | Retry or settings action; demo available |
| `UnsupportedCamera` | Off | Idle | Explanation; demo available |
| `ModelError` | Off | Failed | Local error and retry; demo available |
| `Stopped` | Off | Idle | Same user options as ready |

Only one of live camera or demo may be active. Transitions are idempotent: repeated stop actions do not fail, and repeated start actions do not open multiple camera or inference instances.

## 8. Data, model, and persistence contracts

### 8.1 Runtime prediction contract

```kotlin
data class ExpressionResult(
    val detected: Boolean,
    val scores: Map<ExpressionLabel, Float>,
    val bounds: NormalizedRect?,
    val capturedAtElapsedRealtimeMs: Long,
)
```

Rules:

- `scores` contains all seven labels for a detected face and sums to approximately 1.0 before smoothing.
- A no-face result has `detected = false`, no bounds, and no score update.
- Bounds use normalized coordinates in the correctly rotated analysis image, before preview mirroring.
- Wall-clock time is not required and is not persisted.

### 8.2 Local preferences

Use DataStore or an equivalently lifecycle-safe local preference store with one versioned key:

```text
ui.language = "en" | "it"
```

Unknown or missing values fall back to English. No camera frames, predictions, history, identifiers, or device information are persisted.

### 8.3 Model artifact manifest

The Android build uses a checked-in manifest with filename, pinned source, byte
size, and SHA-256. `scripts/download_models.py` downloads each artifact to the
local ignored `models/` directory and verifies it before saving. The build
checks both hashes again and fails when an asset is missing or differs.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `face_landmarker.task` | 3,758,596 | `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff` |
| `enet_b2_7.onnx` | 30,774,088 | `7863032d8c0c0bc259aa0dd7b8ebf6b607af454e7a0ea82be2b37767914ecd0f` |

The APK bundles the verified artifacts from the sibling Flask project. Model
setup is an explicit, one-time developer step; the app and Gradle build never
download model files. The download script pins MediaPipe's versioned endpoint
and an EmotiEffLib source commit, and rejects any bytes that do not match the
manifest hashes. Model files may be loaded from packaged assets or copied to
private app storage if required by the runtime; that copy is not user data and
must not require a network.

## 9. Offline, privacy, and security requirements

- **SEC-01:** The release manifest must not request `android.permission.INTERNET`, `ACCESS_NETWORK_STATE`, microphone, storage, media-library, location, advertising ID, or account permissions.
- **SEC-02:** The only dangerous runtime permission is `android.permission.CAMERA`.
- **SEC-03:** Do not include an HTTP client, analytics SDK, crash-reporting SDK, ad SDK, remote-config SDK, or WebView.
- **SEC-04:** Camera frames remain in memory and are not written to files, logs, screenshots, caches, databases, or Android media storage by the app.
- **SEC-05:** Logs may contain state names, elapsed inference timings, and error categories, but never image bytes, tensors, landmarks, predictions tied to time-of-day, or personally identifying data.
- **SEC-06:** Release builds disable debug-only performance overlays and verbose inference logging.
- **SEC-07:** Inspect the merged release manifest and built APK permissions in CI.
- **SEC-08:** Include third-party license notices for MediaPipe, ONNX Runtime, HSEmotion, both model artifacts, and any copied visual/font assets.
- **SEC-09:** Document that Android itself can take screenshots; the app's promise is that it does not capture or save frames.

## 10. Technical boundaries

### 10.1 Required stack

- Kotlin and Gradle Kotlin DSL.
- A single-activity Jetpack Compose UI.
- Camera2 `TextureView` preview and bounded `ImageReader` analysis.
- MediaPipe Tasks Vision Face Landmarker.
- ONNX Runtime Android.
- Kotlin coroutines/flows for lifecycle-aware background work and UI state.
- Android string resources for English and Italian.

Dependency versions must be pinned in a version catalog and committed with Gradle dependency locking or verification metadata. Do not use `latest.release` in the production build.

### 10.2 Module boundaries

- `camera/`: camera selection, rotation, frame delivery, and lifecycle.
- `ml/`: model loading, crop, preprocessing, ONNX execution, softmax, smoothing.
- `domain/`: labels, session state, demo signal generation, Dog Mode debounce.
- `ui/`: Compose screen, charts, controls, localization, accessibility.
- `assets/` and `res/`: verified models, dog images, and localized resources.

The UI must not call ONNX Runtime or Camera2 directly. Camera and inference components must not hold references to activities or composables.

## 11. Performance and reliability budgets

The exact target tablet is a prerequisite for release qualification. On that device:

- **PERF-01:** Preview remains visually smooth at a measured median of at least 24 FPS while inference runs.
- **PERF-02:** Detected-face predictions update at least 1.5 times per second over a two-minute test.
- **PERF-03:** P95 frame-to-result latency is no more than 750 ms after warm-up.
- **PERF-04:** First live prediction appears within 5 seconds after tapping **Start camera**, excluding time spent in the Android permission dialog.
- **PERF-05:** A 20-minute live session produces no ANR, crash, runaway queue, or camera freeze.
- **PERF-06:** Record APK size, peak memory, CPU load, and device temperature delta in the release report. A per-ABI arm64 APK above 120 MB or peak PSS above 450 MB requires explicit review rather than silent acceptance.
- **PERF-07:** Repeatedly starting and stopping the camera 20 times does not leak camera or inference instances.

These are release gates for the named target tablet, not performance promises for every API-24 Android device.

## 12. Error handling

| Failure | Required behavior |
| --- | --- |
| Camera permission denied | Localized explanation; demo remains usable |
| Permission permanently denied | Open-settings action; no repeated prompt loop |
| Front camera missing/in use | Localized unavailable state; retry and demo actions |
| Model missing/checksum mismatch at build | Fail the build |
| Model cannot initialize at runtime | Model-error state; retry once on user action; demo usable |
| Inference throws | Close frame, stop live session, release camera, show model error |
| No face | Finding-face state; no history update; clear stale result after 1.5 s |
| App backgrounded | Stop camera, sound, analyzer, and pending Dog Mode transition |
| Low-memory process recreation | Restore language only; return with camera off |

Errors must not expose file paths, stack traces, or raw exception messages in the release UI.

## 13. Build, distribution, rollout, and rollback

### 13.1 Build outputs

- Debug APK for development and performance diagnostics.
- Signed arm64 release APK for the target tablet.
- Optional universal APK for compatibility testing, not the preferred offline delivery artifact.
- Release report containing dependency versions, model hashes, APK hash and size, target-device details, and acceptance-test results.

### 13.1.1 Local signing identity

The release signing key stays outside the repository and is referenced through
the ignored local `keystore.properties` file. Keep the key and its password
backed up securely, and use the same key for future app updates. Never commit
the key or its password.

### 13.2 Distribution

First release is sideloaded by USB or SD card. Installation may require enabling the Android setting that permits installs from the chosen file manager. No Play services dependency is allowed.

### 13.3 Rollout

1. Install debug build on the named tablet.
2. Complete functional and performance qualification.
3. Produce signed release candidate.
4. Verify release candidate in airplane mode after force-stop and device reboot.
5. Archive the signed APK, checksums, signing-key procedure, and release report.

### 13.4 Rollback

Keep the previous signed APK and its release report. Android normally blocks installing a lower version code over a newer one, so rollback requires either a forward-versioned rebuild of the previous source or uninstall/reinstall. Since only language is persisted, uninstalling loses no substantive user data. The signing key must remain stable across releases.

## 14. Testing and verification

### 14.1 JVM unit tests

- Softmax stability and seven-label order.
- Rejection of output tensors with an unexpected shape.
- Crop expansion and boundary clamping.
- RGB conversion, resize, normalization, and NCHW layout using a golden input.
- EMA smoothing and reset behavior.
- No-face timeout behavior.
- Dog Mode three-result debounce.
- Demo sequence determinism.
- State transition idempotency.
- English/Italian resource-key parity.

### 14.2 Instrumented tests

- Both packaged models initialize without network access.
- A licensed or synthetic golden face fixture produces the expected top label and probabilities within an agreed tolerance of the Python reference.
- Camera permission grant, single denial, permanent denial, and settings-return flows.
- Start/stop/background/foreground resource release.
- Rotation and front-camera overlay alignment in portrait and landscape.
- Dog Mode thumbnail visibility rules.
- Immediate language switching and persistence after process recreation.
- No hardcoded visible English in Italian mode.

### 14.3 Release checks

- Run the full app acceptance suite on a physical target tablet.
- Run with airplane mode enabled before process start.
- Reboot the tablet while offline and repeat cold start.
- Inspect merged manifest and APK permissions; assert that `INTERNET` is absent.
- Capture outbound traffic during the full flow and verify no connection attempts.
- Verify the APK contains both model files and all seven dog assets.
- Verify third-party notices and model manifest are packaged.
- Run the 20-minute thermal/reliability test and the 20-cycle start/stop test.

## 15. Acceptance criteria

The Android release is accepted only when all criteria below pass on the declared target tablet.

- **AC-01 Offline:** After installation, reboot, airplane mode, and force-stop, the app launches and completes live inference without Wi-Fi, mobile data, Flask, or another device.
- **AC-02 Permissions:** The built APK requests camera permission only; `INTERNET` and all unrelated sensitive permissions are absent.
- **AC-03 Models:** The packaged model sizes and SHA-256 hashes match §8.3, and the app performs no model download.
- **AC-04 Camera:** Camera starts only after an explicit tap, uses the front camera, displays a mirrored preview, and releases it on stop/background/error.
- **AC-05 Inference parity:** Golden preprocessing tensors match the Python reference within `1e-5`; softmax ordering is exact; golden fixture probabilities are within `±0.02` per class and have the same top label.
- **AC-06 UI:** All seven labels/scores, confidence, live status, sample count, history, disclaimer, demo, sound, Dog Mode, and camera controls are present and functional.
- **AC-07 Dog Mode:** The dog changes after three consecutive results for a new label, never flashes between loads, and shows a live upper-left source thumbnail only during live camera use.
- **AC-08 Localization:** Every visible and accessibility string has English and Italian values; toggling is immediate; selected language survives process restart.
- **AC-09 Privacy:** No frame or prediction history is written to persistent storage, logs, or media collections, and traffic capture shows no outbound attempt.
- **AC-10 Failure paths:** Denied permission, permanently denied permission, missing front camera, no face, model failure, backgrounding, and process recreation produce the specified safe states without crash or stale live claims.
- **AC-11 Performance:** All seven performance gates in §11 pass and are recorded in the release report.
- **AC-12 Accessibility:** Controls meet minimum target size, have localized semantics, work with TalkBack navigation, and do not announce every prediction.
- **AC-13 Reproducibility:** Release report records source revision, dependency versions, model hashes, APK hash, APK size, signing identity, and target-device build information.
- **AC-14 Notices:** Required third-party licenses and model attribution are viewable from an in-app **About** panel and included with the release artifact.

## 16. Design review — issues found

The original proposal was reviewed skeptically against the Flask implementation and the offline objective. The architecture was sound, but the following gaps would have caused ambiguity or release risk.

| # | Issue found | Risk | Resolution incorporated into revised PRD |
| ---: | --- | --- | --- |
| 1 | Feature parity was undefined; demo, sound, history, status states, and accessibility were omitted. | Android implementation could silently lose existing behavior. | §5 and §6 define the supported entry points and complete feature set. |
| 2 | No explicit camera/lifecycle state machine. | Duplicate camera sessions, background capture, or confusing permission behavior. | §7 and FR-CAM requirements define states, idempotency, and foreground rules. |
| 3 | Preview mirroring, sensor rotation, crop coordinates, and overlay transforms were not separated. | Incorrect crop or mirrored face box on tablets and rotation. | FR-CAM-02 and §8.1 define coordinate spaces and transformations. |
| 4 | Model URLs in the Flask code are mutable and the proposal did not pin artifacts. | Non-reproducible builds and silent model changes. | §8.3 pins exact bytes and SHA-256 and requires a verified artifact manifest. |
| 5 | Runtime offline behavior was stated but not technically enforced. | A transitive SDK or manifest merge could add network access. | §9 and release checks ban network SDKs and inspect the merged APK permissions and traffic. |
| 6 | Model and dependency licenses were not part of delivery. | APK redistribution could omit required notices. | SEC-08 and AC-14 require an About panel and release notices. |
| 7 | No-face behavior could leave an old expression looking current. | Misleading UI and unsafe product claim. | FR-TIME-05/06 stop history and clear stale confidence after 1.5 seconds. |
| 8 | Dog switching behavior was described but not specified. | Flashing/regression of the issue already fixed in the web app. | FR-TIME-07 and AC-07 define three-result stability and preloaded local assets. |
| 9 | Persistence was unspecified. | Hidden state and surprising camera/sound restarts. | §8.2 persists language only and resets all session state. |
| 10 | Error and permission flows were incomplete. | Prompt loops, raw exceptions, or camera resources left open. | §5.4 and §12 define recovery and resource release. |
| 11 | Performance was to be “benchmarked” without pass/fail thresholds. | A technically functional but unusably slow release could ship. | §11 introduces measurable latency, frequency, stability, and size review gates. |
| 12 | No semantic parity test with Python preprocessing/model output. | The native port could run but classify differently due to color, layout, or label bugs. | §14 and AC-05 add golden tensor and model-output parity tests. |
| 13 | Multi-face and missing-front-camera behavior were not bounded. | Unpredictable target selection or surprise rear-camera use. | Non-goals, FR-ML-02, and FR-CAM-08 explicitly support one face and no automatic rear fallback. |
| 14 | Rollout, signing, rollback, and physical offline verification were absent. | Unrecoverable or unverifiable sideloaded builds. | §13 defines release artifacts, rollout, signing continuity, and rollback. |
| 15 | “Frames are never saved” could overpromise around OS screenshots. | Inaccurate privacy language. | SEC-09 narrows the promise to app-controlled capture and storage. |
| 16 | Live-stream inference did not define ownership of asynchronous camera buffers. | Use-after-close bugs, corrupted crops, camera stalls, or unbounded bitmap retention. | FR-CAM-09 and FR-ML-08 require an owned, timestamp-matched frame and prompt `Image` closure. |

## 17. Revised PRD decisions

The contract above is the revised PRD. The review produced these material changes from the proposal:

1. The first Android release includes camera mode, demo mode, Dog Mode, source thumbnail, score grid, confidence, history, sound, and EN/IT parity.
2. Runtime state, no-face expiry, permission handling, and lifecycle release behavior are deterministic.
3. Model preprocessing and label order are specified numerically and verified against Python.
4. Models are checksum-pinned and bundled; mutable downloads are forbidden.
5. Offline behavior is enforced through manifest inspection and traffic verification, not only manual airplane-mode testing.
6. Performance has target-device release gates.
7. Privacy, accessibility, third-party notices, sideloading, signing, and rollback are release requirements.

The Android application code is implemented under [`src/mood-mirror-android/`](../../src/mood-mirror-android/). Debug and unsigned arm64 release variants assemble, model hashes are verified during `preBuild`, unit tests cover label order and smoothing, and the merged APK has been checked for network permissions. Physical tablet qualification, golden model-parity capture, long-session performance measurements, and the final password-backed signed build remain release gates rather than unimplemented product scope.

## 18. Open prerequisites and deferred decisions

The supplied tablet, installed Android build, ABI, application ID, and local signing identity resolve the device-selection, OS-version, release-architecture, package-identity, and signing prerequisites. No product decision is currently blocking Android project scaffolding.

Before producing a distributable signed build, verify that the password backup is available and populate the ignored `keystore.properties` file from `keystore.properties.example`; the Gradle release signing configuration is already wired without copying the key into the repository.

Narrow defaults unless changed before implementation:

- Minimum SDK: 24.
- Primary ABI: `arm64-v8a`, verified with `adb shell getprop ro.product.cpu.abilist` on the qualification tablet.
- Distribution: signed sideloaded APK.
- Orientation: responsive portrait and landscape.
- Sound: off by default and not persisted.
- Language: English default; English/Italian selection persisted.
- Camera: front-facing only; no silent rear-camera fallback.
