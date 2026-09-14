"""여행 대표 이미지를 로컬 영속 volume에 저장한다."""

from __future__ import annotations

import os
import uuid
from pathlib import Path

from fastapi import UploadFile

from app.api.errors import AppError

MAX_IMAGE_BYTES = 5 * 1024 * 1024
IMAGE_SIGNATURES = {
    "jpg": lambda data: data.startswith(b"\xff\xd8\xff"),
    "png": lambda data: data.startswith(b"\x89PNG\r\n\x1a\n"),
    "webp": lambda data: data.startswith(b"RIFF") and data[8:12] == b"WEBP",
}
IMAGE_MEDIA_TYPES = {"jpg": "image/jpeg", "png": "image/png", "webp": "image/webp"}


class TripImageStorage:
    """단일 인스턴스용 로컬 여행 이미지 저장소."""

    def __init__(self, root: Path) -> None:
        self.root = root

    async def save(self, trip_id: uuid.UUID, upload: UploadFile) -> None:
        """검증한 이미지를 임시 파일에 쓴 뒤 원자적으로 교체한다."""
        first = await upload.read(16)
        extension = next(
            (name for name, matches in IMAGE_SIGNATURES.items() if matches(first)), None
        )
        if extension is None:
            raise AppError(415, "UNSUPPORTED_IMAGE_TYPE", "jpeg, png, webp 이미지만 업로드할 수 있습니다.")

        self.root.mkdir(parents=True, exist_ok=True)
        temporary = self.root / f".{trip_id}.{uuid.uuid4().hex}.tmp"
        size = len(first)
        try:
            with temporary.open("wb") as output:
                output.write(first)
                while chunk := await upload.read(64 * 1024):
                    size += len(chunk)
                    if size > MAX_IMAGE_BYTES:
                        raise AppError(413, "IMAGE_TOO_LARGE", "이미지는 5MB 이하여야 합니다.")
                    output.write(chunk)
            target = self.root / f"{trip_id}.{extension}"
            os.replace(temporary, target)
            for old in self.root.glob(f"{trip_id}.*"):
                if old != target:
                    old.unlink(missing_ok=True)
        finally:
            temporary.unlink(missing_ok=True)

    def find(self, trip_id: uuid.UUID) -> tuple[Path, str] | None:
        """저장된 이미지 경로와 media type을 반환한다."""
        for extension, media_type in IMAGE_MEDIA_TYPES.items():
            path = self.root / f"{trip_id}.{extension}"
            if path.is_file():
                return path, media_type
        return None

    def delete(self, trip_id: uuid.UUID) -> None:
        """여행의 기존 대표 이미지 파일을 제거한다."""
        for path in self.root.glob(f"{trip_id}.*"):
            path.unlink(missing_ok=True)


# ponytail: 단일 API 인스턴스의 mounted volume 전용이다. 수평 확장 시 object storage adapter로 교체한다.
