package com.gndec.timetable.domain

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled directory JSON (faculty_enriched.json / res/raw/faculty_directory.json)
 * uses snake_case keys — profile_id, dept_id, phone_office… — while the Kotlin model
 * uses camelCase properties. Without @SerialName mappings kotlinx-serialization
 * silently drops every snake_case key (ignoreUnknownKeys=true) and every record
 * decodes with an empty profileId, which breaks deep links and contact cards.
 * These tests pin the mapping.
 */
class FacultyDirectoryDecodeTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun decodesSnakeCaseDirectoryKeys() {
        val payload = """
            [{
                "name": "DR. HARPREET KAUR GREWAL",
                "designation": "Professor and Head",
                "email": "hkgrewal@gndec.ac.in",
                "profile_id": "71",
                "department": "Applied Science",
                "dept_id": 1,
                "profile_url": "https://gndec.ac.in/faculty/?id=71",
                "image_url": "https://gndec.ac.in/images/drhkgrewal%20pic.jpg",
                "phone_office": "0161-5064529",
                "phone_mobile": "98156-57551",
                "admin_roles": "HOD - Applied Sciences",
                "phone_source": "Information Brochure 2026"
            }]
        """.trimIndent()

        val records = json.decodeFromString<List<FacultyRecord>>(payload)
        assertEquals(1, records.size)
        val record = records[0]
        assertEquals("DR. HARPREET KAUR GREWAL", record.name)
        assertEquals("Professor and Head", record.designation)
        assertEquals("hkgrewal@gndec.ac.in", record.email)
        assertEquals("71", record.profileId)
        assertEquals("Applied Science", record.department)
        assertEquals(1, record.deptId)
        assertEquals("https://gndec.ac.in/faculty/?id=71", record.profileUrl)
        assertEquals("https://gndec.ac.in/images/drhkgrewal%20pic.jpg", record.imageUrl)
        assertEquals("0161-5064529", record.phoneOffice)
        assertEquals("98156-57551", record.phoneMobile)
        assertEquals("HOD - Applied Sciences", record.adminRoles)
        assertTrue(record.contactable)
    }

    @Test
    fun missingOptionalFieldsFallBackToDefaults() {
        val payload = """[{"name":"SOMEONE","email":"x@gndec.ac.in"}]"""
        val record = json.decodeFromString<List<FacultyRecord>>(payload)[0]
        assertEquals("", record.profileId)
        assertEquals("", record.imageUrl)
        assertEquals("", record.phoneOffice)
        assertEquals("", record.phoneMobile)
        assertEquals(0, record.deptId)
        assertTrue(record.contactable) // email alone is contactable
    }

    @Test
    fun shortDepartmentUsesCompactChipLabels() {
        assertEquals("CSE", FacultyDirectoryManager.shortDepartment("Computer Science & Engg."))
        assertEquals("ECE", FacultyDirectoryManager.shortDepartment("Electronics & Communication Engineering"))
        assertEquals("IT", FacultyDirectoryManager.shortDepartment("Information Technology"))
        assertEquals("M&PE", FacultyDirectoryManager.shortDepartment("Production Engineering"))
        assertEquals("ME", FacultyDirectoryManager.shortDepartment("Mechanical Engineering"))
        assertEquals("Architecture", FacultyDirectoryManager.shortDepartment("School of Architecture"))
        // Unknown departments fall through unchanged (trimmed)
        assertEquals("Robotics", FacultyDirectoryManager.shortDepartment(" Robotics "))
    }

    @Test
    fun orderedDepartmentsPutsTeachingDepartmentsFirst() {
        val shuffled = listOf(
            "Workshops",
            "Civil Engineering",
            "Mechanical Engineering",
            "Applied Science",
            "Business Administration",
            "Computer Science & Engg."
        )
        val ordered = FacultyDirectoryManager.orderedDepartments(shuffled)
        assertEquals(
            listOf(
                "Applied Science",
                "Computer Science & Engg.",
                "Mechanical Engineering",
                "Civil Engineering",
                "Business Administration",
                "Workshops"
            ),
            ordered
        )
    }

    @Test
    fun orderedDepartmentsSortsNonTeachingAlphabetically() {
        val ordered = FacultyDirectoryManager.orderedDepartments(
            listOf("Sports", "Computer Center", "Workshops", "Business Administration")
        )
        assertEquals(
            listOf("Business Administration", "Computer Center", "Sports", "Workshops"),
            ordered
        )
        assertFalse(ordered.isEmpty())
    }
}
