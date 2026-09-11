package com.gilpick.alternative

/**
 * F009 대체 장소 test가 함께 쓰는 계약 응답 fixture.
 *
 * 값은 `contracts/alternatives.openapi.yaml`(DETECT-002는 F008 `contracts/detections.openapi.yaml`)을
 * 따른다. F007 `DetectionFixtures`와 같은 방식으로 JSON 문자열을 두어 계약과 DTO가 어긋나면
 * 파싱에서 드러나게 한다.
 */
internal const val TRIP_ID = "3f1d2c4b-5a6e-4f70-8a91-b2c3d4e5f607"
internal const val ITEM_ID = "9c8b7a6f-5e4d-4c3b-8a29-18f7e6d5c4b3"
internal const val DETECTION_ID = "8a2918f7-e6d5-4c4b-8a29-18f7e6d5c4b3"
internal const val CANDIDATE_ID = "eyJkIjoiOGEyOTE4ZjcifQ.c2lnbmF0dXJl"
internal const val ALT_REQUEST_ID = "11111111-2222-4333-8444-555555555555"

/** 값이 모두 채워진 TourAPI 장소. F003 PLACE-001 item과 같다. */
internal fun tourPlaceJson(placeId: String = "tourapi:126508", name: String = "창덕궁") = """
    {
      "placeId": "$placeId",
      "source": "TOUR_API",
      "sourcePlaceId": "126508",
      "name": "$name",
      "category": "HISTORY_CULTURE",
      "tourApiCategory": {"large": "HS", "middle": "HS01", "small": "HS0101"},
      "address": "서울 종로구 율곡로 99",
      "latitude": 37.5794,
      "longitude": 126.9910,
      "imageUrl": "https://example.test/a.jpg",
      "recommendedStayMinutes": 90,
      "rating": 4.6,
      "userRatingCount": 1200,
      "businessStatus": "OPERATIONAL",
      "regularOpeningHours": ["월요일 09:00~18:00"],
      "currentOpeningHours": ["오늘 09:00~18:00"],
      "googleAttributions": ["Google 제공"]
    }
""".trimIndent()

/** nullable이 모두 null인 Google 장소. */
internal fun googlePlaceJson() = """
    {
      "placeId": "google:ChIJ_abc-123",
      "source": "GOOGLE_PLACES",
      "sourcePlaceId": "ChIJ_abc-123",
      "name": "이름만 있는 카페",
      "category": "CAFE",
      "tourApiCategory": null,
      "address": null,
      "latitude": null,
      "longitude": null,
      "imageUrl": null,
      "recommendedStayMinutes": 60,
      "rating": null,
      "userRatingCount": null,
      "businessStatus": null,
      "regularOpeningHours": null,
      "currentOpeningHours": null,
      "googleAttributions": null
    }
""".trimIndent()

/** DETECT-001 확장 응답. `eta`·`reason`이 있고 상태가 다른 두 항목. */
internal fun detectionListJson() = """
    {"success": true,
     "data": {"items": [
       {"detectionId": "$DETECTION_ID",
        "itemId": "$ITEM_ID",
        "placeName": "경복궁",
        "primaryType": "WEATHER",
        "status": "ACTIVE",
        "totalRiskScore": 78,
        "eta": "2026-09-09T14:00:00+09:00",
        "reason": "오후 2시 이후 강한 비 + 매우 높은 혼잡",
        "createdAt": "2026-09-09T12:55:00+09:00",
        "read": false},
       {"detectionId": "11111111-1111-4111-8111-111111111111",
        "itemId": "22222222-2222-4222-8222-222222222222",
        "placeName": "인사동거리",
        "primaryType": "CONGESTION",
        "status": "DISMISSED",
        "totalRiskScore": 61,
        "eta": "2026-09-09T16:00:00+09:00",
        "reason": "지금 매우 혼잡해요",
        "createdAt": "2026-09-09T12:40:00+09:00",
        "read": true}
     ]},
     "meta": {"requestId": "$ALT_REQUEST_ID", "pagination": {"nextCursor": null, "hasNext": false}}}
""".trimIndent()

/** DETECT-001 `status=ACTIVE` 결과 2건. 경복궁(14:00)이 인사동거리(16:00)보다 ETA가 이르다. */
internal fun activeDetectionsJson() = """
    {"success": true,
     "data": {"items": [
       {"detectionId": "11111111-1111-4111-8111-111111111111",
        "itemId": "22222222-2222-4222-8222-222222222222",
        "placeName": "인사동거리",
        "primaryType": "CONGESTION",
        "status": "ACTIVE",
        "totalRiskScore": 61,
        "eta": "2026-09-09T16:00:00+09:00",
        "reason": "지금 매우 혼잡해요",
        "createdAt": "2026-09-09T12:40:00+09:00",
        "read": false},
       {"detectionId": "$DETECTION_ID",
        "itemId": "$ITEM_ID",
        "placeName": "경복궁",
        "primaryType": "WEATHER",
        "status": "ACTIVE",
        "totalRiskScore": 78,
        "eta": "2026-09-09T14:00:00+09:00",
        "reason": "오후 2시 이후 강한 비 + 매우 높은 혼잡",
        "createdAt": "2026-09-09T12:55:00+09:00",
        "read": false}
     ]},
     "meta": {"requestId": "$ALT_REQUEST_ID", "pagination": {"nextCursor": null, "hasNext": false}}}
""".trimIndent()

