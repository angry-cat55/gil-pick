# TourAPI fixture 계약

2026-09-03에 공공데이터포털 `KorService2` 실제 응답과 공식 활용 가이드를 대조했다. fixture의 값은 비밀정보와 실제 장소 원문을 제거한 합성값이며, 응답 구조와 필드명만 보존한다.

- Base URL: `https://apis.data.go.kr/B551011/KorService2`
- 키워드 검색: `GET /searchKeyword2`
- 분류·지역 검색: `GET /areaBasedList2`
- 공통 상세: `GET /detailCommon2`, 필수 장소 식별자는 `contentId`
- 유형별 상세: `GET /detailIntro2`, `contentId`와 `contentTypeId` 사용
- 신분류 코드: `GET /lclsSystmCode2`
- 공통 요청값: `MobileOS`, `MobileApp`, `_type=json`, `serviceKey`
- 정상 결과 없음은 `resultCode=0000`, `totalCount=0`, 빈 `items`로 반환될 수 있다.
- URL-encoded service key는 HTTP client query parameter에 전달하기 전에 한 번 decode해야 이중 인코딩을 피할 수 있다.

내부 category의 1차 mapping은 `NA → NATURE`, `HS → HISTORY_CULTURE`, `FD → FOOD`, `SH → SHOPPING`, 나머지 → `OTHER`다. `FD05` 카페·전통찻집 계열은 `CAFE`로 세분화한다.
