# Third-party notices

The MIT license in [`LICENSE`](LICENSE) covers the original Mood Mirror source
code only. Bundled models, libraries, and presentation assets retain their
own terms.

## Runtime libraries

- MediaPipe Tasks Vision: Apache License 2.0 — <https://github.com/google-ai-edge/mediapipe>
- ONNX Runtime: MIT License — <https://github.com/microsoft/onnxruntime>
- Flask, NumPy, OpenCV, and the Python dependencies: each remains under its
  own upstream license as distributed by its respective project.
- AndroidX, Jetpack Compose, Kotlin, and Material components: Apache License
  2.0 or their respective upstream terms.

## Model artifacts

- `face_landmarker.task`: MediaPipe Face Landmarker model bundle downloaded
  from [Google's version 1 model endpoint](https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task).
- `enet_b2_7.onnx`: HSEmotion/EmotiEffLib model from
  [EmotiEffLib commit `af83348`](https://github.com/sb-ai-lab/EmotiEffLib/tree/af833487321c3efdcb1768a91a6c656a1986fdf6), formerly HSEmotion.
  Bundled runtime license texts are in [`licenses/`](licenses/).

The exact model bytes used by Android are recorded in
[`src/mood-mirror-android/MODEL-MANIFEST.md`](src/mood-mirror-android/MODEL-MANIFEST.md).

## Dog Mode images

The dog images under `src/mood-mirror-flask/static/images/dog/` are original
Mood Mirror project-generated presentation assets and may be redistributed
with the project.
