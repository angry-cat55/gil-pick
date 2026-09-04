package com.gilpick.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage

/**
 * 원격 이미지와 그 대체 표현.
 *
 * URL이 없거나 로드에 실패해도 항상 같은 크기를 차지한다. 썸네일이 이미지 유무에 따라
 * 크기가 달라지면 정렬이 무너지고 layout shift가 생긴다. 대체 표현은 이미지 뒤에 항상
 * 그려 두고, 로드가 성공하면 이미지가 그 위를 덮는다.
 *
 * `docs/design/ui-guidelines.md` 10절: 의미 있는 이미지에는 [contentDescription]을,
 * 장소명이 바로 옆에 있어 중복인 장식 이미지에는 `null`을 준다. 상세 hero와 검색 결과
 * 썸네일(#142)이 함께 쓴다.
 *
 * @param url HTTPS 이미지 주소. `null`이면 대체 표현만 보인다.
 * @param contentDescription 화면 판독기가 읽을 설명. 장식용이면 `null`.
 * @param modifier 크기는 호출자가 정한다.
 * @param shape 모서리 곡률.
 * @param fallbackIconSize 대체 표현 아이콘 크기.
 */
@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0),
    fallbackIconSize: Dp = FALLBACK_ICON_SIZE,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // 이미지가 없을 때는 대체 표현이 곧 내용이므로 설명을 컨테이너에 붙인다.
            .then(
                if (url == null && contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 대체 표현. 이미지가 없거나 실패한 동안 이것이 보인다.
        Icon(
            imageVector = Icons.Filled.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(fallbackIconSize),
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private val FALLBACK_ICON_SIZE = Dp(24f)
