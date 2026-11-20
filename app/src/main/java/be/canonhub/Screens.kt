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
import androidx.compose.foundation.horizontalScroll
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
        err?.let {
            Text("Niveaux indisponibles : $it", color = MaterialTheme.colorScheme.error)
            Text("La G3000 n'a pas de capteur réseau des réservoirs : contrôlez les niveaux à l'œil.")
        }
        info?.let { i ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(i.model, style = MaterialTheme.typography.titleMedium)
                    Text("État : ${i.state}")
                    if (i.message.isNotBlank()) Text(i.message, style = MaterialTheme.typography.bodySmall)
                    i.reasons.forEach { Text("• ${reasonLabel(it)}") }
                }
            }
            if (i.inks.isEmpty()) Text("Cette imprimante ne communique pas ses niveaux d'encre.")
            i.inks.forEach { ink ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (ink.level in 0..100) "${ink.name} : ${ink.level} %" else "${ink.name} : niveau inconnu")
                    LinearProgressIndicator(
                        progress = { (ink.level.coerceIn(0, 100)) / 100f },
                        color = ink.color?.let { Color(it) } ?: MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        OutlinedButton(onClick = { load() }, enabled = !loading) { Text("Actualiser") }
        Text(
            "Réservoirs : le contrôle visuel fait foi. Alertes automatiques dans Réglages.",
            style = MaterialTheme.typography.bodySmall,
        )
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
fun MCard(title: String, text: String, action: String? = null, onClick: () -> Unit = {}) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text)
            if (action != null) Button(onClick = onClick) { Text(action) }
        }
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
        Text("Outils de l'imprimante", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Commandes Canon envoyées par le réseau (expérimental). Si rien ne se passe, changez de mode d'envoi ou utilisez le menu de l'imprimante.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Transport.entries.forEach { tr ->
                FilterChip(
                    selected = transport == tr,
                    onClick = { transport = tr; log.transport = tr },
                    label = { Text(tr.label) },
                )
            }
        }
        if (busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg)
        Tool.entries.forEach { t ->
            val last = remember(refresh) { log.last(t) }
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(t.desc)
                    Text(
                        if (last == 0L) "Jamais lancé depuis l'appli" else "Dernier : ${fmt.format(java.util.Date(last))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(enabled = busy == null, onClick = { if (t.heavy) { confirm = t } else { runTool(t) } }) { Text("Lancer") }
                }
            }
        }
        Text("Autres", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        MCard(
            "Page de test (téléphone)",
            "Barres de couleur et traits fins imprimés via Android : utile si le test des buses ne part pas.",
            "Imprimer",
        ) { printBitmap(ctx, "test", testBitmap()) }
        MCard("Niveaux d'encre", "À vérifier avant tout nettoyage.", "Voir les niveaux") { go(Screen.Ink) }
        MCard(
            "Réservoirs d'encre",
            "Rechargez avant que l'encre passe sous le repère inférieur : un réservoir trop vide fait entrer de l'air et bouche les buses.",
        )
        MCard(
            "Interface web de l'imprimante",
            "Page intégrée : état, réseau et, selon le modèle, commandes de maintenance.",
            "Ouvrir",
        ) {
            if (ip.isNotBlank()) ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$ip/")))
        }
        MCard("Service d'impression Android", "Active ou règle le module d'impression Canon / Mopria.", "Ouvrir") {
            ctx.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
        }
        Text(
            "Sans réponse réseau : sur l'imprimante, maintenez Stop jusqu'au nombre de clignotements voulu (1 = test des buses, 2 = nettoyage, 3 = nettoyage en profondeur) puis relâchez. Voir le manuel du modèle.",
            style = MaterialTheme.typography.bodySmall,
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

    fun load() {
        scope.launch {
            loading = true
            msg = try {
                jobs = Ipp.jobs(ip, done)
                if (jobs.isEmpty()) "Aucune tâche." else ""
            } catch (e: Exception) {
                jobs = emptyList(); "Échec : ${e.message}"
            }
            loading = false
        }
    }
    LaunchedEffect(ip, done) { load() }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !done, onClick = { done = false }, label = { Text("En cours") })
            FilterChip(selected = done, onClick = { done = true }, label = { Text("Terminées") })
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg)
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
        Text("Imprimante", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Le téléphone doit être sur le même Wi-Fi que l'imprimante.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Adresse IP (ex. 192.168.1.50)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(text.trim()); msg = "Enregistré" }) { Text("Enregistrer") }
            OutlinedButton(
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
                    Text(f.summary(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (msg.isNotEmpty()) Text(msg)

        HorizontalDivider()
        Text("Mes imprimantes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nom (ex. Bureau)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(enabled = text.isNotBlank(), onClick = {
            prefs.addProfile(name, text); profiles = prefs.profiles; name = ""
        }) { Text("Ajouter l'adresse ci-dessus") }
        profiles.forEach { p ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name + if (p.ip == ip) " ✓" else "", fontWeight = FontWeight.Bold)
                        Text(p.ip, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { text = p.ip; onSave(p.ip) }) { Text("Utiliser") }
                    TextButton(onClick = { prefs.removeProfile(p.ip); profiles = prefs.profiles }) { Text("Suppr.") }
                }
            }
        }

        HorizontalDivider()
        Text("Alertes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Toggle("Encre faible", inkAlert) {
            inkAlert = it; prefs.inkAlert = it
            if (it) askNotif()
        }
        if (inkAlert) {
            Text("Seuil : $threshold %")
            Slider(
                value = threshold.toFloat(),
                onValueChange = { threshold = it.toInt(); prefs.inkThreshold = threshold },
                valueRange = 5f..50f,
                steps = 8,
            )
        }
        Toggle("Rappel anti-bouchage", clog) {
            clog = it; prefs.clogReminder = it
            if (it) askNotif()
        }
        if (clog) {
            Text("Après $clogDays jours sans impression")
            Slider(
                value = clogDays.toFloat(),
                onValueChange = { clogDays = it.toInt(); prefs.clogDays = clogDays },
                valueRange = 3f..21f,
                steps = 17,
            )
        }
        Text("Vérification toutes les 12 h environ. Widget « Canon : encre » disponible sur l'écran d'accueil.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { WatchWorker.now(ctx); msg = "Vérification lancée." }) { Text("Vérifier maintenant") }
    }
}
