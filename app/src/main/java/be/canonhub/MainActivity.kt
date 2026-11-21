@file:OptIn(ExperimentalMaterial3Api::class)

package be.canonhub

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.print.PrintManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.print.PrintHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PrinterPrefs(this)
        prefs.loadHints()
        if (savedInstanceState == null) incoming.value = intent
        runCatching { WatchWorker.schedule(this) }
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
                App(prefs, incoming.value) { incoming.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = intent
    }
}

enum class Screen(val title: String) {
    Home("Canon G3000"), Print("Imprimer"), Scan("Numériser"), Copy("Copier"),
    Ink("Encre et état"), Maintenance("Entretien"), Queue("File d'attente"),
    Info("Infos imprimante"), Settings("Réglages"), History("Documents")
}

fun sharedUri(i: Intent): Uri? = when (i.action) {
    Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
    Intent.ACTION_VIEW -> i.data
    else -> null
}

@Composable
fun App(prefs: PrinterPrefs, incoming: Intent?, consumed: () -> Unit) {
    var screen by remember { mutableStateOf(Screen.Home) }
    var ip by remember { mutableStateOf(prefs.ip) }
    var shared by remember { mutableStateOf<Uri?>(null) }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    LaunchedEffect(incoming) {
        val i = incoming ?: return@LaunchedEffect
        sharedUri(i)?.let { shared = it; screen = Screen.Print }
        i.getStringExtra("screen")?.let { s -> runCatching { Screen.valueOf(s) }.getOrNull()?.let { screen = it } }
        consumed()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(screen.title) },
            navigationIcon = {
                if (screen != Screen.Home) {
                    IconButton(onClick = { screen = Screen.Home }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                }
            },
        )
    }, bottomBar = {
        androidx.compose.material3.NavigationBar {
            listOf(
                Triple(Screen.Home, "Accueil", Icons.Filled.Home),
                Triple(Screen.Scan, "Numériser", Icons.Filled.DocumentScanner),
                Triple(Screen.Print, "Imprimer", Icons.Filled.Print),
                Triple(Screen.History, "Documents", Icons.Filled.Folder),
            ).forEach { (sc, label, icon) ->
                NavigationBarItem(
                    selected = screen == sc,
                    onClick = { screen = sc },
                    icon = { Icon(icon, null) },
                    label = { Text(label) },
                )
            }
        }
    }) { pad ->
        val m = Modifier.padding(pad)
        val go: (Screen) -> Unit = { screen = it }
        val needIp = @Composable { NeedIp(m) { screen = Screen.Settings } }
        val setIp: (String) -> Unit = { prefs.ip = it; ip = it.trim() }
        when (screen) {
            Screen.Home -> HomeScreen(m, prefs, ip, setIp, go)
            Screen.Print -> PrintScreen(m, prefs, ip, shared) { shared = null }
            Screen.Scan -> if (ip.isBlank()) needIp() else ScanScreen(m, ip)
            Screen.Copy -> if (ip.isBlank()) needIp() else CopyScreen(m, prefs, ip)
            Screen.Ink -> if (ip.isBlank()) needIp() else InkScreen(m, prefs, ip)
            Screen.Maintenance -> MaintenanceScreen(m, prefs, ip, go)
            Screen.Queue -> if (ip.isBlank()) needIp() else QueueScreen(m, ip)
            Screen.Info -> if (ip.isBlank()) needIp() else InfoScreen(m, ip)
            Screen.Settings -> SettingsScreen(m, prefs, ip, setIp)
            Screen.History -> HistoryScreen(m)
        }
    }
}

// ---------- Accueil en tuiles ----------

data class Tile(
    val label: String, val sub: String, val icon: ImageVector, val color: Color, val target: Screen,
    val needsIpp: Boolean = false,
)

/** État de l'imprimante affiché en tête de l'accueil. */
data class HomeStatus(val online: Boolean, val model: String, val ipp: Boolean)

