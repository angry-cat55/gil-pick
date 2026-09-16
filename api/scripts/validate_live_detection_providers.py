"""실제 기상청·서울시 변수 공급자의 정규화 결과를 secret 없이 확인한다."""

from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone

from app.clients.kma import KmaClient
from app.clients.seoul_citydata import SeoulCityDataClient
from app.core.config import get_settings
from app.schemas.detection import CongestionVerdict, OperatingHoursVerdict, WeatherVerdict
from app.services.detection.policy import (
    CONGESTION_RISK_LEVEL,
    PRECIPITATION_MM_PER_HOUR_THRESHOLD,
    PRECIPITATION_PROBABILITY_THRESHOLD,
    VARIABLE_WEIGHTS,
    congestion_sensitivity,
)
from app.services.detection.scoring import score_variables
from app.services.detection.weather import evaluate_weather


async def main() -> None:
    settings = get_settings()
    print(
        {
            "kma_configured": bool(settings.kma_service_key.get_secret_value()),
            "seoul_configured": bool(
                settings.seoul_citydata_api_key.get_secret_value()
            ),
            "fcm_enabled": settings.fcm_enabled,
            "fcm_configured": bool(
                settings.fcm_project_id
                and settings.fcm_service_account_json.get_secret_value()
            ),
            "thresholds": {
                "precipitation_probability": PRECIPITATION_PROBABILITY_THRESHOLD,
                "precipitation_mm_per_hour": PRECIPITATION_MM_PER_HOUR_THRESHOLD,
            },
            "weights": VARIABLE_WEIGHTS,
        }
    )

    kma = KmaClient(settings)
    seoul = SeoulCityDataClient(settings)
    try:
        forecasts = await kma.get_forecast(37.5752, 126.9768)
        print(
            {
                "provider": "KMA",
                "slot_count": len(forecasts or []),
                "nearest": min(
                    forecasts or [],
                    key=lambda slot: abs(
                        slot.forecast_at
                        - datetime.now(timezone(timedelta(hours=9)))
                    ),
                    default=None,
                ),
            }
        )

        weather = await evaluate_weather(
            kma,
            category="NATURE",
            latitude=37.5752,
            longitude=126.9768,
            eta=datetime.now(timezone(timedelta(hours=9))) + timedelta(hours=1),
        )
        score = score_variables(
            congestion=CongestionVerdict(
                available=False, unavailable_reason="TIMEOUT"
            ),
            weather=weather,
            operating_hours=OperatingHoursVerdict(
                available=False, unavailable_reason="TIMEOUT"
            ),
        )
        print({"weather_verdict": weather, "weather_only_score": score})

        for area in ("광화문·덕수궁", "홍대 관광특구"):
            population = await seoul.get_population(area)
            sensitivity = congestion_sensitivity("CAFE")
            levels = ["RELAXED", "NORMAL", "SLIGHTLY_CROWDED", "CROWDED"]
            congestion = (
                CongestionVerdict(
                    available=True,
                    level=population.current_level.value,
                    sensitivity=sensitivity,
                    crowded=levels.index(population.current_level.value)
                    >= levels.index(CONGESTION_RISK_LEVEL[sensitivity]),
                )
                if population
                else CongestionVerdict(available=False, unavailable_reason="TIMEOUT")
            )
            print(
                {
                    "provider": "SEOUL_CITYDATA",
                    "area": area,
                    "current_level": population.current_level if population else None,
                    "current_at": population.current_at if population else None,
                    "forecast_count": len(population.forecasts) if population else 0,
                    "cafe_verdict": congestion,
                    "congestion_only_score": score_variables(
                        congestion=congestion,
                        weather=WeatherVerdict(
                            available=False, unavailable_reason="TIMEOUT"
                        ),
                        operating_hours=OperatingHoursVerdict(
                            available=False, unavailable_reason="TIMEOUT"
                        ),
                    ),
                }
            )
    finally:
        await kma.aclose()
        await seoul.aclose()


if __name__ == "__main__":
    asyncio.run(main())
