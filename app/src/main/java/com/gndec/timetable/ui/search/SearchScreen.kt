package com.gndec.timetable.ui.search

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.VacantRoomsState
import com.gndec.timetable.ui.PremiumBottomBarContentClearance
import com.gndec.timetable.ui.PremiumScreenBackground
import com.gndec.timetable.ui.attendance.rememberAttendanceHomeState
import com.gndec.timetable.ui.attendance.subjectAttendance
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun SearchScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLecture: (LectureEntity, LocalDate) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }

    val settings by container.settings.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val notices by container.erpNoticeManager.notices.collectAsStateWithLifecycle()
    val holidays by container.holidayManager.holidays.collectAsStateWithLifecycle()
    val roomState by container.vacantRoomsManager.state.collectAsStateWithLifecycle()

    val group = settings.studentSubsection.ifBlank { settings.studentGroup.ifBlank { settings.group.orEmpty() } }
    val lectures by remember(group) {
        if (group.isBlank()) flowOf(emptyList())
        else container.db.lectureDao().observeForGroup(group)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val attendanceState = rememberAttendanceHomeState(container)

    // Rooms are only downloaded when a query actually looks like a room, so
    // opening search never triggers the fetch on its own.
    val trimmed = query.trim()
    val looksLikeRoom = trimmed.isNotEmpty() && trimmed.split(Regex("\\s+")).any {
        com.gndec.timetable.parse.RoomNameNormalizer.isRoomLike(it.filter { c -> c.isLetterOrDigit() }.lowercase())
    }
    LaunchedEffect(looksLikeRoom) {
        if (looksLikeRoom) container.vacantRoomsManager.ensureLoaded()
    }

    val today = remember { LocalDate.now() }
    val nowMinutes = remember {
        val z = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault())
        z.hour * 60 + z.minute
    }
    val snapshot = remember(settings, lectures, notices, holidays, roomState, attendanceState, today) {
        SearchSnapshot(
            settings = settings,
            lectures = lectures,
            notices = notices,
            holidays = holidays,
            attendance = lectures.mapNotNull { attendanceState.subjectAttendance(it.subject) }
                .distinctBy { it.subject },
            rooms = (roomState as? VacantRoomsState.Ready)?.data,
            today = today,
            nowMinutes = nowMinutes
        )
    }
    val results = remember(query, snapshot) { search(query, snapshot) }

    fun run(action: SearchAction) {
        focusManager.clearFocus()
        when (action) {
            SearchAction.None -> Unit
            is SearchAction.Navigate -> {
                // Search sits on top of Home, so leaving it is always a back press
                // followed by the destination.
                onBack()
                onNavigate(action.route)
            }
            is SearchAction.OpenUrl -> runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.url)))
            }
            is SearchAction.OpenLecture -> {
                onBack()
                onOpenLecture(action.lecture, action.date)
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        PremiumScreenBackground {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = 26.dp + PremiumBottomBarContentClearance
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "search-header") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Text(
                            "Search",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                item(key = "search-field") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        placeholder = { Text("Try crn, F106, 25 Sep, vacant rooms") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                    )
                }

                if (results.isEmpty() && trimmed.isNotEmpty()) {
                    item(key = "no-results") {
                        NoResults(query = trimmed)
                    }
                }

                var lastGroup: SearchGroup? = null
                results.forEachIndexed { index, result ->
                    if (result.group != lastGroup) {
                        lastGroup = result.group
                        item(key = "group-${result.group.name}-${index}") {
                            Text(
                                result.group.title.uppercase(),
                                modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 2.dp),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.2.sp
                            )
                        }
                    }
                    item(key = result.id) {
                        SearchResultRow(
                            result = result,
                            onClick = { run(result.action) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: SearchResult, onClick: () -> Unit) {
    val loading = result.id == ROOMS_LOADING_HINT
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (loading) Modifier else Modifier.clickable(onClick = onClick))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    iconFor(result.group),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    result.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    result.subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (result.action != SearchAction.None && !loading) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NoResults(query: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "Nothing found for “$query”",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Search works across your details, timetable, notices, holidays, attendance and rooms. Try a roll number, a room like F106, or a date like 25 Sep.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun iconFor(group: SearchGroup): ImageVector = when (group) {
    SearchGroup.DAY -> Icons.Default.CalendarMonth
    SearchGroup.YOU -> Icons.Default.Badge
    SearchGroup.ACTION -> Icons.Default.ArrowForward
    SearchGroup.ROOM -> Icons.Default.MeetingRoom
    SearchGroup.LECTURE -> Icons.Default.Today
    SearchGroup.NOTICE -> Icons.Default.Campaign
    SearchGroup.HOLIDAY -> Icons.Default.EventAvailable
    SearchGroup.ATTENDANCE -> Icons.Default.FactCheck
}
