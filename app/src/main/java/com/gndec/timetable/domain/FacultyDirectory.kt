package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.R
import com.gndec.timetable.net.RoomSourceRoot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One faculty member from the bundled college directory (faculty_enriched.json).
 * The directory carries identity + contact details for EVERY teacher — including
 * teachers whose weekly timetable the departments do not publish, which is why
 * the faculty detail screen can always show contact info and only falls back to
 * "timetable not available" for the schedule itself.
 */
@Serializable
data class FacultyRecord(
    val name: String = "",
    val designation: String = "",
    val email: String = "",
    val profileId: String = "",
    val department: String = "",
    val deptId: Int = 0,
    val profileUrl: String = "",
    val phoneOffice: String = "",
    val phoneMobile: String = "",
    val adminRoles: String = ""
) {
    val contactable: Boolean get() = email.isNotBlank() || phoneOffice.isNotBlank() || phoneMobile.isNotBlank()
}

/**
 * Loads the bundled faculty directory once per process and answers lookups.
 *
 * The same-name teachers of different departments (three Mandeep Kaurs across
 * Applied Science / Civil / Electrical) are distinct records with distinct
 * profile ids — search disambiguates them by showing the department.
 */
class FacultyDirectoryManager(context: Context) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val directory: List<FacultyRecord> by lazy {
        runCatching {
            context.resources.openRawResource(R.raw.faculty_directory).bufferedReader().use { reader ->
                json.decodeFromString<List<FacultyRecord>>(reader.readText())
            }
        }.getOrDefault(emptyList())
    }

    fun byProfileId(profileId: String): FacultyRecord? =
        directory.firstOrNull { it.profileId == profileId }

    /** Directory records of every department served by [root]. */
    fun poolForRoot(root: RoomSourceRoot): List<FacultyRecord> =
        directory.filter { rootForDepartment(it.department) == root }

    companion object {
        /**
         * JSON department name → department website that publishes that
         * department's teacher timetable. Departments without a published
         * teacher timetable (Computer Center, Workshops, Sports, BA, MCA,
         * Architecture) map to null — their teachers get contact info with an
         * honest "timetable not available" note instead of a wrong schedule.
         *
         * RAI publishes no timetable of its own: RAI faculty are covered by the
         * ME department's teacher timetable (see FacultyDetailScreen's note).
         */
        fun rootForDepartment(department: String): RoomSourceRoot? = when (department.trim()) {
            "Applied Science" -> RoomSourceRoot.APPSC
            "Information Technology" -> RoomSourceRoot.IT
            "Computer Science & Engg." -> RoomSourceRoot.CSE
            "Electrical Engineering" -> RoomSourceRoot.EE
            "Mechanical Engineering" -> RoomSourceRoot.ME
            "Production Engineering" -> RoomSourceRoot.ME // M&PE publishes one file
            "Civil Engineering" -> RoomSourceRoot.CE
            "Electronics & Communication Engineering" -> RoomSourceRoot.ECE
            else -> null
        }

        /** True for the departments whose teachers live in the ME timetable. */
        val ME_SERVED_DEPARTMENTS = setOf("Mechanical Engineering", "Production Engineering")
    }
}
