package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.net.FacultyTimetableClient
import com.gndec.timetable.net.RoomSourceRoot
import com.gndec.timetable.parse.FacultyNameMatcher
import com.gndec.timetable.parse.FacultySlot
import com.gndec.timetable.parse.ParsedFacultyDoc
import com.gndec.timetable.parse.TeacherTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/** A teacher's resolved weekly schedule, ready for display. */
data class FacultySchedule(
    val faculty: FacultyRecord,
    val table: TeacherTable,
    val sourceUrl: String,
    val sourceRootId: String,
    val generatedAtMillis: Long?,
    val fetchedAtMillis: Long
) {
    val slots: List<FacultySlot> get() = table.slots

    fun slotsForDay(day: Int): List<FacultySlot> = slots.filter { it.day == day }

    val publishedName: String? get() = table.teacherName
}

/**
 * What the teacher is doing right now.
 *
 * "Free right now" is only claimed from a table that actually covers today —
 * a teacher with no classes listed today is shown as free only when today is
 * one of the published weekdays; on a weekend no claim is made at all.
 */
sealed interface FacultyStatus {
    data class Busy(val slot: FacultySlot, val untilMinutes: Int) : FacultyStatus
    data class FreeWithNextClass(val slot: FacultySlot) : FacultyStatus
    data object FreeRestOfDay : FacultyStatus
    data object NoClassesToday : FacultyStatus
    data object Weekend : FacultyStatus
}

/** Outcome of resolving one faculty member's timetable. */
sealed interface FacultyTimetableResult {
    data class Ready(val schedule: FacultySchedule) : FacultyTimetableResult

    /** Honest absence: the department does not publish this teacher's schedule. */
    data object NotPublished : FacultyTimetableResult

    /** The department is covered but the live document could not be fetched. */
    data class Unavailable(val reason: String) : FacultyTimetableResult
}

/**
 * Keeps the per-department teacher timetables loaded, cached and current.
 *
 * The directory's contact details are ALWAYS available offline (bundled in the
 * APK); only the weekly schedule needs the network. Each department root is
 * loaded lazily — the first search hit on a teacher of that department
 * discovers the newest published document, parses it and caches the parsed
 * snapshot on disk for [FRESH_WINDOW_MS]. A cached document is re-served
 * instantly while a refresh runs in the background, mirroring the
 * vacant-rooms manager's behaviour.
 */
