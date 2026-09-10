package com.gilpick.notification

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gilpick.auth.AuthError
import com.gilpick.auth.AuthResult
import com.gilpick.progress.await
import com.google.firebase.messaging.FirebaseMessaging
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 기기 토큰 동기화 한 번의 결과. `SessionRevocationWorker`의 `RevocationOutcome`과 같은 분류다. */
enum class FcmTokenOutcome {
    /** 서버에 반영됐다. */
    COMPLETED,

    /** 결과를 확인할 수 없다. backoff 후 다시 시도한다. */
    RETRY,

    /** 재시도해도 같다(자격 없음·다른 사용자의 기기·계약 위반·Firebase 미초기화). */
    TERMINAL,
}

/**
 * 현재 FCM 토큰을 조회해 DEV-001로 등록한다(FR-020).
 *
 * WorkManager 결과 정책을 Context·Firebase 없이 검증할 수 있도록 worker 본문에서 분리했다.
 * 토큰을 얻지 못해도 로그인은 이미 끝난 뒤이므로 다른 기능을 막지 않는다.
 */
internal suspend fun syncFcmToken(
    repository: NotificationRepository,
    fetchToken: suspend () -> String,
): FcmTokenOutcome {
    val token = try {
        fetchToken()
    } catch (e: IOException) {
        // FCM `SERVICE_NOT_AVAILABLE` 등 일시 장애.
        return FcmTokenOutcome.RETRY
    } catch (e: Exception) {
        // Firebase 미초기화(`google-services.json` 없음)·Play 서비스 없음. 재시도해도 같다.
        return FcmTokenOutcome.TERMINAL
    }
    return repository.registerFcmToken(token).toOutcome()
}

internal fun AuthResult<*>.toOutcome(): FcmTokenOutcome = when (this) {
    is AuthResult.Success -> FcmTokenOutcome.COMPLETED
    is AuthResult.Failure -> when (val error = error) {
        is AuthError.Offline -> FcmTokenOutcome.RETRY
        is AuthError.Server ->
            if (error.retryable || error.httpStatus == 429 || error.httpStatus >= 500) FcmTokenOutcome.RETRY
            else FcmTokenOutcome.TERMINAL
        is AuthError.Malformed, is AuthError.Callback -> FcmTokenOutcome.TERMINAL
    }
}

internal fun FcmTokenOutcome.toResult(): ListenableWorker.Result = when (this) {
    FcmTokenOutcome.COMPLETED -> ListenableWorker.Result.success()
    FcmTokenOutcome.RETRY -> ListenableWorker.Result.retry()
    FcmTokenOutcome.TERMINAL -> ListenableWorker.Result.failure()
}

/** 로그인·토큰 갱신·`onNewToken` 뒤 이 기기의 FCM 토큰을 DEV-001로 등록하는 durable 작업. */
class FcmTokenSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = syncFcmToken(
        repository = NotificationRepository.default(applicationContext),
        fetchToken = { FirebaseMessaging.getInstance().token.await() },
    ).toResult()

    companion object {
        fun enqueue(context: Context) = enqueueFcmTokenWork<FcmTokenSyncWorker>(context)
    }
}

/**
 * 등록·해제가 같은 unique work를 두고 뒤 요청이 앞 요청을 대체한다.
 *
 * 오프라인 로그아웃 뒤 바로 다시 로그인하면 아직 실행되지 않은 해제가 새 등록을 지우지 않도록
 * 마지막 의도만 남긴다. 입력에는 토큰을 담지 않는다(worker가 실행 시점의 토큰을 조회한다).
 */
internal inline fun <reified W : ListenableWorker> enqueueFcmTokenWork(context: Context) {
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
        FCM_TOKEN_WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<W>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build(),
    )
}

internal const val FCM_TOKEN_WORK_NAME = "fcm-token"
