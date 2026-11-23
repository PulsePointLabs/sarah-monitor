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
                if (System.currentTimeMillis() - lastSummaryAt >= 30_000L) refreshLiveSummary()
            }
        }
    }

    suspend fun start(category: SessionCategory = SessionCategory.General, title: String? = null, details: String? = null): SessionEntity = mutex.withLock {
        _active.value?.let { return it }
        val now = System.currentTimeMillis()
        val session = SessionEntity(id = UUID.randomUUID().toString(), title = title ?: defaultTitle(now), startTimeMs = now, category = category.name, details = details)
        withContext(Dispatchers.IO) { dao.upsertSession(session) }
        synchronized(lock) { hr.clear(); rr.clear(); pelvic.clear(); emg.clear(); ecgTs.clear(); ecgUv.clear(); ecgChunks.clear(); lastEcgTs = Long.MIN_VALUE }
        _active.value = session
        session
    }

    private var lastSummaryAt = 0L

    /**
     * Keeps the recording's summary (averages, HRV, events, sparkline) current while it runs, so the session list
     * and review show it like any saved session. Holds the lock so it can't overwrite a session that just stopped.
     */
    suspend fun refreshLiveSummary() {
        mutex.withLock {
            val current = _active.value ?: return
            lastSummaryAt = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                runCatching {
                    val base = (dao.getSession(current.id) ?: current).copy(endTimeMs = null)
                    dao.upsertSession(SessionAnalysis.summarize(base, dao.getHr(base.id), dao.getRr(base.id), dao.getPelvic(base.id), dao.getMarkers(base.id).size))
                }
            }
        }
    }

    suspend fun stop(): SessionEntity? {
        val session = mutex.withLock {
            val current = _active.value ?: return null
            _active.value = null
            current
        }
        flushEcg(force = true, sessionId = session.id)
        flush(session.id)
        val endedAt = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            // Read-modify-write under the lock, so a details edit at the same moment isn't lost.
            mutex.withLock {
                val ended = (dao.getSession(session.id) ?: session).copy(endTimeMs = endedAt)
                val summarized = SessionAnalysis.summarize(ended, dao.getHr(ended.id), dao.getRr(ended.id), dao.getPelvic(ended.id), dao.getMarkers(ended.id).size)
                dao.upsertSession(summarized)
                summarized
            }
        }
    }

    /**
     * Picks a stopped session back up: recording continues into it (the time in between shows as a gap), with
     * its type, devices and events intact and whatever the app has gained since.
     */
    suspend fun resume(id: String): SessionEntity? = mutex.withLock {
        if (_active.value != null) return null
        val s = withContext(Dispatchers.IO) { dao.getSession(id) } ?: return null
        val live = s.copy(endTimeMs = null)
        withContext(Dispatchers.IO) { dao.upsertSession(live) }
        synchronized(lock) { hr.clear(); rr.clear(); pelvic.clear(); emg.clear(); ecgTs.clear(); ecgUv.clear(); ecgChunks.clear(); lastEcgTs = Long.MIN_VALUE }
        _active.value = live
        live
    }

    /** Saves a session's details (live or past) without racing the summary refresh. */
    suspend fun updateDetails(id: String, json: String?) = withContext(Dispatchers.IO) { mutex.withLock { dao.setDetails(id, json) } }

    fun onHeart(snapshot: HeartSnapshot, rrRows: List<RrSampleEntity>, stress: Int? = null, breathsPerMin: Double? = null) {
        val session = _active.value ?: return
        val t = snapshot.updatedAtMs ?: return
        val bpm = snapshot.heartRate ?: return
        synchronized(lock) {
            hr += HrSampleEntity(
                sessionId = session.id, timestampMs = t, bpm = bpm, rmssdMs = snapshot.rmssdMs, sdnnMs = snapshot.sdnnMs,
                pnn50Percent = snapshot.pnn50Percent, load = snapshot.load.score, contact = snapshot.contact,
                stress = stress, breathsPerMin = breathsPerMin
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

    suspend fun addMarker(timestampMs: Long, types: Set<String>, note: String, checkIn: CheckIn = CheckIn(), snapshot: String? = null): MarkerEntity? {
        val session = _active.value ?: return null
        val marker = MarkerEntity(
            UUID.randomUUID().toString(), session.id, timestampMs, MarkerTypes.join(types), MarkerTypes.label(types), note.trim(),
            wellbeing = checkIn.wellbeing, selfStress = checkIn.selfStress, snapshot = snapshot
        )
        withContext(Dispatchers.IO) { dao.upsertMarker(marker) }
        scope.launch(Dispatchers.IO) { flush(); refreshLiveSummary() }
        return marker
    }

    companion object {
        private const val ECG_CHUNK_SAMPLES = 1_300 // about 10 s at 130 Hz
        private val titleFormat = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a")
        fun defaultTitle(ms: Long): String = "Session · " + Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(titleFormat)
    }
}

/** Optional self-report attached to an event: how you feel (1–5) and how stressed (0–10). */
data class CheckIn(
    val wellbeing: Int? = null,
    val selfStress: Int? = null,
    /** Device this event is about (session details gear id). */
    val gearId: String? = null,
    /** Your verdict on the stress estimate at that moment: "right", "high" (reads too high) or "low". */
    val stressFeedback: String? = null
) {
    val empty: Boolean get() = wellbeing == null && selfStress == null
    val label: String?
        get() = listOfNotNull(wellbeing?.let { "feeling ${FEELINGS.getOrNull(it - 1) ?: it}" }, selfStress?.let { "stress $it/10" }).joinToString(" · ").ifBlank { null }

    companion object {
        val FEELINGS = listOf("awful", "low", "OK", "good", "great")
        fun of(m: MarkerEntity) = CheckIn(m.wellbeing, m.selfStress)
    }
}

/** What a recording is: everyday physiology, a kind of sexual session, or a mix (e.g. a 24 h recording). */
enum class SessionCategory(val label: String, val groups: List<MarkerTypes.Group>) {
    General("General", listOf(MarkerTypes.Group.General)),
    Masturbation("Masturbation", listOf(MarkerTypes.Group.Sexual)),
    Sexual("Sexual activity", listOf(MarkerTypes.Group.Sexual)),
    Exploration("Body exploration", listOf(MarkerTypes.Group.Procedure, MarkerTypes.Group.Sexual)),
    Hybrid("Hybrid (general + sexual)", listOf(MarkerTypes.Group.General, MarkerTypes.Group.Sexual, MarkerTypes.Group.Procedure)),
    /** Started automatically by a Kegel set (not offered in the record chooser). */
    Kegel("Kegel set", listOf(MarkerTypes.Group.General));

    val pickable: Boolean get() = this != Kegel

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
    enum class Group(val title: String) { General("General"), Sexual("Sexual"), Procedure("Procedure steps") }

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
        Type("enema", "Enema", Group.Sexual),
        // Procedure steps (body exploration): the device timeline, step by step.
        Type("prep", "Prep / lube", Group.Procedure),
        Type("insert", "Insertion start", Group.Procedure),
        Type("resistance", "Resistance", Group.Procedure),
        Type("seated", "Fully inserted", Group.Procedure),
        Type("balloon", "Balloon inflated", Group.Procedure),
        Type("urine", "Urine return", Group.Procedure),
        Type("instill", "Instillation start", Group.Procedure),
        Type("instill_end", "Instillation end", Group.Procedure),
        Type("hold", "Retention / hold", Group.Procedure),
        Type("urge", "Urge / cramp", Group.Procedure),
        Type("release", "Release / expel", Group.Procedure),
        Type("adjust", "Adjust / reposition", Group.Procedure),
        Type("remove", "Removal", Group.Procedure),
        Type("discomfort", "Discomfort", Group.Procedure),
        Type("estim_change", "E-stim change", Group.Procedure),
        // Said or noted in passing during any sexual / exploration session.
        Type("sensation", "Sensation", Group.Sexual),
        Type("finding", "Body finding", Group.Sexual)
    )

    fun inGroup(group: Group): List<Type> = all.filter { it.group == group }

    fun join(types: Set<String>): String = all.map { it.id }.filter { it in types }.ifEmpty { listOf("event") }.joinToString(",")
    fun split(types: String): Set<String> = types.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun label(types: Set<String>): String = all.filter { it.id in types }.joinToString(" + ") { it.label }.ifEmpty { "Event" }
}
