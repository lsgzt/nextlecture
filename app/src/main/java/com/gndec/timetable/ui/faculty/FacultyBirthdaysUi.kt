package com.gndec.timetable.ui.faculty

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.FacultyBirthday
import com.gndec.timetable.domain.FacultyBirthdays
import com.gndec.timetable.domain.FacultyDirectoryManager
import com.gndec.timetable.domain.FacultyRecord
import com.gndec.timetable.domain.birthdayWishFor
import com.gndec.timetable.ui.motion.pressFeedback
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How many teachers the hero card is followed by in the compact list. */
private const val LISTED_AFTER_HERO = 5

/** "16 Jul" style — day, short month; year is implied by the countdown. */
private val birthdayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/**
 * Faculty birthdays section of the directory browser: a hero card for the
 * next upcoming birthday (photo, name, the age they are turning and a daily
 * wish — mirroring the Home next-lecture hero), then the following birthdays
 * as compact rows in ascending order. Tapping any teacher opens their
 * profile; tapping the hero PHOTO opens the zoomable viewer instead.
 *
 * Hidden while a filter or department is active — it is a highlight strip,
 * not a search result.
 */
@Composable
fun FacultyBirthdaysSection(
    container: AppContainer,
    faculty: List<FacultyRecord>,
    onOpenFaculty: (String) -> Unit,
    onPhotoClick: (FacultyRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    val today = remember { LocalDate.now() }
    val upcoming = remember(faculty, today) {
        FacultyBirthdays.upcoming(faculty, today, limit = 1 + LISTED_AFTER_HERO)
    }
    if (upcoming.isEmpty()) return
    val hero = upcoming.first()

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "BIRTHDAYS",
            modifier = Modifier.padding(start = 22.dp, top = 6.dp),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp
        )
        BirthdayHeroCard(
            birthday = hero,
            store = container.facultyPhotos,
            onClick = { onOpenFaculty(hero.record.profileId) },
            onPhotoClick = { onPhotoClick(hero.record) }
        )
        if (upcoming.size > 1) {
            upcoming.drop(1).forEach { next ->
                BirthdayRow(
                    birthday = next,
                    store = container.facultyPhotos,
                    onClick = { onOpenFaculty(next.record.profileId) }
                )
            }
        }
    }
}

/**
 * The big card for the next birthday — the visual counterpart of the
 * next-lecture hero: badge, photo, name, the age being turned, and the daily
 * wish line. Festive but theme-aware: a warm gradient over the card with the
 * outline border the other hero cards use.
 */
@Composable
private fun BirthdayHeroCard(
    birthday: FacultyBirthday,
    store: com.gndec.timetable.domain.FacultyPhotoStore,
    onClick: () -> Unit,
    onPhotoClick: () -> Unit
) {
    val record = birthday.record
    val pressInteraction = remember { MutableInteractionSource() }
    val badge = when (birthday.daysUntil) {
        0 -> "TODAY"
        1 -> "TOMORROW"
        else -> "IN ${birthday.daysUntil} DAYS"
    }
    val dateLine = birthdayFormat.format(birthday.nextDate)
    val heroBrush = androidx.compose.ui.graphics.Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer
        )
    )
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .pressFeedback(pressInteraction)
            .background(heroBrush, RoundedCornerShape(24.dp)),
        interactionSource = pressInteraction,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ) {
                    Text(
                        badge,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.Cake,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    dateLine,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Open faculty details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(15.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FacultyAvatar(
                    store = store,
                    url = record.imageUrl,
                    name = record.name,
                    size = 64.dp,
                    onPhotoClick = if (record.imageUrl.isNotBlank()) onPhotoClick else null
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        prettyName(record.name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Turning ${birthday.turningAge}",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (record.department.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            listOfNotNull(
                                record.designation.takeIf { it.isNotBlank() },
                                FacultyDirectoryManager.shortDepartment(record.department).takeIf { it.isNotBlank() }
                            ).joinToString(" · "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(13.dp))
            Text(
                "“${birthdayWishFor(record.name, LocalDate.now())}”",
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Compact row for one of the following birthdays: photo, name, date + age. */
@Composable
private fun BirthdayRow(
    birthday: FacultyBirthday,
    store: com.gndec.timetable.domain.FacultyPhotoStore,
    onClick: () -> Unit
) {
    val record = birthday.record
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FacultyAvatar(
                store = store,
                url = record.imageUrl,
                name = record.name,
                size = 34.dp
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    prettyName(record.name),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(
                        FacultyDirectoryManager.shortDepartment(record.department).takeIf { it.isNotBlank() },
                        "turns ${birthday.turningAge}"
                    ).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                birthdayFormat.format(birthday.nextDate),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Open faculty details",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
