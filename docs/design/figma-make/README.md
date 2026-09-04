# Figma Make 소스 사본 — "Design UI from Reference"

- 원본: https://www.figma.com/make/H7SpIPF8iNYyxb5jPlo7xM/Design-UI-from-Reference
- 받은 날짜: 2026-09-04 (Figma MCP). 같은 날 오후 2차 갱신: 장소 상세에 일정 추가 시트, 장소 추가에 행 탭→상세·결과 없음 상태, DayRoute 신규, 여행 상세 메뉴·일정 편집 이동 수단 변경·새 여행/여행 수정 커버 이미지·내 여행 빈 상태 등
- 내용: `src/App.tsx`(화면 목록·네비게이션), `src/index.css`(폰트·전역), `src/screens/*.tsx` 19개 화면
- Figma에서 디자인을 바꾸기 전까지는 다시 불러올 필요 없이 이 사본을 쓴다. Figma를 바꾸면 같은 PR에서 이 폴더를 갱신한다(Figma MCP: 학생 플랜 하루 200회 읽기).
- 이미지 자산(`src/imports/*.png`, unsplash URL)은 저장하지 않았다.

## 화면 ↔ 기능
| 파일 | 화면 |
|---|---|
| LoginScreen | 로그인 |
| LocationPermissionScreen | 위치 권한 |
| MyTripsScreen | 내 여행 |
| TripDetailScreen | 여행 상세 |
| CreateTripScreen | 새 여행 |
| ScheduleEditScreen | 일정 편집 |
| ActiveTravelScreen | 여행 중 |
| AddPlaceScreen | 장소 추가(검색) |
| PlaceDetailScreen | 장소 상세 (#139, PR #160 적용) |
| EditTripScreen | 여행 수정 |
| RoutePreviewScreen | 경로 변경 |
| AlternativePlacesScreen | 대체 장소 |
| SettingsScreen | 설정 |
| ErrorScreen | 오류 안내 |
| VariableMonitorScreen | 변수 감지 |
| RouteRecalculatingScreen | 경로 재생성 |
| NotificationsScreen | 알림 |
| MapSearchScreen | 지도 검색 |
| DayRouteScreen | 일자 경로 (신규) |

## 공통 팔레트 (Tailwind hex)
배경 #F4F6FB · 표면 #FFFFFF · 본문 #111827 · 보조 #6B7280 · 흐림 #94A3B8 · 더 흐림 #CBD5E1 · 선 #E2E8F0
주색 #3B7BF8 (gradient 135deg → #2457C5) · 주색 연함 #EBF2FF/#EFF6FF · 성공 #10B981 / #ECFDF5
경고 #F97316 / #FFF7ED / #FED7AA / #C2410C / #92400E · 오류 #EF4444 / #FEF2F2 · 카카오 #FEE500
지도 배경 #EBF2FF (격자 #3B7BF8 0.3, 도로 white) · 다크 #0B1120
폰트: Outfit(숫자·제목, 400~900) + Noto Sans KR. 곡률: xl 12 · 2xl 16 · 3xl 24 · 시트 32. 뒤로 가기 36×36 rounded-xl.
