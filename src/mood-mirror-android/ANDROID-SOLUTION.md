# Mood Mirror Android — offline solution proposal

> **Planning status:** Reviewed, revised, and implemented as the native Kotlin project in this folder. The authoritative scope and acceptance criteria remain in the [Android offline app PRD](../../docs/prds/android-offline-app.md).

**Qualification device:** Samsung Galaxy Tab S6 Lite Wi‑Fi, model **SM‑P613**, Android 14 / One UI 6.1, build **P613XXS8CYE1 / P613OXM8CYE1**, security patch **2025-05-01**, verified ABI `arm64-v8a`. **Application ID / namespace:** `com.kentaurverk.moodmirror`. The device-specific performance gates are defined in the PRD; the serial number is deliberately not stored here.

## Goal

Create a standalone Android tablet app that continues to work in airplane mode. After the APK is installed, the app must not need Flask, a laptop, Wi-Fi, an internet connection, or a model download.

The current browser/Flask app remains the desktop version in `../mood-mirror-flask/`. This Android folder is the design and implementation home for the native client.

## Current implementation

The project now contains a working Compose application with a direct Camera2 controller, MediaPipe Face Landmarker, ONNX Runtime, local dog assets, English/Italian resources, demo mode, smoothing, sound cues, and optional local release signing. Camera2 is intentional: the SM-P613 exposes extra virtual camera IDs that can stall CameraX provider initialization. The connected SM-P613 has been verified to open its real front sensor (camera ID 1) and deliver a live preview without a network.

The verified model files are checked by the Gradle `preBuild` task; see [MODEL-MANIFEST.md](MODEL-MANIFEST.md). Runtime model inference never calls the Flask `/api/analyze` endpoint.

### Build and install

Gradle needs a full Android SDK (not only `adb`) and a full JDK containing `javac` and `jlink`. If Android Studio installed the SDK in the usual location, set it explicitly:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
test -x "$ANDROID_HOME/platform-tools/adb"
test -f "$ANDROID_HOME/platforms/android-35/android.jar"
```

If the SDK is installed elsewhere, copy `local.properties.example` to `local.properties` and set its `sdk.dir` path instead. Install the required packages once with Android Studio's SDK Manager or `sdkmanager`: `platform-tools`, `platforms;android-35`, and `build-tools;35.0.0`. Use JDK 17 or newer; on Ubuntu/Debian, `sudo apt install openjdk-17-jdk` is the simplest full-JDK setup.

Download the verified model files once from the repository root:

```bash
python3 scripts/download_models.py
```

Then, from this directory, after the SDK and JDK are available:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows PowerShell, use the Gradle wrapper batch file:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

For a signed release, create `keystore.properties` from `keystore.properties.example`, fill in the password you chose for the external `.p12` key, and run:

```bash
./gradlew assembleRelease
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```

The signing properties and key are ignored and must remain outside source
control. When `keystore.properties` is absent, Gradle produces
`app/build/outputs/apk/release/app-release-unsigned.apk`; with the local
signing configuration it produces `app-release.apk`. The release build is
intentionally unsigned by default so local compilation is possible without
exposing a password.

Keep the key outside this checkout and back it up together with its password.
Relative `storeFile` paths resolve from this Android project directory. On
Windows, use forward slashes in the properties file, for example
`C:/Users/you/keys/mood-mirror-release.p12`. Ignore rules cover signing files
throughout the project; only `keystore.properties.example` belongs in Git.

## Recommended architecture

```text
Camera2 preview (real front sensor)
        |
        v
Camera2 ImageReader (latest owned frame)
        |
        v
MediaPipe Face Landmarker (live stream)
        |
        v
Face crop + RGB resize + ImageNet normalization
        |
        v
HSEmotion enet_b2_7.onnx via ONNX Runtime Android
        |
        v
