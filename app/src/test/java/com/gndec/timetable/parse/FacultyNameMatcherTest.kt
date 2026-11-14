package com.gndec.timetable.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FacultyNameMatcherTest {

    // --- extractFullName -----------------------------------------------------

    @Test
    fun extractsFullNameFromMeStyleCodeFirstLabels() {
        assertEquals("Dr. Sehijpal Singh" to "SSK", FacultyNameMatcher.extractFullName("SSK (Dr. Sehijpal Singh)"))
        assertEquals("Dr. Harmeet Singh" to "HMS", FacultyNameMatcher.extractFullName("HMS  (Dr. Harmeet Singh )"))
    }

    @Test
    fun extractsFullNameFromEeEceStyleNameFirstLabels() {
        assertEquals("Dr. Kanwardeep Singh" to "KDS", FacultyNameMatcher.extractFullName("Dr. Kanwardeep Singh (KDS)"))
        assertEquals("Pf. Mandeep Kaur" to "AS", FacultyNameMatcher.extractFullName("Pf. Mandeep Kaur (AS)"))
        assertEquals("Dr. Jagdeep Singh" to "JDP", FacultyNameMatcher.extractFullName("Dr. Jagdeep Singh (JDP MPE)"))
    }

    @Test
    fun extractsFullNameFromDashCodeSuffix() {
        assertEquals("Dr. Akshay Girdhar" to "AG", FacultyNameMatcher.extractFullName("Dr. Akshay Girdhar-AG"))
    }

    @Test
    fun bareCodeLabelsCarryNoName() {
        assertEquals(null to "HVS", FacultyNameMatcher.extractFullName("HVS"))
        // "GDKL(math)": the parens side is a dept note, not a name.
        assertEquals(null to "GDKL", FacultyNameMatcher.extractFullName("GDKL(math)"))
    }

    @Test
    fun plainLabelsStayIntact() {
        assertEquals("DR D S PATHANIA" to null, FacultyNameMatcher.extractFullName("DR D S PATHANIA"))
    }

    // --- nameScore -----------------------------------------------------------

    @Test
    fun exactNamesMatchFully() {
        assertEquals(100, FacultyNameMatcher.nameScore("DR RAJBIR KAUR", "DR. RAJBIR KAUR"))
    }

    @Test
    fun spaceInsensitiveNamesMatch() {
        // The directory writes "SATWINDER JIT KAUR", the file "DR SATWINDERJIT KAUR".
        assertEquals(100, FacultyNameMatcher.nameScore("DR SATWINDERJIT KAUR", "SATWINDER JIT KAUR"))
    }

    @Test
    fun fuzzySpellingVariantsMatch() {
        assertEquals(70, FacultyNameMatcher.nameScore("Dr. Kulwinder Singh Mann", "DR. KULVINDER SINGH MANN"))
        assertEquals(70, FacultyNameMatcher.nameScore("Dr. Pardeep Jaiswal", "DR. PRADEEP JASWAL"))
    }

    @Test
    fun initialPerTokenMatches() {
        assertEquals(85, FacultyNameMatcher.nameScore("D S PATHANIA", "DR. DALJEEP SINGH PATHANIA"))
    }

    @Test
    fun leadingTokenSubsetMatches() {
        assertEquals(78, FacultyNameMatcher.nameScore("DR AMARJOT KAUR", "DR. AMARJOT KAUR RANDHAWA"))
    }

    @Test
    fun givenNamePrefixMatches() {
        // 'PRAGYA' is a leading token-subset of 'PRAGYA GOYAL' (78); the looser
        // single given-name rule (75) fires for e.g. 'Aman' vs 'AMANDEEP SINGH'.
        assertEquals(78, FacultyNameMatcher.nameScore("Ms. PRAGYA", "PRAGYA GOYAL"))
        assertEquals(75, FacultyNameMatcher.nameScore("Aman", "AMANDEEP SINGH"))
    }

    @Test
    fun unrelatedNamesDoNotMatch() {
        assertEquals(0, FacultyNameMatcher.nameScore("Dr. Raman Kumar", "DR. HARBIR SINGH"))
    }

    // --- damerau ---------------------------------------------------------------

    @Test
    fun damerauCountsTranspositionsAsOneEdit() {
        assertEquals(1, FacultyNameMatcher.damerau("kulvindra", "kulvnidra"))
        assertEquals(1, FacultyNameMatcher.damerau("jaswal", "jaiswal"))
        assertEquals(0, FacultyNameMatcher.damerau("same", "same"))
    }

    // --- assignment -------------------------------------------------------------

    /** Runs the assignment and maps every directory name to its teacher label. */
    private fun assign(
        directory: List<Pair<String, String>>, // name to department
        teachers: List<Triple<String?, String?, String>> // name, code, raw label
    ): Map<String, String?> {
        val result = FacultyNameMatcher.assignTables(
            directoryNames = directory.map { it.first },
            directoryDepartments = directory.map { it.second },
            teacherNames = teachers.map { it.first },
            teacherCodes = teachers.map { it.second },
            teacherLabels = teachers.map { it.third },
            tableAt = { i -> teachers[i] }
        )
        return directory.associate { (name, _) ->
            FacultyNameMatcher.normName(name) to result[FacultyNameMatcher.normName(name)]?.third
        }
    }

    @Test
    fun assignsFullNamesDirectly() {
        val assigned = assign(
            directory = listOf("DR. KULVINDER SINGH MANN" to "Information Technology"),
            teachers = listOf(Triple("Dr. Kulwinder Singh Mann", "KSM", "Dr. Kulwinder Singh Mann-KSM"))
        )
        assertEquals("Dr. Kulwinder Singh Mann-KSM", assigned["kulvinder singh mann"])
    }

    @Test
    fun assignsCodesThroughUniqueSubsequence() {
        // HVS: h-v-s appears in order only inside HARVINDER SINGH of this pool.
        val assigned = assign(
            directory = listOf("HARVINDER SINGH" to "Civil Engineering", "GURDEEPAK SINGH" to "Civil Engineering"),
            teachers = listOf(
                Triple(null, "HVS", "HVS"),
                Triple(null, "GSK", "GSK")
            )
        )
        assertEquals("HVS", assigned["harvinder singh"])
        // GSK is genuinely ambiguous (g-k-s vs g-s-k order inside GURDEEPAK SINGH) —
        // Civil publishes no names, so it stays unassigned.
        assertNull(assigned["gurdeepak singh"])
    }

    @Test
    fun fixpointResolvesCodesAmbiguousUntilAStrongerClaimLands() {
        // 'PTG' subseq-matches both PRASHANT GARG and PRABHJOT SINGH; 'PJS'
        // matches only PRABHJOT SINGH. Once PJS claims its teacher, PTG must
        // resolve to PRASHANT GARG on the next iteration.
        val assigned = assign(
            directory = listOf(
                "PRASHANT GARG" to "Civil Engineering",
                "PRABHJOT SINGH" to "Civil Engineering"
            ),
            teachers = listOf(
                Triple(null, "PTG", "PTG"),
                Triple(null, "PJS", "PJS")
            )
        )
        assertEquals("PJS", assigned["prabhjot singh"])
        assertEquals("PTG", assigned["prashant garg"])
    }

    @Test
    fun ambiguousCodesStayUnassigned() {
        val assigned = assign(
            directory = listOf("SUKHWINDER SINGH" to "Civil Engineering", "SUKHWINDERPAL SINGH" to "Civil Engineering"),
            teachers = listOf(Triple(null, "SKS", "SKS"))
        )
        assertNull(assigned["sukhwinder singh"])
        assertNull(assigned["sukhwinderpal singh"])
    }

    @Test
    fun departmentAnnotationPreventsCrossDepartmentClaims() {
        // The college-wide file lists "DR. MANDEEP KAUR (EE)" — an Electrical
        // teacher — which must not claim the Applied Science MANDEEP KAUR when
        // a same-named local caption exists.
        val assigned = assign(
            directory = listOf("MANDEEP KAUR" to "Applied Science"),
            teachers = listOf(
                Triple("Ms. MANDEEP KAUR", null, "Ms. MANDEEP KAUR"),
                Triple("DR. MANDEEP KAUR", "EE", "DR. MANDEEP KAUR (EE)")
            )
        )
        assertEquals("Ms. MANDEEP KAUR", assigned["mandeep kaur"])
    }

    @Test
    fun honorificBreaksSameNameTies() {
        val assigned = assign(
            directory = listOf("DR. RAJBIR KAUR" to "Applied Science"),
            teachers = listOf(
                Triple("DR RAJBIR KAUR", null, "DR RAJBIR KAUR"),
                Triple("ER. RAJBIR KAUR", null, "ER. RAJBIR KAUR")
            )
        )
        assertEquals("DR RAJBIR KAUR", assigned["rajbir kaur"])
    }

    @Test
    fun deptCodesAreNeverTreatedAsTeacherCodes() {
        // "ME" is a department marker, not a teacher code.
        val assigned = assign(
            directory = listOf("AMANDEEP SINGH" to "Civil Engineering"),
            teachers = listOf(Triple(null, "ME", "ME(faculty)"))
        )
        assertNull(assigned["amandeep singh"])
    }
}
