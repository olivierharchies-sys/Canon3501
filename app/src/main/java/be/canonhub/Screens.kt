@file:OptIn(ExperimentalMaterial3Api::class)

package be.canonhub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Color as AColor

// ---------- Encre et état ----------

private fun reasonLabel(r: String): String {
    val base = r.removeSuffix("-error").removeSuffix("-warning").removeSuffix("-report")
    return when (base) {
        "media-empty", "media-needed" -> "Plus de papier"
        "media-jam" -> "Bourrage papier"
        "marker-supply-low" -> "Encre faible"
        "marker-supply-empty" -> "Cartouche vide"
        "cover-open" -> "Capot ouvert"
        "offline" -> "Hors ligne"
        "paused" -> "En pause"
        else -> base
    }
}

@Composable
fun InkScreen(m: Modifier, prefs: PrinterPrefs, ip: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<Ipp.Info?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            loading = true; err = null
            try {
                val i = Ipp.query(ip)
                info = i
                prefs.lastInk = Ipp.inkText(i)
                InkWidget.update(ctx)
            } catch (e: Exception) {
                err = e.message ?: "Erreur"
            }
            loading = false
        }
    }
    LaunchedEffect(ip) { load() }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        info?.let { i ->
            Section(i.model, Icons.Filled.Print, sub = "État : ${i.state}") {
                if (i.message.isNotBlank()) Hint(i.message)
                i.reasons.forEach { Pill(reasonLabel(it), MaterialTheme.colorScheme.error) }
            }
            Section("Niveaux d'encre", Icons.Filled.WaterDrop) {
                if (i.inks.isEmpty()) Hint("Cette imprimante ne communique pas ses niveaux d'encre.")
                i.inks.forEach { ink ->
                    val c = ink.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ink.name, Modifier.weight(1f))
                        Text(if (ink.level in 0..100) "${ink.level} %" else "?", fontWeight = FontWeight.SemiBold)
                    }
                    LinearProgressIndicator(
                        progress = { (ink.level.coerceIn(0, 100)) / 100f },
                        color = c,
                        trackColor = c.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                    )
                }
            }
        }
        if (err != null) {
            Section(
                "Niveaux d'encre", Icons.Filled.WaterDrop, sub = "Non communiqués par le réseau",
                trailing = { Pill("Contrôle visuel", MaterialTheme.colorScheme.secondary) },
            ) {
                Hint("La G3000 n'a pas de capteur réseau des réservoirs : regardez les fenêtres à l'avant.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Noir" to Color(0xFF212121), "Cyan" to Color(0xFF00ACC1), "Magenta" to Color(0xFFD81B60), "Jaune" to Color(0xFFFDD835))
                        .forEach { (n, c) ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(28.dp, 44.dp).background(c, MaterialTheme.shapes.small))
                                Text(n, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                }
                Hint("Rechargez avant le repère inférieur : un réservoir trop vide fait entrer de l'air dans les buses.")
            }
            BjnpState(ip)
        }
        OutlinedButton(onClick = { load() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Actualiser") }
    }
}

// ---------- Entretien ----------

fun testBitmap(): Bitmap {
    val w = 1240
    val h = 1754
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(AColor.WHITE)
    val p = Paint()
    val cols = intArrayOf(AColor.CYAN, AColor.MAGENTA, AColor.YELLOW, AColor.BLACK, AColor.RED, AColor.GREEN, AColor.BLUE)
    cols.forEachIndexed { i, col ->
        p.color = col
        val top = 150f + i * 130f
        c.drawRect(100f, top, (w - 100).toFloat(), top + 100f, p)
    }
    p.color = AColor.BLACK
    p.strokeWidth = 2f
    for (i in 0 until 40) {
        val y = 1150f + i * 12f
        c.drawLine(100f, y, (w - 100).toFloat(), y, p)
    }
    p.textSize = 48f
    c.drawText("Page de test - Canon G3000", 100f, 100f, p)
    return bmp
}

@Composable
fun MCard(title: String, text: String, action: String? = null, icon: ImageVector? = null, onClick: () -> Unit = {}) {
    Section(title, icon) {
        Hint(text)
        if (action != null) OutlinedButton(onClick = onClick) { Text(action) }
    }
}