Temporal smoothing -> mood readout -> dog image / UI
```

All inference happens inside the Android process. No frame is posted to `/api/analyze`.

## Why native Android

The existing app sends JPEG frames to Python, so a PWA or WebView wrapper would still require a reachable server unless inference were rewritten for the browser. A native app removes that dependency and gives us direct control over camera lifecycle, frame back-pressure, model loading, and tablet performance.

Google's MediaPipe Android setup currently requires Android SDK 24 or higher and provides a `tasks-vision` dependency. Face Landmarker supports live-stream input, asynchronous result callbacks, and tracking between frames:

- [MediaPipe Android setup](https://developers.google.com/edge/mediapipe/solutions/setup_android)
- [MediaPipe Face Landmarker for Android](https://developers.google.com/edge/mediapipe/solutions/vision/face_landmarker/android)

The tablet's Camera2 HAL exposes multiple virtual IDs and CameraX can stall while enumerating them. The native controller selects the verified front sensor directly, uses a bounded `ImageReader`, and drops frames while inference is busy so the preview remains live. The app does not open a second camera for Dog Mode.

- [Camera2 API](https://developer.android.com/reference/android/hardware/camera2/package-summary)

ONNX Runtime has an Android package and supports CPU, XNNPACK, and NNAPI execution paths. Start with CPU/XNNPACK for predictable behavior, then benchmark NNAPI on the target tablet:

- [ONNX Runtime mobile deployment](https://onnxruntime.ai/docs/tutorials/mobile/)

## Assets bundled in the APK

The APK packages these files rather than downloading them at runtime. During
the current two-module development layout, Gradle copies the model files from
the sibling Flask `models/` directory and dog images from the Flask static
directory into the APK asset bundle:

| Asset | Current size | Purpose |
| --- | ---: | --- |
| `models/face_landmarker.task` | ~3.6 MB | Face detection and landmarks |
| `models/enet_b2_7.onnx` | ~30 MB | Seven-class expression classifier |
| `static/images/dog/*.png` | ~13 MB total | Dog Mode output images |

The current assets are roughly 47 MB before the Android runtime and UI
resources. APK size should be measured after each release; model compression
or Play Asset Delivery can be considered later, but the initial offline build
keeps the models inside the installable package.

The Android build fails closed when either model is missing or its SHA-256 does
not match [`MODEL-MANIFEST.md`](MODEL-MANIFEST.md). Model binaries are excluded
from Git and are provisioned by the root `scripts/download_models.py` script.
The script checks hashes before saving; the build verifies them again. Android
runtime inference never downloads models.

## Porting details

The Android inference code should preserve the Python pipeline:

1. Receive a camera frame from `ImageReader`.
2. Convert it to the color format expected by MediaPipe.
3. Run Face Landmarker and use the first face.
4. Apply the existing expanded landmark crop.
5. Convert the crop to RGB and resize it to `260 x 260`.
6. Divide by `255`, apply ImageNet mean/std normalization, and arrange the tensor as `NCHW`.
7. Run `enet_b2_7.onnx`.
8. Apply softmax using the existing model-label order.
9. Smooth the scores and update the UI on the main thread.

The Android implementation should use the same seven labels: happy, surprised, sad, angry, disgusted, fearful, and neutral. The labels describe visible expression cues, not a person's internal mood or mental state.

## Offline and privacy requirements

- Do not add the `INTERNET` permission.
- Request only `android.permission.CAMERA`.
- Never serialize camera frames to disk.
- Keep model files and dog images local to the APK.
- Do not include first-run downloads, remote configuration, analytics, or API calls.
- Keep the English/Italian toggle and selected-language preference local on the device.
- Provide a visible camera stop control and release the camera when the app leaves the foreground.

## Android project layout

```text
mood-mirror-android/
├── ANDROID-SOLUTION.md
├── NOTICE.md
├── settings.gradle.kts
├── build.gradle.kts
└── app/
    └── src/main/
        ├── java/.../
        │   ├── MainActivity.kt
        │   ├── camera/CameraController.kt
        │   ├── ml/ExpressionEngine.kt
        │   └── ui/MoodMirrorViewModel.kt
        └── res/
            ├── values/strings.xml
            └── values-it/strings.xml
```

Gradle prepares notices and license texts in
`app/build/generated/noticeAssets/`, then packages them alongside the models
and dog images from the Flask sibling. Edit the source notices, not generated
build files.

Jetpack Compose is a good fit for recreating the current dashboard, but the visual design should be treated as a port of the existing web UI rather than a requirement to share HTML/CSS.

## Delivery phases

1. Create the Android Studio project and verify camera permission on the target tablet.
2. Add bundled Face Landmarker and show a mirrored camera preview with a face box.
3. Port the crop and ONNX preprocessing, then display raw predictions.
4. Add temporal smoothing, confidence display, dog mode, and the camera thumbnail.
5. Add English/Italian resources and the local language toggle.
6. Test with airplane mode enabled, including cold start after force-stop.
7. Benchmark inference latency, memory, battery, and thermal behavior on the actual tablet.
8. Produce a signed APK for USB/SD-card installation, with Play Store packaging optional later.

## Acceptance test for the offline build

With airplane mode enabled and the app freshly force-stopped:

- The app launches without an error.
- The camera permission prompt appears and the preview starts.
- Mood predictions update without a network request.
- Dog Mode follows the predicted label.
- The Italian toggle changes both static and live labels.
- Stopping the camera releases the camera device.
- No network permission or server is required.
