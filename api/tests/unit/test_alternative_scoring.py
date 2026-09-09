import pytest

from app.services.alternatives import policy
from app.services.alternatives.scoring import (
    adjusted_ratings,
    candidate_score,
    ranking_key,
)


def test_policy_values_are_the_scoring_source_of_truth() -> None:
    assert policy.SEARCH_RADII_METERS == (500, 1000, 2000)
    assert policy.SCORE_WEIGHTS == {
        "distance": 0.35,
        "rating": 0.30,
        "congestion": 0.20,
        "weather": 0.15,
    }
    assert policy.CONGESTION_SCORES == {
        "RELAXED": 1.0,
        "NORMAL": 0.75,
        "SLIGHTLY_CROWDED": 0.5,
        "CROWDED": 0.0,
    }
    assert policy.BAYESIAN_MIN_REVIEWS == 20
    assert policy.MAX_CANDIDATES == 10
    assert policy.OPERATING_CHECK_LIMIT == 20
    assert policy.CANDIDATE_TTL_MINUTES == 15
    assert policy.TOUR_API_NUM_OF_ROWS == 100


def test_adjusted_ratings_use_default_m_for_fewer_than_five_candidates() -> None:
    result = adjusted_ratings([(5.0, 10), (3.0, 30)])

    assert result == pytest.approx([4.3333333333, 3.4])


def test_adjusted_ratings_use_review_count_median_for_five_candidates() -> None:
    result = adjusted_ratings(
        [(5.0, 1), (4.0, 10), (3.0, 30), (2.0, 50), (1.0, 100)]
    )

    assert result[0] == pytest.approx((1 / 31) * 5 + (30 / 31) * 3)
    assert result[2] == pytest.approx(3.0)


def test_adjusted_ratings_exclude_missing_rating_or_review_count() -> None:
    result = adjusted_ratings([(4.0, 10), (None, 20), (3.0, None)])

    assert result[0] == pytest.approx((10 / 30) * 4 + (20 / 30) * 4)
    assert result[1:] == [None, None]


def test_candidate_score_normalizes_all_variables_and_builds_reasons() -> None:
    result = candidate_score(
        distance_meters=250,
        search_radius_meters=1000,
        adjusted_rating=4.0,
        congestion_level="NORMAL",
        weather_at_risk=False,
        indoor=True,
        closer=True,
        open_at_eta=True,
    )

    assert result.breakdown == {
        "distance": 0.75,
        "rating": 0.8,
        "congestion": 0.75,
        "weather": 1.0,
    }
    assert result.score == pytest.approx(80.25)
    assert result.display_score == 80
    assert result.reasons == (
        "INDOOR",
        "NOT_CROWDED",
        "NO_RAIN_RISK",
        "CLOSER",
        "OPEN_AT_ETA",
    )


def test_candidate_score_redistributes_missing_weights() -> None:
    result = candidate_score(
        distance_meters=500,
        search_radius_meters=1000,
        adjusted_rating=None,
        congestion_level="RELAXED",
        weather_at_risk=None,
    )

    assert result.breakdown == {"distance": 0.5, "congestion": 1.0}
    assert result.score == pytest.approx((0.35 * 0.5 + 0.20) / 0.55 * 100)


def test_candidate_score_clamps_distance_and_marks_crowded_as_zero() -> None:
    result = candidate_score(
        distance_meters=1500,
        search_radius_meters=1000,
        adjusted_rating=5.0,
        congestion_level="RELAXED",
        crowded=True,
        weather_at_risk=True,
    )

    assert result.breakdown == {
        "distance": 0.0,
        "rating": 1.0,
        "congestion": 0.0,
        "weather": 0.0,
    }
    assert "NOT_CROWDED" not in result.reasons
    assert "NO_RAIN_RISK" not in result.reasons


def test_display_score_rounds_but_ranking_uses_raw_score_and_tie_breakers() -> None:
    assert ranking_key(80.49, 300, 4.0, 100) < ranking_key(80.40, 100, 5.0, 500)
    assert ranking_key(80.0, 100, 3.0, 10) < ranking_key(80.0, 200, 5.0, 100)
    assert ranking_key(80.0, 100, 4.0, 10) < ranking_key(80.0, 100, 3.0, 100)
    assert ranking_key(80.0, 100, 4.0, 100) < ranking_key(80.0, 100, 4.0, 10)

    result = candidate_score(distance_meters=195, search_radius_meters=1000)
    assert result.score == pytest.approx(80.5)
    assert result.display_score == 80