suspend fun homeStatus(ip: String): HomeStatus = withContext(Dispatchers.IO) {
    val online = portOpen(ip, 515, 1500) || portOpen(ip, 80, 1500)
    val model = if (!online) "" else runCatching {
        Bjnp.fields(Bjnp.deviceId(ip)).firstOrNull { it.first == "MDL" || it.first == "MODEL" }?.second
    }.getOrNull().orEmpty()
    HomeStatus(online, model, online && portOpen(ip, 631, 1000))
}

@Composable
fun HomeScreen(m: Modifier, prefs: PrinterPrefs, ip: String, setIp: (String) -> Unit, go: (Screen) -> Unit) {
    val tiles = listOf(
        Tile("Imprimer", "PDF, photos, recto-verso", Icons.Filled.Print, Color(0xFF1E88E5), Screen.Print),
        Tile("Numériser", "Multipage, PDF", Icons.Filled.DocumentScanner, Color(0xFF43A047), Screen.Scan),
        Tile("Copier", "Copies, carte ID", Icons.Filled.ContentCopy, Color(0xFFF4511E), Screen.Copy),
        Tile("Entretien", "Nettoyage, buses", Icons.Filled.Build, Color(0xFF00897B), Screen.Maintenance),
        Tile("Encre et état", "Niveaux, alertes", Icons.Filled.WaterDrop, Color(0xFF8E24AA), Screen.Ink, needsIpp = true),
        Tile("File d'attente", "Tâches, annulation", Icons.Filled.HourglassEmpty, Color(0xFF6D4C41), Screen.Queue, needsIpp = true),
        Tile("Infos", "Firmware, compteurs", Icons.Filled.Info, Color(0xFF3949AB), Screen.Info, needsIpp = true),
        Tile("Documents", "Scans enregistrés", Icons.Filled.Folder, Color(0xFFC62828), Screen.History),
        Tile("Réglages", "Imprimantes, alertes", Icons.Filled.Settings, Color(0xFF546E7A), Screen.Settings),
    )
    val profiles = remember { prefs.profiles }
    var refresh by remember { mutableIntStateOf(0) }
    val status by androidx.compose.runtime.produceState<HomeStatus?>(null, ip, refresh) {
        value = null
        value = if (ip.isBlank()) null else homeStatus(ip)
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = m.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            StatusCard(ip, status, profiles, setIp, onRefresh = { refresh++ }, onSettings = { go(Screen.Settings) })
        }
        items(tiles) { t ->
            val off = t.needsIpp && status?.let { !it.ipp } == true
            TileCard(t, off) { go(t.target) }
        }
    }
}

@Composable
fun StatusCard(
    ip: String, status: HomeStatus?, profiles: List<Profile>, setIp: (String) -> Unit,
    onRefresh: () -> Unit, onSettings: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ip.isBlank()) {
                Text("Aucune imprimante configurée", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Button(onClick = onSettings) { Text("Configurer") }
                return@Column
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                val dot = when {
                    status == null -> MaterialTheme.colorScheme.outline
                    status.online -> Color(0xFF43A047)
                    else -> MaterialTheme.colorScheme.error
                }
                androidx.compose.foundation.layout.Box(
                    Modifier.size(12.dp).background(dot, androidx.compose.foundation.shape.CircleShape)
                )
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        status?.model?.takeIf { it.isNotBlank() } ?: "Imprimante",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "$ip · " + when {
                            status == null -> "vérification…"
                            status.online -> "en ligne"
                            else -> "hors ligne"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "Actualiser") }
            }
            if (status?.online == false) FindPrinter(ip, setIp)
            if (profiles.size >= 2) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    profiles.forEach { p ->
                        FilterChip(selected = p.ip == ip, onClick = { setIp(p.ip) }, label = { Text(p.name) })
                    }
                }
            }
        }
    }
}

