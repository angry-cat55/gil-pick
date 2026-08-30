# F003 장소 검색 검증 가이드

이 문서는 구현 이후 F003의 계약과 사용자 흐름을 검증하기 위한 기준이다. 아래 테스트 파일은 계획 단계에는 아직 존재하지 않으며 `tasks.md`와 구현 Issue에서 생성한다.

## 사전 조건

- Backend 가상환경과 Android SDK가 준비되어 있어야 한다.
- 자동 테스트는 TourAPI 실계정에 의존하지 않고 고정 fixture와 mock transport를 사용한다.
- 실제 TourAPI 검증은 공유 개발·스테이징 환경에 credential과 quota가 준비된 경우에만 수행한다.
- credential, provider 원문 응답, 요청 URL의 `serviceKey`는 저장소나 테스트 출력에 남기지 않는다.

## Backend 자동 검증

```powershell
api\.venv\Scripts\python.exe -m pytest api/tests/contract/test_place_contract.py api/tests/unit/test_tour_api_client.py api/tests/unit/test_place_service.py api/tests/integration/test_place_flow.py
```

필수 시나리오:

1. keyword 단독, category 단독, keyword+category+areaCode 검색이 안정적인 DTO로 변환된다.
2. query와 category가 모두 없거나 trim 후 query가 한 글자면 `400`이며 TourAPI를 호출하지 않는다.
3. cursor는 같은 검색 조건에서만 재사용되고 변조·버전 불일치·조건 불일치는 `INVALID_CURSOR`다.
4. 상세 조회는 `detailCommon2`와 `detailIntro2`를 조합하고 누락 필드는 `null`로 유지한다.
5. 운영 안내가 있어도 `openNow`나 정확한 종료 시각을 추론하지 않는다.
6. timeout과 일시적 5xx는 최대 한 번 재시도하고 TourAPI application error는 재시도하지 않는다.
7. 정상 검색·상세는 5초 이내, 자동 재시도가 발생한 검색·상세는 최종 결과 또는 실패 이유가 11초 이내 반환되는지 측정한다.
7. 잘못된 요청·인증·quota/rate limit 오류는 재시도하지 않고 안정적인 오류 코드로 변환한다.
8. 응답과 로그에 service key와 provider 원문 오류가 노출되지 않는다.

## Android 자동 검증

```powershell
android\gradlew.bat -p android testDebugUnitTest
android\gradlew.bat -p android connectedDebugAndroidTest
```

필수 시나리오:

1. 검색 버튼과 IME Search만 요청을 시작하며 입력·filter 변경만으로는 호출하지 않는다.
2. loading, empty, error, content 상태와 1초 초과 초기 loading의 skeleton을 구분한다.
3. 추가 조회 중 기존 항목을 유지하고 `placeId` 중복을 제거하며 실패 시 재시도할 수 있다.
4. nullable 주소·좌표·이미지·연락처·운영 안내를 안전하게 표시하고 이미지 fallback을 제공한다.
5. 상세 화면에서 뒤로 가면 검색 조건·결과·scroll 위치가 유지된다.
6. 360dp 폭, 최대 font scale, TalkBack focus 순서, live region, 48dp touch target을 확인한다.

## 실제 TourAPI 수동 검증

공유 환경이 준비된 경우에만 정상 검색, 결과 없음, 상세 필드 누락, 다음 페이지, timeout 또는 provider 장애 응답을 확인한다. 개발계정 일일 호출 한도를 소모하므로 CI에서는 반복 호출하지 않는다. `arrange`, `numOfRows`, 분류 코드와 콘텐츠 유형별 `detailIntro2` 필드는 최신 공식 활용가이드 및 실제 fixture로 확정한다.

## 문서 동기화 확인

- `contracts/places.openapi.yaml`과 `docs/design/api-spec.md`의 PLACE-001~002가 일치한다.
- Google Places 평점·리뷰·실시간 영업 상태는 F003에 포함하지 않는다.
- DB table, migration, Redis 또는 server cache가 F003 작업에 추가되지 않는다.
- 구현 중 외부 계약이 바뀌면 `spec.md`, `plan.md`, OpenAPI와 관련 설계 문서를 함께 갱신하고 `speckit-analyze`를 다시 수행한다.