@Composable
fun MaintenanceScreen(m: Modifier, prefs: PrinterPrefs, ip: String, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val log = remember { ToolLog(ctx) }
    var transport by remember { mutableStateOf(log.transport) }
    var busy by remember { mutableStateOf<Tool?>(null) }
    var msg by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Tool?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val fmt = remember { java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE) }

    fun runTool(t: Tool) {
        if (ip.isBlank()) { msg = "Renseignez d'abord l'adresse IP (Réglages)."; return }
        scope.launch {
            busy = t; msg = "${t.label} : envoi…"
            msg = try {
                val via = CanonTools.run(ip, t, transport)
                log.mark(t); prefs.lastPrint = System.currentTimeMillis(); refresh++
                "${t.label} : commande acceptée ($via). Vérifiez que l'imprimante démarre."
            } catch (e: Exception) {
                "${t.label} : échec.\n${e.message}\nLancez-la depuis le menu de l'imprimante."
            }
            busy = null
        }
    }

    confirm?.let { t ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(t.label) },
            text = { Text(t.desc + "\n\nContinuer ?") },
            confirmButton = { TextButton(onClick = { confirm = null; runTool(t) }) { Text("Lancer") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annuler") } },
        )
    }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val idle = remember(refresh) { daysSince(prefs.lastPrint) }
        val late = idle != null && idle >= prefs.clogDays
        Section(
            "Utilisation", Icons.Filled.Schedule,
            sub = when (idle) {
                null -> "Aucune impression enregistrée"
                0L -> "Imprimé aujourd'hui"
                else -> "Dernière impression il y a $idle jour(s)"
            },
            accent = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            trailing = { if (late) Pill("À faire", MaterialTheme.colorScheme.error) else Pill("OK", Color(0xFF43A047)) },
        ) {
            Hint(
                if (prefs.clogReminder) "Rappel après ${prefs.clogDays} jours sans impression (Réglages)."
                else "Rappel anti-bouchage désactivé (Réglages).",
            )
            Button(enabled = busy == null, modifier = Modifier.fillMaxWidth(), onClick = { runTool(Tool.NozzleCheck) }) {
                Text("Imprimer un test des buses")
            }
        }
        Section("Mode d'envoi", Icons.Filled.Settings, sub = "Commandes Canon par le réseau (expérimental)") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Transport.entries.forEach { tr ->
                    FilterChip(
                        selected = transport == tr,
                        onClick = { transport = tr; log.transport = tr },
                        label = { Text(tr.label) },
                    )
                }
            }
            Hint("Si rien ne se passe, changez de mode ou utilisez le menu de l'imprimante.")
        }
        if (busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg)
        Text("Outils", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Tool.entries.forEach { t ->
            val last = remember(refresh) { log.last(t) }
            Section(
                t.label, Icons.Filled.Build,
                sub = if (last == 0L) "Jamais lancé depuis l'appli" else "Dernier : ${fmt.format(java.util.Date(last))}",
                accent = if (t.heavy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trailing = {
                    TextButton(enabled = busy == null, onClick = { if (t.heavy) { confirm = t } else { runTool(t) } }) { Text("Lancer") }
                },
            ) { Hint(t.desc) }
        }
        Text("Autres", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MCard(
            "Page de test (téléphone)",
            "Barres de couleur et traits fins imprimés via Android : utile si le test des buses ne part pas.",
            "Imprimer", Icons.Filled.Print,
        ) { printBitmap(ctx, "test", testBitmap()) }
        MCard("Niveaux d'encre", "À vérifier avant tout nettoyage.", "Voir les niveaux", Icons.Filled.WaterDrop) { go(Screen.Ink) }
        MCard(
            "Interface web de l'imprimante",
            "Page intégrée : état, réseau et, selon le modèle, commandes de maintenance.",
            "Ouvrir", Icons.Filled.Language,
        ) {
            if (ip.isNotBlank()) ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$ip/")))
        }
        MCard("Service d'impression Android", "Active ou règle le module d'impression Canon / Mopria.", "Ouvrir", Icons.Filled.Settings) {
            ctx.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
        }
        Hint(
            "Sans réponse réseau : sur l'imprimante, maintenez Stop jusqu'au nombre de clignotements voulu (1 = test des buses, 2 = nettoyage, 3 = nettoyage en profondeur) puis relâchez. Voir le manuel du modèle.",
        )
    }
}

// ---------- File d'attente ----------

@Composable
fun QueueScreen(m: Modifier, ip: String) {
    val scope = rememberCoroutineScope()
    var done by remember { mutableStateOf(false) }
    var jobs by remember { mutableStateOf<List<Ipp.Job>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var lpd by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            loading = true
            msg = try {
                jobs = Ipp.jobs(ip, done)
                lpd = null
                if (jobs.isEmpty()) "Aucune tâche." else ""
            } catch (e: Exception) {
                jobs = emptyList()
                // Sans IPP : état de la file par LPD (port 515).
                lpd = try {
                    withContext(Dispatchers.IO) { Lpd.status(ip) }.ifBlank { "Aucune tâche en attente." }
                } catch (e2: Exception) {
                    null
                }
                if (lpd != null) "" else "Échec : ${e.message}"
            }
            loading = false
        }
    }
    LaunchedEffect(ip, done) { load() }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (lpd == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !done, onClick = { done = false }, label = { Text("En cours") })
                FilterChip(selected = done, onClick = { done = true }, label = { Text("Terminées") })
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg)
        lpd?.let { st ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("File de l'imprimante (LPD)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(st, style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
            TextButton(onClick = {
                scope.launch {
                    msg = try {
                        val r = withContext(Dispatchers.IO) { Lpd.removeAll(ip) }
                        if (r.isBlank()) "Demande d'annulation envoyée." else r
                    } catch (e: Exception) {
                        "Annulation refusée : ${e.message}"
                    }
                    load()
                }
            }) { Text("Annuler toutes les tâches") }
            Text(
                "IPP absent : liste simplifiée fournie par l'imprimante. L'annulation dépend du firmware.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        jobs.forEach { j ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(j.name, fontWeight = FontWeight.Bold)
                        Text("n° ${j.id} · ${j.state}${if (j.user.isNotBlank()) " · ${j.user}" else ""}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!done) {
                        TextButton(onClick = {
                            scope.launch {
                                msg = try { Ipp.cancel(ip, j.id); "Tâche ${j.id} annulée." } catch (e: Exception) { "Annulation refusée : ${e.message}" }
                                load()
                            }
                        }) { Text("Annuler") }
                    }
                }
            }
        }
        OutlinedButton(onClick = { load() }, enabled = !loading) { Text("Actualiser") }
    }
}

