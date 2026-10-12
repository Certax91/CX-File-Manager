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

/** Markenfarben passend zum App-Icon: tiefes Navy, Bernstein-Orange, dazu Blau und Violett als Akzente. */
object Brand {
    val Navy = Color(0xFF0B1530)
    val NavyLight = Color(0xFF1B3A7A)
    val Amber = Color(0xFFF59E0B)
    val Orange = Color(0xFFF97316)
    val Blue = Color(0xFF3B82F6)
    val Violet = Color(0xFF8B5CF6)
    val Green = Color(0xFF22C55E)
    val Cyan = Color(0xFF22D3EE)
}

private val LightColors = lightColorScheme(
    primary = Color(0xFFB45309),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFEDD5),
    onPrimaryContainer = Color(0xFF431407),
    secondary = Color(0xFF2563EB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE7FF),
    onSecondaryContainer = Color(0xFF0B2A6B),
    tertiary = Color(0xFF7C3AED),
    tertiaryContainer = Color(0xFFEDE5FF),
    onTertiaryContainer = Color(0xFF2E1065),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF5F7FB),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE4E8F0),
    onSurfaceVariant = Color(0xFF516079),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEDF0F6),
    surfaceContainerHighest = Color(0xFFE3E7EF),
    outline = Color(0xFFB4BCCB),
    outlineVariant = Color(0xFFDDE2EA),
    error = Color(0xFFDC2626),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFBBF24),
    onPrimary = Color(0xFF2A1700),
    primaryContainer = Color(0xFF5C3A07),
    onPrimaryContainer = Color(0xFFFFE7B3),
    secondary = Color(0xFF7CB0FF),
    onSecondary = Color(0xFF06204D),
    secondaryContainer = Color(0xFF1C3566),
    onSecondaryContainer = Color(0xFFDCE7FF),
    tertiary = Color(0xFFC4B5FD),
    tertiaryContainer = Color(0xFF3B2A6E),
    onTertiaryContainer = Color(0xFFEDE5FF),
    background = Color(0xFF080E1E),
    onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF080E1E),
    onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1E2840),
    onSurfaceVariant = Color(0xFF9AA6BF),
    surfaceContainerLowest = Color(0xFF050A16),
    surfaceContainerLow = Color(0xFF0E1628),
    surfaceContainer = Color(0xFF111A2F),
    surfaceContainerHigh = Color(0xFF18223A),
    surfaceContainerHighest = Color(0xFF212C46),
    outline = Color(0xFF3A4762),
    outlineVariant = Color(0xFF243049),
    error = Color(0xFFF87171),
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
        Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
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
