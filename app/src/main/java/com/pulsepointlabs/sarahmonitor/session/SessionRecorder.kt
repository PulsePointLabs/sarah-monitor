package com.pulsepointlabs.sarahmonitor.session

import android.content.Context
import com.pulsepointlabs.sarahmonitor.ble.EcgSample
import com.pulsepointlabs.sarahmonitor.core.HeartSnapshot
import com.pulsepointlabs.sarahmonitor.hrv.RrSampleEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Writes the live streams to the session database while a session is running. Rows are buffered and
 * flushed every 2 s; an unfinished session (app killed, phone restarted) carries on when the app comes back.
 */
class SessionRecorder(context: Context, private val scope: CoroutineScope) {
    val dao: SessionDao = SessionDatabase.get(context).dao()

    private val _active = MutableStateFlow<SessionEntity?>(null)
    val active: StateFlow<SessionEntity?> = _active

    private val lock = Any()
    private val mutex = Mutex()
    private var hr = mutableListOf<HrSampleEntity>()
    private var rr = mutableListOf<RrEntity>()
    private var pelvic = mutableListOf<PelvicSampleEntity>()
    private var emg = mutableListOf<EmgSampleEntity>()
    private val ecgTs = ArrayList<Long>()
    private val ecgUv = ArrayList<Int>()
    private var lastEcgTs = Long.MIN_VALUE
    private var ecgChunks = mutableListOf<EcgChunkEntity>()

    init {
        scope.launch(Dispatchers.IO) {
            _active.value = dao.getActiveSession()
            while (isActive) {
                delay(2_000L)
                flush()
            }
        }
    }

    suspend fun start(category: SessionCategory = SessionCategory.General): SessionEntity = mutex.withLock {
        _active.value?.let { return it }
        val now = System.currentTimeMillis()
        val session = SessionEntity(id = UUID.randomUUID().toString(), title = defaultTitle(now), startTimeMs = now, category = category.name)
        withContext(Dispatchers.IO) { dao.upsertSession(session) }
        synchronized(lock) { hr.clear(); rr.clear(); pelvic.clear(); emg.clear(); ecgTs.clear(); ecgUv.clear(); ecgChunks.clear(); lastEcgTs = Long.MIN_VALUE }
        _active.value = session
        session
    }

    suspend fun stop(): SessionEntity? {
        val session = mutex.withLock {
            val current = _active.value ?: return null
            _active.value = null
            current
        }
        flushEcg(force = true, sessionId = session.id)
        flush(session.id)
        return withContext(Dispatchers.IO) {
            val ended = session.copy(endTimeMs = System.currentTimeMillis())
            val summarized = SessionAnalysis.summarize(ended, dao.getHr(ended.id), dao.getRr(ended.id), dao.getPelvic(ended.id), dao.getMarkers(ended.id).size)
            dao.upsertSession(summarized)
            summarized
        }
    }

    fun onHeart(snapshot: HeartSnapshot, rrRows: List<RrSampleEntity>) {
        val session = _active.value ?: return
        val t = snapshot.updatedAtMs ?: return
        val bpm = snapshot.heartRate ?: return
        synchronized(lock) {
            hr += HrSampleEntity(
                sessionId = session.id, timestampMs = t, bpm = bpm, rmssdMs = snapshot.rmssdMs, sdnnMs = snapshot.sdnnMs,
                pnn50Percent = snapshot.pnn50Percent, load = snapshot.load.score, contact = snapshot.contact
            )
            rrRows.forEach {
                rr += RrEntity(
                    sessionId = session.id, timestampMs = t, rawMs = it.rawIntervalMs, cleanMs = it.cleanIntervalMs,
                    accepted = it.accepted, artifact = it.artifact, rejectionReason = it.rejectionReason
                )
            }
        }
    }

    fun onPelvic(timestampMs: Long, pressureKpa: Double, aboveRestKpa: Double?, holding: Boolean) {
        val session = _active.value ?: return
        synchronized(lock) { pelvic += PelvicSampleEntity(sessionId = session.id, timestampMs = timestampMs, pressureKpa = pressureKpa, aboveRestKpa = aboveRestKpa, holding = holding) }
    }

    fun onEmg(row: EmgSampleEntity) {
        val session = _active.value ?: return
        synchronized(lock) { emg += row.copy(sessionId = session.id) }
    }

    fun onEcg(batch: List<EcgSample>) {
        val session = _active.value ?: return
        synchronized(lock) {
            batch.forEach {
                if (it.timestampMs <= lastEcgTs) return@forEach
                lastEcgTs = it.timestampMs
                ecgTs += it.timestampMs
                ecgUv += it.microvolts
            }
        }
        flushEcg(force = false, sessionId = session.id)
    }

