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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
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
        Calib.load(this)
        if (savedInstanceState == null) incoming.value = intent
        runCatching { WatchWorker.schedule(this) }
        setContent {
            AppTheme {
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
    Info("Infos imprimante"), Settings("Réglages"), History("Documents"), Calib("Étalonnage")
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
    BackHandler(enabled = screen != Screen.Home) { screen = if (screen == Screen.Calib) Screen.Settings else Screen.Home }

    // Rappel d'entretien actif par défaut : demande l'autorisation des notifications une fois.
    val ctx0 = LocalContext.current
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotif && (prefs.clogReminder || prefs.inkAlert) &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx0, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            prefs.askedNotif = true
            notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(incoming) {
        val i = incoming ?: return@LaunchedEffect
        sharedUri(i)?.let { shared = it; screen = Screen.Print }
        i.getStringExtra("screen")?.let { s -> runCatching { Screen.valueOf(s) }.getOrNull()?.let { screen = it } }
        consumed()
    }

    val snack = remember { androidx.compose.material3.SnackbarHostState() }
    androidx.compose.runtime.CompositionLocalProvider(LocalSnack provides snack) {
    Scaffold(snackbarHost = { androidx.compose.material3.SnackbarHost(snack) }, topBar = {
        TopAppBar(
            title = { Text(screen.title) },
            navigationIcon = {
                if (screen != Screen.Home) {
                    IconButton(onClick = { screen = if (screen == Screen.Calib) Screen.Settings else Screen.Home }) {
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
            Screen.Settings -> SettingsScreen(m, prefs, ip, setIp) { go(Screen.Calib) }
            Screen.Calib -> CalibScreen(m, ip)
            Screen.History -> HistoryScreen(m)
        }
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
        Tile(
            "Entretien",
            if (prefs.clogReminder && (daysSince(prefs.lastPrint) ?: 0L) >= prefs.clogDays) "Test des buses conseillé" else "Nettoyage, buses",
            Icons.Filled.Build, Color(0xFF00897B), Screen.Maintenance,
        ),
        Tile("Encre et état", "Niveaux, alertes", Icons.Filled.WaterDrop, Color(0xFF8E24AA), Screen.Ink, needsIpp = true),
        Tile("File d'attente", "Tâches, annulation", Icons.Filled.HourglassEmpty, Color(0xFF6D4C41), Screen.Queue),
        Tile("Infos", "Modèle, identifiant", Icons.Filled.Info, Color(0xFF3949AB), Screen.Info),
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
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ip.isBlank()) {
                FirstRun(setIp, onSettings)
                return@Column
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(56.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.medium),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) { Icon(Icons.Filled.Print, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp)) }
                androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
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

/** Premier démarrage : recherche automatique de l'imprimante sur le Wi-Fi, sinon saisie manuelle. */
@Composable
fun FirstRun(setIp: (String) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Discovery.Found>?>(null) }
    suspend fun search() {
        busy = true
        found = runCatching { Discovery.search(ctx, 6) }.getOrDefault(emptyList())
            .sortedByDescending { it.name.contains("Canon", true) }
        busy = false
    }
    LaunchedEffect(Unit) { search() }
    Text("Bienvenue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Hint("1. Allumez l'imprimante.  2. Téléphone sur le même Wi-Fi.  3. Choisissez-la ci-dessous.")
    if (busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Hint("Recherche sur le Wi-Fi…")
    }
    found?.forEach { f ->
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
            val p = PrinterPrefs(ctx)
            p.addProfile(f.name, f.ip)
            p.applyHints(f)
            setIp(f.ip)
        }) { Text("${f.name} · ${f.ip}") }
    }
    if (found?.isEmpty() == true && !busy) Hint("Aucune imprimante trouvée.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = { scope.launch { search() } }) { Text("Relancer") }
        Button(onClick = onSettings) { Text("Saisir l'adresse") }
    }
}

/** Imprimante hors ligne : recherche mDNS d'une Canon à une autre adresse (IP changée par la box). */
@Composable
fun FindPrinter(ip: String, setIp: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    suspend fun search() {
        busy = true; msg = "Recherche sur le Wi-Fi…"
        val found = runCatching { Discovery.search(ctx, 6) }.getOrDefault(emptyList())
            .filter { it.ip != ip && (it.name.contains("Canon", true) || it.services.any { s -> s.txt["mfg"].orEmpty().contains("Canon", true) }) }
        msg = if (found.isEmpty()) "Aucune Canon trouvée. Imprimante allumée et sur le même Wi-Fi ?" else ""
        if (found.isNotEmpty()) {
            android.widget.Toast.makeText(ctx, "Imprimante retrouvée : ${found[0].ip}", android.widget.Toast.LENGTH_LONG).show()
            setIp(found[0].ip)
        }
        busy = false
    }
    // Recherche automatique dès que l'imprimante est vue hors ligne.
    LaunchedEffect(ip) { search() }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedButton(enabled = !busy, onClick = { scope.launch { search() } }) {
            Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
            Text(" Retrouver l'imprimante")
        }
    }
    if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun TileCard(t: Tile, off: Boolean, onClick: () -> Unit) {
    val accent = if (off) MaterialTheme.colorScheme.outline else t.color
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().aspectRatio(1.15f),
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(52.dp).background(accent.copy(alpha = 0.14f), MaterialTheme.shapes.medium),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) { Icon(t.icon, null, tint = accent, modifier = Modifier.size(30.dp)) }
            Column {
                Text(
                    t.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = if (off) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (off) "Non pris en charge" else t.sub,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
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

fun markPrint(ctx: Context) {
    val p = PrinterPrefs(ctx)
    p.lastPrint = System.currentTimeMillis()
    p.bump("prints")
}

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
    }.printBitmap(name, Calib.fixA4(bmp))
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

    // Planche photos : format de case en cm (largeur × hauteur, portrait).
    val sheetSizes = listOf("Identité 3,5×4,5" to (3.5f to 4.5f), "5×5" to (5f to 5f), "6×9" to (6f to 9f), "9×13" to (8.9f to 12.7f), "10×15" to (10.2f to 15.2f))
    var sheet by remember { mutableIntStateOf(0) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            msg = try {
                val (w, h) = sheetSizes[sheet].second
                val bmp = withContext(Dispatchers.IO) {
                    val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("fichier illisible")
                    val b = Images.decode(raw, 3000) ?: throw IOException("image illisible")
                    Images.photoSheet(b, w, h)
                }
                printBitmap(ctx, "planche", bmp)
                "Planche prête. Au premier essai, mesurez une photo à la règle."
            } catch (e: Exception) {
                "Échec : ${e.message}"
            }
            busy = false
        }
    }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        shared?.let { u ->
            Section("Fichier reçu", Icons.Filled.Folder, sub = u.lastPathSegment ?: u.toString()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, modifier = Modifier.weight(1f), onClick = { go(u) }) { Text("Imprimer") }
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = clearShared) { Text("Ignorer") }
                }
            }
        }
        Section("Document", Icons.Filled.Print, sub = "PDF ou image depuis le téléphone") {
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
            if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodyMedium)
            Hint("Astuce : depuis Galerie ou Fichiers, « Partager » puis « Imprimer avec la Canon ».")
        }
        Section("Planche photos", Icons.Filled.Face, sub = "Une photo répétée sur une feuille A4") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                sheetSizes.forEachIndexed { i, (label, _) ->
                    FilterChip(selected = sheet == i, onClick = { sheet = i }, label = { Text(label) })
                }
            }
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { photoPicker.launch(arrayOf("image/*")) }) {
                Text("Choisir la photo")
            }
            Hint("Photo recadrée au centre, traits de coupe gris. Papier photo A4 conseillé.")
        }
        Section("Mode d'envoi", Icons.Filled.Settings) {
            Toggle("Impression directe (IPP, sans fenêtre Android)", direct) { direct = it; prefs.directPrint = it }
            if (direct) {
                Stepper("Copies", copies, 1..99) { copies = it }
                ColorChips(color) { color = it }
                Hint("Si l'imprimante refuse le format, la fenêtre Android s'ouvre automatiquement.")
            } else {
                Hint("La fenêtre Android règle copies, couleur et format.")
            }
        }
        Section("Recto-verso manuel", Icons.Filled.ContentCopy, sub = "PDF : impaires puis paires") {
            Toggle("Activer", duplex) { duplex = it }
            if (duplex) {
                Toggle("Verso en ordre inverse", reverse) { reverse = it }
                Hint("Faites un essai sur 2 feuilles pour trouver le bon sens et l'ordre.")
            }
        }
        OutlinedButton(
            onClick = { ctx.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Service d'impression Android") }
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
fun FilterChips(value: Filter, onChange: (Filter) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Filter.entries.forEach { f ->
            FilterChip(selected = value == f, onClick = { onChange(f) }, label = { Text(f.label) })
        }
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
    var filt by remember { mutableStateOf(Filter.None) }
    var bright by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var pages by remember { mutableStateOf<List<Page>>(emptyList()) }
    var sel by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var ocrPdf by remember { mutableStateOf(false) }
    var ocrText by remember { mutableStateOf<String?>(null) }
    var out by remember { mutableStateOf(OutFmt()) }
    var detected by remember { mutableStateOf<String?>(null) }
    var askInterleave by remember { mutableStateOf(false) }
    val prefs0 = remember { PrinterPrefs(ctx) }
    var plan by remember { mutableStateOf<SavePlan?>(null) }
    val target = out.target()
    val cur = pages.getOrNull(sel.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))

    LaunchedEffect(cur, crop, filt, bright, contrast) {
        val p = cur
        withContext(Dispatchers.Default) {
            val dec = p?.let { Images.decode(it.bytes, 1200) }
            val pv = p?.let { Images.process(it.bytes, 1200, crop, bright, contrast, filt, it.rot) }
            preview = pv
            detected = if (p == null || dec == null || pv == null) null else {
                val (w, h) = Formats.physical(p.bytes, p.dpi, pv, dec.width)
                Formats.detect(w, h)
            }
        }
    }

    fun proc(p: Page, max: Int) = Images.process(p.bytes, max, crop, bright, contrast, filt, p.rot)
    fun full(p: Page) = proc(p, 3508)

    /** Page prête à imprimer : A4 avec l'image à la taille choisie. */
    fun printable(b: Bitmap): Bitmap = target?.let { Formats.onA4(b, it) } ?: b

    fun move(d: Int) {
        val i = sel; val j = i + d
        if (i !in pages.indices || j !in pages.indices) return
        val l = pages.toMutableList(); val t = l[i]; l[i] = l[j]; l[j] = t
        pages = l; sel = j
    }

    val snack = LocalSnack.current
    fun work(label: String, block: suspend () -> String) {
        job = scope.launch {
            busy = true; msg = label; progress = 0f
            val r = try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                "Annulé."
            } catch (e: Exception) {
                "Échec : ${e.message}"
            }
            busy = false; job = null; msg = ""
            if (r.isNotEmpty()) scope.launch { snack.showSnackbar(r) }
        }
    }

    suspend fun pdfUri(folder: String? = null, base: String? = null): Uri = withContext(Dispatchers.IO) {
        val u = if (ocrPdf) {
            val done = pages.map { p ->
                val b = proc(p, 2480) ?: throw IOException("image illisible")
                b to runCatching { Ocr.read(b) }.getOrNull()
            }
            Images.savePdf(ctx, folder, base) { o -> Ocr.searchablePdf(done, target, o) }
                ?.also { OcrIndex.put(ctx, it, done.mapNotNull { d -> d.second?.text }.joinToString("\n")) }
        } else if (target != null) {
            val bm = pages.mapNotNull { full(it) }
            Images.savePdf(ctx, folder, base) { o -> Formats.pdf(bm, target, o) }
        } else {
            Images.savePdf(ctx, folder, base) { o -> Images.pdfFromScans(pages, o) { full(it) } }
        }
        u ?: throw IOException("enregistrement impossible")
    }

    suspend fun saveJpegs(folder: String? = null, base: String? = null) = withContext(Dispatchers.IO) {
        pages.forEachIndexed { i, p ->
            full(p)?.let { b ->
                val n = base?.let { if (pages.size > 1) "${it}_p${i + 1}" else it }
                Images.saveJpeg(ctx, Images.jpeg(target?.let { t -> Formats.resize(b, t) } ?: b), folder, n)
            }
        }
    }

    /** Classement local : OCR de la 1re page sur le téléphone, puis mots-clés. */
    suspend fun analyze(): Classify.Guess {
        val b = withContext(Dispatchers.Default) { proc(pages[0], 2480) } ?: throw IOException("image illisible")
        val txt = runCatching { Ocr.read(b).text }.getOrDefault("")
        return Classify.guess(txt)
    }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Numérisation", Icons.Filled.DocumentScanner) {
            ScanOptions(area, { area = it }, dpi, { dpi = it }, color, { color = it })
        }
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                work("Numérisation en cours…") {
                    val b = ScanService.keep(ctx) { Escl.scan(ip, dpi, color, area) { progress = it } }
                    pages = pages + Page(b, dpi)
                    sel = pages.size - 1
                    PrinterPrefs(ctx).bump("scans")
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
        if (busy && msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
        if (pages.isEmpty() && !busy) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) { Icon(Icons.Filled.DocumentScanner, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp)) }
                Text("Prêt à numériser", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Document face contre la vitre, coin repère en haut à gauche.\n75 dpi = aperçu rapide.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }

        if (pages.isNotEmpty()) {
            preview?.let {
                Card(
                    shape = MaterialTheme.shapes.large,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) { Image(it.asImageBitmap(), "Aperçu", Modifier.fillMaxWidth()) }
            }
            Section("Pages", sub = "Page ${sel + 1} sur ${pages.size} · touchez une vignette") {
                PageStrip(pages, sel) { sel = it }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(enabled = !busy && sel > 0, onClick = { move(-1) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Déplacer vers la gauche")
                    }
                    IconButton(enabled = !busy && sel < pages.size - 1, onClick = { move(1) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Déplacer vers la droite")
                    }
                    IconButton(enabled = !busy, onClick = {
                        pages = pages.toMutableList().also { l -> l[sel] = l[sel].rotated() }
                    }) { Icon(Icons.Filled.Refresh, "Tourner d'un quart de tour") }
                    IconButton(enabled = !busy, onClick = {
                        pages = pages.toMutableList().also { l -> l.removeAt(sel) }
                        sel = sel.coerceAtMost((pages.size - 1).coerceAtLeast(0))
                    }) { Icon(Icons.Filled.Delete, "Supprimer la page") }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    androidx.compose.material3.TextButton(enabled = !busy, onClick = { pages = emptyList(); sel = 0; msg = "" }) { Text("Tout effacer") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                        work("Recherche des photos…") {
                            val p = pages[sel]
                            val parts = withContext(Dispatchers.Default) {
                                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                android.graphics.BitmapFactory.decodeByteArray(p.bytes, 0, p.bytes.size, o)
                                val b = Images.decode(p.bytes, 4000)?.let { Images.rotate(it, p.rot) } ?: throw IOException("image illisible")
                                val origW = if (p.rot % 180 == 0) o.outWidth else o.outHeight
                                val d = (p.dpi * b.width / origW.coerceAtLeast(1)).coerceAtLeast(1)
                                Images.splitPhotos(b).map { Page(Images.jpeg(it, 92), d) }
                            }
                            if (parts.isEmpty()) "Moins de deux photos détectées : espacez-les d'au moins 1 cm."
                            else {
                                pages = pages.toMutableList().also { l -> l.removeAt(sel); l.addAll(sel, parts) }
                                "${parts.size} photos séparées."
                            }
                        }
                    }) { Text("Séparer les photos") }
                    OutlinedButton(
                        enabled = !busy && pages.size >= 2 && pages.size % 2 == 0,
                        modifier = Modifier.weight(1f),
                        onClick = { askInterleave = true },
                    ) { Text("Intercaler R/V") }
                }
            }
            Section("Retouche", sub = "S'applique à toutes les pages") {
                Toggle("Redresser et recadrer", crop) { crop = it }
                Text("Filtre", style = MaterialTheme.typography.labelLarge)
                FilterChips(filt) { filt = it }
                if (filt == Filter.Doc || filt == Filter.BW) Hint("Fond blanchi, ombres et papier jauni supprimés : économise l'encre.")
                Text("Luminosité ${bright.toInt()}", style = MaterialTheme.typography.labelLarge)
                Slider(value = bright, onValueChange = { bright = it }, valueRange = -80f..80f)
                Text("Contraste ${"%.1f".format(contrast)}", style = MaterialTheme.typography.labelLarge)
                Slider(value = contrast, onValueChange = { contrast = it }, valueRange = 0.5f..2f)
                FormatPicker(out, { out = it }, detected)
            }
            Section("Enregistrer et partager") {
                Toggle("PDF avec texte cherchable (OCR)", ocrPdf) { ocrPdf = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                        if (prefs0.autoClass) work("Analyse du document…") { plan = SavePlan(true, analyze()); "" }
                        else work(if (ocrPdf) "Création du PDF (OCR)…" else "Création du PDF…") { pdfUri(); "PDF enregistré (Documents)." }
                    }) { Text("PDF") }
                    Button(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                        if (prefs0.autoClass) work("Analyse du document…") { plan = SavePlan(false, analyze()); "" }
                        else work("Enregistrement…") { saveJpegs(); "JPEG enregistré(s) (Documents)." }
                    }) { Text("JPEG") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                        work("Préparation…") {
                            if (pages.size == 1) {
                                val b = withContext(Dispatchers.Default) { full(pages[0])?.let { printable(it) } } ?: throw IOException("image illisible")
                                printBitmap(ctx, "scan", b)
                            } else {
                                val f = File(ctx.cacheDir, "scan.pdf")
                                withContext(Dispatchers.IO) {
                                    f.outputStream().use { o -> Images.pdfFromScans(pages, o) { p -> full(p)?.let { b -> printable(b) } } }
                                }
                                androidPrint(ctx, Uri.fromFile(f), "application/pdf")
                            }
                            "Fenêtre d'impression ouverte."
                        }
                    }) { Text("Imprimer") }
                    OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                        work("Préparation…") {
                            if (pages.size == 1) {
                                val u = withContext(Dispatchers.IO) { full(pages[0])?.let { b -> Images.saveJpeg(ctx, Images.jpeg(target?.let { t -> Formats.resize(b, t) } ?: b)) } }
                                    ?: throw IOException("enregistrement impossible")
                                share(ctx, u, "image/jpeg")
                            } else {
                                share(ctx, pdfUri(), "application/pdf")
                            }
                            ""
                        }
                    }) { Text("Partager") }
                }
                OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    work("Lecture du texte…") {
                        val txt = withContext(Dispatchers.Default) {
                            pages.mapNotNull { p -> proc(p, 2480) }
                        }.map { Ocr.read(it).text }.joinToString("\n\n")
                        ocrText = txt
                        if (txt.isBlank()) "Aucun texte détecté." else ""
                    }
                }) { Text("Extraire le texte (OCR)") }
            }
        }
        plan?.let { pl ->
            var type by remember(pl) { mutableStateOf(pl.guess.type) }
            var name by remember(pl) { mutableStateOf(pl.guess.name()) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { plan = null },
                title = { Text("Classer le document") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Hint("Type détecté sur le téléphone (aucun envoi en ligne). Modifiable.")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DocType.entries.forEach { d ->
                                FilterChip(selected = type == d, onClick = { type = d; name = pl.guess.name(d) }, label = { Text(d.label) })
                            }
                        }
                        androidx.compose.material3.OutlinedTextField(
                            name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Nom du fichier") },
                        )
                        Hint("Dossier : ${if (pl.pdf) "Téléchargements" else "Images"}/Canon/${type.folder}")
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val f = type.folder; val n = Classify.clean(name)
                        plan = null
                        work("Enregistrement…") {
                            if (pl.pdf) pdfUri(f, n) else saveJpegs(f, n)
                            "Classé dans ${type.label} : $n"
                        }
                    }) { Text("Enregistrer") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        plan = null
                        work("Enregistrement…") {
                            if (pl.pdf) pdfUri() else saveJpegs()
                            "Enregistré sans classement."
                        }
                    }) { Text("Sans classer") }
                },
            )
        }
        if (askInterleave) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { askInterleave = false },
                title = { Text("Intercaler recto/verso") },
                text = { Text("Première moitié = rectos, seconde moitié = versos. Les versos ont-ils été numérisés dans l'ordre inverse (pile retournée) ?") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { pages = interleave(pages, true); sel = 0; askInterleave = false }) { Text("Ordre inverse") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { pages = interleave(pages, false); sel = 0; askInterleave = false }) { Text("Même ordre") }
                },
            )
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

