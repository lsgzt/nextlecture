package com.gndec.timetable.ui.notice

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.ErpNotice
import com.gndec.timetable.domain.ErpNoticeManager
import com.gndec.timetable.domain.Holiday
import com.gndec.timetable.ui.PremiumBottomBarContentClearance
import com.gndec.timetable.ui.PremiumPageHeader
import com.gndec.timetable.ui.PremiumScreenBackground
import com.gndec.timetable.ui.TealOutlineButton
import com.gndec.timetable.ui.motion.LocalReducedMotion
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.hapticTick
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.motion.pressFeedback
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private enum class NoticeTab(val label: String) { NOTICES("Notices"), HOLIDAYS("Holidays") }

private val HolidayLongDate = DateTimeFormatter.ofPattern("d MMMM yyyy (EEEE)")
private val HolidayMonthFormat = DateTimeFormatter.ofPattern("MMMM yyyy")

/**
 * A holiday resolved against today, so the row knows at a glance whether it is
 * still ahead of us. [date] is null when the feed's `date` is not ISO `yyyy-MM-dd`
 * — such a row is never counted as past and never gets a countdown, but it is
 * still listed rather than dropped.
 */
private data class HolidayRow(
    val holiday: Holiday,
    val date: LocalDate?,
    val daysUntil: Long?
) {
    val isPast: Boolean get() = daysUntil != null && daysUntil < 0L
    val month: YearMonth? get() = date?.let(YearMonth::from)
}

/**
 * The three categories the official feed uses. Matching is substring-based on
 * purpose: the feed has shipped "Public holiday", "Restricted holiday" and
 * "Half-day holiday", but `holidayHomeBannerItems` in PremiumUi already leans on
 * `contains` for the same reason. Half-day is tested first because it is the more
 * specific of the two — a "Half-day holiday" is not a restricted holiday.
 */
private enum class HolidayKind(val label: String) {
    PUBLIC("Public holiday"),
    RESTRICTED("Restricted holiday"),
    HALF_DAY("Half-day holiday"),
    OTHER("")
}

private fun holidayKind(category: String): HolidayKind = when {
    category.contains("half", ignoreCase = true) -> HolidayKind.HALF_DAY
    category.contains("restricted", ignoreCase = true) -> HolidayKind.RESTRICTED
    category.contains("public", ignoreCase = true) -> HolidayKind.PUBLIC
    else -> HolidayKind.OTHER
}

private fun buildHolidayRows(holidays: List<Holiday>, today: LocalDate): List<HolidayRow> =
    holidays
        .map { holiday ->
            val date = runCatching { LocalDate.parse(holiday.date) }.getOrNull()
            HolidayRow(holiday, date, date?.let { ChronoUnit.DAYS.between(today, it) })
        }
        .sortedWith(
            // Unparseable dates sort last so a malformed feed row never leads the list.
            compareBy<HolidayRow>({ it.date == null }, { it.date ?: LocalDate.MAX })
        )