    private fun flushEcg(force: Boolean, sessionId: String) {
        synchronized(lock) {
            if (ecgTs.isEmpty() || (!force && ecgTs.size < ECG_CHUNK_SAMPLES)) return
            val ts = ecgTs.toLongArray()
            val uv = ecgUv.toIntArray()
            ecgChunks += EcgChunkEntity(sessionId = sessionId, startMs = ts.first(), endMs = ts.last(), sampleCount = ts.size, data = EcgCodec.encode(ts.first(), ts, uv))
            ecgTs.clear(); ecgUv.clear()
        }
    }

    private suspend fun flush(sessionId: String? = null) {
        val (h, r, p, e) = synchronized(lock) {
            val out = listOf(hr, rr, pelvic, ecgChunks)
            hr = mutableListOf(); rr = mutableListOf(); pelvic = mutableListOf(); ecgChunks = mutableListOf()
            out
        }
        val m = synchronized(lock) { emg.also { emg = mutableListOf() } }
        if (h.isEmpty() && r.isEmpty() && p.isEmpty() && e.isEmpty() && m.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("UNCHECKED_CAST")
                dao.insertHr(h as List<HrSampleEntity>); dao.insertRr(r as List<RrEntity>)
                @Suppress("UNCHECKED_CAST")
                dao.insertPelvic(p as List<PelvicSampleEntity>); dao.insertEcg(e as List<EcgChunkEntity>)
                if (m.isNotEmpty()) dao.insertEmg(m)
            }
        }
    }

    suspend fun addMarker(timestampMs: Long, types: Set<String>, note: String): MarkerEntity? {
        val session = _active.value ?: return null
        val marker = MarkerEntity(UUID.randomUUID().toString(), session.id, timestampMs, MarkerTypes.join(types), MarkerTypes.label(types), note.trim())
        withContext(Dispatchers.IO) { dao.upsertMarker(marker) }
        return marker
    }

    companion object {
        private const val ECG_CHUNK_SAMPLES = 1_300 // about 10 s at 130 Hz
        private val titleFormat = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a")
        fun defaultTitle(ms: Long): String = "Session · " + Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(titleFormat)
    }
}

/** What a recording is: everyday physiology, a kind of sexual session, or a mix (e.g. a 24 h recording). */
enum class SessionCategory(val label: String, val groups: List<MarkerTypes.Group>) {
    General("General", listOf(MarkerTypes.Group.General)),
    Masturbation("Masturbation", listOf(MarkerTypes.Group.Sexual)),
    Sexual("Sexual activity", listOf(MarkerTypes.Group.Sexual)),
    Exploration("Body exploration", listOf(MarkerTypes.Group.Sexual)),
    Hybrid("Hybrid (general + sexual)", listOf(MarkerTypes.Group.General, MarkerTypes.Group.Sexual));

    val sexual: Boolean get() = MarkerTypes.Group.Sexual in groups

    companion object {
        fun of(id: String?): SessionCategory? = entries.firstOrNull { it.name == id }

        private const val PREFS = "sarahmonitor_session_category"
        /** The category picked last time (default for the next recording). */
        fun last(c: android.content.Context): SessionCategory =
            of(c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString("last", null)) ?: General
        fun remember(c: android.content.Context, cat: SessionCategory) =
            c.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString("last", cat.name).apply()
    }
}

/** Event types: SarahVS's general set plus sexual-response types, shown in two groups. */
object MarkerTypes {
    enum class Group(val title: String) { General("General"), Sexual("Sexual") }

    data class Type(val id: String, val label: String, val group: Group)

    val all = listOf(
        Type("event", "Event", Group.General),
        Type("symptom", "Symptom", Group.General),
        Type("activity", "Activity", Group.General),
        Type("position", "Position change", Group.General),
        Type("intake", "Intake", Group.General),
        Type("recovery", "Recovery", Group.General),
        Type("stimulation", "Stimulation", Group.Sexual),
        Type("arousal", "Arousal", Group.Sexual),
        Type("plateau", "Plateau", Group.Sexual),
        Type("edge", "Edge", Group.Sexual),
        Type("near", "Near climax", Group.Sexual),
        Type("orgasm", "Orgasm", Group.Sexual),
        Type("ejaculation", "Ejaculation", Group.Sexual),
        Type("resolution", "Resolution", Group.Sexual),
        Type("catheter", "Catheter", Group.Sexual),
        Type("enema", "Enema", Group.Sexual)
    )

    fun inGroup(group: Group): List<Type> = all.filter { it.group == group }

    fun join(types: Set<String>): String = all.map { it.id }.filter { it in types }.ifEmpty { listOf("event") }.joinToString(",")
    fun split(types: String): Set<String> = types.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun label(types: Set<String>): String = all.filter { it.id in types }.joinToString(" + ") { it.label }.ifEmpty { "Event" }
}
