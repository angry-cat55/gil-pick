package com.gilpick.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #424: 실제 worker를 한 번 돌려 logcat(`GilpickFcm`)에 단계별 원인이 남는지 본다.
 *
 * 계측 앱은 로그인 session이 없으므로 결과는 항상 TERMINAL이다(google-services.json 유무와 무관:
 * 없으면 token unavailable, 있으면 DEV-001 no session). 확인은 `adb logcat -d -s GilpickFcm`으로 한다.
 */
@RunWith(AndroidJUnit4::class)
class FcmTokenSyncWorkerLogTest {

    @Test
    fun 실제_worker는_session_없이_TERMINAL로_끝나고_원인을_로그로_남긴다() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val worker = TestListenableWorkerBuilder<FcmTokenSyncWorker>(context).build()

        val result = runBlocking { worker.doWork() }

        assertEquals(ListenableWorker.Result.failure(), result)
    }
}
