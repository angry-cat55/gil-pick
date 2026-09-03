# Google Places fixture 계약

2026-09-03에 Places API (New) 공식 문서와 실제 개발계정 응답을 대조했다. fixture의 값은 비밀정보와 실제 장소 원문을 제거한 합성값이며, 응답 구조와 필드명만 보존한다.

- Base URL: `https://places.googleapis.com/v1`
- Text Search: `POST /places:searchText`
- Place Details: `GET /places/{placeId}`
- 인증: `X-Goog-Api-Key`
- 응답 필드 지정: `X-Goog-FieldMask` 필수, 공백과 wildcard 사용 금지
- Text Search mask: `places.id,places.displayName,places.formattedAddress,places.location,places.types,places.rating,places.userRatingCount,places.businessStatus,places.regularOpeningHours,places.currentOpeningHours,places.attributions,nextPageToken`
- Details mask: `id,displayName,formattedAddress,location,types,nationalPhoneNumber,rating,userRatingCount,businessStatus,regularOpeningHours,currentOpeningHours,attributions`
- 정상 결과 없음은 빈 JSON 객체 `{}`로 반환될 수 있다.
- 사진과 리뷰는 요청하지 않는다. 반환된 attribution은 Google 데이터와 가까운 위치에 표시한다.
