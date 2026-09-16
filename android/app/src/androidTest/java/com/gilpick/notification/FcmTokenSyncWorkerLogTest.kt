package com.gilpick.notification

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #424: 실제 worker를 한 번 돌려 logcat(`GilpickFcm`)에 단계별 원인이 남는지 본다.
 *
 * 로그인 상태와 Firebase 네트워크에 따라 결과가 달라지는 진단 절차라 일반 계측 suite에서는 실행하지
 * 않는다. 결과 매핑은 `FcmTokenSyncWorkerTest`가 단위 test로 고정하고, 실기기 확인은 #634 checklist로
 * 기록한다.
 */
@RunWith(AndroidJUnit4::class)
@Ignore("실기기 로그인·Firebase 상태에 의존하는 수동 진단 절차")
class FcmTokenSyncWorkerLogTest {

    @Test
    fun 실제_worker는_session_없이_TERMINAL로_끝나고_원인을_로그로_남긴다() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val worker = TestListenableWorkerBuilder<FcmTokenSyncWorker>(context).build()

        val result = runBlocking { worker.doWork() }

        assertEquals(ListenableWorker.Result.failure(), result)
    }
}
