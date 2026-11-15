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
            val base = withContext(Dispatchers.Default) {
                container.facultyTimetables.combinedFaculty()
            }
            faculty = base
            listLoading = false
        }
    }

    val today = remember { LocalDate.now() }
    val upcoming = remember(faculty, today) {
        FacultyBirthdays.upcoming(faculty, today, limit = FACULTY_BIRTHDAY_LIST_LIMIT)
    }

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

                if (listLoading && upcoming.isEmpty()) {
                    item(key = "loading") {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
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
                        }
                    }
                } else if (upcoming.isEmpty()) {
                    item(key = "empty") {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
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
                } else {
                    val hero = upcoming.first()
                    item(key = "hero") {
                        BirthdayHeroCard(
                            birthday = hero,
                            store = container.facultyPhotos,
                            onClick = { onOpenFaculty(hero.record.profileId) },
                            onPhotoClick = { photoViewerFor = hero.record }
                        )
                    }
                    // Following birthdays in ascending order, a separator
                    // whenever the calendar month rolls over (the hero's own
                    // month starts unlabelled — the hero card carries it).
                    var currentMonth = YearMonth.from(hero.nextDate)
                    upcoming.drop(1).forEach { next ->
                        val month = YearMonth.from(next.nextDate)
                        if (month != currentMonth) {
                            currentMonth = month
                            item(key = "month-$month") {
                                Text(
                                    monthLabel(month),
                                    modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 2.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                )
                            }
                        }
                        item(key = "birthday-${next.record.profileId}") {
                            BirthdayRow(
                                birthday = next,
                                store = container.facultyPhotos,
                                onClick = { onOpenFaculty(next.record.profileId) }
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

/** "NOVEMBER 2026" — full month name so a year-long list stays unambiguous. */
private fun monthLabel(month: YearMonth): String =
    "${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).uppercase()} ${month.year}"
