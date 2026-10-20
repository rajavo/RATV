package com.ratv.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.ratv.app.R

object RColors {
    val Ink = Color(0xFF0B0C10)
    val Surface = Color(0xFF14161C)
    val Surface2 = Color(0xFF1C1F27)
    val Surface3 = Color(0xFF262A34)
    val Line = Color(0xFF1C1F27)
    val LineStrong = Color(0xFF3A3F4B)
    val Text = Color(0xFFF4F2EE)
    val Text2 = Color(0xFFB4B0A8)
    val Text3 = Color(0xFF8C8880)
    val Saffron = Color(0xFFFF9F1C)
    val SaffronLight = Color(0xFFFFB54D)
    val OnSaffron = Color(0xFF1A1205)
    val SaffronSoft = Color(0x29FF9F1C)
    val SaffronTint = Color(0x24FF9F1C)
    val Live = Color(0xFFD92B3A)
    val Success = Color(0xFF5BE29A)
    val SuccessBg = Color(0xFF10261A)
    val Danger = Color(0xFFFF7A7A)
    val DangerBg = Color(0xFF2A1214)
    val NavBg = Color(0xFF0F1115)
    val Scrim = Color(0xD9050608)
}

val Alexandria = FontFamily(
    Font(R.font.alexandria_medium, FontWeight.Medium),
    Font(R.font.alexandria_bold, FontWeight.Bold),
    Font(R.font.alexandria_extrabold, FontWeight.ExtraBold)
)

val Tajawal = FontFamily(
    Font(R.font.tajawal_regular, FontWeight.Normal),
    Font(R.font.tajawal_medium, FontWeight.Medium),
    Font(R.font.tajawal_bold, FontWeight.Bold)
)

/** Text styles used across the app (body in Tajawal, display in Alexandria). */
object T {
    fun body(size: TextUnit = 15.sp, weight: FontWeight = FontWeight.Normal, color: Color = RColors.Text) =
        TextStyle(fontFamily = Tajawal, fontSize = size, fontWeight = weight, color = color, lineHeight = size * 1.5f)

    fun display(size: TextUnit = 26.sp, weight: FontWeight = FontWeight.Bold, color: Color = RColors.Text) =
        TextStyle(fontFamily = Alexandria, fontSize = size, fontWeight = weight, color = color, lineHeight = size * 1.35f)

    val h1 = display(26.sp)
    val h2 = display(18.sp)
    val title = body(15.sp, FontWeight.Bold)
    val label = body(13.sp, FontWeight.Medium, RColors.Text2)
    val caption = body(12.sp, FontWeight.Normal, RColors.Text3)
    val small = body(11.sp, FontWeight.Medium, RColors.Text3)
}

private val scheme = darkColorScheme(
    primary = RColors.Saffron,
    onPrimary = RColors.OnSaffron,
    secondary = RColors.Saffron,
    onSecondary = RColors.OnSaffron,
    background = RColors.Ink,
    onBackground = RColors.Text,
    surface = RColors.Surface,
    onSurface = RColors.Text,
    surfaceVariant = RColors.Surface2,
    onSurfaceVariant = RColors.Text2,
    outline = RColors.LineStrong,
    error = RColors.Danger
)

private val base = Typography()

private val typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = Alexandria),
    displayMedium = base.displayMedium.copy(fontFamily = Alexandria),
    displaySmall = base.displaySmall.copy(fontFamily = Alexandria),
    headlineLarge = base.headlineLarge.copy(fontFamily = Alexandria),
    headlineMedium = base.headlineMedium.copy(fontFamily = Alexandria),
    headlineSmall = base.headlineSmall.copy(fontFamily = Alexandria),
    titleLarge = base.titleLarge.copy(fontFamily = Alexandria),
    titleMedium = base.titleMedium.copy(fontFamily = Tajawal),
    titleSmall = base.titleSmall.copy(fontFamily = Tajawal),
    bodyLarge = base.bodyLarge.copy(fontFamily = Tajawal),
    bodyMedium = base.bodyMedium.copy(fontFamily = Tajawal),
    bodySmall = base.bodySmall.copy(fontFamily = Tajawal),
    labelLarge = base.labelLarge.copy(fontFamily = Tajawal),
    labelMedium = base.labelMedium.copy(fontFamily = Tajawal),
    labelSmall = base.labelSmall.copy(fontFamily = Tajawal)
)

@Composable
fun RatvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            content()
        }
    }
}
