package com.gndec.timetable.domain

import com.gndec.timetable.data.db.LectureEntity
import kotlinx.serialization.Serializable

/**
 * A classmate the student has added locally, so their free periods can be
 * compared against the student's own. Purely on-device: nothing about a friend
 * is ever sent anywhere, and removing one removes it permanently.
 */
@Serializable
data class Friend(
    val name: String,
    /** Exact timetable group, as it appears in the parsed document (e.g. "ITB2"). */
    val group: String
) {
    val isUsable: Boolean get() = name.isNotBlank() && group.isNotBlank()
}

/**
 * One friend resolved against a particular day.
 *
 * [hasData] matters more than it looks. The local `lectures` table holds every
 * parsed group, so a group the device has never parsed simply comes back empty —
 * and reporting that as "your friend is free all day" would be a confident
 * invention. Callers must skip friends whose group has no timetable at all.
 */
data class FriendDay(
    val friend: Friend,
    val lectures: List<LectureEntity>,
    val hasData: Boolean
) {
    /**
     * True when nothing in [startMinutes]..[endMinutes] overlaps one of this
     * friend's lectures. Touching endpoints do not count as an overlap, so a
     * friend whose class ends exactly when the slot starts is free.
     */
    fun isFreeDuring(startMinutes: Int, endMinutes: Int): Boolean =
        lectures.none { it.startMinutes < endMinutes && it.endMinutes > startMinutes }
}

/** Names of the friends who are free for the whole of [startMinutes]..[endMinutes]. */
fun List<FriendDay>.freeFriendNames(startMinutes: Int, endMinutes: Int): List<String> =
    filter { it.hasData && it.isFreeDuring(startMinutes, endMinutes) }
        .map { it.friend.name }

/**
 * "Aarav and Meera" / "Aarav, Meera and Karan" / "Aarav". Oxford-comma style so
 * a long list of friends still reads as a sentence.
 */
fun friendNameList(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    2 -> "${names[0]} and ${names[1]}"
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/** Joins a friend list into a clause: "Aarav and Meera are", "Aarav is". */
fun friendVerb(names: List<String>): String = if (names.size == 1) "is" else "are"

/**
 * A friend's earliest class that starts before the student's first lecture of the day.
 * Used on the Today/Tomorrow timeline to surface "Vishal has a lecture at 8:30 AM"
 * above the student's own rail.
 */
data class FriendEarlySlot(
    val name: String,
    /** Canonical type label: lecture / tutorial / practical. */
    val typeLabel: String,
    val startMinutes: Int
)

/**
 * For each friend with timetable data, pick their earliest lecture that starts
 * strictly before [beforeMinutes]. Sorted by start time, then name.
 */
fun List<FriendDay>.earlySlotsBefore(beforeMinutes: Int): List<FriendEarlySlot> =
    filter { it.hasData }
        .mapNotNull { day ->
            val first = day.lectures
                .filter { it.startMinutes < beforeMinutes }
                .minByOrNull { it.startMinutes }
                ?: return@mapNotNull null
            val typeLabel = when (first.lectureType?.trim()?.uppercase()) {
                "TUTORIAL" -> "tutorial"
                "PRACTICAL" -> "practical"
                else -> "lecture"
            }
            FriendEarlySlot(day.friend.name, typeLabel, first.startMinutes)
        }
        .sortedWith(compareBy({ it.startMinutes }, { it.name }))
