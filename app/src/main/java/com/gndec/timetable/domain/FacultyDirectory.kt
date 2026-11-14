package com.gndec.timetable.domain

import android.content.Context
import com.gndec.timetable.R
import com.gndec.timetable.net.RoomSourceRoot
import kotlinx.serialization.SerialName
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
    @SerialName("profile_id") val profileId: String = "",
    val department: String = "",
    @SerialName("dept_id") val deptId: Int = 0,
    @SerialName("profile_url") val profileUrl: String = "",
    /** Absolute photo URL from the college website, blank when it has none
     * (two of the 271 directory pages carry no usable picture). */
    @SerialName("image_url") val imageUrl: String = "",
    @SerialName("phone_office") val phoneOffice: String = "",
    @SerialName("phone_mobile") val phoneMobile: String = "",
    @SerialName("admin_roles") val adminRoles: String = ""
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

    private val directoryLazy: Lazy<List<FacultyRecord>> = lazy {
        runCatching {
            context.resources.openRawResource(R.raw.faculty_directory).bufferedReader().use { reader ->
                json.decodeFromString<List<FacultyRecord>>(reader.readText())
            }
        }.getOrDefault(emptyList())
    }

    val directory: List<FacultyRecord> get() = directoryLazy.value

    /** True once the bundled JSON has been parsed this process. */
    val isLoaded: Boolean get() = directoryLazy.isInitialized()

    fun byProfileId(profileId: String): FacultyRecord? =
        directory.firstOrNull { it.profileId == profileId }

    /**
     * Main-thread-safe lookup: returns a match only when the directory is
     * already in memory. Never triggers the JSON parse.
     */
    fun byProfileIdIfLoaded(profileId: String): FacultyRecord? =
        if (!directoryLazy.isInitialized()) null
        else directory.firstOrNull { it.profileId == profileId }

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

        /**
         * Inverse of [rootForDepartment]: the directory department name a
         * timetable root serves. Used to attribute timetable-discovered
         * teachers to a department when the directory itself has no record
         * of them.
         */
        fun departmentForRoot(root: RoomSourceRoot): String = when (root) {
            RoomSourceRoot.APPSC -> "Applied Science"
            RoomSourceRoot.IT -> "Information Technology"
            RoomSourceRoot.CSE -> "Computer Science & Engg."
            RoomSourceRoot.EE -> "Electrical Engineering"
            RoomSourceRoot.ME -> "Mechanical Engineering"
            RoomSourceRoot.CE -> "Civil Engineering"
            RoomSourceRoot.ECE -> "Electronics & Communication Engineering"
            else -> "College"
        }

        /** True for the departments whose teachers live in the ME timetable. */
        val ME_SERVED_DEPARTMENTS = setOf("Mechanical Engineering", "Production Engineering")

        /**
         * Compact chip label for a department — the full names ("Electronics &
         * Communication Engineering") are far too wide for the filter chips, so
         * the picker and the faculty directory browser show the familiar branch
         * short forms instead.
         */
        fun shortDepartment(department: String): String = when (department.trim()) {
            "Applied Science" -> "Applied Science"
            "Information Technology" -> "IT"
            "Computer Science & Engg." -> "CSE"
            "Electrical Engineering" -> "EE"
            "Mechanical Engineering" -> "ME"
            "Production Engineering" -> "M&PE"
            "Civil Engineering" -> "CE"
            "Electronics & Communication Engineering" -> "ECE"
            "Business Administration" -> "MBA"
            "Computer Applications" -> "MCA"
            "Computer Center" -> "Computer Center"
            "School of Architecture" -> "Architecture"
            else -> department.trim()
        }

        /**
         * Sorts department names into the canonical display order used by the
         * search department picker and the faculty directory browser: the seven
         * departments that publish a teacher timetable (plus M&PE, served by
         * ME) in branch order first, then every remaining department
         * alphabetically.
         */
        fun orderedDepartments(departments: List<String>): List<String> {
            val canonical = listOf(
                "Applied Science",
                "Computer Science & Engg.",
                "Information Technology",
                "Electronics & Communication Engineering",
                "Electrical Engineering",
                "Mechanical Engineering",
                "Production Engineering",
                "Civil Engineering"
            )
            return departments.sortedWith(
                compareBy(
                    { dept -> canonical.indexOf(dept.trim()).let { if (it >= 0) it else canonical.size } },
                    { it.trim().lowercase() }
                )
            )
        }
    }
}
