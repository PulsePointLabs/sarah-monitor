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
    private var sensorEvents = mutableListOf<SensorEventEntity>()
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
        synchronized(lock) { hr.clear(); rr.clear(); pelvic.clear(); emg.clear(); sensorEvents.clear(); ecgTs.clear(); ecgUv.clear(); ecgChunks.clear(); lastEcgTs = Long.MIN_VALUE }
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
                    dao.upsertSession(dao.getMarkers(base.id).let { m -> SessionAnalysis.summarize(base, dao.getHr(base.id), dao.getRr(base.id), dao.getPelvic(base.id), m.size, m) })
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
                val summarized = dao.getMarkers(ended.id).let { m -> SessionAnalysis.summarize(ended, dao.getHr(ended.id), dao.getRr(ended.id), dao.getPelvic(ended.id), m.size, m) }
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
        synchronized(lock) { hr.clear(); rr.clear(); pelvic.clear(); emg.clear(); sensorEvents.clear(); ecgTs.clear(); ecgUv.clear(); ecgChunks.clear(); lastEcgTs = Long.MIN_VALUE }
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

    fun onPelvic(timestampMs: Long, pressureKpa: Double, aboveRestKpa: Double?, holding: Boolean, quality: String?) {
        val session = _active.value ?: return
        synchronized(lock) { pelvic += PelvicSampleEntity(sessionId = session.id, timestampMs = timestampMs, pressureKpa = pressureKpa, aboveRestKpa = aboveRestKpa, holding = holding, quality = quality) }
    }

    /** A sensor connection, calibration or quality change, kept with the recording. Ignored when not recording. */
    fun onSensorEvent(timestampMs: Long, device: String, kind: String, detail: String) {
        val session = _active.value ?: return
        synchronized(lock) { sensorEvents += SensorEventEntity(sessionId = session.id, timestampMs = timestampMs, device = device, kind = kind, detail = detail) }
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
        val ev = synchronized(lock) { sensorEvents.also { sensorEvents = mutableListOf() } }
        if (h.isEmpty() && r.isEmpty() && p.isEmpty() && e.isEmpty() && m.isEmpty() && ev.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("UNCHECKED_CAST")
                dao.insertHr(h as List<HrSampleEntity>); dao.insertRr(r as List<RrEntity>)
                @Suppress("UNCHECKED_CAST")
                dao.insertPelvic(p as List<PelvicSampleEntity>); dao.insertEcg(e as List<EcgChunkEntity>)
                if (m.isNotEmpty()) dao.insertEmg(m)
                if (ev.isNotEmpty()) dao.insertSensorEvents(ev)
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
    /** [Sensor] types are offered in every session type (on every tab), whatever the category's groups. */
    enum class Group(val title: String) { General("General"), Sexual("Sexual"), Procedure("Procedure steps"), Sensor("Sensor"), Voice("Asked Sarah") }

    /** [hint] says what the type means, for Claude sorting spoken notes (and anyone reading the code). */
    data class Type(val id: String, val label: String, val group: Group, val hint: String = "")

    val all = listOf(
        Type("event", "Event", Group.General, "anything that fits no other type"),
        Type("symptom", "Symptom", Group.General, "a physical symptom: headache, nausea, palpitations, dizziness, pain, cramping, tingling, shortness of breath"),
        Type("activity", "Activity", Group.General, "what he is doing or about to do: chores, walking, working, eating, showering, gaming, watching something, going to bed"),
        Type("position", "Position change", Group.General, "lying down, sitting up, standing, changing posture"),
        Type("intake", "Intake", Group.General, "food, water or other non-psychoactive drink"),
        Type("substance", "Substance", Group.General, "a psychoactive substance taken or felt: cannabis (indica, sativa, hybrid; smoked, vaped, edible), alcohol (beer, wine, liquor), caffeine (coffee, energy drink, soda), nicotine (vape, pouch, cigarette), a medication or supplement; also how strongly he feels it (buzzed, high, intoxicated, wired, overstimulated, coming down)"),
        Type("state", "How I feel", Group.General, "his mood or mental/energy state: relaxed, calm, anxious, stressed, irritable, tired, wired, overstimulated, focused, foggy, content, low, good"),
        Type("recovery", "Recovery", Group.General, "ONLY physiological recovery after exertion or a stress response: heart rate and breathing settling after effort. Not for feeling relaxed in general (use How I feel)"),
        Type("stimulation", "Stimulation", Group.Sexual, "sexual stimulation started, changed or stopped"),
        Type("arousal", "Arousal", Group.Sexual, "sexual arousal rising or falling"),
        Type("plateau", "Plateau", Group.Sexual, "high, steady sexual arousal"),
        Type("edge", "Edge", Group.Sexual, "brought close to orgasm and backed off"),
        Type("near", "Near climax", Group.Sexual, "close to orgasm"),
        Type("orgasm", "Orgasm", Group.Sexual, "orgasm"),
        Type("ejaculation", "Ejaculation", Group.Sexual, "ejaculation"),
        Type("resolution", "Resolution", Group.Sexual, "coming down after orgasm"),
        Type("catheter", "Catheter", Group.Sexual, "anything about a urethral catheter in a sexual session"),
        Type("enema", "Enema", Group.Sexual, "anything about an enema in a sexual session"),
        // Procedure steps (body exploration): the device timeline, step by step.
        Type("prep", "Prep / lube", Group.Procedure, "preparing, cleaning, lubricating"),
        Type("insert", "Insertion start", Group.Procedure, "starting to insert a device"),
        Type("resistance", "Resistance", Group.Procedure, "resistance or tightness while inserting"),
        Type("seated", "Fully inserted", Group.Procedure, "device fully in place"),
        Type("balloon", "Balloon inflated", Group.Procedure, "inflating or deflating a balloon"),
        Type("urine", "Urine return", Group.Procedure, "urine flow or drainage"),
        Type("instill", "Instillation start", Group.Procedure, "fluid starting or continuing to go in"),
        Type("instill_end", "Instillation end", Group.Procedure, "fluid stopped going in"),
        Type("hold", "Retention / hold", Group.Procedure, "holding fluid in"),
        Type("urge", "Urge / cramp", Group.Procedure, "urge to defecate or void, cramping"),
        Type("release", "Release / expel", Group.Procedure, "letting fluid out, expelling"),
        Type("adjust", "Adjust / reposition", Group.Procedure, "adjusting or repositioning a device"),
        Type("remove", "Removal", Group.Procedure, "taking a device out"),
        Type("discomfort", "Discomfort", Group.Procedure, "pinch, pop, burning, pain during a procedure"),
        Type("estim_change", "E-stim change", Group.Procedure, "e-stim level, pads or mode changed"),
        // Said or noted in passing during any sexual / exploration session.
        Type("sensation", "Sensation", Group.Sexual, "a sensation he describes: warmth, pressure, fullness, tingling"),
        Type("finding", "Body finding", Group.Sexual, "something observed on his body: erection, softening, swelling, flushing"),
        // The measuring sensor itself (CIVET slipped, re-seated, re-inflated): stops pressure analysis until recalibrated.
        Type("sensor_issue", "Sensor problem", Group.Sensor, "the CIVET probe/sensor slipped, moved, came out, deflated or was re-seated"),
        // A question he asked Sarah out loud (her answer is kept with it). Added by hands-free, never offered as a chip.
        Type("question", "Asked Sarah", Group.Voice, "a question for Sarah")
    )

    /** Offered in every session type: what he took and how he feels matter whatever the session. */
    private val EVERYWHERE = setOf("event", "substance", "state", "symptom")
    fun inGroup(group: Group): List<Type> = all.filter { it.group == group }

    /** Types offered for a session with these groups: its own groups, the sensor types, and substance / feeling / symptom. */
    fun offered(groups: List<Group>): List<Type> = all.filter { it.group in groups || it.group == Group.Sensor || it.id in EVERYWHERE }

    fun join(types: Set<String>): String = all.map { it.id }.filter { it in types }.ifEmpty { listOf("event") }.joinToString(",")
    fun split(types: String): Set<String> = types.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    fun label(types: Set<String>): String = all.filter { it.id in types }.joinToString(" + ") { it.label }.ifEmpty { "Event" }
}
