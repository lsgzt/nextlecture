package com.gndec.timetable.domain

import com.gndec.timetable.net.RoomSourceRoot
import com.gndec.timetable.parse.FacultyNameMatcher
import com.gndec.timetable.parse.FacultyTimetableParser
import com.gndec.timetable.parse.ParsedFacultyDoc
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery of timetable-only teachers against the REAL department files
 * (downloaded from the live pages on 2026-10-07) and the REAL bundled
 * directory.
 *
 * The reported case is pinned, but in the opposite direction now: "Ms. MANPREET
 * KAUR." publishes an Applied Science table and the college's own faculty list
 * does carry her (profile 472, added 2026-10-06). She used to be missing from
 * the directory, so search could only surface her as a synthetic extra teacher
 * with no contact details. She must now be a FIRST-CLASS directory record whose
 * table the name pass claims.
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
    fun manpreetKaurIsADirectoryRecordAndClaimsHerAppliedScienceTable() {
        val record = directory.firstOrNull { it.profileId == "472" }
        assertNotNull("MANPREET KAUR (Applied Science) must be in the bundled directory", record)
        assertEquals("MANPREET KAUR", record!!.name)
        assertEquals("Applied Science", record.department)
        assertEquals(1, record.deptId)
        assertEquals("https://gndec.ac.in/faculty/?id=472", record.profileUrl)
        // The college's own list publishes a broken address for her; the
        // directory carries the repaired one, so she is contactable.
        assertEquals("as_manpreet@gndec.ac.in", record.email)
        assertTrue(record.contactable)

        // Her published table is now claimed by that directory record, so she
        // must NOT be re-published as a synthetic extra teacher.
        val appsc = extrasFor(RoomSourceRoot.APPSC)
        val synthetic = appsc.firstOrNull { FacultyNameMatcher.normName(it.record.name) == "manpreet kaur" }
        assertNull(
            "she is a directory record now and must not be a synthetic extra",
            synthetic
        )

        // The name pass must hand her table to her record.
        val doc = parse(RoomSourceRoot.APPSC)
        val pool = directory.filter { FacultyDirectoryManager.rootForDepartment(it.department) == RoomSourceRoot.APPSC }
        val labels = doc.tables.map { listOfNotNull(it.teacherName, it.code).joinToString(" ") }
        val assignment = FacultyNameMatcher.assignTables(
            directoryNames = pool.map { it.name },
            directoryDepartments = pool.map { it.department },
            teacherNames = doc.tables.map { it.teacherName },
            teacherCodes = doc.tables.map { it.code },
            teacherLabels = labels,
            tableAt = { doc.tables[it] }
        )
        val claimed = assignment[FacultyNameMatcher.normName("MANPREET KAUR")]
        assertNotNull("her Applied Science table must be claimed by her record", claimed)
        val table = claimed!!
        assertEquals("Ms. MANPREET KAUR.", table.teacherName)
        assertTrue("her table must carry actual slots", table.slots.isNotEmpty())
    }

    @Test
    fun theTeachersAddedFromTheOfficialListClaimTheirPublishedTables() {
        // The teachers the college's own list carries but the directory used to
        // miss. Each one publishes a table, and the directory record must be
        // the one that claims it — otherwise their profile shows no schedule
        // (or, worse, somebody else's).
        val expected = listOf(
            Triple("MANPREET KAUR", RoomSourceRoot.APPSC, "Ms. MANPREET KAUR."),
            Triple("KIRAN KUMARI", RoomSourceRoot.APPSC, "Ms. KIRAN"),
            Triple("HARMINDER KAUR", RoomSourceRoot.CSE, "Pf. Harminder Kaur"),
            Triple("DR PREETI PANNU", RoomSourceRoot.ECE, "Dr. Preeti Pannu"),
            Triple("NAVJOT SINGH", RoomSourceRoot.ECE, "Dr. Navjot Singh")
        )
        for ((name, root, label) in expected) {
            val doc = parse(root)
            val pool = directory.filter {
                FacultyDirectoryManager.rootForDepartment(it.department) == root
            }
            val labels = doc.tables.map { listOfNotNull(it.teacherName, it.code).joinToString(" ") }
            val assignment = FacultyNameMatcher.assignTables(
                directoryNames = pool.map { it.name },
                directoryDepartments = pool.map { it.department },
                teacherNames = doc.tables.map { it.teacherName },
                teacherCodes = doc.tables.map { it.code },
                teacherLabels = labels,
                tableAt = { doc.tables[it] }
            )
            val claimed = assignment[FacultyNameMatcher.normName(name)]
            assertNotNull("$name must claim a published table", claimed)
            assertEquals("$name must claim its own table", label, claimed!!.teacherName)
        }
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
