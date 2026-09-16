package com.gilpick.ui.component

/**
 * 모든 하위 화면 헤더 왼쪽 뒤로 가기 버튼의 공통 테스트 태그(#510).
 *
 * 화면마다 한 개뿐이고 navigation 전환이 끝나면 이전 화면은 composition에서 빠지므로 같은 값을 쓴다.
 * 탭 루트(내 여행·설정)와 로그인에는 뒤로 가기가 없다.
 */
const val TAG_HEADER_BACK = "header_back"

/**
 * 여행 카드 3종의 대표 이미지 테스트 태그(#617).
 *
 * 이미지는 장식이라 접근성 tree에 이름이 없다. test가 "이미지가 그려졌는지"를 확인할 다른 표식이 없어 태그를 둔다.
 */
const val TAG_TRIP_CARD_IMAGE = "trip_card_image"
