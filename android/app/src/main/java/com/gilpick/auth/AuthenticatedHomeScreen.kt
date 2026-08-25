package com.gilpick.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gilpick.R

/**
 * 로그인 후 진입하는 빈 여행 목록 shell.
 *
 * F002 여행 관리 구현 전까지 로그인 성공의 도착 지점 역할만 한다. 카카오 profile에
 * 동의하지 않아 [nickname]이 `null`인 사용자도 같은 화면으로 들어온다.
 *
 * @param nickname 표시 이름. 미동의 시 `null`이며 이때는 제목만 표시한다.
 */
@Composable
fun AuthenticatedHomeScreen(
    nickname: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = nickname ?: stringResource(R.string.trips_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.trips_empty),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.trips_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}
