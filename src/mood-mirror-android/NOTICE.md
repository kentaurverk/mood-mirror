# Mood Mirror Android notices

The repository-wide third-party summary is in
[`../../THIRD-PARTY-NOTICES.md`](../../THIRD-PARTY-NOTICES.md). The build copies
this file, that summary, and the repository's `licenses/` directory into APK
assets so the notices remain available offline without a separately maintained
copy. In the APK, the summary is `THIRD-PARTY-NOTICES.md` beside this file.

Mood Mirror Android includes or links the following open-source components:

- AndroidX, Jetpack Compose, Camera2, Kotlin, and Material components — Apache License 2.0.
- MediaPipe Tasks Vision (`com.google.mediapipe:tasks-vision`) — Apache License 2.0.
- ONNX Runtime Android (`com.microsoft.onnxruntime:onnxruntime-android`) — MIT License.
- HSEmotion `enet_b2_7.onnx` — model source:
  https://github.com/HSE-asavchenko/face-emotion-recognition.

The face-landmarker task is from Google's MediaPipe model bundle. Dog Mode
images are original Mood Mirror project-generated presentation assets.

The bundled `licenses/` directory contains the MediaPipe license (including
Apache-2.0) and ONNX Runtime MIT license. The in-app About panel identifies the
components and explains the offline privacy behavior.