@Composable
fun NoticeScreen(
    container: AppContainer,
    onOpenHome: () -> Unit,
    onOpenToday: () -> Unit,
    onOpenAlerts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNoticeDetail: (ErpNotice) -> Unit
) {
    val notices by container.erpNoticeManager.notices.collectAsStateWithLifecycle()
    val refreshing by container.erpNoticeManager.refreshing.collectAsStateWithLifecycle()
    val lastError by container.erpNoticeManager.lastError.collectAsStateWithLifecycle()
    val holidays by container.holidayManager.holidays.collectAsStateWithLifecycle()
    val holidaysRefreshing by container.holidayManager.refreshing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tabName by rememberSaveable { mutableStateOf(NoticeTab.NOTICES.name) }
    val tab = NoticeTab.valueOf(tabName)
    val reduced = LocalReducedMotion.current

    fun open(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        PremiumScreenBackground {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(top = 8.dp, bottom = 26.dp + PremiumBottomBarContentClearance),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    PremiumPageHeader(
                        title = "Notice",
                        subtitle = "Latest GNDEC official updates",
                        onSettings = onOpenSettings
                    )
                }
                item {
                    NoticeTabSelector(tab) { tabName = it.name }
                }
                item(key = "notice-tab-content") {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            if (reduced) {
                                fadeIn(tween(Motion.Normal, easing = Motion.EasingStandard)) togetherWith
                                    fadeOut(tween(Motion.Fast, easing = Motion.EasingStandard))
                            } else {
                                // Segmented-control motion — same as the Today screen's
                                // today/tomorrow switch: content slides toward the
                                // direction of the newly selected tab.
                                val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                                (slideInHorizontally(tween(Motion.Emphasized, easing = Motion.EasingEnter)) { dir * it / 12 } +
                                    fadeIn(tween(Motion.Emphasized, easing = Motion.EasingEnter))) togetherWith
                                    (slideOutHorizontally(tween(Motion.Normal, easing = Motion.EasingExit)) { -dir * it / 14 } +
                                        fadeOut(tween(Motion.Normal, easing = Motion.EasingExit)))
                            }
                        },
                        label = "noticeTabContent"
                    ) { current ->
                        when (current) {
                            NoticeTab.NOTICES -> NoticesTab(
                                notices = notices,
                                refreshing = refreshing,
                                lastError = lastError,
                                onRefresh = { container.appScope.launch { container.erpNoticeManager.refresh(forceRefresh = true) } },
                                onOpenNotice = onOpenNoticeDetail,
                                onViewAll = { open(ErpNoticeManager.NOTICE_URL) }
                            )
                            NoticeTab.HOLIDAYS -> HolidaysTab(
                                holidays = holidays,
                                refreshing = holidaysRefreshing,
                                onOpen = { open(it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Notices tab: the official feed exactly as it rendered before the split. */
@Composable
private fun NoticesTab(
    notices: List<ErpNotice>,
    refreshing: Boolean,
    lastError: String?,
    onRefresh: () -> Unit,
    onOpenNotice: (ErpNotice) -> Unit,
    onViewAll: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("GNDEC OFFICIAL NOTICE FEED", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                    Text("ERP + homepage notices · cached for instant loading", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onRefresh, enabled = !refreshing) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh notices", modifier = Modifier.size(18.dp))
                }
            }
        }
        if (notices.isEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(0.dp)) {
                val phase = if (refreshing) "__loading" else (lastError ?: "__empty")
                val phaseSwapIn = motionTween<Float>(Motion.Normal)
                val phaseSwapOut = motionTween<Float>(Motion.Fast)
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = { fadeIn(phaseSwapIn) togetherWith fadeOut(phaseSwapOut) },
                    label = "noticePhase"
                ) { current ->
                    Text(
                        when {
                            current == "__loading" -> "Loading official notices…"
                            current == "__empty" -> "No cached notices available yet."
                            else -> current
                        },
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            notices.forEach { notice ->
                NoticeCard(notice = notice, onClick = { onOpenNotice(notice) })
            }
        }
        TealOutlineButton(
            text = "View all previous notices on GNDEC official website",
            icon = Icons.Default.OpenInNew,
            modifier = Modifier.fillMaxWidth(),
            onClick = onViewAll
        )
    }
}

/** Holidays tab: the next holiday up front, then the full official list by month. */
@Composable
private fun HolidaysTab(holidays: List<Holiday>, refreshing: Boolean, onOpen: (String) -> Unit) {
    val today = remember(holidays) { LocalDate.now() }
    val rows = remember(holidays, today) { buildHolidayRows(holidays, today) }
    val next = remember(rows) { rows.firstOrNull { it.daysUntil != null && it.daysUntil >= 0L } }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NextHolidayCard(next = next, hasAny = rows.isNotEmpty(), refreshing = refreshing)
        HolidaysPanel(
            rows = rows,
            nextId = next?.holiday?.id,
            today = today,
            refreshing = refreshing,
            onOpen = onOpen
        )
    }
}

@Composable
private fun NextHolidayCard(next: HolidayRow?, hasAny: Boolean, refreshing: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val days = next?.daysUntil
                Text(
                    when {
                        next != null && days == 0L -> "HOLIDAY · TODAY"
                        next != null -> "NEXT HOLIDAY"
                        hasAny -> "NO UPCOMING HOLIDAYS"
                        refreshing -> "LOADING HOLIDAYS"
                        else -> "HOLIDAYS"
                    },
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    next?.holiday?.name ?: if (hasAny) "That’s everything for now" else "Official list unavailable",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (next != null && days != null) {
                    Spacer(Modifier.height(3.dp))
                    if (days == 0L) {
                        Text(
                            "Enjoy the break",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // "in 5 days" with the count picked out in the accent colour.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("in ", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "$days",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (days == 1L) " day" else " days",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    val date = next.date
                    if (date != null) {
                        Spacer(Modifier.height(9.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                date.format(HolidayLongDate),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            HolidayGlyph()
        }
    }
}

/** Decorative calendar mark standing in for the artwork in the design reference. */
@Composable
private fun HolidayGlyph() {
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape))
        listOf(
            Alignment.TopStart to 6.dp,
            Alignment.BottomEnd to 4.dp,
            Alignment.CenterEnd to 0.dp
        ).forEach { (alignment, inset) ->
            Box(
                Modifier
                    .align(alignment)
                    .padding(inset)
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape)
            )
        }
        Icon(
            Icons.Default.CalendarMonth,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp)
        )
    }
}

@Composable
private fun HolidaysPanel(
    rows: List<HolidayRow>,
    nextId: String?,
    today: LocalDate,
    refreshing: Boolean,
    onOpen: (String) -> Unit
) {
    // Months still to come read top-down in calendar order; months already gone
    // follow, most recent first, so the tab opens on what still matters.
    val months = remember(rows, today) {
        val byMonth = rows
            .mapNotNull { row -> row.month?.let { it to row } }
            .groupBy({ it.first }, { it.second })
        val (past, future) = byMonth.entries.partition { (month, _) -> month.atEndOfMonth().isBefore(today) }
        future.sortedBy { it.key } + past.sortedByDescending { it.key }
    }
    val undated = remember(rows) { rows.filter { it.month == null } }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "ALL HOLIDAYS",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp
            )
            Text(
                when {
                    rows.isEmpty() && refreshing -> "Loading…"
                    rows.isEmpty() -> "Unavailable"
                    else -> "Total ${rows.size} holidays"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (rows.isEmpty()) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(0.dp)) {
                Text(
                    if (refreshing) "Loading official holiday list…" else "Official list unavailable",
                    modifier = Modifier.padding(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            months.forEach { (month, monthRows) ->
                HolidayMonthHeader(month, monthRows.size)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    monthRows.forEach { row ->
                        HolidayRowCard(row, isNext = row.holiday.id == nextId, onOpen = onOpen)
                    }
                }
            }
            if (undated.isNotEmpty()) {
                HolidayMonthHeader(null, undated.size)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    undated.forEach { row -> HolidayRowCard(row, isNext = false, onOpen = onOpen) }
                }
            }
        }
    }
}

@Composable
private fun HolidayMonthHeader(month: YearMonth?, count: Int) {
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (month?.format(HolidayMonthFormat) ?: "DATE NOT AVAILABLE").uppercase(),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "$count",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(10.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Amber for restricted holidays. The theme's vivid [androidx.compose.material3.ColorScheme.tertiary]
 * measures about 2.3:1 against a white surface — fine as a fill, unreadable as
 * text — so light mode steps down to a darker orange that still reads as orange
 * but clears AA for body text. Dark mode keeps the theme tertiary, which has
 * plenty of contrast on the dark surface.
 */
@Composable
private fun restrictedAmber(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color(0xFFC2410C)
    else MaterialTheme.colorScheme.tertiary

@Composable
private fun HolidayRowCard(row: HolidayRow, isNext: Boolean, onOpen: (String) -> Unit) {
    val pressInteraction = remember { MutableInteractionSource() }
    val url = remember(row.holiday.source) { row.holiday.source.takeIf { it.startsWith("http") } }
    val kind = remember(row.holiday.category) { holidayKind(row.holiday.category) }
    // The rule and the label carry the kind: amber for restricted, aqua for a
    // half day, teal for a public holiday.
    val typeColor = when (kind) {
        HolidayKind.RESTRICTED -> restrictedAmber()
        HolidayKind.HALF_DAY -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    val lineColor = if (row.isPast) MaterialTheme.colorScheme.onSurfaceVariant else typeColor
    val badge = when (kind) {
        // A category the feed invents later still gets shown rather than dropped.
        HolidayKind.OTHER -> row.holiday.category.trim().takeIf { it.isNotBlank() }
        else -> kind.label
    }
    Card(
        onClick = { if (url != null) onOpen(url) },
        enabled = url != null,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (row.isPast) Modifier.alpha(0.42f) else Modifier)
            .pressFeedback(pressInteraction),
        interactionSource = pressInteraction,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    row.date?.let { it.dayOfMonth.toString() } ?: "—",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    row.date?.month?.getDisplayName(TextStyle.SHORT, Locale.getDefault())?.uppercase()
                        ?: row.holiday.displayDate.take(3).uppercase(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.width(3.dp).height(if (badge != null) 50.dp else 42.dp).background(lineColor, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.holiday.name,
                    // Restricted holidays are the ones worth flagging loudly — they
                    // still need the college open, so they get the amber type.
                    color = if (kind == HolidayKind.RESTRICTED) typeColor else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    row.holiday.weekday,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (badge != null) {
                    Text(
                        badge.uppercase(),
                        color = typeColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (isNext && row.daysUntil != null) {
                Spacer(Modifier.width(8.dp))
                CountdownPill(row.daysUntil)
            }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ChevronRight, contentDescription = "Open holiday notice", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CountdownPill(days: Long) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = when {
                days <= 0L -> "Today"
                days == 1L -> "In 1 day"
                else -> "In $days days"
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun NoticeTabSelector(tab: NoticeTab, onSelect: (NoticeTab) -> Unit) {
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        NoticeTab.values().forEach { item ->
            val active = item == tab
            val containerColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                animationSpec = motionTween(Motion.Normal),
                label = "noticeTabContainer"
            )
            val borderColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                animationSpec = motionTween(Motion.Normal),
                label = "noticeTabBorder"
            )
            val contentColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                animationSpec = motionTween(Motion.Normal),
                label = "noticeTabContent"
            )
            val pressInteraction = remember { MutableInteractionSource() }
            Card(
                onClick = {
                    if (item != tab) view.hapticTick()
                    onSelect(item)
                },
                modifier = Modifier.weight(1f).pressFeedback(pressInteraction, pressedScale = 0.96f),
                interactionSource = pressInteraction,
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = containerColor),
                border = BorderStroke(1.dp, borderColor),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (item == NoticeTab.NOTICES) Icons.Default.Campaign else Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.label,
                        color = contentColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(notice: ErpNotice, onClick: () -> Unit) {
    val pressInteraction = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().pressFeedback(pressInteraction),
        interactionSource = pressInteraction,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(notice.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.size(4.dp))
                Text(notice.displayDate, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                val sourceLabel = notice.author.ifBlank { notice.source }
                if (sourceLabel.isNotBlank()) Text(sourceLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "Open notice", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