/** Enregistrement en attente de validation du classement. */
class SavePlan(val pdf: Boolean, val guess: Classify.Guess)

/** Rectos (1re moitié) et versos (2e moitié) remis dans l'ordre r1, v1, r2, v2… */
fun interleave(l: List<Page>, reverse: Boolean): List<Page> {
    val h = l.size / 2
    val r = l.take(h)
    val v = l.drop(h).let { if (reverse) it.reversed() else it }
    return r.indices.flatMap { listOf(r[it], v[it]) } + l.drop(2 * h)
}

/** Vignettes des pages numérisées ; la page choisie est encadrée. */
@Composable
fun PageStrip(pages: List<Page>, sel: Int, onSelect: (Int) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(pages.size) { i ->
            val p = pages[i]
            val th by androidx.compose.runtime.produceState<Bitmap?>(null, p) {
                value = withContext(Dispatchers.Default) { Images.decode(p.bytes, 240)?.let { Images.rotate(it, p.rot) } }
            }
            val on = i == sel
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Card(
                    onClick = { onSelect(i) },
                    shape = MaterialTheme.shapes.small,
                    border = androidx.compose.foundation.BorderStroke(
                        if (on) 2.dp else 1.dp,
                        if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.size(64.dp, 88.dp),
                ) {
                    th?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                }
                Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
    var out by remember { mutableStateOf(OutFmt()) }
    var filt by remember { mutableStateOf(Filter.None) }
    var batch by remember { mutableStateOf(prefs.copyBatch) }
    var layout by remember { mutableStateOf(prefs.copyLayout) }
    var queue by remember { mutableStateOf<List<CopyItem>>(emptyList()) }
    var sel by remember { mutableIntStateOf(0) }

    /** Une seule feuille : impression image (IPP direct possible). */
    suspend fun printSheet(bmp: Bitmap): String = if (direct) {
        try {
            val data = withContext(Dispatchers.Default) { Images.jpeg(Calib.fixA4(bmp)) }
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

    /** Met en page et imprime une liste de documents. */
    suspend fun printAll(items: List<CopyItem>): String {
        val f = File(ctx.cacheDir, "copie.pdf")
        var first: Bitmap? = null
        val n = withContext(Dispatchers.Default) {
            val doc = android.graphics.pdf.PdfDocument()
            var k = 0
            CopyLayout.render(items, layout, out.target(), filt) { b ->
                k++
                if (k == 1) first = b
                CopyLayout.addPdfPage(doc, b, k)
                if (k > 1) b.recycle()
            }
            if (k > 1) f.outputStream().use { doc.writeTo(it) }
            doc.close()
            k
        }
        if (n == 0) throw IOException("rien à imprimer")
        PrinterPrefs(ctx).bump("copies", items.size)
        val docs = items.size
        return if (n == 1) {
            val r = printSheet(first!!)
            if (docs > 1) "$docs documents sur 1 feuille. $r" else r
        } else {
            androidPrint(ctx, Uri.fromFile(f), "application/pdf")
            markPrint(ctx)
            "$docs document(s) sur $n feuilles : fenêtre d'impression ouverte."
        }
    }

    suspend fun add(item: CopyItem): String =
        if (batch) {
            queue = queue + item; sel = queue.size - 1
            "Ajouté au lot (${queue.size}). Posez le document suivant."
        } else printAll(listOf(item))

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Numérisation", Icons.Filled.ContentCopy) {
            ScanOptions(area, { area = it; front = null }, dpi, { dpi = it }, color, { color = it }, cardLabel = "Carte ID R/V")
        }
        Section("Copie") {
            Text("Mode", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !batch, onClick = { batch = false; prefs.copyBatch = false }, label = { Text("Immédiate") })
                FilterChip(selected = batch, onClick = { batch = true; prefs.copyBatch = true }, label = { Text("Par lot") })
            }
            Hint(if (batch) "Numérisez plusieurs documents, puis imprimez l'ensemble." else "Chaque document est imprimé juste après le scan.")
            if (area != Area.Card) {
                Text("Mise en page", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PageLayout.entries.forEach { l ->
                        FilterChip(selected = layout == l, onClick = { layout = l; prefs.copyLayout = l }, label = { Text(l.label) })
                    }
                }
                Hint(
                    when (layout) {
                        PageLayout.One -> "Une feuille par document."
                        PageLayout.Pack -> "Documents recadrés, à taille réelle, regroupés pour utiliser le moins de feuilles (tickets, cartes, petits formats)."
                        PageLayout.Two -> "Deux documents réduits par feuille A4 (A5 chacun)."
                    },
                )
                FormatPicker(out, { out = it })
                if (out.fmt != Fmt.Original) Hint("Format imposé : une feuille par document, redressé et recadré.")
            } else {
                Hint("Recto et verso de la carte sur une seule feuille A4, taille réelle.")
            }
            Text("Filtre", style = MaterialTheme.typography.labelLarge)
            FilterChips(filt) { filt = it }
            if (filt == Filter.Doc || filt == Filter.BW) Hint("Fond blanchi : économise l'encre.")
            Toggle("Impression directe (IPP)", direct) { direct = it; prefs.directPrint = it }
            if (direct) Stepper("Copies", copies, 1..99) { copies = it }
        }
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    busy = true; msg = "Numérisation en cours…"
                    msg = try {
                        val b = ScanService.keep(ctx) { Escl.scan(ip, dpi, color, area) }
                        val f = front
                        if (area != Area.Card) {
                            add(CopyItem(Page(b, dpi)))
                        } else if (f == null) {
                            front = withContext(Dispatchers.Default) { Images.decode(b, 3508) } ?: throw IOException("image illisible")
                            "Recto lu. Retournez la carte au même endroit, puis appuyez à nouveau."
                        } else {
                            val sheet = withContext(Dispatchers.Default) {
                                val back = Images.decode(b, 3508) ?: throw IOException("image illisible")
                                Images.jpeg(Images.cardSheet(f, back), 92)
                            }
                            front = null
                            add(CopyItem(Page(sheet, 300), sheet = true))
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
                    area == Area.Card && front == null -> "Lire le recto"
                    area == Area.Card -> "Lire le verso"
                    batch -> "Numériser et ajouter (${queue.size + 1})"
                    else -> "Copier"
                },
            )
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg)
        if (batch && queue.isNotEmpty()) {
            Section("Lot en attente", sub = "${queue.size} document(s)") {
                PageStrip(queue.map { it.page }, sel.coerceIn(0, queue.size - 1)) { sel = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                        queue = queue.toMutableList().also { it.removeAt(sel.coerceIn(0, queue.size - 1)) }
                        sel = (sel - 1).coerceAtLeast(0)
                    }) { Text("Retirer") }
                    OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { queue = emptyList(); sel = 0 }) { Text("Vider") }
                }
                Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        busy = true; msg = "Mise en page…"
                        msg = try {
                            val r = printAll(queue)
                            queue = emptyList(); sel = 0
                            r
                        } catch (e: Exception) {
                            "Échec : ${e.message}"
                        }
                        busy = false
                    }
                }) { Text("Imprimer le lot") }
            }
        }
    }
}