class FacultyTimetableManager(
    context: Context,
    private val directory: FacultyDirectoryManager,
    private val client: FacultyTimetableClient = FacultyTimetableClient()
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cacheDir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
    private val loadMutex = Mutex()

    private val memory = mutableMapOf<RoomSourceRoot, ParsedFacultyDoc?>()
    private val inFlight = mutableMapOf<RoomSourceRoot, Mutex>()

    init {
        // Warm the memory cache from disk without any network access.
        runCatching {
            for (root in ROOTS) {
                val cached = readCache(root)
                if (cached != null) memory[root] = cached
            }
        }
    }

    /**
     * Resolves one teacher's schedule. Departments without a published teacher
     * timetable return [FacultyTimetableResult.NotPublished] immediately
     * (offline) — the detail screen then shows the directory's contact details
     * plus the "timetable not available" note.
     */
    suspend fun load(faculty: FacultyRecord, forceRefresh: Boolean = false): FacultyTimetableResult {
        val root = FacultyDirectoryManager.rootForDepartment(faculty.department)
            ?: return FacultyTimetableResult.NotPublished

        val doc = ensureDoc(root, forceRefresh)
            ?: return FacultyTimetableResult.Unavailable(
                "The ${faculty.department} teacher timetable could not be loaded. Check your connection and try again."
            )

        val table = matchTable(root, doc, faculty) ?: return FacultyTimetableResult.NotPublished
        return FacultyTimetableResult.Ready(
            FacultySchedule(
                faculty = faculty,
                table = table,
                sourceUrl = doc.url,
                sourceRootId = doc.rootId,
                generatedAtMillis = doc.generatedAtMillis,
                fetchedAtMillis = doc.fetchedAtMillis
            )
        )
    }

    /** Doc from cache when fresh, else discover + download + parse. */
    private suspend fun ensureDoc(root: RoomSourceRoot, force: Boolean): ParsedFacultyDoc? {
        val mutex = synchronized(inFlight) { inFlight.getOrPut(root) { Mutex() } }
        return mutex.withLock {
            val cached = memory[root]
            val fresh = cached != null && System.currentTimeMillis() - cached!!.fetchedAtMillis < FRESH_WINDOW_MS
            if (!force && fresh) return@withLock cached

            val fetched = withContext(Dispatchers.IO) {
                runCatching {
                    val candidates = client.discoverTeacherCandidates(root)
                    client.fetchTeacherDoc(root, candidates)
                }.getOrNull()
            }
            // A failed refresh keeps the previously cached document usable.
            if (fetched != null) {
                memory[root] = fetched
                runCatching { writeCache(root, fetched) }
                fetched
            } else {
                cached
            }
        }
    }

    /**
     * Runs the matcher for one root's whole pool (the ambiguity resolution in
     * [FacultyNameMatcher] needs every candidate, not just the requested
     * teacher) and picks this teacher's table. Same-name records of the same
     * department share one table.
     */
    private fun matchTable(root: RoomSourceRoot, doc: ParsedFacultyDoc, faculty: FacultyRecord): TeacherTable? {
        val pool = directory.poolForRoot(root)
        if (pool.isEmpty()) return null
        val labels = doc.tables.map { listOfNotNull(it.teacherName, it.code).joinToString(" ") }
        val assignment = FacultyNameMatcher.assignTables(
            directoryNames = pool.map { it.name },
            directoryDepartments = pool.map { it.department },
            teacherNames = doc.tables.map { it.teacherName },
            teacherCodes = doc.tables.map { it.code },
            teacherLabels = labels,
            tableAt = { doc.tables[it] }
        )
        return assignment[FacultyNameMatcher.normName(faculty.name)]
    }

    // --- disk cache ------------------------------------------------------------

    @Serializable
    private data class CachedDoc(val doc: ParsedFacultyDoc)

    private fun cacheFile(root: RoomSourceRoot): File = File(cacheDir, "${root.id}.json")

    private fun readCache(root: RoomSourceRoot): ParsedFacultyDoc? = runCatching {
        val file = cacheFile(root)
        if (!file.isFile) return null
        json.decodeFromString<CachedDoc>(file.readText()).doc
    }.getOrNull()

    private fun writeCache(root: RoomSourceRoot, doc: ParsedFacultyDoc) {
        runCatching {
            val file = cacheFile(root)
            val tmp = File(cacheDir, file.name + ".tmp")
            tmp.writeText(json.encodeToString(CachedDoc.serializer(), CachedDoc(doc)))
            if (!tmp.renameTo(file)) {
                file.writeText(json.encodeToString(CachedDoc.serializer(), CachedDoc(doc)))
                tmp.delete()
            }
        }
    }

    companion object {
        /** Roots that publish a teacher timetable (MCA/MBA do not publish one). */
        private val ROOTS = listOf(
            RoomSourceRoot.APPSC, RoomSourceRoot.CSE, RoomSourceRoot.ECE, RoomSourceRoot.EE,
            RoomSourceRoot.ME, RoomSourceRoot.CE, RoomSourceRoot.IT
        )

        private const val CACHE_DIR = "faculty_timetables_v1"

        /** The college republishes roughly weekly; half a working day is cheap. */
        const val FRESH_WINDOW_MS = 6L * 60 * 60 * 1000

        /**
         * What the teacher is doing at [nowMinutes] on [today]. A teacher whose
         * table shows nothing today still counts as covered when the document
         * publishes that weekday.
         */
        fun statusAt(schedule: FacultySchedule, today: LocalDate, nowMinutes: Int): FacultyStatus {
            val dow = today.dayOfWeek
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return FacultyStatus.Weekend

            val day = dow.value - 1
            val todaySlots = schedule.slotsForDay(day).sortedBy { it.startMinutes }
            val current = todaySlots.lastOrNull { it.startMinutes <= nowMinutes && nowMinutes < it.endMinutes }
            if (current != null) return FacultyStatus.Busy(current, untilMinutes = current.endMinutes)
            val next = todaySlots.firstOrNull { it.startMinutes > nowMinutes }
            return when {
                next != null -> FacultyStatus.FreeWithNextClass(next)
                todaySlots.isNotEmpty() -> FacultyStatus.FreeRestOfDay
                else -> FacultyStatus.NoClassesToday
            }
        }
    }
}
