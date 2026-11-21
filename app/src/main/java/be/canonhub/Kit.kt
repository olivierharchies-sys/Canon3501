@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package be.canonhub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Dégradés de chaque fonction : même couleur partout (accueil, en-tête, bouton). */
object Brand {
    val Print = listOf(Color(0xFF3D6DF2), Color(0xFF7B4DFF))
    val Copy = listOf(Color(0xFFFF8A3D), Color(0xFFE8456B))
    val Scan = listOf(Color(0xFF12B76A), Color(0xFF0A9396))
    val Care = listOf(Color(0xFF00A6A6), Color(0xFF2E7DD1))
    val Ink = listOf(Color(0xFF9C3DF2), Color(0xFFE040A0))
    val Docs = listOf(Color(0xFFE5484D), Color(0xFFF76B15))
    val Queue = listOf(Color(0xFF8D6E63), Color(0xFF5D4037))
    val Info = listOf(Color(0xFF3949AB), Color(0xFF00ACC1))
    val Settings = listOf(Color(0xFF5B6B82), Color(0xFF39445A))
    val Home = listOf(Color(0xFF1F2A44), Color(0xFF3D6DF2))
}

/** En-tête graphique en dégradé, avec cercles décoratifs. */
@Composable
fun Hero(
    title: String,
    sub: String?,
    icon: ImageVector,
    colors: List<Color>,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(colors))
            .drawBehind {
                drawCircle(Color.White.copy(alpha = 0.08f), radius = size.height * 0.9f, center = Offset(size.width * 0.95f, -size.height * 0.1f))
                drawCircle(Color.White.copy(alpha = 0.06f), radius = size.height * 0.5f, center = Offset(size.width * 0.75f, size.height * 1.1f))
            }
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                trailing()
            }
            content()
        }
    }
}

/** Libellé de groupe. */
@Composable
fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Choix unique en boutons segmentés (2 à 4 options). */
@Composable
fun <T> Segmented(
    items: List<T>,
    sel: T,
    label: (T) -> String,
    icon: ((T) -> ImageVector?)? = null,
    enabled: Boolean = true,
    onSel: (T) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { v ->
            val on = v == sel
            val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, label = "seg")
            val fg by animateColorAsState(if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, label = "segT")
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(bg)
                    .clickable(enabled = enabled) { onSel(v) }.padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon?.invoke(v)?.let {
                    Icon(it, null, tint = fg, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label(v), color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Grande case de choix (icône + titre + sous-titre), encadrée quand elle est choisie. */
@Composable
fun ChoiceTile(
    label: String,
    sub: String?,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLow, label = "tileBg")
    val bd by animateColorAsState(if (selected) accent else MaterialTheme.colorScheme.outlineVariant, label = "tileBd")
    val a = if (enabled) 1f else 0.45f
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .border(if (selected) 2.dp else 1.dp, bd, RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).background(accent.copy(alpha = 0.16f * a), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = accent.copy(alpha = a), modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.weight(1f))
                if (selected) Icon(Icons.Filled.Check, null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = a), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = a), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Grille de cases de choix, [cols] par ligne. */
@Composable
fun <T> ChoiceGrid(
    items: List<T>,
    sel: T,
    cols: Int = 2,
    label: (T) -> String,
    sub: (T) -> String? = { null },
    icon: (T) -> ImageVector,
    accent: Color = MaterialTheme.colorScheme.primary,
    enabled: (T) -> Boolean = { true },
    onSel: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { v ->
                    ChoiceTile(label(v), sub(v), icon(v), v == sel, Modifier.weight(1f), accent, enabled(v)) { onSel(v) }
                }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Section repliable : en-tête cliquable, contenu animé. */
@Composable
fun Fold(
    title: String,
    icon: ImageVector,
    sub: String? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    open: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var on by rememberSaveable(title) { mutableStateOf(open) }
    val rot by animateFloatAsState(if (on) 180f else 0f, label = "fold")
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { on = !on }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).background(accent.copy(alpha = 0.14f), MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = accent, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (sub != null) Hint(sub)
            }
            Icon(Icons.Filled.ExpandMore, if (on) "Replier" else "Déplier", Modifier.rotate(rot))
        }
        AnimatedVisibility(on) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
            }
        }
    }
}

/** Compteur avec gros boutons − et +. */
@Composable
fun Counter(label: String, value: Int, range: IntRange, icon: ImageVector? = null, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        FilledTonalIconButton(enabled = value > range.first, onClick = { onChange(value - 1) }) { Icon(Icons.Filled.Remove, "Moins") }
        Text("$value", Modifier.widthIn(min = 48.dp), style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        FilledTonalIconButton(enabled = value < range.last, onClick = { onChange(value + 1) }) { Icon(Icons.Filled.Add, "Plus") }
    }
}

