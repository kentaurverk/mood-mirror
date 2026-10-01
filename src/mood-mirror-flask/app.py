from __future__ import annotations

from pathlib import Path

import cv2
import numpy as np
from flask import Flask, jsonify, render_template, request

from expression import ExpressionEngine


BASE_DIR = Path(__file__).resolve().parent

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 3 * 1024 * 1024
engine = ExpressionEngine(
    BASE_DIR / "models" / "face_landmarker.task",
    BASE_DIR / "models" / "enet_b2_7.onnx",
)


@app.get("/")
def index():
    return render_template("index.html")


@app.get("/api/health")
def health():
    return jsonify(
        {
            "ok": True,
            "model": "HSEmotion enet_b2_7",
            "model_ready": engine.emotion_model_path.exists(),
        }
    )


@app.post("/api/analyze")
def analyze():
    image = request.files.get("frame")
    if image is None:
        return jsonify({"error": "A JPEG frame is required."}), 400

    encoded = np.frombuffer(image.read(), dtype=np.uint8)
    frame = cv2.imdecode(encoded, cv2.IMREAD_COLOR)
    if frame is None:
        return jsonify({"error": "The frame could not be decoded."}), 400

    # Bound compute cost even if a client sends a very large image.
    height, width = frame.shape[:2]
    if width > 960:
        ratio = 960 / width
        frame = cv2.resize(frame, (960, round(height * ratio)))

    try:
        result = engine.analyze_bgr(frame)
    except Exception:
        app.logger.exception("Expression engine failed")
        return jsonify({"error": "The expression engine could not start. Check the server log."}), 503
    if not result.detected:
        return jsonify({"detected": False, "scores": result.scores})

    label = max(result.scores, key=result.scores.get)
    return jsonify(
        {
            "detected": True,
            "label": label,
            "confidence": result.scores[label],
            "scores": result.scores,
            "bounds": result.bounds,
        }
    )


@app.errorhandler(413)
def too_large(_error):
    return jsonify({"error": "Frame is too large."}), 413


if __name__ == "__main__":
    app.run(host="127.0.0.1", port=5000, debug=True)
