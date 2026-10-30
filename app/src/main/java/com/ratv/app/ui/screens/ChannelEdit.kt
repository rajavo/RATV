package com.ratv.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ratv.app.i18n.tr
import com.ratv.app.ui.components.RIcons
import com.ratv.app.ui.components.tvClickable
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T

/*
 * Editing the channel lists, as on a receiver: channels (or whole sections) deleted from the lists
 * leave every list, the guide and zapping, and come back from the same screen or from the settings.
 */

/** A small button of the lists' editing: "تعديل القوائم" / "تم", "حذف القسم كله". */
@Composable
internal fun EditPill(label: String, icon: ImageVector, active: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val color = when {
        danger -> RColors.Danger
        active -> RColors.OnSaffron
        else -> RColors.Text
    }
    Row(
        Modifier
            .clip(shape)
            .background(
                when {
                    active -> RColors.Saffron
                    danger -> RColors.DangerBg
                    else -> RColors.Surface2
                }
            )
            .tvClickable(shape, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Text(label, style = T.body(13.sp, FontWeight.Bold, color), maxLines = 1)
    }
}

/** The mark of a channel deleted from the lists (seen only while editing them). */
@Composable
internal fun DeletedMark() {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, RColors.Danger, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(RIcons.Close, contentDescription = null, tint = RColors.Danger, modifier = Modifier.size(12.dp))
        Text(tr("محذوفة"), style = T.body(11.sp, FontWeight.Bold, RColors.Danger), maxLines = 1)
    }
}
