package de.ordnungshelfer

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Farben: ruhiges Anthrazit/Dunkelgrau mit weißer und grauer Schrift.
 * Das Blau aus dem App-Icon setzt nur Akzente (aktive Elemente, Fortschritt, Links).
 */
object Brand {
    /** Akzentblau aus dem Icon */
    val Accent = Color(0xFF4C8DF6)
    val AccentLight = Color(0xFF8AB4FF)
    /** Anthrazit-Töne */
    val Anthracite = Color(0xFF1C1E21)
    val AnthraciteLight = Color(0xFF2B2E33)
    /** Favoriten-Stern: dezent im Akzentblau statt gelb */
    val Star = Color(0xFF8AB4FF)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5FCC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6F7),
    onPrimaryContainer = Color(0xFF0E2C5E),
    secondary = Color(0xFF55595F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E4E7),
    onSecondaryContainer = Color(0xFF1C1E21),
    tertiary = Color(0xFF3D6FC4),
    tertiaryContainer = Color(0xFFDCE6F7),
    onTertiaryContainer = Color(0xFF0E2C5E),
    background = Color(0xFFF2F3F4),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF2F3F4),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E3E6),
    onSurfaceVariant = Color(0xFF5C6066),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFB),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE9EBED),
    surfaceContainerHighest = Color(0xFFE1E3E6),
    outline = Color(0xFFB6BAC0),
    outlineVariant = Color(0xFFD9DCE0),
    inverseSurface = Color(0xFF2B2E33),
    inverseOnSurface = Color(0xFFF2F3F4),
    inversePrimary = Color(0xFF8AB4FF),
    error = Color(0xFFC62828),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF0B1F3F),
    primaryContainer = Color(0xFF243A5C),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFB4B8BE),
    onSecondary = Color(0xFF1C1E21),
    secondaryContainer = Color(0xFF34383D),
    onSecondaryContainer = Color(0xFFE6E7E9),
    tertiary = Color(0xFF9DB9E8),
    tertiaryContainer = Color(0xFF2A3A52),
    onTertiaryContainer = Color(0xFFD6E3FF),
    background = Color(0xFF16181A),
    onBackground = Color(0xFFF1F2F3),
    surface = Color(0xFF16181A),
    onSurface = Color(0xFFF1F2F3),
    surfaceVariant = Color(0xFF2B2E33),
    onSurfaceVariant = Color(0xFFA3A8AF),
    surfaceContainerLowest = Color(0xFF101113),
    surfaceContainerLow = Color(0xFF1A1C1F),
    surfaceContainer = Color(0xFF1F2124),
    surfaceContainerHigh = Color(0xFF282B2F),
    surfaceContainerHighest = Color(0xFF32353A),
    outline = Color(0xFF4A4E55),
    outlineVariant = Color(0xFF33363B),
    inverseSurface = Color(0xFFE6E7E9),
    inverseOnSurface = Color(0xFF1C1E21),
    inversePrimary = Color(0xFF1F5FCC),
    error = Color(0xFFE57373),
    errorContainer = Color(0xFF5C2323),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private fun appTypography(): Typography {
    val t = Typography()
    return t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/**
 * Darstellung der App. [mode]: 0 = wie System, 1 = hell, 2 = dunkel.
 * [dynamic]: Farben aus dem Hintergrundbild (ab Android 12) statt der CX-Markenfarben.
 * Ohne Angaben werden die gespeicherten Einstellungen gelesen.
 */
@Composable
fun AppTheme(mode: Int? = null, dynamic: Boolean? = null, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val stored = remember { Store(ctx) }
    val m = mode ?: stored.loadThemeMode()
    val dyn = dynamic ?: stored.loadDynamicColor()
    val dark = when (m) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val scheme = when {
        dyn && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, shapes = AppShapes, typography = appTypography(), content = content)
}

/** Rundes, farbig hinterlegtes Symbol (für Kategorien, Einstellungen, leere Zustände). */
@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 44.dp, iconSize: Dp = 24.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(iconSize), tint = tint)
    }
}

/** Gruppierter Bereich mit Überschrift – wie in modernen Einstellungs-Apps. */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/** Großer, zentrierter Hinweis für leere Seiten. */
@Composable
fun ModernEmptyState(icon: ImageVector, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconBadge(icon, MaterialTheme.colorScheme.primary, size = 88.dp, iconSize = 44.dp)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (text.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

val SmallCaps = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
