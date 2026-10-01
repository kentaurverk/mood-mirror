# Mood Mirror

Mood Mirror is a private, local expression visualizer. It estimates visible
facial-expression cues — happy, surprised, sad, angry, disgusted, fearful, and
neutral — rather than a person's inner mood, intent, or mental health.

There are two implementations:

```text
src/
├── mood-mirror-flask/    Desktop/browser prototype
└── mood-mirror-android/  Native offline Android app
```

Both implementations use the same HSEmotion `enet_b2_7.onnx` classifier and
MediaPipe face-landmarker asset. The runtimes and camera pipelines differ:
Flask sends browser-captured JPEG frames to a local Python process, while
Android performs inference inside the APK.

## Event context

Mood Mirror was developed for **SHARPER Cagliari 2026**, the European
Researchers’ Night, as part of [FUORI GIOCO. L’arte di cambiare le
regole](https://www.sharper-night.it/evento/fuori-gioco-larte-di-cambiare-le-regole/).
It supported the interactive empathy activity **“Cambiamo prospettiva? Dalla
soggettività all’intersoggettività”**, including the webcam activity **“Check
your Emotion!”**.

- [SHARPER at the University of Cagliari](https://www.unica.it/it/societa-e-territorio/public-engagement/notte-europea-delle-ricercatrici-e-dei-ricercatori/sharper)
- [SHARPER Cagliari](https://www.sharper-night.it/cagliari/)
- [FUORI GIOCO event page](https://www.sharper-night.it/evento/fuori-gioco-larte-di-cambiare-le-regole/)

## Flask desktop version

Python 3.10–3.12 is recommended.

### Linux or macOS

```bash
python3 scripts/download_models.py
cd src/mood-mirror-flask
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
python app.py
```

### Windows PowerShell

```powershell
py -3.12 scripts\download_models.py
cd .\src\mood-mirror-flask
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
python app.py
```

If PowerShell blocks activation, use
`Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass` for the current
terminal, or run the virtual environment interpreter directly.

Open <http://127.0.0.1:5000>, choose **Start camera**, and allow browser camera
access. Camera access is intentionally limited to localhost by the Flask
server. The included Flask server runs in debug mode for local development;
do not expose it to an untrusted network. See
[`src/mood-mirror-flask/README.md`](src/mood-mirror-flask/README.md)
for model, privacy, dog-mode, and troubleshooting details.

Run the Flask tests from the implementation directory:

```bash
cd src/mood-mirror-flask
python -m pip install -r requirements-dev.txt
python -m pytest -q
```

Model binaries are not stored in Git. Run `python3 scripts/download_models.py`
from the repository root before starting Flask or building Android. The script
downloads the pinned files and verifies their sizes and SHA-256 hashes. Internet
is needed for this setup step; after it completes, both apps can run offline.

## Android offline version

The Android app is designed for the Samsung Galaxy Tab S6 Lite SM-P613 and
works after installation without Flask, Wi-Fi, internet access, or a model
download. It uses Camera2, MediaPipe Tasks Vision, and ONNX Runtime Android.

Read [`src/mood-mirror-android/ANDROID-SOLUTION.md`](src/mood-mirror-android/ANDROID-SOLUTION.md)
for SDK/JDK setup, model verification, debug installation, release signing,
offline acceptance checks, and device qualification details.

From `src/mood-mirror-android/`:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows PowerShell, use `gradlew.bat` instead of `./gradlew`. Run
`py -3.12 ..\..\scripts\download_models.py` from the Android project directory
before building if the models have not already been downloaded.

The Android build consumes the verified model files from the sibling Flask
`models/` directory and fails closed if they are absent or changed. The
manifest records their source URLs, expected sizes, and SHA-256 hashes.

Release signing is deliberately local-only. The repository contains only
`keystore.properties.example`; never commit the real `keystore.properties`, a
`.p12`/`.jks`/`.keystore`/key file, or its password. Without that ignored local
configuration, Gradle produces an unsigned release APK. These ignore rules
apply throughout this project, including nested folders. Store the release key
outside the checkout and keep its password in your password manager; preserve
both for future app updates. Debug builds do not require the release key.

## Privacy and limitations

Camera frames are processed in memory. The Android app has no `INTERNET`
permission; the Flask version binds to `127.0.0.1` by default. Results are
playful estimates of visible expression cues and should not be treated as
psychological, medical, or factual mood measurements.

## License

Original Mood Mirror source code is released under the MIT License in
[`LICENSE`](LICENSE). Third-party libraries and model artifacts retain their
own terms; the dog images are original project-generated assets. See
[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) and the Android
[`NOTICE.md`](src/mood-mirror-android/NOTICE.md).
