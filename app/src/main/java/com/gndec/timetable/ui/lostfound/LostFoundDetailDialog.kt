package com.gndec.timetable.ui.lostfound

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gndec.timetable.domain.LostFoundImageStore
import com.gndec.timetable.domain.LostFoundManager
import com.gndec.timetable.net.LostFoundItem

/**
 * Full view of one post: the FULL image (downloaded once, on open), the
 * complete description and the relative post time.
 */
@Composable
fun LostFoundDetailDialog(
    item: LostFoundItem,
    store: LostFoundImageStore,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Image area (or kind icon when the post is text-only).
                if (item.image_path != null) {
                    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = item.image_path) {
                        value = store.loadFull(item.image_path)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.3f)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap!!.asImageBitmap(),
                                contentDescription = item.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            androidx.compose.material3.CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(10.dp))
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (item.kind == LostFoundItem.KIND_FOUND)
                                    MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.tertiaryContainer
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (item.kind == LostFoundItem.KIND_FOUND) "F" else "L",
                            color = if (item.kind == LostFoundItem.KIND_FOUND)
                                MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${item.kind.replaceFirstChar { it.uppercase() }} · " +
                                LostFoundManager.relativeTime(item.created_at),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                if (item.description.isNotBlank()) {
                    Text(
                        item.description,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}
