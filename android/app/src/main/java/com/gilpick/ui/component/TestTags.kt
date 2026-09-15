package com.gilpick.ui.component

/**
 * 모든 하위 화면 헤더 왼쪽 뒤로 가기 버튼의 공통 테스트 태그(#510).
 *
 * 화면마다 한 개뿐이고 navigation 전환이 끝나면 이전 화면은 composition에서 빠지므로 같은 값을 쓴다.
 * 탭 루트(내 여행·설정)와 로그인에는 뒤로 가기가 없다.
 */
const val TAG_HEADER_BACK = "header_back"