/** Imprimante hors ligne : recherche mDNS d'une Canon à une autre adresse (IP changée par la box). */
@Composable
fun FindPrinter(ip: String, setIp: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedButton(enabled = !busy, onClick = {
            scope.launch {
                busy = true; msg = "Recherche sur le Wi-Fi…"
                val found = runCatching { Discovery.search(ctx, 6) }.getOrDefault(emptyList())
                    .filter { it.ip != ip && (it.name.contains("Canon", true) || it.services.any { s -> s.txt["mfg"].orEmpty().contains("Canon", true) }) }
                msg = when (found.size) {
                    0 -> "Aucune Canon trouvée. Imprimante allumée et sur le même Wi-Fi ?"
                    else -> { setIp(found[0].ip); "Nouvelle adresse : ${found[0].ip}" }
                }
                busy = false
            }
        }) {
            Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
            Text(" Retrouver l'imprimante")
        }
    }
    if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun TileCard(t: Tile, off: Boolean, onClick: () -> Unit) {
    val bg = if (off) MaterialTheme.colorScheme.surfaceVariant else t.color
    val fg = if (off) MaterialTheme.colorScheme.onSurfaceVariant else Color.White
    Card(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        modifier = Modifier.fillMaxWidth().aspectRatio(1.1f),
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(48.dp).background(fg.copy(alpha = 0.18f), androidx.compose.foundation.shape.CircleShape),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) { Icon(t.icon, null, tint = fg, modifier = Modifier.size(28.dp)) }
            Column {
                Text(t.label, color = fg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (off) "Non pris en charge (IPP absent)" else t.sub,
                    color = fg.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun NeedIp(m: Modifier, onSettings: () -> Unit) {
    Column(m.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Renseignez d'abord l'adresse IP de l'imprimante.")
        Button(onClick = onSettings) { Text("Ouvrir les réglages") }
    }
}

// ---------- Outils communs ----------

fun markPrint(ctx: Context) { PrinterPrefs(ctx).lastPrint = System.currentTimeMillis() }

/** Impression par la fenêtre Android (service Canon / Mopria). */
fun androidPrint(ctx: Context, uri: Uri, mime: String) {
    val name = "Canon-${System.currentTimeMillis() / 1000}"
    if (mime == "application/pdf") {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(name, PdfPrintAdapter(ctx.applicationContext, uri, name), null)
    } else {
        PrintHelper(ctx).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }.printBitmap(name, uri)
    }
    markPrint(ctx)
}

fun printBitmap(ctx: Context, name: String, bmp: Bitmap) {
    PrintHelper(ctx).apply {
        scaleMode = PrintHelper.SCALE_MODE_FIT
        colorMode = PrintHelper.COLOR_MODE_COLOR
    }.printBitmap(name, bmp)
    markPrint(ctx)
}

/** Impression directe IPP ; convertit les images non JPEG. */
suspend fun directPrint(ctx: Context, ip: String, uri: Uri, mime: String, copies: Int, color: Boolean): String {
    val (data, fmt) = withContext(Dispatchers.IO) {
        val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("fichier illisible")
        if (mime == "application/pdf" || mime == "image/jpeg") raw to mime
        else {
            val b = Images.decode(raw, 3508) ?: throw IOException("image illisible")
            Images.jpeg(b) to "image/jpeg"
        }
    }
    val id = Ipp.printDoc(ip, data, fmt, "Canon", copies, color)
    markPrint(ctx)
    return "Envoyé directement (tâche $id)."
}

fun share(ctx: Context, uri: Uri, mime: String) {
    ctx.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Partager",
        ),
    )
}

@Composable
fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(enabled = value > range.first, onClick = { onChange(value - 1) }) { Text("−") }
        Text("$value", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(enabled = value < range.last, onClick = { onChange(value + 1) }) { Text("+") }
    }
}

@Composable
fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun ColorChips(color: Boolean, onColor: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = color, onClick = { onColor(true) }, label = { Text("Couleur") })
        FilterChip(selected = !color, onClick = { onColor(false) }, label = { Text("Noir et blanc") })
    }
}

// ---------- Impression ----------

