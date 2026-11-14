package com.gndec.timetable.ui.search

import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.domain.FacultyRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FacultySearchTest {

    private val faculty = listOf(
        FacultyRecord(
            name = "DR. HARPREET KAUR GREWAL",
            designation = "Professor and Head",
            email = "hkgrewal@gndec.ac.in",
            profileId = "71",
            department = "Applied Science"
        ),
        FacultyRecord(
            name = "DR. KULVINDER SINGH MANN",
            designation = "Associate Professor",
            email = "ksm@gndec.ac.in",
            profileId = "123",
            department = "Information Technology"
        ),
        FacultyRecord(
            name = "MANDEEP KAUR",
            designation = "Assistant Professor",
            email = "ce_mandeep@gndec.ac.in",
            profileId = "249",
            department = "Civil Engineering"
        ),
        FacultyRecord(
            name = "MANDEEP KAUR",
            designation = "Assistant Professor",
            email = "ee_mandeep@gndec.ac.in",
            profileId = "426",
            department = "Electrical Engineering"
        )
    )

    private fun snapshot(queryFaculty: List<FacultyRecord> = faculty) = SearchSnapshot(
        settings = AppSettings(),
        lectures = emptyList(),
        notices = emptyList(),
        holidays = emptyList(),
        attendance = emptyList(),
        rooms = null,
        faculty = queryFaculty,
        today = LocalDate.of(2026, 10, 7),
        nowMinutes = 600
    )

    @Test
    fun findsTeachersByPartialName() {
        val results = search("harpreet", snapshot())
        val facultyResults = results.filter { it.group == SearchGroup.FACULTY }
        assertEquals(1, facultyResults.size)
        assertEquals("DR. HARPREET KAUR GREWAL", facultyResults[0].title)
        assertEquals("Professor and Head · Applied Science", facultyResults[0].subtitle)
        assertEquals(SearchAction.OpenFaculty("71"), facultyResults[0].action)
    }

    @Test
    fun sameNameTeachersAreDisambiguatedByDepartment() {
        val results = search("mandeep kaur", snapshot())
        val facultyResults = results.filter { it.group == SearchGroup.FACULTY }
        assertEquals(2, facultyResults.size)
        val subtitles = facultyResults.map { it.subtitle }.toSet()
        assertTrue("Assistant Professor · Civil Engineering" in subtitles)
        assertTrue("Assistant Professor · Electrical Engineering" in subtitles)
        val ids = facultyResults.map { (it.action as SearchAction.OpenFaculty).profileId }.toSet()
        assertEquals(setOf("249", "426"), ids)
    }

    @Test
    fun subsequenceToleranceOnNames() {
        // "kulwinder" is a loose subsequence of "DR. KULVINDER SINGH MANN"'s key
        // only through the letter ladder; the word-boundary rules dominate.
        val results = search("kulwinder", snapshot())
        val facultyResults = results.filter { it.group == SearchGroup.FACULTY }
        assertEquals(1, facultyResults.size)
        assertEquals("DR. KULVINDER SINGH MANN", facultyResults[0].title)
    }

    @Test
    fun facultyResultsAppearForNameQueries() {
        val results = search("mann", snapshot())
        val groups = results.map { it.group }.distinct()
        assertTrue(SearchGroup.FACULTY in groups)
    }

    @Test
    fun emptyDirectoryYieldsNoFacultyResults() {
        val results = search("harpreet", snapshot(queryFaculty = emptyList()))
        assertTrue(results.none { it.group == SearchGroup.FACULTY })
    }
}
