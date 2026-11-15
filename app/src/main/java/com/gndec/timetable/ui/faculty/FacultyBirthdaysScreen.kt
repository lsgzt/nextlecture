package com.gndec.timetable.ui.faculty

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.FacultyBirthday
import com.gndec.timetable.domain.FacultyBirthdays
import com.gndec.timetable.domain.FacultyRecord
import com.gndec.timetable.ui.Header
import com.gndec.timetable.ui.PremiumBottomBarContentClearance
import com.gndec.timetable.ui.PremiumScreenBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * One rendered entry of the dedicated Faculty Birthday list. The screen builds
 * this immutable model up front (inside `remember`) and the LazyColumn only
 * walks it — no per-recomposition date math, no mutable locals.
 *
 * [stableId] is stamped with the entry's POSITION as well as its content, so
 * no dataset — today's or a future directory refresh — can ever produce two
 * items with the same key: duplicate LazyColumn keys crash the list while
 * scrolling, and this list feeds from a pool the background doc refresher
 * rebuilds, so uniqueness is proven by construction instead of trusted.
 */
private sealed interface BirthdayItem {
    val stableId: String

    data class Hero(val birthday: FacultyBirthday, val position: Int) : BirthdayItem {
        override val stableId get() = "hero-$position"
    }

    data class MonthHeader(val label: String, val month: YearMonth, val position: Int) : BirthdayItem {
        override val stableId get() = "month-$position-${month.year}-${month.monthValue}"
    }

    data class Row(val birthday: FacultyBirthday, val position: Int) : BirthdayItem {
        override val stableId get() = "row-$position-${birthday.record.profileId}"
    }
}

/** "NOVEMBER 2026" — full month name so a year-long list stays unambiguous. */
private fun monthLabel(month: YearMonth): String =
    "${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).uppercase()} ${month.year}"

/**
 * The flat render list: hero first, then every following birthday in ascending
 * order with a month separator whenever the calendar month rolls over (the
 * hero's own month starts unlabelled — the hero card carries it).
 */
private fun birthdayItems(upcoming: List<FacultyBirthday>): List<BirthdayItem> {
    if (upcoming.isEmpty()) return emptyList()
    val out = mutableListOf<BirthdayItem>(BirthdayItem.Hero(upcoming.first(), 0))
    var currentMonth = YearMonth.from(upcoming.first().nextDate)
    upcoming.drop(1).forEach { next ->
        val month = YearMonth.from(next.nextDate)
        if (month != currentMonth) {
            currentMonth = month
            out += BirthdayItem.MonthHeader(monthLabel(month), month, out.size)
        }
        out += BirthdayItem.Row(next, out.size)
    }
    return out
}

/**
 * Dedicated Faculty Birthday browser — opened from the "Faculty Birthday"
 * card in the More tab. Separated from the faculty members directory so each
 * screen does one job: this one answers "whose birthday is next?".
 *
 * A hero card celebrates the next upcoming teacher (photo, name, the age
 * they are turning and a daily wish), then EVERY following birthday in the
 * year ahead as compact rows in ascending order, grouped under month
 * separators so the long list stays navigable. Tapping a teacher opens their
 * profile; tapping the hero PHOTO opens the zoomable viewer instead.
 */
@Composable
fun FacultyBirthdaysScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenFaculty: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    // The teacher whose photo the user tapped open in the zoomable viewer.
    var photoViewerFor by remember { mutableStateOf<FacultyRecord?>(null) }

    // Prefer the process-wide snapshot (instant). Never parse the bundled
    // directory on the main thread — that froze the UI for hundreds of ms.
    var faculty by remember {
        mutableStateOf(container.facultyTimetables.combinedFacultyFast().orEmpty())
    }
    var listLoading by remember { mutableStateOf(faculty.isEmpty()) }

    LaunchedEffect(Unit) {
        if (faculty.isEmpty()) {
            // The pool is also rebuilt by background doc refreshes; any failure
            // here must degrade to the empty state, never crash the app.
            val base = withContext(Dispatchers.Default) {
                runCatching { container.facultyTimetables.combinedFaculty() }
                    .getOrDefault(emptyList())
            }
            faculty = base
        }
        listLoading = false
    }

    val today = remember { LocalDate.now() }
    val upcoming = remember(faculty, today) {
        FacultyBirthdays.upcoming(faculty, today, limit = FACULTY_BIRTHDAY_LIST_LIMIT)
            // Belt-and-braces: the combined pool is directory + live-discovered
            // extras rebuilt on a worker thread; a duplicate record must never
            // reach a keyed LazyColumn, so collapse by profile id (soonest wins).
            .distinctBy { it.record.profileId }
    }
    val listItems = remember(upcoming) { birthdayItems(upcoming) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        PremiumScreenBackground {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = 26.dp + PremiumBottomBarContentClearance
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "header") {
                    Header(
                        title = "Faculty Birthday",
                        subtitle = if (upcoming.isEmpty()) null
                        else "Upcoming birthdays, soonest first",
                        onBack = onBack,
                        onSettings = onOpenSettings
                    )
                }

                if (upcoming.isEmpty()) {
                    item(key = if (listLoading) "loading" else "empty") {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            if (listLoading) {
                                Row(
                                    Modifier.fillMaxWidth().padding(18.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "Loading the college directory…",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            } else {
                                Column(Modifier.padding(18.dp)) {
                                    Text(
                                        "No birthdays to show",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "No teacher in the college directory publishes a birth date.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                } else {
                    items(listItems, key = { it.stableId }) { entry ->
                        when (entry) {
                            is BirthdayItem.Hero -> BirthdayHeroCard(
                                birthday = entry.birthday,
                                store = container.facultyPhotos,
                                onClick = { onOpenFaculty(entry.birthday.record.profileId) },
                                onPhotoClick = { photoViewerFor = entry.birthday.record }
                            )
                            is BirthdayItem.MonthHeader -> Text(
                                entry.label,
                                modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 2.dp),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                            is BirthdayItem.Row -> BirthdayRow(
                                birthday = entry.birthday,
                                store = container.facultyPhotos,
                                onClick = { onOpenFaculty(entry.birthday.record.profileId) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Zoomable photo viewer — opened by tapping a teacher's avatar.
    photoViewerFor?.let { record ->
        FacultyPhotoViewer(
            store = container.facultyPhotos,
            url = record.imageUrl,
            name = prettyName(record.name),
            onDismiss = { photoViewerFor = null }
        )
    }
}
