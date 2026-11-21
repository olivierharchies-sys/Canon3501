package be.canonhub

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Palette sobre : neutres + un accent bleu. */
private val Light = lightColorScheme(
    primary = Color(0xFF2F5BEA), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE4FF), onPrimaryContainer = Color(0xFF0B1F66),
    secondary = Color(0xFF5B6270), secondaryContainer = Color(0xFFE3E6EE), onSecondaryContainer = Color(0xFF1A1E26),
    background = Color(0xFFF7F8FB), surface = Color(0xFFF7F8FB),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F3F8),
    surfaceContainer = Color(0xFFECEEF4), surfaceContainerHigh = Color(0xFFE6E9F0),
    surfaceVariant = Color(0xFFE6E9F0), onSurfaceVariant = Color(0xFF5B6270),
    outline = Color(0xFFB4BAC6), outlineVariant = Color(0xFFDADEE6),
    error = Color(0xFFD32F2F),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DB2FF), onPrimary = Color(0xFF0B1F66),
    primaryContainer = Color(0xFF22357F), onPrimaryContainer = Color(0xFFDDE4FF),
    secondary = Color(0xFFC0C6D4), secondaryContainer = Color(0xFF2C313B), onSecondaryContainer = Color(0xFFE3E6EE),
    background = Color(0xFF111318), surface = Color(0xFF111318),
    surfaceContainerLowest = Color(0xFF0C0E12), surfaceContainerLow = Color(0xFF181B21),
    surfaceContainer = Color(0xFF1D2027), surfaceContainerHigh = Color(0xFF262A32),
    surfaceVariant = Color(0xFF262A32), onSurfaceVariant = Color(0xFFB4BAC6),
    outline = Color(0xFF5B6270), outlineVariant = Color(0xFF353A44),
    error = Color(0xFFFF8A80),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

/** Bandeau de messages partagé par tous les écrans. */
val LocalSnack = staticCompositionLocalOf { SnackbarHostState() }

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, shapes = AppShapes, content = content)
}
