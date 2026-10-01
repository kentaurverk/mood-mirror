#!/usr/bin/env python3
"""Download the pinned Mood Mirror models into the local Flask models folder."""

from __future__ import annotations

import hashlib
import os
import sys
import tempfile
from pathlib import Path
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
MODEL_DIR = ROOT / "src" / "mood-mirror-flask" / "models"
CHUNK_SIZE = 1024 * 1024
MODELS = (
    (
        "face_landmarker.task",
        "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task",
        3_758_596,
        "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff",
    ),
    (
        "enet_b2_7.onnx",
        "https://raw.githubusercontent.com/sb-ai-lab/EmotiEffLib/af833487321c3efdcb1768a91a6c656a1986fdf6/models/affectnet_emotions/onnx/enet_b2_7.onnx",
        30_774_088,
        "7863032d8c0c0bc259aa0dd7b8ebf6b607af454e7a0ea82be2b37767914ecd0f",
    ),
)


def digest_file(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as model_file:
        while chunk := model_file.read(CHUNK_SIZE):
            size += len(chunk)
            digest.update(chunk)
    return size, digest.hexdigest()


def download_one(name: str, url: str, expected_size: int, expected_hash: str) -> None:
    destination = MODEL_DIR / name
    if destination.is_file():
        size, digest = digest_file(destination)
        if size == expected_size and digest == expected_hash:
            print(f"Already verified: {destination.relative_to(ROOT)}")
            return
        raise RuntimeError(
            f"Existing file failed verification: {destination}\n"
            "Move or remove it, then run this script again."
        )

    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        request = Request(url, headers={"User-Agent": "MoodMirror-model-bootstrap/1.0"})
        with urlopen(request, timeout=60) as response:
            with tempfile.NamedTemporaryFile(
                mode="wb", dir=MODEL_DIR, prefix=f"{name}.", suffix=".download", delete=False
            ) as temporary_file:
                temporary_path = Path(temporary_file.name)
                digest = hashlib.sha256()
                size = 0
                while chunk := response.read(CHUNK_SIZE):
                    size += len(chunk)
                    if size > expected_size:
                        raise RuntimeError(f"Download exceeded expected size for {name}")
                    digest.update(chunk)
                    temporary_file.write(chunk)

        actual_hash = digest.hexdigest()
        if size != expected_size or actual_hash != expected_hash:
            raise RuntimeError(
                f"Verification failed for {name}: expected {expected_size} bytes and "
                f"SHA-256 {expected_hash}; received {size} bytes and SHA-256 {actual_hash}"
            )

        os.replace(temporary_path, destination)
        temporary_path = None
        print(f"Downloaded and verified: {destination.relative_to(ROOT)}")
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def main() -> int:
    try:
        for model in MODELS:
            download_one(*model)
    except Exception as error:
        print(f"Model setup failed: {error}", file=sys.stderr)
        return 1
    print("All model files are ready. They are stored locally and excluded from Git.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
