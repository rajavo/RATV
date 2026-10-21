package com.ratv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ratv.app.ui.screens.rememberNow
import com.ratv.app.ui.theme.RColors
import com.ratv.app.ui.theme.T

/** Sections along the top, with search and the time: the layout for TV screens and wide windows. */
@Composable
fun TopBar(current: String, onSelect: (String) -> Unit, onSearch: () -> Unit) {
    val now = rememberNow(30_000L)
    Column(
        Modifier
            .fillMaxWidth()
            .background(RColors.NavBg)
            .statusBarsPadding()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RatvLogo(fontSize = 22.sp)
            Spacer(Modifier.width(28.dp))
            navItems.forEachIndexed { index, item ->
                if (index > 0) {
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .width(1.dp)
                            .height(22.dp)
                            .background(RColors.LineStrong)
                    )
                }
                TopTab(item.label, item.route == current) { onSelect(item.route) }
            }
            Spacer(Modifier.weight(1f))
            SearchPill(onSearch)
            Spacer(Modifier.width(20.dp))
            Text(hm(now), style = T.display(19.sp, FontWeight.Bold, RColors.Text2))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(RColors.Line)
        )
    }
}

@Composable
private fun TopTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .height(48.dp)
            .clip(shape)
            .background(if (focused) RColors.Surface2 else Color.Transparent)
            .focusRing(focused, shape)
            .clickable(interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            label,
            style = T.body(
                16.sp,
                if (selected || focused) FontWeight.Bold else FontWeight.Medium,
                when {
                    selected -> RColors.Saffron
                    focused -> RColors.Text
                    else -> RColors.Text2
                }
            ),
            maxLines = 1
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(22.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) RColors.Saffron else Color.Transparent)
        )
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .widthIn(min = 220.dp, max = 360.dp)
            .height(46.dp)
            .clip(shape)
            .background(RColors.Surface)
            .border(1.dp, RColors.LineStrong, shape)
            .tvClickable(shape, onClickLabel = "بحث", onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(RIcons.Search, contentDescription = null, tint = RColors.Text2, modifier = Modifier.size(20.dp))
        Text(
            "ابحث عن قناة أو فيلم أو مسلسل",
            style = T.body(14.sp, color = RColors.Text3),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
