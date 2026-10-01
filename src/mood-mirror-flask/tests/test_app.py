import io

import cv2
import numpy as np

from app import app


def test_index_and_health_routes():
    client = app.test_client()
    page = client.get("/")
    assert page.status_code == 200
    assert b"DOG MODE" in page.data
    assert b"images/dog/neutral.png" in page.data
    assert b"YOUR FACE / SOURCE" in page.data
    assert b"languageButton" in page.data
    assert b"Passa all'italiano" in page.data
    health = client.get("/api/health")
    assert health.status_code == 200
    assert health.get_json()["ok"] is True


def test_analyze_requires_a_frame():
    response = app.test_client().post("/api/analyze")
    assert response.status_code == 400
    assert "required" in response.get_json()["error"]


def test_dog_mode_assets_are_served():
    client = app.test_client()
    for label in ("happy", "sad", "neutral", "angry", "disgusted", "fearful", "surprised"):
        response = client.get(f"/static/images/dog/{label}.png")
        assert response.status_code == 200
        assert response.content_type == "image/png"


def test_analyze_rejects_invalid_image():
    response = app.test_client().post(
        "/api/analyze",
        data={"frame": (io.BytesIO(b"not an image"), "frame.jpg")},
    )
    assert response.status_code == 400


def test_analyze_blank_frame_finds_no_face():
    blank = np.zeros((120, 160, 3), dtype=np.uint8)
    ok, encoded = cv2.imencode(".jpg", blank)
    assert ok
    response = app.test_client().post(
        "/api/analyze",
        data={"frame": (io.BytesIO(encoded.tobytes()), "frame.jpg")},
    )
    assert response.status_code == 200
    assert response.get_json()["detected"] is False
