package com.ratv.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ratv.app.ui.components.formatCount
import com.ratv.app.ui.components.tvClickable
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import kotlinx.coroutines.delay

/** One line of the section column: a special list (recent, all, favourites) or a provider section. */
data class SideEntry(val key: String, val name: String, val count: Int, val icon: ImageVector? = null)

/**
 * The column of sections with how many items each holds. With a remote, resting on a section
 * shows its content beside it; OK moves into that content.
 */
@Composable
fun SectionColumn(
    entries: List<SideEntry>,
    selected: String,
    onSelect: (String) -> Unit,
    onEnter: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedFocus: FocusRequester? = null
) {
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pending) {
        val key = pending ?: return@LaunchedEffect
        delay(350L)
        onSelect(key)
    }
    LazyColumn(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(RColors.Surface),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(entries, key = { it.key }) { entry ->
            val isSelected = entry.key == selected
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .then(if (isSelected && selectedFocus != null) Modifier.focusRequester(selectedFocus) else Modifier)
                    .onFocusChanged { if (it.isFocused) pending = entry.key }
                    .clip(shape)
                    .background(if (isSelected) RColors.Surface3 else Color.Transparent)
                    .tvClickable(shape) {
                        pending = null
                        onSelect(entry.key)
                        onEnter(entry.key)
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (entry.icon != null) {
                    Icon(
                        entry.icon,
                        contentDescription = null,
                        tint = if (isSelected) RColors.Saffron else RColors.Text3,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    entry.name,
                    style = T.body(15.sp, if (isSelected) FontWeight.Bold else FontWeight.Medium, if (isSelected) RColors.Saffron else RColors.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) RColors.SaffronSoft else RColors.Surface2)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        formatCount(entry.count),
                        style = T.body(12.sp, FontWeight.Bold, if (isSelected) RColors.SaffronLight else RColors.Text2),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** Long press of OK (or the Menu key) on a remote, as a second action besides the normal click. */
fun Modifier.tvLongPress(onLongPress: () -> Unit): Modifier = composed {
    var fired by remember { mutableStateOf(false) }
    onPreviewKeyEvent { e ->
        val center = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        when {
            e.key == Key.Menu && e.type == KeyEventType.KeyUp -> {
                onLongPress()
                true
            }
            center && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> {
                if (!fired) {
                    fired = true
                    onLongPress()
                }
                true
            }
            center && e.type == KeyEventType.KeyUp && fired -> {
                fired = false
                true
            }
            else -> false
        }
    }
}
