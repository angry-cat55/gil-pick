"""배포 health endpoint 검증."""

from fastapi.testclient import TestClient

from app.main import create_app


def test_health_endpoint() -> None:
    response = TestClient(create_app()).get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
