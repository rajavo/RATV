package com.ratv.app.ui.components

import com.ratv.app.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
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
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(RColors.Saffron.copy(alpha = 0.18f), Color.Transparent),
                        center = Offset(size.width, size.height * 0.5f),
                        radius = size.width * 0.35f
                    )
                )
            }
            .statusBarsPadding()
    ) {
        val width = LocalConfiguration.current.screenWidthDp
        // Tighter sections on smaller screens, so the logo, the slogan and the time all fit.
        val tabPadding = when {
            width >= 1100 -> 16.dp
            width >= 760 -> 12.dp
            else -> 8.dp
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The slogan beside the logo whenever the screen is as wide as a TV's (960).
            // The slogan only where it fits beside the sections (a long translation leaves it out).
            RatvLogo(fontSize = 22.sp, slogan = width >= 940 && com.ratv.app.ui.theme.SLOGAN.length <= 34)
            Spacer(Modifier.width(20.dp))
            tvNavItems.forEachIndexed { index, item ->
                if (index > 0) {
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .width(1.dp)
                            .height(22.dp)
                            .background(RColors.LineStrong)
                    )
                }
                TopTab(item.label, item.route == current, tabPadding) { onSelect(item.route) }
            }
            // Search takes what is left, so it shrinks before anything else is squeezed.
            Box(
                Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                SearchPill(onSearch, Modifier.widthIn(max = 360.dp).fillMaxWidth())
            }
            Spacer(Modifier.width(16.dp))
            Text(hm(now), style = T.display(19.sp, FontWeight.Bold, RColors.Text2), maxLines = 1, softWrap = false)
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
private fun TopTab(label: String, selected: Boolean, padding: Dp, onClick: () -> Unit) {
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
            .padding(horizontal = padding),
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
                .then(if (selected) Modifier.background(RColors.AccentBrush) else Modifier)
        )
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    BoxWithConstraints(modifier) {
        // The full hint when it fits, a short one when it doesn't, the icon alone when space is tight.
        val hint = when {
            maxWidth >= 250.dp -> tr("ابحث عن قناة أو فيلم أو مسلسل")
            maxWidth >= 96.dp -> tr("ابحث")
            else -> null
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(shape)
                .background(RColors.Surface)
                .border(1.dp, RColors.LineStrong, shape)
                .tvClickable(shape, onClickLabel = tr("بحث"), onClick = onClick)
                // Named even when only the icon shows.
                .semantics { contentDescription = tr("ابحث") }
                .padding(horizontal = if (hint == null) 0.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (hint == null) Arrangement.Center else Arrangement.spacedBy(10.dp)
        ) {
            Icon(RIcons.Search, contentDescription = if (hint == null) tr("ابحث") else null, tint = RColors.Text2, modifier = Modifier.size(20.dp))
            if (hint != null) {
                Text(
                    hint,
                    style = T.body(14.sp, color = RColors.Text3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
