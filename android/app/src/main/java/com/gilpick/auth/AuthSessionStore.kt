package com.gilpick.auth

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toPath

/** 복호화된 session. 화면·network 계층이 실제로 사용하는 형태다. */
data class ActiveSession(
    val sessionId: String,
    val userId: String,
    val nickname: String?,
    val profileImageUrl: String?,
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtEpochSeconds: Long,
    val refreshExpiresAtEpochSeconds: Long,
)

/** 서버 폐기를 재시도할 때 필요한 복호화된 logout 자격. */
data class RevocationRequest(
    val revocationOperationId: String,
    val refreshToken: String,
    val deviceId: String,
)

/**
 * `auth_session.pb`에 대한 유일한 접근 지점.
 *
 * Token은 항상 [SessionCipher]로 암호화한 뒤 저장하며, 평문은 memory에만 존재한다.
 * DataStore가 쓰기를 직렬화하므로 Token pair 교체는 한 번의 update로 원자적으로 끝난다.
 *
 * @property dataStore 암호문과 metadata를 담는 store.
 * @property cipher Token 암·복호화 구현.
 */
class AuthSessionStore(
    private val dataStore: DataStore<AuthSessionState>,
    private val cipher: SessionCipher,
) {

    /** 현재 로그인 여부만 필요한 화면을 위한 stream. */
    val hasSession: Flow<Boolean> = dataStore.data.map { it.sessionEnvelope != null }

    /**
     * 설치 단위 기기 ID를 반환한다.
     *
     * 최초 호출에서 UUID를 생성해 저장하고 이후에는 같은 값을 재사용한다. 앱 데이터를
     * 삭제하면 새 값이 생성된다.
     */
    suspend fun deviceId(): String =
        dataStore.updateData { state ->
            if (state.deviceId.isNotEmpty()) state
            else state.copy(deviceId = UUID.randomUUID().toString())
        }.deviceId

    /**
     * Access·Refresh Token pair와 사용자 정보를 한 번의 update로 교체한다.
     *
     * 부분 저장이 생기지 않도록 암호화를 마친 뒤 하나의 envelope로 기록한다.
     */
    suspend fun saveSession(
        sessionId: String,
        userId: String,
        nickname: String?,
        profileImageUrl: String?,
        accessToken: String,
        refreshToken: String,
        accessExpiresAtEpochSeconds: Long,
        refreshExpiresAtEpochSeconds: Long,
    ) {
        val envelope = SessionEnvelope(
            sessionId = sessionId,
            userId = userId,
            nickname = nickname,
            profileImageUrl = profileImageUrl,
            accessToken = cipher.encrypt(accessToken),
            refreshToken = cipher.encrypt(refreshToken),
            accessExpiresAtEpochSeconds = accessExpiresAtEpochSeconds,
            refreshExpiresAtEpochSeconds = refreshExpiresAtEpochSeconds,
        )
        dataStore.updateData { it.copy(sessionEnvelope = envelope) }
    }

    /**
     * 저장된 session을 복호화해 반환한다.
     *
     * key 무효화로 복호화할 수 없으면 읽을 수 없는 local data를 제거하고 `null`을 반환해
     * 로그아웃 상태로 되돌린다.
     */
    suspend fun loadSession(): ActiveSession? {
        val envelope = dataStore.data.first().sessionEnvelope ?: return null
        return try {
            ActiveSession(
                sessionId = envelope.sessionId,
                userId = envelope.userId,
                nickname = envelope.nickname,
                profileImageUrl = envelope.profileImageUrl,
                accessToken = cipher.decrypt(envelope.accessToken),
                refreshToken = cipher.decrypt(envelope.refreshToken),
                accessExpiresAtEpochSeconds = envelope.accessExpiresAtEpochSeconds,
                refreshExpiresAtEpochSeconds = envelope.refreshExpiresAtEpochSeconds,
            )
        } catch (e: SessionCipherUnavailableException) {
            clearUnreadable()
            null
        }
    }

    /** 현재 session만 제거한다. deviceId와 pending revocation은 유지한다. */
    suspend fun clearSession() {
        dataStore.updateData { it.copy(sessionEnvelope = null) }
    }

    /**
     * 서버 폐기 대기 항목을 추가하고 그 operation ID를 반환한다.
     *
     * 요청마다 새 ID를 부여해 같은 기기의 재로그인·복수 logout 요청이 서로의 재시도
     * 작업과 충돌하지 않게 한다.
     */
    suspend fun enqueueRevocation(refreshToken: String, deviceId: String): String {
        val operationId = UUID.randomUUID().toString()
        val pending = PendingRevocation(
            revocationOperationId = operationId,
            refreshToken = cipher.encrypt(refreshToken),
            deviceId = deviceId,
        )
        dataStore.updateData { it.copy(pendingRevocations = it.pendingRevocations + pending) }
        return operationId
    }

    /**
     * [revocationOperationId]에 해당하는 대기 항목을 복호화해 반환한다.
     *
     * 항목이 없거나 key 무효화로 복호화할 수 없으면 해당 항목을 제거하고 `null`을 반환한다.
     */
    suspend fun loadRevocation(revocationOperationId: String): RevocationRequest? {
        val pending = dataStore.data.first().pendingRevocations
            .firstOrNull { it.revocationOperationId == revocationOperationId }
            ?: return null
        return try {
            RevocationRequest(
                revocationOperationId = pending.revocationOperationId,
                refreshToken = cipher.decrypt(pending.refreshToken),
                deviceId = pending.deviceId,
            )
        } catch (e: SessionCipherUnavailableException) {
            removeRevocation(revocationOperationId)
            null
        }
    }

    /** 폐기가 끝났거나 더 진행할 수 없는 대기 항목을 제거한다. */
    suspend fun removeRevocation(revocationOperationId: String) {
        dataStore.updateData { state ->
            state.copy(
                pendingRevocations = state.pendingRevocations
                    .filterNot { it.revocationOperationId == revocationOperationId },
            )
        }
    }

    /** 읽을 수 없게 된 session과 대기 항목을 제거하되 deviceId는 보존한다. */
    private suspend fun clearUnreadable() {
        dataStore.updateData {
            it.copy(sessionEnvelope = null, pendingRevocations = emptyList())
        }
    }

    companion object {
        /** 암호문만 담기므로 backup·data extraction에서 제외한다. */
        const val FILE_NAME = "auth_session.pb"

        /** 앱 private 저장소에 store를 만든다. */
        fun create(context: Context, cipher: SessionCipher = KeystoreSessionCipher()): AuthSessionStore =
            AuthSessionStore(
                dataStore = createDataStore(File(context.filesDir, FILE_NAME)),
                cipher = cipher,
            )

        /**
         * 임의 경로에 store를 만든다. test에서 임시 디렉터리를 쓸 때 사용한다.
         *
         * DataStore 기본 `produceFile` 저장소는 임시 파일을 `File.renameTo`로 옮기는데,
         * Windows에서는 대상 파일이 이미 있으면 실패해 두 번째 쓰기부터 IOException이
         * 난다. okio 저장소는 `Files.move(..., REPLACE_EXISTING)`을 쓰므로 개발용 JVM
         * unit test와 Android 기기에서 모두 동작한다.
         *
         * 한 파일에는 store 인스턴스가 하나만 살아 있어야 한다. process 재시작을 모사하는
         * test는 [scope]를 직접 넘겨 이전 인스턴스를 취소한 뒤 새로 만든다.
         */
        fun createDataStore(
            file: File,
            scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ): DataStore<AuthSessionState> =
            DataStoreFactory.create(
                storage = OkioStorage(
                    fileSystem = FileSystem.SYSTEM,
                    serializer = AuthSessionSerializer,
                    producePath = { file.absolutePath.toPath() },
                ),
                corruptionHandler = ReplaceFileCorruptionHandler { AuthSessionState() },
                scope = scope,
            )
    }
}

/** `auth_session.pb`의 protobuf 직렬화 규칙. */
@OptIn(ExperimentalSerializationApi::class)
internal object AuthSessionSerializer : OkioSerializer<AuthSessionState> {
    override val defaultValue: AuthSessionState = AuthSessionState()

    override suspend fun readFrom(source: BufferedSource): AuthSessionState =
        try {
            val bytes = source.readByteArray()
            if (bytes.isEmpty()) defaultValue
            else ProtoBuf.decodeFromByteArray(AuthSessionState.serializer(), bytes)
        } catch (e: SerializationException) {
            throw CorruptionException("auth_session.pb를 읽을 수 없습니다", e)
        }

    override suspend fun writeTo(t: AuthSessionState, sink: BufferedSink) {
        sink.write(ProtoBuf.encodeToByteArray(AuthSessionState.serializer(), t))
    }
}
