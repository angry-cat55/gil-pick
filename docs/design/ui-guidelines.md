# 길픽 UI 가이드라인

앱 전체에서 반복되는 시각 기준을 정의한다. 기능별 화면 요구사항은 각 feature의 `spec.md`에 두고, 이 문서는 모든 화면이 공유하는 값과 규칙만 담는다.

**역할 분담**

- **화면의 모양은 Figma Make `Design UI from Reference`가 정한다.** 어떤 요소를 어떻게 배치하고 무엇을 넣고 뺄지는 이 문서가 아니라 Figma에서 읽는다. 원본은 https://www.figma.com/make/H7SpIPF8iNYyxb5jPlo7xM 이고, 화면별 소스(React+Tailwind) 사본은 `docs/design/figma-make`에 commit한다. 2026-09-04 팀 결정으로 `gilpick-design-reference.pen`을 대체했다.
- **토큰 값의 정본은 `com.gilpick.ui.theme`(`Theme.kt`)다.** Figma 소스의 Tailwind 값(hex, px)을 dp/sp로 옮긴 것이며, 이 문서의 표는 거기서 추출한 사본이다. 값을 바꿀 때는 Figma를 먼저 고치고, 토큰과 표를 같은 PR에서 맞춘다.
- **이 문서는 그림으로 표현할 수 없는 것만 정한다.** 접근성 최저선, 화면 상태 요구, 텍스트 작성 방식, 토큰 사용 규칙, 검증된 대비 수치가 그것이다.

Figma에는 48dp 터치 영역도, 4.5:1 대비도, 오류 화면도 그려지지 않는다. 그 자리를 이 문서가 맡는다. 우선순위는 12절에 있다.

## 1. 제품 성격과 디자인 방향

길픽은 계획만 하는 앱이 아니라 **이동 중에도 보는 앱**이다. 이 차이가 모든 시각 결정의 기준이다.

> 조밀하고 편집하기 쉬운 일정 문서 구조에, 이동 중에도 즉시 읽히는 큰 ETA와 신뢰 가능한 실시간 상태 레이어를 결합한다.

**채택하는 구조 (Figma 기준)**

- 흰 헤더 + 연한 회청색(`#F4F6FB`) 바탕 위에 흰 카드·행이 놓이는 2단 구조
- 큰 사진 hero(여행 상세 180dp, 장소 상세 240dp) 위에 원형 반투명 버튼과 흰 제목
- 날짜별 진행 점(dot)과 세로 타임라인 목록
- 하단 고정 CTA(gradient 파랑)와 bottom sheet(상단 32dp 곡률)로 확인·선택
- 지도 미리보기는 격자·도로·핀을 그린 연파랑 자리 표시

**다르게 만들 것**

- 더 큰 본문과 터치 영역. 야외에서 걸으면서 본다
- 현재 위치·다음 장소·ETA의 강한 시각 계층 (ETA 26~28sp Black)
- 자동 처리의 남은 시간과 되돌리기 수단을 항상 노출 (어두운 toast + `되돌리기`)
- 변수 추천에 결론뿐 아니라 이유를 함께
- 팝업 대신 화면 안 배너와 bottom sheet로 점진적으로 확장되는 안내
- 계획 모드와 진행 모드를 명확히 구분

## 2. 디자인 원칙

1. **명세가 요구한 동작을 먼저 만족시킨다.** 시각 완성도는 요구된 동작을 가린 채 추가하지 않는다.
2. **강조는 화면당 한 곳에만 둔다.** gradient 주버튼 하나가 강조를 가져가고 나머지는 `#F4F6FB` 보조 버튼으로 물러난다.
3. **색만으로 의미를 전달하지 않는다.** 상태·오류·성공은 색과 함께 텍스트나 아이콘을 병기한다.
4. **카드는 흰 바탕 하나로 묶는다.** 목록 행은 카드 안에서 1dp `#F4F6FB` 구분선으로 잇고, 행마다 그림자를 주지 않는다.
5. **임시 화면은 임시라고 표시한다.** 동작만 구현한 shell을 완료로 간주하지 않고 task와 PR에 명시한다.

## 3. 색상

파란 브랜드색(`#3B7BF8`)과 차가운 회청색 중립 계열을 쓴다. 의미색은 성공(초록)·경고(주황)·오류(빨강) 세 계열로 제한하고, 각 계열은 **진한 글자색 + 연한 배경색** 한 쌍으로 쓴다.

### 라이트

