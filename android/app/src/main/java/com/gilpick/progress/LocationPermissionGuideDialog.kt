package com.gilpick.progress

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gilpick.auth.AuthResult
import com.gilpick.settings.PolicyDocument
import com.gilpick.settings.PolicyDocumentLauncher
import kotlinx.coroutines.launch

/**
 * 위치 권한 안내 화면([LocationPermissionScreen])을 전체 화면 Dialog로 띄우고 위치기반서비스 약관 동의를 기록한다.
 *
 * 동의를 서버에 기록하기 전에는 위치 권한을 요청하지 않는다(F007 FR-024a). 기록에 성공하면 [onConsented]를
 * 부르며, 실제 권한 요청은 호출부가 한다. 시작 방식 시트(F006)와 자동 감지 꺼짐 안내(F007 UI-005)가 같은 안내를 쓴다.
 *
 * @param onClose 뒤로 가기·`나중에 하기`. 권한을 요청하지 않고 닫는다.
 * @param onConsented 약관 동의를 기록했다. 호출부가 이 안내를 닫고 권한 요청을 시작한다.
 */
@Composable
internal fun LocationPermissionGuideDialog(onClose: () -> Unit, onConsented: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val consentRepository = remember(context) { LbsConsentRepository.default(context) }
    val policyLauncher = remember(context) { PolicyDocumentLauncher.default(context) }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }

    // 헤더 없는 전체 화면 안내라 창 폭 제한을 끈 Dialog로 덮는다.
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LocationPermissionScreen(
            onAllow = {
                saving = true
                failed = false
                scope.launch {
                    when (consentRepository.agree()) {
                        is AuthResult.Success -> {
                            saving = false
                            onConsented()
                        }
                        is AuthResult.Failure -> {
                            saving = false
                            failed = true
                        }
                    }
                }
            },
            onLater = onClose,
            lbsAgreed = agreed,
            onLbsAgreedChange = {
                agreed = it
                failed = false
            },
            onOpenLbsTerms = { policyLauncher.open(PolicyDocument.LOCATION_TERMS) },
            submitting = saving,
            submitFailed = failed,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
