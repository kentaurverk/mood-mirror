# Offline model manifest

Model binaries are downloaded to the ignored Flask `models/` directory with `python scripts/download_models.py` from the repository root. The script checks size and SHA-256 before installing each file. The Android build checks both hashes again during `preBuild` and fails closed if either file is missing or changed.

| Asset | Bytes | SHA-256 | Upstream source |
| --- | ---: | --- | --- |
| `../mood-mirror-flask/models/face_landmarker.task` | 3,758,596 | `64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff` | [MediaPipe model version 1](https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task) |
| `../mood-mirror-flask/models/enet_b2_7.onnx` | 30,774,088 | `7863032d8c0c0bc259aa0dd7b8ebf6b607af454e7a0ea82be2b37767914ecd0f` | [Pinned EmotiEffLib source commit](https://github.com/sb-ai-lab/EmotiEffLib/tree/af833487321c3efdcb1768a91a6c656a1986fdf6) |

Dog images are also copied from `../mood-mirror-flask/static/images/dog/` into APK assets. They are local presentation assets, not downloaded at runtime.
