package com.ratv.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ratv.app.ui.components.CircleIconButton
import com.ratv.app.ui.components.QualityBadge
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.tvClickable
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T
import java.util.Locale
import kotlin.math.abs

/** A panel sliding over the picture from the start side, closed by tapping outside it. */
@Composable
internal fun SidePanel(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    width: Dp = 380.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }
    ) {
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .width(width)
                .fillMaxHeight()
                .background(Color(0xF20B0C10))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = 12.dp, end = 12.dp, top = 16.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = T.display(17.sp))
                    if (subtitle != null) Text(subtitle, style = T.caption, maxLines = 2)
                }
                CircleIconButton(RIcons.Close, "إغلاق", onClose, background = RColors.Surface2, size = 40.dp, iconSize = 20.dp)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
internal fun PanelHeader(text: String) {
    Text(text, style = T.label, modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
internal fun PanelNote(text: String, color: Color = RColors.Text3) {
    Text(text, style = T.body(12.5.sp, color = color), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
internal fun SourceRow(
    title: String,
    subtitle: String?,
    badge: String?,
    selected: Boolean,
    icon: ImageVector,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) RColors.Surface2 else Color.Transparent)
            .tvClickable(RoundedCornerShape(14.dp), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) RColors.Saffron else RColors.Text2, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = T.body(14.sp, FontWeight.Bold, if (selected) RColors.Saffron else RColors.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) Text(subtitle, style = T.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (badge != null) QualityBadge(badge, color = if (badge == "4K") RColors.SaffronLight else RColors.Text2)
        if (selected) Icon(RIcons.Check, contentDescription = "المختار", tint = RColors.Saffron, modifier = Modifier.size(18.dp))
    }
}

/** A small rounded action button for use inside panel rows. */
@Composable
internal fun ActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    icon: ImageVector? = null
) {
    Row(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(if (selected) RColors.Saffron else RColors.Surface2)
            .border(1.dp, if (selected) RColors.Saffron else RColors.LineStrong, RoundedCornerShape(19.dp))
            .tvClickable(RoundedCornerShape(19.dp), ringColor = if (selected) Color.White else RColors.Saffron, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (selected) RColors.OnSaffron else RColors.Text, modifier = Modifier.size(16.dp))
        }
        Text(label, style = T.body(13.sp, FontWeight.Bold, if (selected) RColors.OnSaffron else RColors.Text), maxLines = 1)
    }
}

/** A highlighted box at the top of a panel. */
@Composable
internal fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(RColors.Surface)
            .border(1.dp, RColors.SaffronSoft, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

/** "1.5" seconds, in the style used across the player. */
internal fun seconds(ms: Long): String {
    val s = abs(ms) / 1000.0
    return if (s == Math.floor(s)) String.format(Locale.US, "%d", s.toLong()) else String.format(Locale.US, "%.1f", s)
}

@Composable
internal fun BoxScope.TopNotice(text: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(
        modifier
            .align(Alignment.TopCenter)
            .padding(top = 24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xD90B0C10))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = RColors.Saffron, modifier = Modifier.size(16.dp))
        Text(text, style = T.body(13.sp, FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
