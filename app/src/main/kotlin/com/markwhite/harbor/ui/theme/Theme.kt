package com.markwhite.harbor.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.markwhite.harbor.R

/** Barlow Condensed (OFL): the panel's silkscreen and Harbor's display face. */
val Condensed = FontFamily(
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

/** Palette tokens (ui-spec §9.2), from fm1-emu's web/style.css and panel.rs, contrast-corrected. */
object Tokens {
    val room = Color(0xFF18191B)
    val card = Color(0xFF1E1F22)
    val well = Color(0xFF232427)
    val bar = Color(0xFF161719)
    val line = Color(0xFF3A3B3F)
    val ink = Color(0xFFE9E6DE)
    val dim = Color(0xFFA9A7A1)
    val faint = Color(0xFF8C8A85)
    val amber = Color(0xFFF2A33A)
    val onAmber = Color(0xFF2A1A03)
    val amberHi = Color(0xFFFFC56E)
    val green = Color(0xFF7CC48A)
    val error = Color(0xFFFFB4AB)
    /** the record button's red */
    val rec = Color(0xFFF0574B)

    // the FM-1 panel
    val bodyTop = Color(0xFF5E6166)
    val bodyBot = Color(0xFF4A4D52)
    val tray = Color(0xFF2B2C2F)
    val silk = Color(0xFFE9E6DE)
    val btnTop = Color(0xFF4B4E54)
    val btnBot = Color(0xFF3B3D42)
    val btnInk = Color(0xFFE9E6DE)
    val keyTop = Color(0xFF6A5557)
    val keyBot = Color(0xFF4F3F42)
    val keyLine = Color(0xFF8F7C7E)
    val ledGreen = Color(0xFF62D36E)
    val knobCap = Color(0xFF2E3034)
    val knobRim = Color(0xFF1F2023)
}

private val colors = darkColorScheme(
    primary = Tokens.amber,
    onPrimary = Tokens.onAmber,
    primaryContainer = Color(0xFF4A3410),
    onPrimaryContainer = Tokens.amberHi,
    secondary = Tokens.dim,
    onSecondary = Tokens.room,
    secondaryContainer = Color(0xFF3A3B3F),
    onSecondaryContainer = Tokens.ink,
    background = Tokens.room,
    onBackground = Tokens.ink,
    surface = Tokens.room,
    onSurface = Tokens.ink,
    surfaceVariant = Tokens.well,
    onSurfaceVariant = Tokens.dim,
    surfaceContainerLowest = Tokens.bar,
    surfaceContainerLow = Tokens.card,
    surfaceContainer = Tokens.card,
    surfaceContainerHigh = Tokens.well,
    surfaceContainerHighest = Color(0xFF2A2B2F),
    outline = Color(0xFF6A6965),
    outlineVariant = Tokens.line,
    error = Tokens.error,
    onError = Color(0xFF690005),
    tertiary = Tokens.green,
)

/** Small caps labels over sections (web `.eyebrow`). */
val Eyebrow = TextStyle(fontFamily = Condensed, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.14.em, color = Tokens.dim)


/** Panel silkscreen: condensed, tracked out, in the finish's ink. */
val Silk = TextStyle(fontFamily = Condensed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.12.em, color = Tokens.silk)

/** The docked firmware's name over the panel. */
val Display = TextStyle(fontFamily = Condensed, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.02.em)

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

/** Headings in the panel's condensed face; body text stays the system's, for reading. */
private val typography = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontFamily = Condensed, fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 28.sp),
        titleLarge = t.titleLarge.copy(fontFamily = Condensed, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = 0.01.em),
        titleMedium = t.titleMedium.copy(fontFamily = Condensed, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 0.01.em),
        titleSmall = t.titleSmall.copy(fontFamily = Condensed, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun HarborTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes) {
        Surface(color = Tokens.room, contentColor = Tokens.ink, content = content)
    }
}
