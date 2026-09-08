"""F007 DetectionService 공개 import가 패키지 전환 후에도 유지되는지 검증한다."""

from app.services.detection import DetectionService


def test_detection_service_remains_public_from_existing_import_path() -> None:
    assert DetectionService.__name__ == "DetectionService"
