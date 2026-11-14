package com.gndec.timetable.ui.profile

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.Friend
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.motionTween
import kotlinx.coroutines.launch

/**
 * Local-only friend list for the friends-aware timetable. Nothing here is
 * uploaded, shared or synced — it exists so the Today tab can compare the
 * student's free periods against classmates whose groups this device has already
 * parsed.
 */
@Composable
fun FriendsSection(
    container: AppContainer,
    friends: List<Friend>,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    // Group names come from the parsed document, so a typo cannot silently add a
    // friend whose timetable will never resolve. Suggestions only, never a limit.
    val knownGroups by produceState<Pair<List<String>, Boolean>>(emptyList<String>() to true, expanded) {
        if (!expanded) return@produceState
        value = runCatching { container.db.lectureDao().distinctGroups() }.getOrDefault(emptyList()) to false
    }

    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(motionTween(Motion.Normal)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Groups,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Friends",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (friends.isEmpty()) "Add classmates to compare free periods"
                        else "${friends.size} added · shown on your Today tab",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Done" else "Edit")
                }
            }

            if (friends.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                friends.forEach { friend ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                friend.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                friend.group,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (expanded) {
                            IconButton(onClick = { scope.launch { container.settings.removeFriend(friend.name) } }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove ${friend.name}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; message = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Friend's name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next
                    )
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it; message = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Timetable group") },
                    placeholder = { Text("e.g. ITB2") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                val suggestions = remember(group, knownGroups.first) {
                    val q = group.trim()
                    knownGroups.first.filter { it.isNotBlank() && (q.isEmpty() || it.contains(q, ignoreCase = true)) }.take(8)
                }
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        suggestions.take(6).forEach { candidate ->
                            // Capsule chips (not CircleShape + TextButton) so long
                            // names like "BBA1 Automatic Subgroup" stay readable.
                            androidx.compose.material3.Surface(
                                onClick = { group = candidate },
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    candidate,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                if (knownGroups.first.isEmpty() && !knownGroups.second) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "No timetable has been parsed yet, so groups cannot be suggested. Type the group exactly as it appears in the timetable.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = name.isNotBlank() && group.isNotBlank(),
                        onClick = {
                            scope.launch {
                                container.settings.addFriend(Friend(name = name, group = group))
                                message = "Added ${name.trim()}"
                                name = ""
                                group = ""
                            }
                        }
                    ) {
                        Text("Add friend")
                    }
                    message?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Stored only on this device. Their timetable is read from timetables this app has already downloaded.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
