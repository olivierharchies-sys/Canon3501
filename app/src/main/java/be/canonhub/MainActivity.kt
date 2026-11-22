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
import androidx.compose.material.icons.filled.Crop
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
    Info("Infos imprimante"), Settings("Réglages"), History("Documents"), Calib("Étalonnage"),
    GpTune("Réglage du pilote")
}

fun sharedUri(i: Intent): Uri? = when (i.action) {
    Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
    Intent.ACTION_VIEW -> i.data
    else -> null
}

@Composable
fun App(prefs: PrinterPrefs, incoming: Intent?, consumed: () -> Unit) {
    var screen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(Screen.Home) }
    var ip by remember { mutableStateOf(prefs.ip) }
    var shared by remember { mutableStateOf<Uri?>(null) }
    BackHandler(enabled = screen != Screen.Home) { screen = if (screen == Screen.Calib || screen == Screen.GpTune) Screen.Settings else Screen.Home }

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
    // Écrans principaux : en-tête graphique propre, sans barre de titre.
    val tabs = setOf(Screen.Home, Screen.Print, Screen.Copy, Screen.Scan, Screen.History)
    Scaffold(snackbarHost = { androidx.compose.material3.SnackbarHost(snack) }, topBar = {
        if (screen !in tabs) TopAppBar(
            title = { Text(screen.title, fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = { screen = if (screen == Screen.Calib || screen == Screen.GpTune) Screen.Settings else Screen.Home }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                }
            },
        )
    }, bottomBar = {
        androidx.compose.material3.NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
            listOf(
                Triple(Screen.Home, "Accueil", Icons.Filled.Home),
                Triple(Screen.Print, "Imprimer", Icons.Filled.Print),
                Triple(Screen.Copy, "Copier", Icons.Filled.ContentCopy),
                Triple(Screen.Scan, "Numériser", Icons.Filled.DocumentScanner),
                Triple(Screen.History, "Documents", Icons.Filled.Folder),
            ).forEach { (sc, label, icon) ->
                NavigationBarItem(
                    selected = screen == sc,
                    onClick = { screen = sc },
                    icon = { Icon(icon, null) },
                    label = { Text(label, maxLines = 1) },
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
            Screen.Settings -> SettingsScreen(m, prefs, ip, setIp, onGpTune = { go(Screen.GpTune) }) { go(Screen.Calib) }
            Screen.GpTune -> GpTuneScreen(m, prefs, ip)
            Screen.Calib -> CalibScreen(m, ip)
            Screen.History -> HistoryScreen(m)
        }
    }
    }
}

// ---------- Accueil : voir Home.kt ----------

/** État de l'imprimante affiché en tête de l'accueil. */
data class HomeStatus(val online: Boolean, val model: String, val ipp: Boolean)

