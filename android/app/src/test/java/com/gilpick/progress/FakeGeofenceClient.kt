package com.gilpick.progress

/** 등록·해제 호출만 기록하는 [GeofenceClient]. Play Services 없이 차이 계산을 검증한다. */
internal class RecordingGeofenceClient : GeofenceClient {

    val added = mutableListOf<List<String>>()
    val removed = mutableListOf<List<String>>()
    var failOnAdd = false

    override suspend fun add(targets: List<DetectionTargetDto>) {
        if (failOnAdd) throw IllegalStateException("등록 실패")
        added += targets.map { it.geofenceId }
    }

    override suspend fun remove(geofenceIds: List<String>) {
        removed += geofenceIds
    }

    fun clearLog() {
        added.clear()
        removed.clear()
    }
}

/** `SharedPreferences` 없이 같은 계약을 흉내 내는 저장소. */
internal class FakeDetectionSessionStore : DetectionSessionStore {
    private var value: DetectionSession? = null
    override val current: DetectionSession? get() = value
    override fun save(tripId: String, date: String) {
        value = DetectionSession(tripId, date)
    }

    override fun clear() {
        value = null
    }
}
