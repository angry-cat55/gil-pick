package com.gilpick.auth

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.gilpick.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 폐기 대기 항목 한 건을 처리한 결과. */
enum class RevocationOutcome {
    /** 서버 자격이 없어졌음이 확정됐다. 대기 항목을 제거했다. */
    COMPLETED,

    /** 결과를 확인할 수 없다. 대기 항목을 남기고 다시 시도해야 한다. */
    RETRY,

    /** 이 자격으로는 폐기할 수 없다. 재시도해도 같으므로 대기 항목을 제거했다. */
    TERMINAL,
}

/**
 * 폐기 대기 항목 하나를 서버에 반영한다.
 *
 * WorkManager 결과 정책을 Context 없이 검증할 수 있도록 worker 본문에서 분리했다.
 * 자격이 이미 무효이거나 만료됐다면 폐기 목적은 달성된 것이므로 성공과 동등하게
 * 처리한다. 기기 불일치는 이 기기의 자격이 아니라는 뜻이라 재시도해도 바뀌지 않는다.
 */
internal suspend fun revokePendingSession(
    store: AuthSessionStore,
    api: AuthService,
    revocationOperationId: String,
): RevocationOutcome {
    // 항목이 없거나 key 손실로 복호화할 수 없으면 store가 정리하므로 할 일이 없다.
    val pending = store.loadRevocation(revocationOperationId) ?: return RevocationOutcome.COMPLETED

    val result = try {
        api.logout(RefreshTokenRequest(pending.refreshToken, pending.deviceId)).toEmptyAuthResult()
    } catch (e: IOException) {
        AuthResult.Failure(AuthError.Offline(e))
    }

    return when (result) {
        is AuthResult.Success -> {
            store.removeRevocation(revocationOperationId)
            RevocationOutcome.COMPLETED
        }

        is AuthResult.Failure -> when (val error = result.error) {
            // 연결 복구 후 다시 시도한다.
            is AuthError.Offline -> RevocationOutcome.RETRY

            is AuthError.Server -> when {
                error.code == AuthErrorCodes.INVALID_REFRESH_TOKEN ||
                    error.code == AuthErrorCodes.TOKEN_EXPIRED -> {
                    store.removeRevocation(revocationOperationId)
                    RevocationOutcome.COMPLETED
                }

                // 일시적 장애만 backoff 재시도 대상이다.
                error.retryable || error.httpStatus == 429 || error.httpStatus >= 500 ->
                    RevocationOutcome.RETRY

                else -> {
                    // DEVICE_MISMATCH 등. 다른 기기 session은 건드리지 않고 끝낸다.
                    store.removeRevocation(revocationOperationId)
                    RevocationOutcome.TERMINAL
                }
            }

            // 계약과 다른 응답은 재시도해도 같다.
            is AuthError.Malformed, is AuthError.Callback -> {
                store.removeRevocation(revocationOperationId)
                RevocationOutcome.TERMINAL
            }
        }
    }
}

/**
 * 오프라인 로그아웃의 서버 폐기를 연결 복구 후 재시도하는 작업.
 *
 * 입력에는 operation ID만 담고 Token은 담지 않는다. WorkManager 입력·출력은 앱 밖에
 * 남을 수 있으므로 자격 원문은 언제나 [AuthSessionStore]의 암호문에만 둔다.
 */
class SessionRevocationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val operationId = inputData.getString(KEY_OPERATION_ID) ?: return Result.failure()
        val outcome = revokePendingSession(
            store = AuthSessionStore.create(applicationContext),
            api = createAuthRetrofit(BuildConfig.API_BASE_URL).create(AuthService::class.java),
            revocationOperationId = operationId,
        )
        return when (outcome) {
            RevocationOutcome.COMPLETED -> Result.success()
            RevocationOutcome.RETRY -> Result.retry()
            RevocationOutcome.TERMINAL -> Result.failure()
        }
    }

    companion object {
        /** worker 입력에 담는 유일한 값. */
        const val KEY_OPERATION_ID = "revocationOperationId"

        /** 요청별 unique work 이름. 같은 기기의 복수 로그아웃이 서로를 밀어내지 않는다. */
        fun uniqueWorkName(revocationOperationId: String): String =
            "auth-revocation-$revocationOperationId"

        /** Token 없이 operation ID만 담은 worker 입력. */
        fun inputData(revocationOperationId: String): Data =
            workDataOf(KEY_OPERATION_ID to revocationOperationId)

        /**
         * [AuthRepository]에 넘길 폐기 예약 함수를 만든다.
         *
         * 이미 같은 operation의 작업이 있으면 그대로 두어 재시도 backoff를 초기화하지
         * 않는다.
         */
        fun scheduler(context: Context): (String) -> Unit {
            val appContext = context.applicationContext
            return { operationId ->
                WorkManager.getInstance(appContext).enqueueUniqueWork(
                    uniqueWorkName(operationId),
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<SessionRevocationWorker>()
                        .setInputData(inputData(operationId))
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build(),
                        )
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .build(),
                )
            }
        }
    }
}
