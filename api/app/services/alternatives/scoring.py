"""대체 장소 후보의 점수 계산."""

from __future__ import annotations

from dataclasses import dataclass
from statistics import median

from app.services.alternatives.policy import (
    BAYESIAN_MIN_REVIEWS,
    CONGESTION_SCORES,
    SCORE_WEIGHTS,
)


@dataclass(frozen=True)
class CandidateScore:
    score: float
    display_score: int
    breakdown: dict[str, float]
    reasons: tuple[str, ...]


def adjusted_ratings(
    ratings: list[tuple[float | None, int | None]],
) -> list[float | None]:
    """현재 후보 집합을 기준으로 평점을 베이지안 보정한다."""
    available = [
        (rating, count)
        for rating, count in ratings
        if rating is not None and count is not None
    ]
    if not available:
        return [None] * len(ratings)

    mean_rating = sum(rating for rating, _ in available) / len(available)
    minimum_reviews = (
        median(count for _, count in available)
        if len(ratings) >= 5
        else BAYESIAN_MIN_REVIEWS
    )
    return [
        (
            (count / (count + minimum_reviews)) * rating
            + (minimum_reviews / (count + minimum_reviews)) * mean_rating
        )
        if rating is not None and count is not None
        else None
        for rating, count in ratings
    ]


def candidate_score(
    *,
    distance_meters: float,
    search_radius_meters: int,
    adjusted_rating: float | None = None,
    congestion_level: str | None = None,
    crowded: bool = False,
    weather_at_risk: bool | None = None,
    indoor: bool = False,
    closer: bool = False,
    open_at_eta: bool = False,
) -> CandidateScore:
    """가용 변수의 가중치를 재분배해 0~100 후보 점수를 만든다."""
    distance = (
        1
        - min(max(distance_meters, 0), search_radius_meters)
        / search_radius_meters
    )
    breakdown = {"distance": distance}
    if adjusted_rating is not None:
        breakdown["rating"] = adjusted_rating / 5
    if congestion_level is not None:
        breakdown["congestion"] = (
            CONGESTION_SCORES["CROWDED"]
            if crowded
            else CONGESTION_SCORES[congestion_level]
        )
    if weather_at_risk is not None:
        breakdown["weather"] = 0.0 if weather_at_risk else 1.0

    weight = sum(SCORE_WEIGHTS[name] for name in breakdown)
    score = (
        sum(SCORE_WEIGHTS[name] * value for name, value in breakdown.items())
        / weight
        * 100
    )
    reasons = tuple(
        reason
        for enabled, reason in (
            (indoor, "INDOOR"),
            (breakdown.get("congestion", 0) > 0, "NOT_CROWDED"),
            (breakdown.get("weather") == 1, "NO_RAIN_RISK"),
            (closer, "CLOSER"),
            (open_at_eta, "OPEN_AT_ETA"),
        )
        if enabled
    )
    return CandidateScore(score, round(score), breakdown, reasons)


def ranking_key(
    score: float,
    distance_meters: float,
    adjusted_rating: float | None,
    review_count: int | None,
) -> tuple[float, float, float, int]:
    """오름차순 ``sorted``에서 명세의 후보 순서를 만드는 key를 반환한다."""
    return (
        -score,
        distance_meters,
        -(adjusted_rating if adjusted_rating is not None else -1),
        -(review_count if review_count is not None else -1),
    )


__all__ = ["CandidateScore", "adjusted_ratings", "candidate_score", "ranking_key"]