suspend fun homeStatus(ip: String): HomeStatus = withContext(Dispatchers.IO) {
    val online = portOpen(ip, 515, 1500) || portOpen(ip, 80, 1500)
    val model = if (!online) "" else runCatching {
        Bjnp.fields(Bjnp.deviceId(ip)).firstOrNull { it.first == "MDL" || it.first == "MODEL" }?.second
    }.getOrNull().orEmpty()
    HomeStatus(online, model, online && portOpen(ip, 631, 1000))
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

/** Appli Canon PRINT Inkjet/SELPHY. */
const val CANON_PRINT = "jp.co.canon.bsd.ad.pixmaprint"

/** Fenêtre Android avec les réglages de l'appli : format, orientation, couleur, brouillon, pages par feuille. */
suspend fun androidPrintOpts(ctx: Context, uri: Uri, mime: String, o: PrintOpts): String {
    val (u, land) = if (PrintPrep.needsPrep(mime, o)) {
        withContext(Dispatchers.IO) { PrintPrep.prepare(ctx, uri, mime, o) }.let { Uri.fromFile(it.first) to it.second }
    } else uri to (o.orient == Orient.Landscape)
    val name = "Canon-${System.currentTimeMillis() / 1000}"
    val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
    pm.print(name, PdfPrintAdapter(ctx.applicationContext, u, name), PrintPrep.attributes(o, land))
    markPrint(ctx)
    return "Fenêtre d'impression ouverte. " + o.reminder()
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

/** Titre + rangée de puces défilante, une seule sélection. */
@Composable
fun <T> OptChips(title: String, items: List<T>, sel: T, label: (T) -> String, on: (T) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { v -> FilterChip(selected = v == sel, onClick = { on(v) }, label = { Text(label(v)) }) }
    }
}

@Composable
fun ColorChips(color: Boolean, onColor: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = color, onClick = { onColor(true) }, label = { Text("Couleur") })
        FilterChip(selected = !color, onClick = { onColor(false) }, label = { Text("Noir et blanc") })
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
    val prefs0 = remember { PrinterPrefs(ctx) }
    var area by remember { mutableStateOf(Area.A4) }
    var dpi by remember { mutableIntStateOf(300) }
    var color by remember { mutableStateOf(true) }
    var crop by remember { mutableStateOf(false) }
    var straight by remember { mutableStateOf(prefs0.bool("scanStraight", false)) }
    var multi by remember { mutableStateOf(prefs0.bool("scanMulti", false)) }
    var count by remember { mutableIntStateOf(prefs0.int("scanCount", 0)) }
    var check by remember { mutableStateOf(prefs0.bool("scanCheck", false)) }
    var skipBlank by remember { mutableStateOf(prefs0.bool("scanSkipBlank", false)) }
    var separate by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(runCatching { OutType.valueOf(prefs0.str("scanType", "PDF")) }.getOrDefault(OutType.PDF)) }
    var quality by remember { mutableIntStateOf(prefs0.int("scanQ", 85)) }
    var nameTpl by remember { mutableStateOf(prefs0.str("scanName", Output.DEFAULT_NAME)) }
    var protect by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var estimate by remember { mutableStateOf<String?>(null) }
    var preset by remember { mutableStateOf<Preset?>(null) }
    var filt by remember { mutableStateOf(Filter.None) }
    var fix by remember { mutableStateOf(Fix()) }
    var bright by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var busy by remember { mutableStateOf(false) }
    KeepAwake(busy)
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
    var plan by remember { mutableStateOf<SavePlan?>(null) }
    var cropIdx by remember { mutableStateOf<Int?>(null) }
    val target = out.target()
    val cur = pages.getOrNull(sel.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))

    LaunchedEffect(cur, crop, straight, filt, bright, contrast, fix) {
        val p = cur
        withContext(Dispatchers.Default) {
            val dec = p?.let { Images.decode(it.bytes, 1200) }
            val pv = p?.let { Images.process(it.bytes, 1200, crop, bright, contrast, filt, it.rot, straight, fix, it.crop) }
            preview = pv
            detected = if (p == null || dec == null || pv == null) null else {
                val (w, h) = Formats.physical(p.bytes, p.dpi, pv, dec.width)
                Formats.detect(w, h)
            }
        }
    }

    fun bounds(p: Page): Pair<Int, Int> {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(p.bytes, 0, p.bytes.size, o)
        return o.outWidth to o.outHeight
    }

    // Poids estimé : recalculé après une courte pause (curseur de qualité).
    LaunchedEffect(preview, type, quality, pages.size, target, ocrPdf, separate) {
        kotlinx.coroutines.delay(250)
        val pv = preview; val p = cur
        estimate = if (pv == null || p == null) null else withContext(Dispatchers.Default) {
            runCatching {
                val (ow, oh) = bounds(p)
                val k = Images.sampleFor(ow, oh, 1200).toFloat() / Images.sampleFor(ow, oh, 3508)
                val fullPx = if (target != null) {
                    val (w, h) = Formats.fit(pv.width, pv.height, target)
                    (w * 300f / 2.54f).toLong() * (h * 300f / 2.54f).toLong()
                } else (pv.width * k).toLong() * (pv.height * k).toLong()
                val one = Output.estimatePage(pv, fullPx, type, quality, ocrPdf)
                val files = if (separate || !type.multi) pages.size else 1
                "≈ ${SizeEst.label(one * pages.size)}" + if (files > 1) " · $files fichiers" else ""
            }.getOrNull()
        }
    }

    fun proc(p: Page, max: Int) = Images.process(p.bytes, max, crop, bright, contrast, filt, p.rot, straight, fix, p.crop)
    fun full(p: Page) = proc(p, 3508)

    /** Résolution réelle de l'image traitée (le décodage peut réduire). */
    fun effDpi(p: Page): Int {
        val (ow, oh) = bounds(p)
        return (p.dpi / Images.sampleFor(ow, oh, 3508)).coerceAtLeast(1)
    }

    val drv = rememberDriver(prefs0, true)

    /** Feuille A4 prête à imprimer : taille choisie, sinon taille réelle si l'image est plus petite qu'un A4. */
    fun sheetOf(p: Page, b: Bitmap): Bitmap {
        target?.let { return Formats.onA4(b, it) }
        val d = effDpi(p).toFloat()
        val wCm = b.width / d * 2.54f; val hCm = b.height / d * 2.54f
        return if (minOf(wCm, hCm) < 20f && maxOf(wCm, hCm) < 28.5f) Formats.onA4(b, wCm to hCm) else b
    }

    /** Impression : pilote intégré (voie des copies) si disponible, sinon fenêtre Android. */
    suspend fun printPages(): String {
        val route = prefs0.copyRoute.let { if (it == Route.Driver && !Gutenprint.available) Route.Android else it }
        val gpDpi = when (prefs0.copyQuality) { Quality.Draft -> 150; Quality.Standard -> 200; Quality.High -> 300 }
        val f = File(ctx.cacheDir, "scan.pdf")
        // Une feuille à la fois : peu de mémoire même avec beaucoup de pages.
        val n = withContext(Dispatchers.Default) {
            val doc = android.graphics.pdf.PdfDocument()
            var k = 0
            for (p in pages) {
                val b = full(p) ?: continue
                val sh = sheetOf(p, b)
                CopyLayout.addPdfPage(doc, sh, ++k, route != Route.Driver)
                if (sh !== b) sh.recycle()
            }
            f.outputStream().use { doc.writeTo(it) }
            doc.close()
            k
        }
        if (n == 0) throw IOException("image illisible")
        val u = Uri.fromFile(f)
        if (route == Route.Driver && ip.isNotBlank()) {
            return try {
                driverFile(ctx, ip, drv, u, "application/pdf", color, 1, gpDpi) { msg = it }
            } catch (e: Exception) {
                androidPrint(ctx, u, "application/pdf")
                "Pilote intégré : échec (${e.message}). Fenêtre Android ouverte."
            }
        }
        androidPrint(ctx, u, "application/pdf")
        return "Fenêtre d'impression ouverte."
    }

    fun move(d: Int) {
        val i = sel; val j = i + d
        if (i !in pages.indices || j !in pages.indices) return
        val l = pages.toMutableList(); val t = l[i]; l[i] = l[j]; l[j] = t
        pages = l; sel = j
    }

    fun applyPreset(pr: Preset) {
        preset = pr; area = Area.A4; color = true; bright = 0f; contrast = 1f
        when (pr) {
            Preset.Photos -> { dpi = 300; multi = true; straight = true; crop = false; filt = Filter.None; fix = Fix(Enhance.Mode.Auto, dust = true); type = OutType.JPEG; quality = 95 }
            Preset.Document -> { dpi = 300; multi = false; straight = true; crop = true; filt = Filter.None; fix = Fix(Enhance.Mode.Document); type = OutType.PDF; quality = 80 }
            Preset.Archive -> { dpi = 300; multi = false; straight = true; crop = false; filt = Filter.None; fix = Fix(); type = OutType.TIFF }
            Preset.Fast -> { dpi = 150; multi = false; straight = false; crop = false; filt = Filter.None; fix = Fix(); type = OutType.PDF; quality = 70 }
        }
        prefs0.setBool("scanMulti", multi); prefs0.setBool("scanStraight", straight)
        prefs0.setStr("scanType", type.name); prefs0.setInt("scanQ", quality)
    }

    val snack = LocalSnack.current
    val splitAsk = remember { SplitAsk() }
    SplitDialog(splitAsk)
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

    /** Documents posés sur la vitre ; [all] : garder aussi un document seul (recadré, redressé). */
    suspend fun splitPage(p: Page, all: Boolean, expected: Int = 0): List<Page> {
        val (b, d, docs) = withContext(Dispatchers.Default) {
            val (ow, oh) = bounds(p)
            val b = Images.decode(p.bytes, 4000)?.let { Images.rotate(it, p.rot) } ?: throw IOException("image illisible")
            val origW = if (p.rot % 180 == 0) ow else oh
            val d = (p.dpi * b.width / origW.coerceAtLeast(1)).coerceAtLeast(1)
            Triple(b, d, Images.documents(b, expected))
        }
        // Numérisation multi-documents : cadres à ajuster si moins que prévu (ou toujours, si demandé).
        val kept = if (all) splitAsk.review(b, d, expected, docs, check) ?: throw kotlinx.coroutines.CancellationException("repositionner") else docs
        return withContext(Dispatchers.Default) { (if (all || kept.size >= 2) kept else emptyList()).map { Page(Images.jpeg(it, 92), d) } }
    }

    /** Écrit un groupe de pages dans le type choisi ; renvoie le texte OCR éventuel. */
    suspend fun writeGroup(g: List<Page>, o: java.io.OutputStream, t: OutType): String {
        val text = StringBuilder()
        fun outBmp(p: Page): Bitmap {
            val src = full(p) ?: throw IOException("image illisible")
            return target?.let { Formats.resize(src, it) } ?: src
        }
        when (t) {
            OutType.PDF -> {
                val pdf = PdfOut(o, if (protect) password else null)
                for (p in g) {
                    val b = outBmp(p)
                    val (pw, ph) = if (target == null) 595f to 842f else {
                        val (w, h) = Formats.fit(b.width, b.height, target)
                        w * 72f / 2.54f to h * 72f / 2.54f
                    }
                    val sc = minOf(pw / b.width, ph / b.height)
                    val dw = b.width * sc; val dh = b.height * sc; val x = (pw - dw) / 2
                    val lines: List<PdfOut.Line> = if (!ocrPdf) emptyList() else runCatching { Ocr.read(b) }.getOrNull()?.let { tx ->
                        text.append(tx.text).append('\n')
                        tx.textBlocks.flatMap { it.lines }.mapNotNull { ln ->
                            val r = ln.boundingBox ?: return@mapNotNull null
                            val h = r.height() * sc
                            if (h <= 0f || ln.text.isBlank()) null
                            else PdfOut.Line(ln.text, x + r.left * sc, r.bottom * sc - h * 0.15f, h * 0.85f, r.width() * sc)
                        }
                    } ?: emptyList()
                    pdf.page(pw, ph, Images.jpeg(b, quality), b.width, b.height, x, 0f, dw, dh, lines)
                }
                pdf.close()
            }
            OutType.TIFF -> Tiff.write(o, g.size) { k -> Output.tiffPage(outBmp(g[k]), if (target != null) 300 else effDpi(g[k])) }
            else -> o.write(Output.encode(outBmp(g[0]), t, quality))
        }
        return text.toString()
    }

    /** Enregistre toutes les pages ; un fichier par page si demandé ou si le type est mono-page. */
    suspend fun saveAll(folder: String? = null, base: String? = null): List<Uri> = withContext(Dispatchers.IO) {
        val t = type
        if (t == OutType.PDF && protect && password.isEmpty()) throw IOException("mot de passe vide")
        val b0 = base?.takeIf { it.isNotBlank() } ?: Output.name(nameTpl)
        val groups = if (separate || !t.multi) pages.map { listOf(it) } else listOf(pages)
        groups.mapIndexed { i, g ->
            val n = if (groups.size > 1) "${b0}_${i + 1}" else b0
            var txt = ""
            val u = Output.save(ctx, t, folder, n) { o -> txt = writeGroup(g, o, t) }
            if (txt.isNotBlank()) OcrIndex.put(ctx, u, txt)
            u
        }
    }

    fun savedMsg(n: Int) = "$n fichier(s) ${type.label} enregistré(s) (Documents)."

    /** Classement local : OCR de la 1re page sur le téléphone, puis mots-clés. */
    suspend fun analyze(): Classify.Guess {
        val b = withContext(Dispatchers.Default) { proc(pages[0], 2480) } ?: throw IOException("image illisible")
        val txt = runCatching { Ocr.read(b).text }.getOrDefault("")
        return Classify.guess(txt)
    }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Hero(
            "Numériser",
            if (pages.isEmpty()) "${area.label} · $dpi dpi · ${if (color) "Couleur" else "Gris"}" else "${pages.size} page(s) prête(s)",
            Icons.Filled.DocumentScanner, Brand.Scan,
        )
        Section("Numérisation", Icons.Filled.DocumentScanner, accent = Brand.Scan[0]) {
            Text("Réglage rapide", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Preset.entries.forEach { pr ->
                    FilterChip(selected = preset == pr, onClick = { applyPreset(pr) }, label = { Text(pr.label) })
                }
            }
            ScanOptions(area, { area = it; preset = null }, dpi, { dpi = it; preset = null }, color, { color = it; preset = null })
            Switcher("Détecter plusieurs documents", multi, "Photos, cartes, tickets espacés d'au moins 5 mm : un fichier chacun, redressé.") {
                multi = it; prefs0.setBool("scanMulti", it)
            }
            if (multi) DocCount(count, check, { check = it; prefs0.setBool("scanCheck", it) }) { count = it; prefs0.setInt("scanCount", it) }
            Switcher("Ignorer les pages blanches", skipBlank, "Une page vide n'est pas ajoutée.") {
                skipBlank = it; prefs0.setBool("scanSkipBlank", it)
            }
        }
        BigButton(
            if (pages.isEmpty()) "Numériser" else "Ajouter une page (${pages.size + 1})",
            Icons.Filled.DocumentScanner, Brand.Scan, busy = busy,
        ) {
            work("Numérisation en cours…") {
                val b = ScanService.keep(ctx) { Escl.scan(ip, dpi, color, area) { progress = it } }
                PrinterPrefs(ctx).bump("scans")
                val pg = Page(b, dpi)
                if (skipBlank && withContext(Dispatchers.Default) { Images.decode(b, 800)?.let { Images.isBlank(it) } == true }) {
                    return@work "Page blanche ignorée."
                }
                if (multi) {
                    msg = "Recherche des documents…"
                    val parts = splitPage(pg, all = true, expected = count)
                    if (parts.isNotEmpty()) {
                        pages = pages + parts
                        sel = pages.size - parts.size
                        if (parts.size > 1) separate = true
                        return@work if (parts.size > 1) "${parts.size} documents détectés : un fichier chacun." else "1 document détecté, recadré et redressé."
                    }
                }
                pages = pages + pg
                sel = pages.size - 1
                "${pages.size} page(s) numérisée(s)."
            }
        }
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
                    IconButton(enabled = !busy, onClick = { cropIdx = sel }) {
                        Icon(Icons.Filled.Crop, "Choisir la zone", tint = if (cur?.crop != null) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current)
                    }
                    IconButton(enabled = !busy, onClick = {
                        pages = pages.toMutableList().also { l -> l.removeAt(sel) }
                        sel = sel.coerceAtMost((pages.size - 1).coerceAtLeast(0))
                    }) { Icon(Icons.Filled.Delete, "Supprimer la page") }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    androidx.compose.material3.TextButton(enabled = !busy, onClick = { pages = emptyList(); sel = 0; msg = ""; separate = false }) { Text("Tout effacer") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                        work("Recherche des documents…") {
                            val parts = splitPage(pages[sel], all = false)
                            if (parts.isEmpty()) "Moins de deux documents détectés : espacez-les d'au moins 5 mm."
                            else {
                                pages = pages.toMutableList().also { l -> l.removeAt(sel); l.addAll(sel, parts) }
                                separate = true
                                "${parts.size} documents séparés."
                            }
                        }
                    }) { Text("Séparer les documents") }
                    OutlinedButton(
                        enabled = !busy && pages.size >= 2 && pages.size % 2 == 0,
                        modifier = Modifier.weight(1f),
                        onClick = { askInterleave = true },
                    ) { Text("Intercaler R/V") }
                }
            }
            Section("Retouche", sub = "S'applique à toutes les pages") {
                Toggle("Redresser automatiquement", straight) { straight = it; prefs0.setBool("scanStraight", it) }
                Toggle("Recadrer les marges", crop) { crop = it }
                FixPicker(fix) { fix = it }
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
                OptChips("Type de fichier", OutType.entries, type, { it.label }) { type = it; prefs0.setStr("scanType", it.name) }
                Hint(type.sub)
                if (type.lossy) {
                    Text("Qualité $quality %", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = quality.toFloat(), valueRange = 40f..100f, steps = 11,
                        onValueChange = { quality = it.toInt() }, onValueChangeFinished = { prefs0.setInt("scanQ", quality) },
                    )
                }
                if (type == OutType.PDF) {
                    Toggle("Texte cherchable (OCR)", ocrPdf) { ocrPdf = it }
                    Toggle("Protéger par mot de passe", protect) { protect = it }
                    if (protect) androidx.compose.material3.OutlinedTextField(
                        password, { password = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Mot de passe d'ouverture") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    )
                }
                if (type.multi && pages.size > 1) Toggle("Un fichier par page", separate) { separate = it }
                androidx.compose.material3.OutlinedTextField(
                    nameTpl, { nameTpl = it; prefs0.setStr("scanName", it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nom du fichier") }, supportingText = { Text("Variables : {date} {heure}") },
                )
                Text(
                    "Poids estimé : ${estimate ?: "calcul…"}",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                        if (prefs0.autoClass) work("Analyse du document…") { plan = SavePlan(analyze()); "" }
                        else work(if (ocrPdf && type == OutType.PDF) "Création du PDF (OCR)…" else "Enregistrement…") { savedMsg(saveAll().size) }
                    }) { Text("Enregistrer") }
                    OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                        work("Préparation…") { Output.shareAll(ctx, saveAll(), type.mime); "" }
                    }) { Text("Partager") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                        work("Préparation de l'impression…") { printPages() }
                    }) { Text("Imprimer") }
                    OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                        work("Lecture du texte…") {
                            val txt = withContext(Dispatchers.Default) {
                                pages.mapNotNull { p -> proc(p, 2480) }
                            }.map { Ocr.read(it).text }.joinToString("\n\n")
                            ocrText = txt
                            if (txt.isBlank()) "Aucun texte détecté." else ""
                        }
                    }) { Text("Extraire le texte") }
                }
            }
        }
        cropIdx?.let { ci ->
            pages.getOrNull(ci)?.let { p ->
                val (ow, oh) = bounds(p)
                val cm = 2.54f / p.dpi
                val full = if (p.rot % 180 == 0) ow * cm to oh * cm else oh * cm to ow * cm
                CropDialog(
                    p.crop, full, out, { out = it },
                    load = { Images.decode(p.bytes, 1400)?.let { Images.rotate(it, p.rot) } },
                    onDone = { c -> pages = pages.toMutableList().also { l -> l[ci] = p.withCrop(c) }; cropIdx = null },
                    onCancel = { cropIdx = null },
                )
            } ?: run { cropIdx = null }
        }
        plan?.let { pl ->
            var dt by remember(pl) { mutableStateOf(pl.guess.type) }
            var name by remember(pl) { mutableStateOf(pl.guess.name()) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { plan = null },
                title = { Text("Classer le document") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Hint("Type détecté sur le téléphone (aucun envoi en ligne). Modifiable.")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DocType.entries.forEach { d ->
                                FilterChip(selected = dt == d, onClick = { dt = d; name = pl.guess.name(d) }, label = { Text(d.label) })
                            }
                        }
                        androidx.compose.material3.OutlinedTextField(
                            name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Nom du fichier") },
                        )
                        Hint("Dossier : ${if (type == OutType.PDF || type == OutType.TIFF) "Téléchargements" else "Images"}/Canon/${dt.folder}")
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val f = dt.folder; val n = Classify.clean(name); val lbl = dt.label
                        plan = null
                        work("Enregistrement…") {
                            saveAll(f, n)
                            "Classé dans $lbl : $n"
                        }
                    }) { Text("Enregistrer") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        plan = null
                        work("Enregistrement…") { savedMsg(saveAll().size) }
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

/** Choix des corrections automatiques (scan et copie). */
@Composable
fun FixPicker(fix: Fix, onChange: (Fix) -> Unit) {
    OptChips("Correction auto", Enhance.Mode.entries, fix.mode, { it.label }) { m ->
        val old = m == Enhance.Mode.Faded || m == Enhance.Mode.OldBw
        onChange(fix.copy(mode = m, dust = fix.dust || old))
    }
    if (fix.mode.hint.isNotEmpty()) Hint(fix.mode.hint)
    Toggle("Dépoussiérer (taches, poussières)", fix.dust) { onChange(fix.copy(dust = it)) }
    Toggle("Accentuer la netteté", fix.sharp) { onChange(fix.copy(sharp = it)) }
}

/** Enregistrement en attente de validation du classement. */
class SavePlan(val guess: Classify.Guess)

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