@Composable
fun PrintScreen(m: Modifier, prefs: PrinterPrefs, ip: String, shared: Uri?, clearShared: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var direct by remember { mutableStateOf(prefs.directPrint) }
    var copies by remember { mutableIntStateOf(1) }
    var color by remember { mutableStateOf(true) }
    var duplex by remember { mutableStateOf(false) }
    var reverse by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var verso by remember { mutableStateOf<File?>(null) }

    suspend fun output(uri: Uri, mime: String): String =
        if (direct && ip.isNotBlank()) {
            try {
                directPrint(ctx, ip, uri, mime, copies, color)
            } catch (e: Exception) {
                androidPrint(ctx, uri, mime)
                "Impression directe refusée (${e.message}) : fenêtre Android ouverte."
            }
        } else {
            androidPrint(ctx, uri, mime)
            "Fenêtre d'impression ouverte."
        }

    fun go(uri: Uri) {
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        scope.launch {
            busy = true
            msg = try {
                if (duplex && mime == "application/pdf") {
                    val (r, v) = withContext(Dispatchers.IO) {
                        Images.splitPdf(ctx, uri, true, false) to Images.splitPdf(ctx, uri, false, reverse)
                    }
                    if (r == null) throw IOException("PDF illisible")
                    val s = output(Uri.fromFile(r), "application/pdf")
                    verso = v
                    if (v == null) "$s Une seule page : pas de verso."
                    else "$s Quand le recto est imprimé : retournez la pile, remettez-la dans le bac, puis « Imprimer le verso »."
                } else {
                    output(uri, mime)
                }
            } catch (e: Exception) {
                "Échec : ${e.message}"
            }
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) go(uri)
    }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        shared?.let { u ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fichier reçu", fontWeight = FontWeight.Bold)
                    Text(u.lastPathSegment ?: u.toString(), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = { go(u) }) { Text("Imprimer") }
                        OutlinedButton(onClick = clearShared) { Text("Ignorer") }
                    }
                }
            }
        }
        Toggle("Impression directe (IPP, sans fenêtre Android)", direct) { direct = it; prefs.directPrint = it }
        if (direct) {
            Stepper("Copies", copies, 1..99) { copies = it }
            ColorChips(color) { color = it }
            Text(
                "Si l'imprimante refuse le format, la fenêtre Android s'ouvre automatiquement.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = duplex, onCheckedChange = { duplex = it })
            Text("Recto-verso manuel (PDF)")
        }
        if (duplex) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = reverse, onCheckedChange = { reverse = it })
                Text("Verso en ordre inverse")
            }
            Text(
                "Imprime les pages impaires, puis les paires après retournement. Faites un essai sur 2 feuilles pour trouver le bon sens et l'ordre.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(enabled = !busy, onClick = { picker.launch(arrayOf("application/pdf", "image/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Choisir un fichier")
        }
        verso?.let { v ->
            Button(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true
                        msg = try { output(Uri.fromFile(v), "application/pdf") } catch (e: Exception) { "Échec : ${e.message}" }
                        verso = null
                        busy = false
                    }
                },
            ) { Text("Imprimer le verso") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg)
        OutlinedButton(
            onClick = { ctx.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Service d'impression Android") }
        Text(
            "Astuce : depuis Galerie ou Fichiers, « Partager » puis « Imprimer avec la Canon ».",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ---------- Numérisation ----------

@Composable
fun ScanOptions(
    area: Area, onArea: (Area) -> Unit, dpi: Int, onDpi: (Int) -> Unit, color: Boolean, onColor: (Boolean) -> Unit,
    cardLabel: String = "Carte",
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Area.entries.forEach { a ->
                FilterChip(selected = area == a, onClick = { onArea(a) }, label = { Text(if (a == Area.Card) cardLabel else a.label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(75, 150, 300, 600).forEach { d ->
                FilterChip(selected = dpi == d, onClick = { onDpi(d) }, label = { Text("$d dpi") })
            }
        }
        ColorChips(color, onColor)
        if (area != Area.A4) Text("Placez le document contre le coin repère de la vitre.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ScanScreen(m: Modifier, ip: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var area by remember { mutableStateOf(Area.A4) }
    var dpi by remember { mutableIntStateOf(300) }
    var color by remember { mutableStateOf(true) }
    var crop by remember { mutableStateOf(false) }
    var bright by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var pages by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var ocrPdf by remember { mutableStateOf(false) }
    var ocrText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pages.lastOrNull(), crop, bright, contrast) {
        val last = pages.lastOrNull()
        preview = if (last == null) null else withContext(Dispatchers.Default) { Images.process(last, 1200, crop, bright, contrast) }
    }

    fun full(b: ByteArray) = Images.process(b, 3508, crop, bright, contrast)

    fun work(label: String, block: suspend () -> String) {
        job = scope.launch {
            busy = true; msg = label; progress = 0f
            msg = try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                "Annulé."
            } catch (e: Exception) {
                "Échec : ${e.message}"
            }
            busy = false; job = null
        }
    }

    suspend fun pdfUri(): Uri = withContext(Dispatchers.IO) {
        val u = if (ocrPdf) {
            val done = pages.map { p ->
                val b = Images.process(p, 2480, crop, bright, contrast) ?: throw IOException("image illisible")
                b to runCatching { Ocr.read(b) }.getOrNull()
            }
            Images.savePdf(ctx) { out -> Ocr.searchablePdf(done, out) }
        } else {
            Images.savePdf(ctx) { out -> Images.pdfFromScans(pages, out) { full(it) } }
        }
        u ?: throw IOException("enregistrement impossible")
    }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScanOptions(area, { area = it }, dpi, { dpi = it }, color, { color = it })
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                work("Numérisation en cours…") {
                    val b = Escl.scan(ip, dpi, color, area) { progress = it }
                    pages = pages + listOf(b)
                    "${pages.size} page(s) numérisée(s)."
                }
            },
        ) { Text(if (pages.isEmpty()) "Numériser" else "Ajouter une page (${pages.size + 1})") }
        if (busy) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (progress > 0f) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f))
                    Text(" ${(progress * 100).toInt()} %")
                } else {
                    LinearProgressIndicator(Modifier.weight(1f))
                }
                androidx.compose.material3.TextButton(onClick = { job?.cancel() }) { Text("Annuler") }
            }
        }
        if (msg.isNotEmpty()) Text(msg)
        if (pages.isEmpty() && !busy) {
            Text(
                "Posez le document face contre la vitre, coin repère en haut à gauche. 75 dpi = aperçu rapide.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (pages.isNotEmpty()) {
            Toggle("Recadrage automatique", crop) { crop = it }
            Text("Luminosité ${bright.toInt()}")
            Slider(value = bright, onValueChange = { bright = it }, valueRange = -80f..80f)
            Text("Contraste ${"%.1f".format(contrast)}")
            Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.5f..2f)
            preview?.let { Image(it.asImageBitmap(), "Aperçu", Modifier.fillMaxWidth()) }
            Text("${pages.size} page(s). Les réglages s'appliquent à toutes les pages.", style = MaterialTheme.typography.bodySmall)
            Toggle("PDF avec texte cherchable (OCR)", ocrPdf) { ocrPdf = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    work(if (ocrPdf) "Création du PDF (OCR)…" else "Création du PDF…") { pdfUri(); "PDF enregistré (Historique)." }
                }) { Text("PDF") }
                Button(enabled = !busy, onClick = {
                    work("Enregistrement…") {
                        withContext(Dispatchers.IO) {
                            pages.forEach { p -> full(p)?.let { Images.saveJpeg(ctx, Images.jpeg(it)) } }
                        }
                        "JPEG enregistré(s) (Historique)."
                    }
                }) { Text("JPEG") }
                OutlinedButton(enabled = !busy, onClick = {
                    work("Préparation…") {
                        if (pages.size == 1) {
                            val b = withContext(Dispatchers.Default) { full(pages[0]) } ?: throw IOException("image illisible")
                            printBitmap(ctx, "scan", b)
                        } else {
                            val f = File(ctx.cacheDir, "scan.pdf")
                            withContext(Dispatchers.IO) { f.outputStream().use { o -> Images.pdfFromScans(pages, o) { full(it) } } }
                            androidPrint(ctx, Uri.fromFile(f), "application/pdf")
                        }
                        "Fenêtre d'impression ouverte."
                    }
                }) { Text("Imprimer") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = {
                    work("Préparation…") {
                        if (pages.size == 1) {
                            val u = withContext(Dispatchers.IO) { full(pages[0])?.let { Images.saveJpeg(ctx, Images.jpeg(it)) } }
                                ?: throw IOException("enregistrement impossible")
                            share(ctx, u, "image/jpeg")
                        } else {
                            share(ctx, pdfUri(), "application/pdf")
                        }
                        ""
                    }
                }) { Text("Partager") }
                OutlinedButton(enabled = !busy, onClick = { pages = pages.dropLast(1); msg = "" }) { Text("Suppr. dernière") }
                OutlinedButton(enabled = !busy, onClick = { pages = emptyList(); msg = "" }) { Text("Effacer") }
            }
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                work("Lecture du texte…") {
                    val txt = withContext(Dispatchers.Default) {
                        pages.mapNotNull { p -> Images.process(p, 2480, crop, bright, contrast) }
                    }.map { Ocr.read(it).text }.joinToString("\n\n")
                    ocrText = txt
                    if (txt.isBlank()) "Aucun texte détecté." else ""
                }
            }) { Text("Extraire le texte (OCR)") }
        }
        ocrText?.let { t ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { ocrText = null },
                title = { Text("Texte reconnu") },
                text = {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(t, modifier = Modifier.verticalScroll(rememberScrollState()))
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("OCR", t))
                        ocrText = null
                    }) { Text("Copier") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        ctx.startActivity(
                            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Partager"),
                        )
                    }) { Text("Partager") }
                },
            )
        }
    }
}

