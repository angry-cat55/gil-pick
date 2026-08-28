package com.gilpick.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gilpick.R

/**
 * 로그인 상태 갱신이 통신 장애로 중단됐을 때 보여주는 화면.
 *
 * session은 그대로 두고 보호 기능만 막는다. 사용자는 재시도하거나, 지금 기기에서
 * 로그아웃하고 나중에 다시 로그인할 수 있다.
 *
 * @param onRetry 갱신을 다시 시도한다.
 * @param onLogout 현재 기기에서 로그아웃한다.
 */
@Composable
fun RefreshOfflineScreen(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onLogout: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.refresh_offline_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.refresh_offline_message),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry) {
            Text(stringResource(R.string.refresh_offline_retry))
        }
        TextButton(onClick = onLogout) {
            Text(stringResource(R.string.logout))
        }
    }
}
