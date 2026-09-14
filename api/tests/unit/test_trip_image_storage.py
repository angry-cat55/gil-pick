"""여행 대표 이미지 로컬 저장소 단위 테스트."""

import uuid
from io import BytesIO

import pytest
from fastapi import UploadFile

from app.api.errors import AppError
from app.services.trip_image import MAX_IMAGE_BYTES, TripImageStorage


@pytest.mark.asyncio
async def test_save_replaces_previous_format_atomically(tmp_path) -> None:
    """새 형식을 저장하면 같은 여행의 이전 파일을 정리한다."""
    storage = TripImageStorage(tmp_path)
    trip_id = uuid.uuid4()
    await storage.save(trip_id, UploadFile(file=BytesIO(b"\xff\xd8\xffjpeg")))
    await storage.save(
        trip_id,
        UploadFile(file=BytesIO(b"\x89PNG\r\n\x1a\ncontent")),
    )

    stored = storage.find(trip_id)

    assert stored is not None
    assert stored[0].suffix == ".png"
    assert stored[1] == "image/png"
    assert len(list(tmp_path.iterdir())) == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("code", ["UNSUPPORTED_IMAGE_TYPE", "IMAGE_TOO_LARGE"])
async def test_save_rejects_invalid_image_and_cleans_temporary_file(
    tmp_path,
    code: str,
) -> None:
    """검증 실패 파일과 임시 파일을 남기지 않는다."""
    storage = TripImageStorage(tmp_path)
    payload = (
        b"not-image"
        if code == "UNSUPPORTED_IMAGE_TYPE"
        else b"\x89PNG\r\n\x1a\n" + b"x" * MAX_IMAGE_BYTES
    )

    with pytest.raises(AppError) as error:
        await storage.save(uuid.uuid4(), UploadFile(file=BytesIO(payload)))

    assert error.value.code == code
    assert not list(tmp_path.iterdir())
