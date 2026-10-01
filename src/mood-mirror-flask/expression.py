"""Face detection plus trained facial-expression recognition.

MediaPipe locates a face. HSEmotion's EfficientNet-B2 model, trained for
facial-expression recognition, classifies the cropped face. The output still
describes visible expression—not a person's internal emotional state.
"""

from __future__ import annotations

from dataclasses import dataclass
from math import exp
from pathlib import Path
from threading import Lock


MODEL_LABELS = (
    "angry",
    "disgusted",
    "fearful",
    "happy",
    "neutral",
    "sad",
    "surprised",
)
LABELS = ("happy", "surprised", "sad", "angry", "disgusted", "fearful", "neutral")


def softmax_scores(logits) -> dict[str, float]:
    """Convert the HSEmotion logits to a stable, normalized label mapping."""
    values = [float(value) for value in logits]
    if len(values) != len(MODEL_LABELS):
        raise ValueError(f"Expected {len(MODEL_LABELS)} model outputs, received {len(values)}")
    peak = max(values)
    weights = [exp(value - peak) for value in values]
    total = sum(weights)
    by_model_order = dict(zip(MODEL_LABELS, (value / total for value in weights)))
    return {label: round(by_model_order[label], 4) for label in LABELS}


@dataclass(frozen=True)
class Analysis:
    detected: bool
    scores: dict[str, float]
    bounds: dict[str, float] | None = None


class ExpressionEngine:
    """Lazy, thread-safe face detector and HSEmotion ONNX wrapper."""

    INPUT_SIZE = 260

    def __init__(self, face_model_path: Path, emotion_model_path: Path) -> None:
        self.face_model_path = face_model_path
        self.emotion_model_path = emotion_model_path
        self._landmarker = None
        self._emotion_session = None
        self._lock = Lock()

    def _ensure_landmarker(self):
        if self._landmarker is not None:
            return self._landmarker

        if not self.face_model_path.is_file():
            raise FileNotFoundError(
                f"Missing {self.face_model_path.name}; run `python scripts/download_models.py` "
                "from the Mood Mirror repository root."
            )
        import mediapipe as mp

        options = mp.tasks.vision.FaceLandmarkerOptions(
            base_options=mp.tasks.BaseOptions(model_asset_path=str(self.face_model_path)),
            running_mode=mp.tasks.vision.RunningMode.IMAGE,
            num_faces=1,
            min_face_detection_confidence=0.55,
            min_face_presence_confidence=0.55,
        )
        self._landmarker = mp.tasks.vision.FaceLandmarker.create_from_options(options)
        return self._landmarker

    def _ensure_emotion_session(self):
        if self._emotion_session is not None:
            return self._emotion_session

        if not self.emotion_model_path.is_file():
            raise FileNotFoundError(
                f"Missing {self.emotion_model_path.name}; run `python scripts/download_models.py` "
                "from the Mood Mirror repository root."
            )
        import onnxruntime as ort

        self._emotion_session = ort.InferenceSession(
            str(self.emotion_model_path),
            providers=["CPUExecutionProvider"],
        )
        return self._emotion_session

    @staticmethod
    def _face_crop(frame, landmarks):
        height, width = frame.shape[:2]
        xs = [point.x for point in landmarks]
        ys = [point.y for point in landmarks]
        face_width = max(xs) - min(xs)
        face_height = max(ys) - min(ys)

        # The classifier was trained on full-face crops with some head context;
        # a tight landmark crop makes facial hair and isolated mouth cues noisy.
        left = max(0.0, min(xs) - face_width * 0.50)
        right = min(1.0, max(xs) + face_width * 0.50)
        top = max(0.0, min(ys) - face_height * 0.50)
        bottom = min(1.0, max(ys) + face_height * 0.35)

        x1, x2 = int(left * width), max(int(right * width), int(left * width) + 1)
        y1, y2 = int(top * height), max(int(bottom * height), int(top * height) + 1)
        bounds = {"x": left, "y": top, "width": right - left, "height": bottom - top}
        return frame[y1:y2, x1:x2], bounds

    def _predict(self, face_bgr) -> dict[str, float]:
        import cv2
        import numpy as np

        # HSEmotion was trained on ImageNet-normalized RGB face crops.
        face_rgb = cv2.cvtColor(face_bgr, cv2.COLOR_BGR2RGB)
        tensor = cv2.resize(face_rgb, (self.INPUT_SIZE, self.INPUT_SIZE)).astype(np.float32) / 255.0
        tensor = (tensor - np.array([0.485, 0.456, 0.406], dtype=np.float32)) / np.array(
            [0.229, 0.224, 0.225], dtype=np.float32
        )
        tensor = np.ascontiguousarray(tensor.transpose(2, 0, 1)[None, ...])
        logits = self._ensure_emotion_session().run(None, {"input": tensor})[0][0]
        return softmax_scores(logits)

    def analyze_bgr(self, frame) -> Analysis:
        import cv2
        import mediapipe as mp

        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)

        with self._lock:
            result = self._ensure_landmarker().detect(image)
            if not result.face_landmarks:
                return Analysis(False, {label: 0.0 for label in LABELS})

            face, bounds = self._face_crop(frame, result.face_landmarks[0])
            if face.size == 0:
                return Analysis(False, {label: 0.0 for label in LABELS})
            scores = self._predict(face)

        return Analysis(True, scores, bounds)
