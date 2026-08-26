package com.gilpick.auth

import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.gilpick.R
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/**
 * T045: 실제 기기 저장소에 Token 원문이 남지 않는지 검증.
 *
 * unit test는 test용 cipher로 파일 내용을 확인하지만, 여기서는 실제 AndroidKeyStore
 * 암호화와 앱의 실제 저장 경로를 그대로 쓴다. DataStore 파일, WorkManager database,
 * backup·기기 이전 규칙 세 곳을 함께 본다.
 */
@RunWith(AndroidJUnit4::class)
class AuthSecureStorageTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = AuthSessionStore.create(context)

    private var operationId: String? = null

    @After
    fun tearDown() = runBlocking {
        operationId?.let {
            WorkManager.getInstance(context)
                .cancelUniqueWork(SessionRevocationWorker.uniqueWorkName(it))
            store.removeRevocation(it)
        }
        store.clearSession()
    }

    @Test
    fun 앱_저장소와_WorkManager에_Token_원문이_남지_않는다() = runBlocking {
        store.saveSession(
            sessionId = "session-1",
            userId = USER_ID,
            nickname = "길픽",
            profileImageUrl = null,
            accessToken = ACCESS_TOKEN,
            refreshToken = REFRESH_TOKEN,
            accessExpiresAtEpochSeconds = 3_600,
            refreshExpiresAtEpochSeconds = 2_592_000,
        )
        val enqueued = store.enqueueRevocation(REFRESH_TOKEN, DEVICE_ID).also { operationId = it }
        SessionRevocationWorker.scheduler(context)(enqueued)
        // 저장·enqueue 결과가 디스크에 반영된 뒤 읽는다.
        store.loadSession()
        store.loadRevocation(enqueued)

        val scanned = buildList {
            add(File(context.filesDir, AuthSessionStore.FILE_NAME))
            addAll(context.getDatabasePath(WORK_DB).parentFile?.listFiles().orEmpty())
        }.filter { it.isFile && it.length() > 0 }

        assertTrue("검사한 파일이 있어야 한다", scanned.isNotEmpty())
        scanned.forEach { file ->
            val raw = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse("${file.name}에 Access Token 원문이 있으면 안 된다", raw.contains(ACCESS_TOKEN))
            assertFalse("${file.name}에 Refresh Token 원문이 있으면 안 된다", raw.contains(REFRESH_TOKEN))
        }
    }

    @Test
    fun session_파일이_backup과_기기_이전에서_제외된다() {
        assertTrue(
            "API 30 이하 backup 규칙에서 제외해야 한다",
            excludedFiles(R.xml.backup_rules).contains(AuthSessionStore.FILE_NAME),
        )
        // cloud-backup과 device-transfer 두 곳 모두에 제외 항목이 있어야 한다.
        assertTrue(
            "API 31 이상 backup·기기 이전 규칙에서 제외해야 한다",
            excludedFiles(R.xml.data_extraction_rules)
                .count { it == AuthSessionStore.FILE_NAME } >= 2,
        )
    }

    /** backup 규칙 XML에서 `exclude`한 file domain 경로를 모은다. */
    private fun excludedFiles(xmlResId: Int): List<String> {
        val parser: XmlResourceParser = context.resources.getXml(xmlResId)
        val excluded = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG || parser.name != "exclude") continue
            // backup 규칙의 attribute에는 android namespace prefix가 없다.
            val domain = parser.getAttributeValue(null, "domain")
            val path = parser.getAttributeValue(null, "path")
            if (domain == "file" && path != null) excluded += path
        }
        parser.close()
        return excluded
    }

    private companion object {
        const val WORK_DB = "androidx.work.workdb"
        const val USER_ID = "33333333-4444-4555-8666-777777777777"
        const val DEVICE_ID = "11111111-2222-4333-8444-555555555555"
        const val ACCESS_TOKEN = "access-token-plaintext-canary-value"
        const val REFRESH_TOKEN = "session-1.refresh-token-plaintext-canary-value"
    }
}
