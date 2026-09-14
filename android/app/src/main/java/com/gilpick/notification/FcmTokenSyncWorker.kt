package com.gilpick.notification

import android.content.Context
import android.util.Log
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
    log: (String) -> Unit = ::logFcm,
    fetchToken: suspend () -> String,
): FcmTokenOutcome {
    val token = try {
        fetchToken().also { log("token fetch ok (length=${it.length})") }
    } catch (e: IOException) {
        // FCM `SERVICE_NOT_AVAILABLE` 등 일시 장애.
        log("token fetch failed -> RETRY: ${e.describe()}")
        return FcmTokenOutcome.RETRY
    } catch (e: Exception) {
        // Firebase 미초기화(`google-services.json` 없음)·Play 서비스 없음. 재시도해도 같다.
        log("token unavailable -> TERMINAL (Firebase 미초기화 또는 Play 서비스 없음): ${e.describe()}")
        return FcmTokenOutcome.TERMINAL
    }
    val result = repository.registerFcmToken(token)
    val outcome = result.toOutcome()
    log("DEV-001 register ${result.describe()} -> $outcome")
    return outcome
}

/** logcat 진단용 tag(#424). `adb logcat -s GilpickFcm`. */
internal const val FCM_LOG_TAG = "GilpickFcm"

private fun logFcm(message: String) {
    Log.i(FCM_LOG_TAG, message)
}

/** 예외 종류와 메시지만 남긴다. FCM 예외 메시지에는 토큰이 들어 있지 않다. */
private fun Throwable.describe(): String = "${javaClass.simpleName}: $message"

/**
 * 등록 결과를 원인 식별이 가능하게 요약한다. 토큰·access token·요청 본문은 담지 않는다.
 * 로그인 전(session 없음)은 [AuthError.Callback]로 오므로 그 code가 그대로 보인다.
 */
private fun AuthResult<*>.describe(): String = when (this) {
    is AuthResult.Success -> "ok"
    is AuthResult.Failure -> when (val error = error) {
        is AuthError.Offline -> "offline (${error.cause.javaClass.simpleName})"
        is AuthError.Server -> "server http=${error.httpStatus} code=${error.code} retryable=${error.retryable}"
        is AuthError.Malformed -> "malformed response (${error.cause.javaClass.simpleName})"
        is AuthError.Callback -> "no session or callback error code=${error.code}"
    }
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

    override suspend fun doWork(): Result {
        logFcm("sync start attempt=${runAttemptCount + 1}")
        val outcome = syncFcmToken(
            repository = NotificationRepository.default(applicationContext),
            fetchToken = { FirebaseMessaging.getInstance().token.await() },
        )
        logFcm("sync end -> $outcome (${if (outcome == FcmTokenOutcome.RETRY) "backoff 후 재시도" else "종료"})")
        return outcome.toResult()
    }

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
