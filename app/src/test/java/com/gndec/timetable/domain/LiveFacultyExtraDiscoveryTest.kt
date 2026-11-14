package com.gndec.timetable.domain

import com.gndec.timetable.net.RoomSourceRoot
import com.gndec.timetable.parse.FacultyNameMatcher
import com.gndec.timetable.parse.FacultyTimetableParser
import com.gndec.timetable.parse.ParsedFacultyDoc
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery of timetable-only teachers against the REAL department files
 * (downloaded from the live pages on 2026-10-07) and the REAL bundled
 * directory. The reported case is pinned: "Ms. MANPREET KAUR." publishes an
 * Applied Science table but no directory record of any department matches —
 * search used to have no way to surface her at all.
 */
class LiveFacultyExtraDiscoveryTest {

    private fun load(name: String): String =
        javaClass.getResourceAsStream("/faculty/$name")!!.bufferedReader().use { it.readText() }

    private val directory: List<FacultyRecord> by lazy {
        Json { ignoreUnknownKeys = true; isLenient = true }
            .decodeFromString<List<FacultyRecord>>(load("faculty_directory.json"))
    }

    private val fetchedAt = 1_760_000_000_000L

    private fun parse(root: RoomSourceRoot): ParsedFacultyDoc =
        FacultyTimetableParser.parse(
            load("${root.id}_teachers.html"),
            root.id,
            "https://${root.id}.example.org/teachers",
            fetchedAt
        )

    private fun extrasFor(root: RoomSourceRoot): List<ExtraFacultyTeacher> =
        FacultyExtraDiscovery.discoverForDoc(parse(root), root, directory)

    @Test
    fun everyLiveDocumentParsesWithTeacherTables() {
        val roots = listOf(
            RoomSourceRoot.APPSC, RoomSourceRoot.CSE, RoomSourceRoot.ECE, RoomSourceRoot.EE,
            RoomSourceRoot.ME, RoomSourceRoot.CE, RoomSourceRoot.IT
        )
        for (root in roots) {
            val doc = parse(root)
            assertTrue("$root parsed no tables", doc.tables.isNotEmpty())
        }
    }

    @Test
    fun manpreetKaurIsDiscoveredFromTheAppliedScienceFile() {
        val appsc = extrasFor(RoomSourceRoot.APPSC)
        val manpreet = appsc.firstOrNull { FacultyNameMatcher.normName(it.record.name) == "manpreet kaur" }
        assertTrue(
            "Ms. MANPREET KAUR. (no directory record) must be discovered, got: " +
                appsc.joinToString { it.record.name },
            manpreet != null
        )
        assertEquals("tt-appsc-manpreet-kaur", manpreet!!.record.profileId)
        assertEquals("Applied Science", manpreet.record.department)
        assertTrue("her table must carry actual slots", manpreet.table.slots.isNotEmpty())
        assertTrue("no contact details exist for her", !manpreet.record.contactable)
    }

    @Test
    fun noDiscoveredTeacherDuplicatesADirectoryRecord() {
        // The skip rules must keep every synthetic entry strictly OUTSIDE the
        // directory: no (department, normalised name) pair may collide.
        val directoryKeys = directory.map { "${it.department}|${FacultyNameMatcher.normName(it.name)}" }.toSet()
        val roots = listOf(
            RoomSourceRoot.APPSC, RoomSourceRoot.CSE, RoomSourceRoot.ECE, RoomSourceRoot.EE,
            RoomSourceRoot.ME, RoomSourceRoot.CE, RoomSourceRoot.IT
        )
        val merged = FacultyExtraDiscovery.mergeExtras(roots.flatMap { extrasFor(it) })
        val collisions = merged.filter { "${it.record.department}|${FacultyNameMatcher.normName(it.record.name)}" in directoryKeys }
        assertTrue(
            "synthetic entries colliding with the directory: " + collisions.joinToString { it.record.name },
            collisions.isEmpty()
        )
    }

    @Test
    fun mergedExtrasAreSortedAndUnique() {
        val roots = listOf(
            RoomSourceRoot.APPSC, RoomSourceRoot.CSE, RoomSourceRoot.ECE, RoomSourceRoot.EE,
            RoomSourceRoot.ME, RoomSourceRoot.CE, RoomSourceRoot.IT
        )
        val merged = FacultyExtraDiscovery.mergeExtras(roots.flatMap { extrasFor(it) })
        val keys = merged.map { "${it.record.department}|${FacultyNameMatcher.normName(it.record.name)}" }
        assertEquals("duplicate keys after merge", keys.size, keys.distinct().size)
        assertEquals(merged.map { it.record.name }, merged.map { it.record.name }.sorted())
        // The live files do carry unlisted teachers (APSC alone has ~30).
        assertTrue(merged.isNotEmpty())
    }
}