/** DETECT-002 상세. 혼잡·날씨는 평가됐고 운영시간은 확인 불가(`HOURS_UNKNOWN`)다. */
internal fun detectionDetailJson(
    status: String = "ACTIVE",
    // 서버는 instant를 UTC로 준다. 기본값은 기존 test가 쓰던 KST 표기 그대로 두고,
    // 시각대 변환을 보는 test만 UTC 표기를 넘긴다(#410).
    eta: String = "2026-09-09T14:00:00+09:00",
) = """
    {"success": true,
     "data": {
       "detectionId": "$DETECTION_ID",
       "tripId": "$TRIP_ID",
       "itemId": "$ITEM_ID",
       "placeName": "경복궁",
       "primaryType": "WEATHER",
       "status": "$status",
       "eta": "$eta",
       "totalRiskScore": 78,
       "reason": "오후 2시 이후 강한 비 + 매우 높은 혼잡",
       "variables": {
         "congestion": {"available": true, "unavailableReason": null, "level": "CROWDED", "sensitivity": "MEDIUM", "crowded": true},
         "weather": {"available": true, "unavailableReason": null, "precipitationProbability": 80, "precipitationMmPerHour": 3.5, "precipitationType": "RAIN", "atRisk": true},
         "operatingHours": {"available": false, "unavailableReason": "HOURS_UNKNOWN", "closesAt": null, "closingSoon": null}
       },
       "read": false,
       "createdAt": "2026-09-09T12:55:00+09:00",
       "lastEvaluatedAt": "2026-09-09T13:10:00+09:00"
     },
     "meta": {"requestId": "$ALT_REQUEST_ID"}}
""".trimIndent()

/** ALT-001 후보 2개. 1위는 평점·마감 시각이 있고, 2위는 평점 없음·운영시간 확인 불가다. */
internal fun alternativesJson() = """
    {"success": true,
     "data": {
       "detectionId": "$DETECTION_ID",
       "originPlaceId": "tourapi:126001",
       "eta": "2026-09-09T14:00:00+09:00",
       "searchRadiusMeters": 1000,
       "categoryMatchLevel": "MIDDLE",
       "evaluatedAt": "2026-09-09T13:12:00+09:00",
       "items": [
         {"rank": 1,
          "candidateId": "$CANDIDATE_ID",
          "place": ${tourPlaceJson()},
          "distanceMeters": 820,
          "adjustedRating": 4.48,
          "score": 87.4,
          "displayScore": 87,
          "scoreBreakdown": {"distance": 0.9, "rating": 0.85, "congestion": 0.75, "weather": 1.0},
          "operatingStatus": "OPEN",
          "closesAt": "2026-09-09T18:00:00+09:00",
          "reasons": ["INDOOR", "NOT_CROWDED", "CLOSER"]},
         {"rank": 2,
          "candidateId": "$CANDIDATE_ID.2",
          "place": ${googlePlaceJson()},
          "distanceMeters": 1450,
          "adjustedRating": null,
          "score": 62.0,
          "displayScore": 62,
          "scoreBreakdown": {"distance": 0.6, "congestion": 0.5},
          "operatingStatus": "UNKNOWN",
          "closesAt": null,
          "reasons": []}
       ]
     },
     "meta": {"requestId": "$ALT_REQUEST_ID"}}
""".trimIndent()

/** ALT-001 후보 없음. 마지막 반경 2000·분류 단계 NONE. */
internal fun alternativesEmptyJson() = """
    {"success": true,
     "data": {
       "detectionId": "$DETECTION_ID",
       "originPlaceId": "tourapi:126001",
       "eta": "2026-09-09T14:00:00+09:00",
       "searchRadiusMeters": 2000,
       "categoryMatchLevel": "NONE",
       "evaluatedAt": "2026-09-09T13:12:00+09:00",
       "items": []
     },
     "meta": {"requestId": "$ALT_REQUEST_ID"}}
""".trimIndent()

/** ALT-002 검색 결과. 방문 가능 1건, 운영 종료(방문 불가) 1건, 이미 일정에 있음 1건. */
internal fun searchJson() = """
    {"success": true,
     "data": {"items": [
       {"place": ${tourPlaceJson()},
        "distanceMeters": 820,
        "operatingStatus": "OPEN",
        "visitable": true,
        "inSchedule": false},
       {"place": ${googlePlaceJson()},
        "distanceMeters": null,
        "operatingStatus": "CLOSED",
        "visitable": false,
        "inSchedule": false},
       {"place": ${tourPlaceJson(placeId = "tourapi:126001", name = "경복궁")},
        "distanceMeters": 0,
        "operatingStatus": "UNKNOWN",
        "visitable": false,
        "inSchedule": true}
     ]},
     "meta": {"requestId": "$ALT_REQUEST_ID", "pagination": {"nextCursor": "c2Vjb25k", "hasNext": true}}}
""".trimIndent()

/** DETECT-004 결과. 거절됐거나(DISMISSED) 이미 처리된 현재 상태다. */
internal fun dismissJson(status: String = "DISMISSED", decidedAt: String? = "2026-09-09T13:20:00+09:00") = """
    {"success": true,
     "data": {"detectionId": "$DETECTION_ID", "status": "$status", "decidedAt": ${decidedAt?.let { "\"$it\"" } ?: "null"}},
     "meta": {"requestId": "$ALT_REQUEST_ID"}}
""".trimIndent()

internal fun alternativeErrorJson(code: String, retryable: Boolean = false, details: String = "null") = """
    {"success": false,
     "error": {"code": "$code", "message": "진단용 설명", "retryable": $retryable, "details": $details},
     "meta": {"requestId": "$ALT_REQUEST_ID"}}
""".trimIndent()