| 토큰 | 값 | Figma 용도 |
|---|---|---|
| `background` | `#F4F6FB` | 화면 바탕, 보조 버튼·입력창·비선택 칩 배경, 카드 안 구분선 |
| `surface` | `#FFFFFF` | 헤더, 카드, 목록 블록, sheet |
| `surfaceTint` | `#F8FBFF` | 읽지 않은 알림, 선택된 목록 행 |
| `onSurface` | `#111827` | 제목, 본문, 선택된 칩 배경 |
| `onSurfaceVariant` | `#6B7280` | 보조 본문, 보조 버튼 라벨, 아이콘 |
| `muted` | `#94A3B8` | 주소·기간·설명, 섹션 라벨, 비활성 아이콘, placeholder |
| `faint` | `#CBD5E1` | 예정 항목 글자, chevron, 이미지 대체 배경, 비활성 상태 점 |
| `outlineVariant` | `#E2E8F0` | 구분선, 비선택 카드 테두리, 진행 바 트랙, sheet handle |
| `primary` | `#3B7BF8` | 주버튼(gradient 시작), 선택 상태, 링크 글자, 현재 위치 핀 |
| `primaryDark` | `#2457C5` | 주버튼 gradient 끝(135°), 안내 글자 |
| `primaryContainer` | `#EBF2FF` | 선택된 카드 배경, 아이콘 배경, 지도 자리 표시 바탕 |
| `primarySoft` | `#EFF6FF` | 안내 배너 배경 |
| `primaryLight` | `#93C5FD` | 배너 안 출처 글자, 어두운 지도 위 강조 |
| `success` | `#10B981` | 여행 중·운영 중·완료 글자, 완료 핀 |
| `successContainer` | `#ECFDF5` | 상태 칩 배경 |
| `warning` | `#F97316` | 변수 감지 아이콘·배지, 마감 임박 글자 |
| `warningContainer` | `#FFF7ED` | 경고 배너 배경(gradient 끝 `#FFEDD5`, 테두리 `#FED7AA`) |
| `onWarningContainer` | `#92400E` | 경고 배너 제목 (`#B45309`·`#C2410C`는 보조 문장) |
| `amber` | `#F59E0B` | 낮은 위험 단계 글자 |
| `error` | `#EF4444` | 실패, 삭제, 입력 오류 |
| `errorContainer` | `#FEF2F2` | 삭제 버튼·경고 카드 배경 (`#F87171`은 보조 문장) |
| `star` | `#FBBF24` | 평점 `★` |
| `dark` | `#0B1120` | 로그인 배경(→ `#0E1A3A` → `#0F2050` gradient), 데모 프레임 |
| `darkMap` | `#0F1A2E` | 일자 경로 화면 배경(도로 `#1E3A5F`·`#263A5F`, 블록 `#152033`) |
| `onDarkMuted` | `#8BA3C7` | 어두운 배경 위 보조 글자 |
| `toast` | `rgba(17,24,39,0.92)` | 하단 toast 배경 (`되돌리기`는 `#34D399`) |
| `scrim` | `rgba(0,0,0,0.5)` | sheet·dialog 뒤 어둡게 (여행 중 화면은 0.4) |

hero 사진 위 gradient는 `rgba(0,0,0,0.3) → transparent(50%) → rgba(0,0,0,0.5)`, 사진 위 원형 버튼은 `rgba(0,0,0,0.3)` + 흰 아이콘이다.

### 다크

역상이 아니라 별도로 설계한다. Figma에는 로그인·일자 경로 두 화면만 어두운 배경이고 앱 전체 다크 팔레트는 없다. 확정 시 이 절에 라이트와 같은 형식으로 값과 검증 수치를 넣는다. 그전까지 다크 테마를 지원한다고 표시하지 않는다.

### 검증된 대비

WCAG 2.x 상대 휘도 공식으로 계산했다(2026-09-04). 본문 4.5:1, 컨트롤 경계와 의미 있는 선 3:1이 기준이다. **Figma 값 그대로 쓰기로 했으므로 기준 미달 조합도 그대로 기록한다.** 미달 조합은 정보를 색에만 싣지 말고, 같은 정보를 본문색이나 아이콘으로도 전달해야 한다(10절).