/** Interrupteur avec icône et sous-titre. */
@Composable
fun Switcher(label: String, checked: Boolean, sub: String? = null, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Hint(sub)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Liste déroulante : utile pour les longues listes (types de papier). */
@Composable
fun Picker(title: String, icon: ImageVector, items: List<Pair<String, String>>, sel: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val cur = items.firstOrNull { it.first == sel }?.second ?: "—"
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .clickable(enabled = items.isNotEmpty()) { open = true }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(cur, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (k, t) ->
                DropdownMenuItem(
                    text = { Text(t, fontWeight = if (k == sel) FontWeight.Bold else FontWeight.Normal) },
                    leadingIcon = { if (k == sel) Icon(Icons.Filled.Check, null) },
                    onClick = { open = false; onPick(k) },
                )
            }
        }
    }
}

/** Puces défilantes à choix unique (listes moyennes). */
@Composable
fun <T> Chips(items: List<T>, sel: T, label: (T) -> String, onSel: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { v -> FilterChip(selected = v == sel, onClick = { onSel(v) }, label = { Text(label(v)) }) }
    }
}

/** Petite feuille dessinée : orientation, pages par feuille, couleur. */
@Composable
fun SheetGlyph(landscape: Boolean, nUp: Int, color: Boolean, modifier: Modifier = Modifier.size(72.dp)) {
    val paper = MaterialTheme.colorScheme.surfaceContainerLowest
    val edge = MaterialTheme.colorScheme.outline
    val line = MaterialTheme.colorScheme.outlineVariant
    val img = if (color) listOf(Color(0xFF3D6DF2), Color(0xFFE040A0), Color(0xFFFFB300)) else listOf(Color(0xFF555555), Color(0xFF999999), Color(0xFF777777))
    Canvas(modifier) {
        val s = minOf(size.width, size.height) * 0.92f
        val w = if (landscape) s else s / 1.414f
        val h = if (landscape) s / 1.414f else s
        val o = Offset((size.width - w) / 2, (size.height - h) / 2)
        drawRoundRect(paper, o, Size(w, h), CornerRadius(6f))
        drawRoundRect(edge, o, Size(w, h), CornerRadius(6f), style = Stroke(2f))
        val m = s * 0.07f
        val cells: List<Pair<Offset, Size>> = when (nUp) {
            2 -> if (w >= h) {
                val cw = (w - 3 * m) / 2
                listOf(Offset(o.x + m, o.y + m) to Size(cw, h - 2 * m), Offset(o.x + 2 * m + cw, o.y + m) to Size(cw, h - 2 * m))
            } else {
                val ch = (h - 3 * m) / 2
                listOf(Offset(o.x + m, o.y + m) to Size(w - 2 * m, ch), Offset(o.x + m, o.y + 2 * m + ch) to Size(w - 2 * m, ch))
            }
            4 -> {
                val cw = (w - 3 * m) / 2; val ch = (h - 3 * m) / 2
                listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).map { (i, j) -> Offset(o.x + m + i * (cw + m), o.y + m + j * (ch + m)) to Size(cw, ch) }
            }
            else -> listOf(Offset(o.x + m, o.y + m) to Size(w - 2 * m, h - 2 * m))
        }
        cells.forEachIndexed { k, (co, cs) ->
            val ih = cs.height * 0.45f
            drawRoundRect(img[k % img.size].copy(alpha = 0.75f), co, Size(cs.width, ih), CornerRadius(3f))
            var y = co.y + ih + cs.height * 0.12f
            while (y < co.y + cs.height - 1f) {
                drawLine(line, Offset(co.x, y), Offset(co.x + cs.width * (if (y > co.y + cs.height * 0.85f) 0.6f else 1f), y), strokeWidth = 2f)
                y += cs.height * 0.14f
            }
        }
    }
}

/** Gros bouton d'action en dégradé. */
@Composable
fun BigButton(text: String, icon: ImageVector, colors: List<Color>, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    val on = enabled && !busy
    Box(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(18.dp))
            .background(if (enabled) Brush.horizontalGradient(colors) else SolidColor(MaterialTheme.colorScheme.surfaceContainerHigh))
            .clickable(enabled = on, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val fg = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), color = fg, strokeWidth = 2.5.dp)
            else Icon(icon, null, tint = fg)
            Spacer(Modifier.width(10.dp))
            Text(text, color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** Écran avec contenu défilant et barre d'action fixe en bas. */
@Composable
fun ActionScreen(m: Modifier, bottom: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(m.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
        Surface(shadowElevation = 10.dp, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                bottom()
            }
        }
    }
}

/** Message d'état : progression et texte copiable. */
@Composable
fun StatusMsg(msg: String, busy: Boolean) {
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
    if (msg.isNotEmpty()) {
        val clip = LocalClipboardManager.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f)) {
                Text(msg, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
            if (!busy) TextButton(onClick = { clip.setText(AnnotatedString(msg)) }) { Text("Copier") }
        }
    }
}