// ---------- Infos imprimante ----------

@Composable
fun InfoScreen(m: Modifier, ip: String) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            loading = true; err = ""
            try {
                rows = Ipp.details(ip)
            } catch (e: Exception) {
                rows = try {
                    val labels = mapOf("MFG" to "Fabricant", "MDL" to "Modèle", "CMD" to "Langages", "DES" to "Description", "CLS" to "Classe", "VER" to "Version")
                    withContext(Dispatchers.IO) { Bjnp.fields(Bjnp.deviceId(ip)) }.map { (k, v) -> (labels[k] ?: k) to v }
                } catch (e2: Exception) {
                    err = "Échec : ${e.message}\nBJNP : ${e2.message}"; emptyList()
                }
                if (rows.isNotEmpty()) err = "IPP absent : infos limitées (protocole Canon BJNP)."
            }
            loading = false
        }
    }
    LaunchedEffect(ip) { load() }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (err.isNotEmpty()) Text(err, color = if (rows.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEach { (k, v) ->
            Column {
                Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(v, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
        }
        if (!loading && rows.isNotEmpty() && rows.none { it.first == "Pages imprimées" || it.first == "Pages (total)" }) {
            Text("Compteur de pages non communiqué par l'imprimante.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { load() }, enabled = !loading) { Text("Actualiser") }
    }
}

// ---------- Réglages ----------

@Composable
fun SettingsScreen(m: Modifier, prefs: PrinterPrefs, ip: String, onSave: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(ip) }
    var name by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Discovery.Found>>(emptyList()) }
    var profiles by remember { mutableStateOf(prefs.profiles) }
    var inkAlert by remember { mutableStateOf(prefs.inkAlert) }
    var threshold by remember { mutableIntStateOf(prefs.inkThreshold) }
    var clog by remember { mutableStateOf(prefs.clogReminder) }
    var clogDays by remember { mutableIntStateOf(prefs.clogDays) }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) msg = "Notifications refusées : les alertes ne s'afficheront pas."
    }
    fun askNotif() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Imprimante", Icons.Filled.Wifi, sub = "Même Wi-Fi que le téléphone") {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Adresse IP (ex. 192.168.1.50)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.weight(1f), onClick = { onSave(text.trim()); msg = "Enregistré" }) { Text("Enregistrer") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !busy && text.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true; msg = "Test en cours (≈10 s)…"
                            msg = try { Diag.run(ctx, text.trim()) } catch (e: Exception) { "Test impossible : ${e.message}" }
                            busy = false
                        }
                    },
                ) { Text("Tester") }
            }
            OutlinedButton(
                enabled = !searching,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        searching = true; msg = "Recherche sur le Wi-Fi…"
                        found = try { Discovery.search(ctx) } catch (e: Exception) { emptyList() }
                        msg = if (found.isEmpty()) "Aucune imprimante trouvée. Saisissez l'adresse à la main." else ""
                        searching = false
                    }
                },
            ) { Text(if (searching) "Recherche…" else "Rechercher l'imprimante") }
            if (busy || searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            found.forEach { f ->
                Card(
                    onClick = {
                        text = f.ip; onSave(f.ip)
                        prefs.addProfile(f.name, f.ip); profiles = prefs.profiles
                        prefs.applyHints(f)
                        msg = "Enregistré : ${f.ip}"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(f.name, fontWeight = FontWeight.Bold)
                        Text(f.ip)
                        Hint(f.summary())
                    }
                }
            }
            if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodyMedium)
        }

        Section("Mes imprimantes", Icons.Filled.Bookmark, sub = "Bascule rapide depuis l'accueil") {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom (ex. Bureau)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                prefs.addProfile(name, text); profiles = prefs.profiles; name = ""
            }) { Text("Ajouter l'adresse ci-dessus") }
            profiles.forEach { p ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold)
                            if (p.ip == ip) Pill("Active", MaterialTheme.colorScheme.primary)
                        }
                        Hint(p.ip)
                    }
                    TextButton(onClick = { text = p.ip; onSave(p.ip) }) { Text("Utiliser") }
                    TextButton(onClick = { prefs.removeProfile(p.ip); profiles = prefs.profiles }) { Text("Suppr.") }
                }
            }
        }

        Section("Alertes", Icons.Filled.Notifications, sub = "Vérification toutes les 12 h environ") {
            Toggle("Rappel anti-bouchage", clog) {
                clog = it; prefs.clogReminder = it
                if (it) askNotif()
            }
            if (clog) {
                Hint("Propose un test des buses après $clogDays jours sans impression.")
                Slider(
                    value = clogDays.toFloat(),
                    onValueChange = { clogDays = it.toInt(); prefs.clogDays = clogDays },
                    valueRange = 3f..21f,
                    steps = 17,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Toggle("Encre faible (IPP)", inkAlert) {
                inkAlert = it; prefs.inkAlert = it
                if (it) askNotif()
            }
            if (inkAlert) {
                Hint("Seuil : $threshold %. Sans effet si l'imprimante ne donne pas ses niveaux.")
                Slider(
                    value = threshold.toFloat(),
                    onValueChange = { threshold = it.toInt(); prefs.inkThreshold = threshold },
                    valueRange = 5f..50f,
                    steps = 8,
                )
            }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { WatchWorker.now(ctx); msg = "Vérification lancée." }) { Text("Vérifier maintenant") }
            Hint("Widget « Canon : encre » disponible sur l'écran d'accueil.")
        }
    }
}

/** État lu par BJNP (identifiant Canon), quand IPP est absent. */
@Composable
fun BjnpState(ip: String) {
    val rows by androidx.compose.runtime.produceState<List<Pair<String, String>>?>(null, ip) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { Bjnp.fields(Bjnp.deviceId(ip)) }.getOrDefault(emptyList())
        }
    }
    val r = rows ?: return
    if (r.isEmpty()) return
    val labels = mapOf("MFG" to "Fabricant", "MDL" to "Modèle", "CMD" to "Langages", "DES" to "Description", "STS" to "État", "VER" to "Version")
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("État BJNP", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            r.forEach { (k, v) ->
                Text(labels[k] ?: k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(v, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