| 조합 | 비율 | 사용처 | 판정 |
|---|---|---|---|
| `onSurface` / `surface` | 17.74:1 | 제목, 본문 | 통과 |
| `onSurface` / `background` | 16.41:1 | 화면 제목 | 통과 |
| `surface` / `onSurface` | 17.74:1 | 선택된 칩 라벨 | 통과 |
| `onSurfaceVariant` / `surface` | 4.84:1 | 보조 본문 | 통과 |
| `onSurfaceVariant` / `background` | 4.47:1 | 보조 버튼 라벨 | 4.5:1 근사, 14sp 600 이상에서만 쓴다 |
| `muted` / `surface` | 2.56:1 | 주소, 섹션 라벨 | **미달**. 보조 정보 전용, 핵심 정보에 쓰지 않는다 |
| `faint` / `surface` | 1.48:1 | 예정 항목, chevron | **미달**. 장식·비활성 전용 |
| `surface` / `primaryDark` | 6.47:1 | gradient 주버튼 오른쪽 | 통과 |
| `surface` / `primary` | 3.91:1 | gradient 주버튼 왼쪽, 선택된 칩 | **미달**(15sp 700). 버튼 라벨은 gradient 중앙 이상에서 읽히므로 허용, 단독 `primary` 배경 위 작은 글자는 피한다 |
| `primary` / `surface` | 3.91:1 | 링크 글자, 선택된 카드 제목 | 14sp 700 이상에서만 쓴다 |
| `primary` / `primaryContainer` | 3.60:1 | 선택된 이동 수단 카드 | 테두리 2dp와 체크 아이콘을 병기한다 |
| `success` / `successContainer` | 2.41:1 | 운영 중·여행 중 칩 | **미달**. 칩 문구 자체가 의미를 전달하며 11sp 700 |
| `success` / `surface` | 2.88:1 | 완료 글자 | **미달**. 체크 아이콘 병기 |
| `warning` / `surface` | 2.80:1 | 마감 임박 글자 | **미달**. 문구 병기 |
| `onWarningContainer` / `warningContainer` | 6.68:1 | 경고 배너 제목 | 통과 |
| `error` / `surface` | 3.76:1 | 오류 글자, 삭제 | 14sp 600 이상에서만 쓴다 |
| `onDarkMuted` / `dark` | 7.32:1 | 로그인 부제 | 통과 |
| `outlineVariant` / `surface` | 1.23:1 | 구분선, 카드 테두리 | 구분선 전용, 3:1 대상 아님 |
| `onSurface` 40% / `surface` | — | 데모 상태 표시줄 | 앱에 쓰지 않는다 |

컨트롤 경계는 Figma에서 색이 아니라 배경 차이(`#F4F6FB` 위 흰 카드, 흰 위 `#F4F6FB` 버튼)로 구분한다. 3:1을 만족하는 경계선이 필요하면 `onSurfaceVariant`(`#6B7280`, 흰 배경 대비 4.84:1)를 쓴다.

### 지도 위 색 사용

지도 자리 표시는 `primaryContainer` 바탕에 24dp 격자(`primary` 0.3dp, 50% 투명)와 흰 도로(굵기 8·5, 70%·50%)를 그린다. 지도 검색 화면만 `#D6E8F5` 바탕에 공원 `#C3DBA8`, 흰 블록을 쓴다.

| 핀 | 색 |
|---|---|
| 일반 장소 | 흰 원 + `primary` 2dp 테두리, 번호 `primary` |
| 선택된 장소·현재 위치 | `primary` 채움, 바깥 halo `primary` 20% |
| 방문 완료 | `success` (halo 20%) |
| 주의 필요 | `warning` (`혼잡` 배지), `onSurfaceVariant` (`HH:MM마감` 배지) |
| 예정 (어두운 지도) | `#1E3A5F` |

### 출처와 조정 기록

