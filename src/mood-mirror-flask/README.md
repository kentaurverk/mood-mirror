# Mood Mirror Flask

A local Flask/browser expression visualizer built with MediaPipe, HSEmotion,
ONNX Runtime, OpenCV, and a small browser UI. It estimates visible expression
cues such as happy, surprised, sad, angry, disgusted, fearful, and neutral.
It does not read a person's inner mood, intent, personality, or mental health.

## Run it

Python 3.10–3.12 is recommended.

### Linux or macOS

```bash
cd src/mood-mirror-flask
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
python app.py
```

### Windows PowerShell

```powershell
cd .\src\mood-mirror-flask
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
python app.py
```

If PowerShell reports that script execution is disabled, allow it only for
the current terminal with:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

Then activate the environment again. You can also run
`.\.venv\Scripts\python.exe app.py` without activating it.

Open <http://127.0.0.1:5000>, choose **Start camera**, and allow camera access.
Browsers permit camera access on localhost. Exposing the app to another device
requires HTTPS in the browser.

The model binaries are not stored in Git. From the repository root, run
`python scripts/download_models.py` before starting the server. The script
downloads the pinned files and verifies their sizes and SHA-256 hashes. Once
downloaded, Flask can analyze frames offline; it does not download models while
running. See [`../mood-mirror-android/MODEL-MANIFEST.md`](../mood-mirror-android/MODEL-MANIFEST.md)
for the artifact details.

## Tests

From this directory:

```bash
python -m pip install -r requirements-dev.txt
python -m pytest -q
```

The local `pytest.ini` keeps imports and test discovery correct whether the
project is cloned standalone or stored inside a larger workspace.

## Privacy and dog mode

The browser samples a compressed frame roughly twice per second and sends it
only to the local Flask process. Frames are analyzed in memory and are not
written to disk. **DOG MODE** replaces the visible human preview with the
matching local dog image while the camera continues to drive the classifier.

## Model details

MediaPipe Face Landmarker finds and crops the face. HSEmotion `enet_b2_7`, an
EfficientNet-B2 seven-class expression model, performs classification through
ONNX Runtime. The app applies softmax and temporal smoothing; there is no
hand-written expression formula.

See the repository [`THIRD-PARTY-NOTICES.md`](../../THIRD-PARTY-NOTICES.md)
before redistributing the bundled model files.
