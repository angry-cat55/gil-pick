package com.gilpick.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gilpick.progress.await
import com.google.firebase.messaging.FirebaseMessaging

/**
 * DEV-002로 서버의 토큰을 비우고 기기 토큰도 지운다.
 *
 * `deleteToken`은 best-effort다. 로그아웃 뒤에는 local session이 이미 없어 DEV-002가
 * 자격 없음으로 끝나지만, 서버 로그아웃 폐기가 `fcm_token`을 함께 비우므로(FR-011) 문제없다.
 */
internal suspend fun clearFcmToken(
    repository: NotificationRepository,
    deleteToken: suspend () -> Unit,
): FcmTokenOutcome {
    runCatching { deleteToken() }
    return repository.unregisterFcmToken().toOutcome()
}

/** 로그아웃 뒤 DEV-002와 기기 토큰 삭제를 수행하는 durable 작업. */
class FcmTokenClearWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = clearFcmToken(
        repository = NotificationRepository.default(applicationContext),
        deleteToken = { FirebaseMessaging.getInstance().deleteToken().await() },
    ).toResult()

    companion object {
        fun enqueue(context: Context) = enqueueFcmTokenWork<FcmTokenClearWorker>(context)
    }
}