값은 Figma Make 소스(`src/screens/*.tsx`)의 Tailwind 클래스와 인라인 hex에서 가져왔다. 2026-09-04 팀 결정에 따라 **Figma 값을 조정 없이 그대로 채택**했고, 대비 미달 조합은 위 표에 판정을 남겨 구현에서 문구·아이콘 병기로 보완한다. 이전 pen 기준 조정 기록(`#0A7268` 청록 팔레트, `outline` 분리, 필터 칩 `primary` 배경 #153)은 이 결정으로 폐기했다.

## 4. 타이포그래피

Figma는 `Outfit`(숫자·라틴)과 `Noto Sans KR`(한글)을 쓴다. Android는 **Outfit 가변 폰트를 번들**(`res/font/outfit.ttf`, OFL, `app/OFL-Outfit.txt`)하고 한글은 기기 기본 폰트(Noto Sans CJK 계열)로 둔다. Noto Sans KR은 용량 때문에 번들하지 않는다.

한글이 섞인 문자열에 Outfit 패밀리를 지정하면 fallback 글리프에 굵기가 적용되지 않아 얇게 나온다. **문자열에 한글이 있으면 시스템 폰트, 없으면 Outfit**을 고른다(`PlaceDetailScreen.kt`의 `displayFont()` 방식).

| 역할 | 크기/행간 | 굵기 | Figma 사용처 |
|---|---|---|---|
| Brand | 36 / 44sp | 900 | 로그인 `길픽` (자간 -0.5) |
| Display | 28 / 36sp | 900 | 여행 중 ETA `오후 2:35` |
| Hero title | 26 / 34sp | 900 | 내 여행 제목, 장소 상세 이름 (자간 -0.5) |
| Screen title | 22 / 30sp | 900 | 다음 장소, 여행 상세 제목, 경로 재생성 |
| Sheet title | 20 / 28sp | 900 | bottom sheet·dialog 제목 |
| Page title | 18 / 26sp | 900 | 헤더 제목(장소 추가, 알림), 프로필 이름 |
| Card title | 15~16 / 22sp | 700 | 장소명, 여행명, 후보 이름 |
| Body | 14 / 20sp | 500~600 | 정보 값, 목록 항목, 설정 항목 |
| Button | 15 / 20sp | 700 | 주버튼 |
| Button secondary | 14 / 20sp | 600 | 보조 버튼, 칩 |
| Supporting | 13 / 18sp | 400~600 | 주소, 기간, 설명, 시트 부제 |
| Label | 12 / 16sp | 500~700 | 상태 라벨, 시간, 링크 |
| Caption | 11 / 14sp | 500~700 | 섹션 라벨(대문자·자간 +0.05em), 상태 칩, stats 라벨 |
| Micro | 9~10 / 12sp | 700~800 | 지도 핀 글자, 날짜 점 라벨, TOP 배지 |

- Figma가 11sp·10sp·9sp를 쓰므로 **최소 크기 제한을 두지 않는다.** 단 9~10sp는 핀·배지처럼 반복 정보에만 쓰고, 단독으로 의미를 전달하는 글자는 12sp 이상으로 한다.
- 크기를 새로 만들지 않는다. 위 역할 중에서 고른다.
- 행간은 크기 +6~8sp로 잡는다. 한글은 라틴 문자보다 넉넉한 행간이 필요하다.
- 시스템 글자 크기 확대에서 잘리지 않아야 한다. 고정 높이는 hero 사진과 버튼에만 쓴다.
- 시각·거리·소요 시간 숫자는 Outfit으로 표시해 자릿수 정렬을 맞춘다.

## 5. 간격과 크기

4dp 배수만 쓴다. Figma의 Tailwind 단위(1 = 4px)를 그대로 dp로 옮긴다.

| 토큰 | 값 | Figma 용도 |
|---|---|---|
| `space1` | 4dp | 제목과 설명 사이(`mb-1`), 칩 안 세로 |
| `space2` | 8dp | 아이콘과 텍스트, 카드 사이(`space-y-2`, `gap-2`), 섹션 블록 사이(`mt-2`) |
| `space3` | 12dp | 버튼 사이(`gap-3`), 헤더 상단(`pt-3`), 카드 사이(`mt-3`) |
| `space4` | 16dp | 카드·목록 행 안쪽(`px-4 py-4`), 카드 좌우 여백(`mx-4`), 정보 행 아이콘 간격 |
| `space5` | 20dp | 화면·헤더 좌우 여백(`px-5`), 카드 안쪽(`p-5`) |
| `space6` | 24dp | bottom sheet 좌우(`px-6`), 로그인 카드 |
| `space8` | 32dp | sheet 하단(`pb-8`), 화면 하단 |

**화면 좌우 여백은 20dp, 카드 좌우 여백은 16dp, sheet 안쪽은 24dp다.**

| 요소 | 크기 |
|---|---|
| 터치 영역 | 최소 48 × 48dp (보이는 원·사각이 더 작아도 터치 영역은 48로 잡는다) |
| 헤더 아이콘 버튼 | 36 × 36dp(`w-9`), 알림 버튼 40 |
| hero 위 원형 버튼 | 36 × 36dp, 위 12~16 · 좌우 20 |
| 주요 CTA 높이 | 52 ~ 54dp |
| 보조 버튼 높이 | 46 ~ 50dp |
| 입력창 높이 | 44dp(검색), 50dp(폼) |
| 장소 썸네일 | 60 × 60dp(검색), 64(예정 여행), 56(지난 여행), 프로필 56 |
| hero 사진 | 240dp(장소 상세), 180dp(여행 상세), 160dp(커버), 140dp(진행 중 카드) |
| 지도 미리보기 | 130~190dp, 화면 지도 38~55% |
| 체류 시간 ± 버튼 | 40 × 40dp 원 (dialog는 44) |
| 진행 바 | 6dp 높이, 날짜 점 10dp |
| 목록 그룹 헤더 점 | 8 × 8dp 원. 진행 중 `success`, 다가오는 `primary`, 지난 `faint` |
| 빈 상태 아이콘 상자 | 64 × 64dp(`background`, 16dp 곡률) 안 28dp `faint` 아이콘. 빈 화면은 세로 중앙보다 살짝 위(하단 여백 60dp) |
| 터치 영역 간격 | 최소 8dp |

## 6. 곡률과 그림자

Figma는 Tailwind 곡률 단계를 쓴다. 목록 안 행은 곡률 없이 구분선으로 잇고, 카드·버튼·sheet에만 강하게 쓴다.

| 토큰 | 값 | Figma 용도 |
|---|---|---|
| `radiusSm` | 6~8dp | 상태 칩(`rounded-lg`), 작은 배지(`rounded-md` 6), 핀 라벨 |
| `radiusMd` | 12dp | 버튼, 입력창, 헤더 아이콘 버튼, 썸네일, 칩(`rounded-xl`) |
| `radiusLg` | 16dp | 카드, 지도 미리보기, 이동 수단 카드, 큰 버튼(`rounded-2xl`) |
| `radiusXl` | 24dp | 큰 카드, dialog, bottom sheet 상단(`rounded-3xl`) |
| `radiusSheet` | 32dp | 선택 sheet 상단(`rounded-t-[32px]`), 로그인 카드 36 |
| `radiusFull` | 50% | 원형 버튼, 핀, 상태 점, 지도 검색 칩, 진행 바 |

| 그림자 | 값 | 용도 |
|---|---|---|
| 카드 | `0 1px 4px rgba(0,0,0,0.06)` | 흰 카드 |
| 플로팅 카드 | `0 4px 20px rgba(0,0,0,0.08)` | 다음 장소 카드 |
| 주버튼 | `0 4px 16px rgba(59,123,248,0.3)` | gradient CTA |
| FAB | `0 8px 28px rgba(59,123,248,0.5)` | 새 여행 만들기 |
| sheet | `0 -4px 24px rgba(0,0,0,0.12)` | bottom sheet |
| 진행 중 여행 카드 | `0 0 0 2px #3B7BF8` + `0 2px 12px rgba(59,123,248,0.12)` | 강조 테두리 |

- 목록 행은 카드로 감싸지 않는다. 흰 블록 안에서 1dp `background` 색 구분선(좌우 여백 16~20dp)으로 잇는다.
- bottom sheet는 상단 모서리만 둥글게 하고 40 × 4dp `outlineVariant` handle을 둔다.
- Compose에서 그림자는 `Modifier.shadow`로 근사한다. 색 있는 그림자(주버튼·FAB)는 `ambientColor`·`spotColor`에 `primary`를 준다.

## 7. 공통 컴포넌트와 아이콘

공통 컴포넌트는 `com.gilpick.ui.component`에 두고 모든 feature가 재사용한다. 화면에서 Material 컴포넌트를 직접 조립하지 않는다.

**각 컴포넌트가 어떻게 생겼는지는 이 문서가 정하지 않는다.** 모양의 정본은 Figma 소스이고, 실행 가능한 형태는 `com.gilpick.ui.component`의 구현이다. 컴포넌트를 새로 만들거나 고칠 때는 Figma 소스에서 모양을 읽고, 3절의 색 역할과 대비, 4~6절의 토큰, 10절의 접근성 최저선을 지켰는지 확인한다.

### 아이콘

Figma는 **lucide 계열 stroke 아이콘**을 인라인 SVG로 쓴다. Android는 같은 path를 `res/drawable/ic_lucide_*.xml` vector drawable로 옮긴다(예: `ic_lucide_arrow_left`, `ic_lucide_heart`, `ic_lucide_map_pin`, `ic_lucide_clock`, `ic_lucide_users`, `ic_lucide_cloud_drizzle`, `ic_lucide_walk`, `ic_lucide_transit`, `ic_lucide_car`, `ic_lucide_check`). Material Icons는 Figma 글리프와 모양이 다르므로 쓰지 않는다.

| 항목 | 값 |
|---|---|
| stroke 굵기 | 2 (뒤로 가기·플러스·체크는 2.5, 큰 안내 아이콘은 1.6~1.8) |
| 끝·모서리 | round |
| 크기 | 14 / 16 / 18 / 20 / 22dp (안내 화면 큰 아이콘 28~44) |
| 색 | `muted`(정보 행), `onSurfaceVariant`(헤더), `primary`(선택·안내), 흰색(사진·gradient 위) |

### 카카오 로그인 버튼

카카오 브랜드 가이드가 정한 외부 고정값이라 Figma나 구현이 바꿀 수 있는 값이 아니다.

| 항목 | 값 |
|---|---|
| 배경 | `#FEE500` |
| 라벨 | `#111827` (Figma), 대비 15.3:1 |
| 높이·곡률 | 56dp, 16dp |

이 색을 카카오 로그인 버튼과 설정의 카카오 연동 표시 밖에서 쓰지 않는다.

## 8. 텍스트 작성 방식

계획 화면은 명사 중심으로 짧게, 진행 화면은 상태와 다음 행동을 문장으로 분명히 쓴다. 문장은 `~해요` 체를 쓴다(Figma 문구 기준).

**계획 화면**

```
2일차 · 경복궁 · 09:00 · 90분 · 도보 12분 · 0.8km · 장소 추가
```

**진행 화면**

```
북촌한옥마을에 도착하셨나요?
예상 도착 오후 2:35 · 12분 남았어요
4분 뒤 자동으로 도착 처리돼요
장소가 창덕궁으로 변경되었습니다 · 12초 · 되돌리기
```

**변수와 추천은 기술 용어가 아니라 방문 판단으로 쓴다**

| 쓰지 않는다 | 쓴다 |
|---|---|
| 운영시간 변수 감지 | 도착 전에 문을 닫을 수 있어요 |
| 강수확률 임계값 초과 | 도착 시간에 비가 올 수 있어요 |
| 대체 후보 추천 | 인사동거리 지금 매우 혼잡해요 |

추천에는 결론뿐 아니라 짧은 이유를 붙인다.

```
실내 관람 가능 · 현재보다 8분 가까워요
궁궐 · 0.8km · ★4.6 · 18:00 마감
```

## 9. 화면 상태

목록과 상세를 가진 모든 화면은 네 상태를 전부 가진다.

| 상태 | 표현 |
|---|---|
| `loading` | 1초를 넘길 때만 대기 표시를 띄운다. 그 전에는 아무것도 표시하지 않는다 |
| `empty` | Figma 빈 상태 형식: 64dp `background` 원각 사각 안 `faint` 아이콘, 15~18sp 700 제목, 13~14sp `muted` 설명, 다음 행동 버튼(테두리형 또는 gradient) |
| `error` | Figma `ErrorScreen` 형식: 96dp `errorContainer` 아이콘, 24sp 제목, 원인 카드, 안내 배너, `다시 시도하기` 주버튼 + 돌아가기 보조 버튼 |
| `content` | 실제 내용 |

갱신 중 기존 내용을 유지할지 비울지는 화면마다 다르므로 해당 feature의 `spec.md`에서 정한다.

### 변수 경고

모달을 남발하지 않는다. `warningContainer` gradient 배너(테두리 `#FED7AA`, 36dp `warning` 아이콘 박스)나 bottom sheet 확장 상태를 쓴다.

```
인사동거리 지금 매우 혼잡해요
오후 4:00 도착 예정 · 5분 전 감지                         ›
```

실시간 데이터를 근거로 안내할 때는 **출처와 갱신 시각**을 함께 표시한다(`기상청 3분 전`). 단, F003 장소 검색·상세는 장소별 provider 배지·출처 문구를 표시하지 않고 Google 평점·영업정보가 있는 영역에 정책상 필수 attribution만 표시한다.

## 10. 접근성 최저선

Figma 팔레트가 대비 기준을 여러 곳에서 넘지 못하므로(3절), 최저선은 **색이 아닌 수단**으로 지킨다.

**이 절의 항목은 모두 지킨다. 다만 스크린리더(TalkBack) 대응은 이번 프로젝트 범위 밖이다**(아래 "범위" 참조). 아래 표는 유지하는 최저선이다.

| 항목 | 기준 |
|---|---|
| 핵심 정보 글자 | `onSurface`(`#111827`)로 쓴다. `muted`·`faint`는 보조 정보 전용 |
| 컨트롤 경계·의미 있는 선·아이콘 | 배경 차이나 2dp 테두리·아이콘 병기로 구분한다. 색 하나로 구분하지 않는다 |
| 최소 글자 크기 | 단독으로 의미를 전달하는 글자는 12sp 이상. 9~11sp는 칩·핀·섹션 라벨 |
| 터치 영역 | 48 × 48dp 이상. 보이는 요소가 36~40dp여도 터치 영역은 48로 잡는다 |
| 터치 영역 간격 | 8dp 이상 |
| 아이콘 전용 버튼 | `contentDescription` 필수 |
| 장식용 아이콘 | `contentDescription = null` |
| 색 단독 의미 전달 | 금지. 상태 칩 문구, 체크 아이콘, 테두리를 병기 |
| 시스템 글자 확대 | 최대 배율에서 잘림 없음 |
| 포커스 표시 | 키보드·스위치 접근 시 보이는 포커스 링 |

- 화면 너비 360dp에서 가로 스크롤이나 잘림이 없어야 한다.
- 자동 도착 처리처럼 시간이 걸리는 동작에는 남은 시간과 취소 수단을 함께 노출한다.
- 지도 위 정보는 지도를 못 보는 사용자를 위해 목록으로도 제공한다.
- 상태 표시줄 뒤까지 그리는 edge-to-edge 화면은 상단에 흰 띠를 두고(`statusBarsPadding`) 하단 CTA는 제스처 바 위에 둔다(`navigationBarsPadding`).

### 범위 — 스크린리더 대응은 하지 않는다

시각장애인 접근성(스크린리더 낭독)은 이번 프로젝트 범위에서 제외한다. 위 표의 최저선은 그대로 유지한다.

| 구분 | 항목 |
|---|---|
| **유지** | 터치 영역 48 × 48dp, 터치 영역 간격 8dp |
| **유지** | 명도 대비 — 본문 4.5:1, 컨트롤 경계·의미 있는 선 3:1 |
| **유지** | 색 단독 의미 전달 금지 — 문구·아이콘·테두리 병기 |
| **유지** | 최소 글자 크기 12sp, 시스템 글자 확대 시 잘림 없음 |
| **유지** | 아이콘 전용 버튼의 `contentDescription` 필수, 장식용 아이콘은 `null` |
| **제외** | `liveRegion`(오류·상태 변화의 자동 낭독) |
| **제외** | TalkBack 공지·포커스 순서 조정 |
| **제외** | 스크린리더만을 위한 `semantics` 추가 작업 |

경계가 헷갈릴 때의 기준은 **"화면을 보는 사용자에게도 쓸모가 있는가"**다. 있으면 유지, 스크린리더 사용자만을 위한 것이면 제외다.

- `contentDescription`은 유지한다. 아이콘 전용 버튼이 무엇을 하는지 적어 두는 일은 계측 test가 요소를 찾는 근거이기도 하고, 코드를 읽는 사람에게도 필요하다.
- `FilterChip`의 `isSelected`처럼 **컴포넌트가 기본으로 붙이는** semantics는 그대로 둔다. 없애는 것이 오히려 추가 작업이다.
- 제외 대상은 스크린리더 대응을 위해 **새로 더하는** 작업이다. 이미 있는 것을 걷어내지 않는다.

### 범위 결정 기록

| 결정 | 근거 |
|---|---|
| 스크린리더(TalkBack) 대응을 범위에서 제외 (2026-09-05 팀 결정) | 남은 일정에서 낭독 품질까지 책임질 수 없다고 판단했다. 대신 색·크기·터치 영역처럼 모든 사용자에게 영향을 주는 최저선은 그대로 지킨다. Issue #131(`TripFormScreen` 오류 메시지 `liveRegion` 추가)을 이 결정에 따라 `not planned`로 닫았다 |

범위가 다시 넓어지면 이 절의 "제외" 항목부터 되살린다. #131 본문에 `liveRegion` 적용 방향이 남아 있다.

## 11. Compose 구현 규칙

**소유 위치**

| 경로 | 내용 |
|---|---|
| `com.gilpick.ui.theme` | 색상·타이포·간격·곡률 토큰, `GilpickTheme` |
| `com.gilpick.ui.component` | 공통 컴포넌트 |
| `res/font/outfit.ttf` | Outfit 가변 폰트 |
| `res/drawable/ic_lucide_*.xml` | Figma 아이콘 |

feature 패키지가 아니라 중립 위치에 둔다. 특정 feature가 소유하면 다른 feature가 쓸 근거가 없어진다.

**Material 3에 있는 값**

`background`, `surface`, `onSurface`, `onSurfaceVariant`, `primary`, `primaryContainer`, `error`, `errorContainer`, `outlineVariant`는 `ColorScheme`에 대응 역할이 있다. 그대로 매핑하고 `MaterialTheme.colorScheme`에서 읽는다.

**Material 3에 없는 값**

`muted`, `faint`, `primaryDark`, `primarySoft`, `success`·`successContainer`, `warning`·`warningContainer`·`onWarningContainer`, `star`, `dark`, `toast`와 간격·곡률·그림자 토큰은 `CompositionLocal`과 data class로 `GilpickTheme` 안에서 제공한다.

```kotlin
data class GilpickColors(val muted: Color, val faint: Color, val primaryDark: Color, /* … */ val success: Color)
data class GilpickSpacing(val space1: Dp, /* … */ val space8: Dp)

val LocalGilpickColors = compositionLocalOf<GilpickColors> { error("GilpickTheme 밖입니다") }
```

- 기본값을 주지 않고 `error(...)`로 둔다. 테마 밖에서 쓰면 조용히 잘못된 색이 나오는 대신 즉시 실패한다.
- gradient 주버튼은 `Brush.linearGradient(listOf(primary, primaryDark))`를 공통 컴포넌트(`GradientButton`)로 만들어 재사용한다.

**공통 규칙**

- 화면 코드에 색상 리터럴(`Color(0xFF...)`)을 쓰지 않는다. `PlaceDetailScreen.kt`의 `Figma` 팔레트 객체는 `Theme.kt` 교체 전에 만든 것이라 값이 같으며, 토큰 참조로 바꾸는 정리가 남아 있다.
- 간격·곡률도 리터럴 `dp` 대신 토큰을 쓴다. 예외는 토큰으로 표현할 수 없는 일회성 조정이며, 그 경우 주석으로 이유를 남긴다.
- 다크 테마는 `colorScheme`과 `CompositionLocal` 값 교체로만 처리한다. 화면에서 `isSystemInDarkTheme()`로 분기하지 않는다.
- dynamic color(Material You)는 쓰지 않는다.
- Figma의 `backdrop-filter: blur`는 Compose에서 흉내 내지 않는다. 반투명 배경만 쓴다.
- 이 규칙을 지키면 나중에 색상 방향을 바꿀 때 `Theme.kt` 한 파일만 고치면 된다.

구현 시 `.claude/skills/compose-expert`의 `references/theming-material3.md`와 `references/atomic-design.md`를 참고한다.

## 12. 변경 절차

### 어긋났을 때 무엇이 이기는가

1. **모양은 Figma가 이긴다.** 이 문서·feature `spec.md`·구현 중 어느 것과 달라도 Figma에 맞춘다. 화면 구성, 배치, 어떤 요소를 넣고 뺄지, 색·글자·간격 값이 여기 해당한다. (2026-09-04 팀 결정: "pen 정본 신경쓰지 말고 Figma와 완전히 똑같게")
2. **명세는 Figma를 따라 고친다.** Figma에 있는 화면·항목·행동은 `spec.md`에 반영하고, Figma에 없는 항목은 명세에서도 뺀다. API에 없는 값은 지어내지 않고 `정보 없음`으로 두며, 필요한 필드는 Backend 계약에 추가 요청한다.
3. **최저선은 Figma보다 위다.** 10절 접근성 최저선(48dp 터치, 색 단독 의미 전달 금지, 아이콘 설명)과 9절 화면 상태 요구, Google attribution 같은 정책 필수 표시. Figma가 이를 어기면 구현은 이 문서를 따르되 모양을 바꾸지 않는 방식(터치 영역 확장, 문구·아이콘 병기)으로 지킨다.
4. **어긋난 지점은 반드시 기록한다.** 임의로 해석하지 말고 Issue나 PR에 차이를 남긴다.

### 모양과 기능의 경계

판단 기준은 **"이걸 빼면 사용자가 못 하게 되는 일이 있는가"**다. 있으면 기능이고, 없으면 모양이다. Figma 우선 결정 이후에는 기능 항목도 Figma에 있는 것을 기준으로 명세를 맞춘다.

| 예 | 성격 | 결정 |
|---|---|---|
| 결과 행의 정보 순서와 강조 | 모양 | Figma |
| 상세에 어떤 정보 행을 보여줄지 | 모양 → 명세 반영 | Figma → `spec.md` FR |
| `일정에 추가` 시트의 존재와 선택지 | 기능 | Figma → `spec.md` FR·UI |
| `loading`·`empty`·`error` 구분 | 상태 | 9절 |
| 터치 48dp, 아이콘 설명 | 접근성 | 10절 |

### 값을 바꿀 때

- Figma를 바꾸면 로컬 소스 사본을 다시 받고, 같은 작업에서 이 문서의 표와 관련 feature `spec.md`, 구현을 함께 맞춘다.
- 대비에 영향을 주는 색상 변경은 수치를 다시 계산해 3절 표를 갱신한다.
- 토큰 값은 `Theme.kt`에서 고치고, 같은 PR에서 이 문서의 표를 맞춘다. 모든 화면의 색이 바뀌는 변경은 각 화면 스크린샷을 다시 확인한다.

## 13. 관련 문서

- **모양의 정본: Figma Make `Design UI from Reference`** https://www.figma.com/make/H7SpIPF8iNYyxb5jPlo7xM
  - 저장소 사본 `docs/design/figma-make`(`src/screens/*.tsx` 19개 + `App.tsx` + `index.css` + 팔레트 README)를 기준으로 작업하고, Figma를 바꾼 사람이 같은 PR에서 사본을 갱신한다.
  - `docs/design/gilpick-design-reference.pen`은 2026-09-04부로 참고용이며 정본이 아니다.
- 토큰 값의 정본: `android/app/src/main/java/com/gilpick/ui/theme/Theme.kt`
- 기능별 화면 요구사항: 각 feature의 `specs/<feature>/spec.md`
- 적용 절차: `AGENTS.md` 9절, 10절
- API 계약: `docs/design/api-spec.md`
