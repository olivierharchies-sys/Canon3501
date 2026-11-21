package be.canonhub

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Carte de section : en-tête (icône + titre + sous-titre) puis contenu. */
@Composable
fun Section(
    title: String,
    icon: ImageVector? = null,
    sub: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(
                        Modifier.size(40.dp).background(accent.copy(alpha = 0.14f), MaterialTheme.shapes.small),
                        contentAlignment = Alignment.Center,
                    ) { Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.size(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (sub != null) Hint(sub)
                }
                trailing()
            }
            content()
        }
    }
}

/** Texte d'aide discret. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Petite pastille d'état. */
@Composable
fun Pill(text: String, color: Color) {
    Box(Modifier.background(color.copy(alpha = 0.14f), MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** Jours écoulés depuis un instant (ms), ou null si jamais. */
fun daysSince(t: Long): Long? = if (t == 0L) null else (System.currentTimeMillis() - t) / (24 * 3600 * 1000L)
