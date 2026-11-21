@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package be.canonhub

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Grande tuile d'action en dégradé (Imprimer, Copier, Numériser). */
@Composable
fun ActionTile(label: String, sub: String, icon: ImageVector, colors: List<Color>, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(colors))
            .drawBehind { drawCircle(Color.White.copy(alpha = 0.10f), radius = size.width * 0.55f, center = Offset(size.width, 0f)) }
            .clickable(onClick = onClick).padding(14.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(46.dp).background(Color.White.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Column {
                Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(sub, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Petite tuile d'outil : pastille colorée + libellé. */
@Composable
fun ToolTile(label: String, sub: String, icon: ImageVector, colors: List<Color>, off: Boolean, modifier: Modifier, badge: Boolean = false, onClick: () -> Unit) {
    Card(
        onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (off) Brush.linearGradient(listOf(Color.Gray, Color.DarkGray)) else Brush.linearGradient(colors)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                if (badge) Box(Modifier.align(Alignment.TopEnd).size(12.dp).background(MaterialTheme.colorScheme.error, CircleShape))
            }
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (off) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text(if (off) "Non pris en charge" else sub, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Compteur d'activité (impressions, scans, copies). */
@Composable
fun Stat(value: Int, label: String, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun HomeScreen(m: Modifier, prefs: PrinterPrefs, ip: String, setIp: (String) -> Unit, go: (Screen) -> Unit) {
    val profiles = remember { prefs.profiles }
    var refresh by remember { mutableIntStateOf(0) }
    val status by produceState<HomeStatus?>(null, ip, refresh) {
        value = null
        value = if (ip.isBlank()) null else homeStatus(ip)
    }
    val clog = prefs.clogReminder && (daysSince(prefs.lastPrint) ?: 0L) >= prefs.clogDays

    Column(
        m.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ----- État de l'imprimante -----
        val st = status
        Hero(
            st?.model?.takeIf { it.isNotBlank() } ?: "Canon G3000",
            when {
                ip.isBlank() -> "Aucune imprimante configurée"
                st == null -> "$ip · vérification…"
                st.online -> "$ip · prête"
                else -> "$ip · hors ligne"
            },
            Icons.Filled.Print, Brand.Home,
            trailing = {
                if (ip.isNotBlank()) IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, "Actualiser", tint = Color.White) }
            },
        ) {
            if (ip.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                val dot = when { st == null -> Color(0xFFB0BEC5); st.online -> Color(0xFF4ADE80); else -> Color(0xFFFF6B6B) }
                Box(Modifier.size(10.dp).background(dot, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    when { st == null -> "Connexion…"; st.online -> "En ligne sur le Wi-Fi"; else -> "Injoignable" },
                    color = Color.White, style = MaterialTheme.typography.labelLarge,
                )
            }
            if (profiles.size >= 2) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { p ->
                    val on = p.ip == ip
                    Text(
                        p.name,
                        Modifier.clip(CircleShape).background(Color.White.copy(alpha = if (on) 0.95f else 0.18f))
                            .clickable { setIp(p.ip) }.padding(horizontal = 14.dp, vertical = 6.dp),
                        color = if (on) Brand.Home[0] else Color.White, style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (clog) Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x33FFB300)).clickable { go(Screen.Maintenance) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Warning, null, tint = Color(0xFFFFD166), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Pas d'impression depuis ${daysSince(prefs.lastPrint)} j : test des buses conseillé", color = Color.White,
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        if (ip.isBlank() || st?.online == false) Section(if (ip.isBlank()) "Connexion" else "Imprimante introuvable", Icons.Filled.Settings) {
            if (ip.isBlank()) FirstRun(setIp) { go(Screen.Settings) } else FindPrinter(ip, setIp)
        }

        // ----- Actions principales -----
        Label("Actions rapides")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val tm = Modifier.weight(1f).aspectRatio(0.82f)
            ActionTile("Imprimer", "PDF, photos", Icons.Filled.Print, Brand.Print, tm) { go(Screen.Print) }
            ActionTile("Copier", "Document, carte ID", Icons.Filled.ContentCopy, Brand.Copy, tm) { go(Screen.Copy) }
            ActionTile("Numériser", "PDF, JPEG, OCR", Icons.Filled.DocumentScanner, Brand.Scan, tm) { go(Screen.Scan) }
        }

        // ----- Outils -----
        Label("Outils")
        val tools = listOf(
            listOf(
                Triple("Entretien", if (clog) "Buses conseillé" else "Nettoyage, buses", Icons.Filled.Build) to (Brand.Care to Screen.Maintenance),
                Triple("Encre", "Niveaux, état", Icons.Filled.WaterDrop) to (Brand.Ink to Screen.Ink),
                Triple("File", "Tâches", Icons.Filled.HourglassEmpty) to (Brand.Queue to Screen.Queue),
            ),
            listOf(
                Triple("Documents", "Scans enregistrés", Icons.Filled.Folder) to (Brand.Docs to Screen.History),
                Triple("Infos", "Modèle, compteurs", Icons.Filled.Info) to (Brand.Info to Screen.Info),
                Triple("Réglages", "Imprimantes", Icons.Filled.Settings) to (Brand.Settings to Screen.Settings),
            ),
        )
        tools.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (t, cs) ->
                    val (label, sub, icon) = t
                    val (colors, target) = cs
                    val off = target == Screen.Ink && st?.let { !it.ipp } == true
                    ToolTile(label, sub, icon, colors, off, Modifier.weight(1f), badge = target == Screen.Maintenance && clog) { go(target) }
                }
            }
        }

        // ----- Activité -----
        Card(
            Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Label("Activité depuis l'installation")
                Row {
                    Stat(prefs.counter("prints"), "impressions", Brand.Print[0], Modifier.weight(1f))
                    Stat(prefs.counter("copies"), "copies", Brand.Copy[1], Modifier.weight(1f))
                    Stat(prefs.counter("scans"), "scans", Brand.Scan[0], Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