// ---------- Copie ----------

@Composable
fun CopyScreen(m: Modifier, prefs: PrinterPrefs, ip: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var area by remember { mutableStateOf(Area.A4) }
    var dpi by remember { mutableIntStateOf(300) }
    var color by remember { mutableStateOf(true) }
    var copies by remember { mutableIntStateOf(1) }
    var direct by remember { mutableStateOf(prefs.directPrint) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var front by remember { mutableStateOf<Bitmap?>(null) }

    suspend fun output(bmp: Bitmap): String =
        if (direct) {
            try {
                val data = withContext(Dispatchers.Default) { Images.jpeg(bmp) }
                val id = Ipp.printDoc(ip, data, "image/jpeg", "Copie", copies, color)
                markPrint(ctx)
                "Copie envoyée (tâche $id)."
            } catch (e: Exception) {
                printBitmap(ctx, "copie", bmp)
                "Impression directe refusée (${e.message}) : fenêtre Android ouverte."
            }
        } else {
            printBitmap(ctx, "copie", bmp)
            "Fenêtre d'impression ouverte : réglez le nombre de copies."
        }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScanOptions(area, { area = it; front = null }, dpi, { dpi = it }, color, { color = it }, cardLabel = "Carte ID R/V")
        Toggle("Impression directe (IPP)", direct) { direct = it; prefs.directPrint = it }
        if (direct) Stepper("Copies", copies, 1..99) { copies = it }
        if (area == Area.Card) Text("Recto et verso de la carte sur une seule feuille A4, taille réelle.")
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    busy = true; msg = "Numérisation en cours…"
                    msg = try {
                        val b = Escl.scan(ip, dpi, color, area)
                        val bmp = withContext(Dispatchers.Default) { Images.decode(b, 3508) } ?: throw IOException("image illisible")
                        val f = front
                        if (area != Area.Card) {
                            output(bmp)
                        } else if (f == null) {
                            front = bmp
                            "Recto lu. Retournez la carte au même endroit, puis appuyez à nouveau."
                        } else {
                            val sheet = withContext(Dispatchers.Default) { Images.cardSheet(f, bmp) }
                            front = null
                            output(sheet)
                        }
                    } catch (e: Exception) {
                        "Échec : ${e.message}"
                    }
                    busy = false
                }
            },
        ) {
            Text(
                when {
                    busy -> "Patientez…"
                    area != Area.Card -> "Copier"
                    front == null -> "Lire le recto"
                    else -> "Lire le verso et copier"
                },
            )
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg)
    }
}
