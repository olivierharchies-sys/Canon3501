@file:OptIn(ExperimentalMaterial3Api::class)

package be.canonhub

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.print.PrintHelper
import kotlinx.coroutines.launch
import android.graphics.Color as AColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PrinterPrefs(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App(prefs)
            }
        }
    }
}

enum class Screen(val title: String) {
    Home("Canon G3000"), Print("Imprimer"), Scan("Numériser"), Copy("Copier"),
    Ink("Encre et état"), Maintenance("Entretien"), Settings("Réglages")
}

@Composable
fun App(prefs: PrinterPrefs) {
    var screen by remember { mutableStateOf(Screen.Home) }
    var ip by remember { mutableStateOf(prefs.ip) }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

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
    }) { pad ->
        val m = Modifier.padding(pad)
        val needIp = @Composable { NeedIp(m) { screen = Screen.Settings } }
        when (screen) {
            Screen.Home -> HomeScreen(m, ip) { screen = it }
            Screen.Print -> PrintScreen(m)
            Screen.Scan -> if (ip.isBlank()) needIp() else ScanScreen(m, ip)
            Screen.Copy -> if (ip.isBlank()) needIp() else CopyScreen(m, ip)
            Screen.Ink -> if (ip.isBlank()) needIp() else InkScreen(m, ip)
            Screen.Maintenance -> MaintenanceScreen(m, ip) { screen = it }
            Screen.Settings -> SettingsScreen(m, ip) { prefs.ip = it; ip = it.trim() }
        }
    }
}

// ---------- Accueil en tuiles ----------

data class Tile(val label: String, val sub: String, val icon: ImageVector, val color: Color, val target: Screen)

@Composable
fun HomeScreen(m: Modifier, ip: String, go: (Screen) -> Unit) {
    val tiles = listOf(
        Tile("Imprimer", "PDF, photos", Icons.Filled.Print, Color(0xFF1E88E5), Screen.Print),
        Tile("Numériser", "Vitre vers image", Icons.Filled.DocumentScanner, Color(0xFF43A047), Screen.Scan),
        Tile("Copier", "Scan puis impression", Icons.Filled.ContentCopy, Color(0xFFF4511E), Screen.Copy),
        Tile("Encre et état", "Niveaux, alertes", Icons.Filled.WaterDrop, Color(0xFF8E24AA), Screen.Ink),
        Tile("Entretien", "Test, nettoyage, aide", Icons.Filled.Build, Color(0xFF00897B), Screen.Maintenance),
        Tile("Réglages", "Adresse IP", Icons.Filled.Settings, Color(0xFF546E7A), Screen.Settings),
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = m.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(if (ip.isBlank()) "Aucune imprimante configurée (voir Réglages)" else "Imprimante : $ip")
        }
        items(tiles) { t -> TileCard(t) { go(t.target) } }
    }
}

@Composable
fun TileCard(t: Tile, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = t.color),
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(t.icon, null, tint = Color.White, modifier = Modifier.size(40.dp))
            Column {
                Text(t.label, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(t.sub, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
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

// ---------- Impression ----------

fun printUri(ctx: Context, uri: Uri) {
    val name = "Canon-${System.currentTimeMillis() / 1000}"
    if (ctx.contentResolver.getType(uri) == "application/pdf") {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(name, PdfPrintAdapter(ctx.applicationContext, uri, name), null)
    } else {
        PrintHelper(ctx).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }.printBitmap(name, uri)
    }
}

fun printBitmap(ctx: Context, name: String, bmp: Bitmap) {
    PrintHelper(ctx).apply {
        scaleMode = PrintHelper.SCALE_MODE_FIT
        colorMode = PrintHelper.COLOR_MODE_COLOR
    }.printBitmap(name, bmp)
}

@Composable
fun PrintScreen(m: Modifier) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) printUri(ctx, uri)
    }
    Column(m.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Choisissez un PDF ou une image. La fenêtre d'impression d'Android s'ouvre : choisissez la Canon, le nombre de copies, le format et la couleur.")
        Button(onClick = { picker.launch(arrayOf("application/pdf", "image/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Choisir un fichier")
        }
        OutlinedButton(
            onClick = { ctx.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Service d'impression Android") }
        Text("Si la Canon n'apparaît pas dans la liste, activez le service d'impression Canon (ou « Mopria ») dans ce menu.")
    }
}

// ---------- Numérisation et copie ----------

@Composable
fun ScanOptions(dpi: Int, onDpi: (Int) -> Unit, color: Boolean, onColor: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Résolution")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(150, 300, 600).forEach { d ->
                FilterChip(selected = dpi == d, onClick = { onDpi(d) }, label = { Text("$d dpi") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = color, onClick = { onColor(true) }, label = { Text("Couleur") })
            FilterChip(selected = !color, onClick = { onColor(false) }, label = { Text("Noir et blanc") })
        }
    }
}

fun saveJpeg(ctx: Context, data: ByteArray) {
    val cv = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "scan_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Canon")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return
    ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }
}

@Composable
fun ScanScreen(m: Modifier, ip: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dpi by remember { mutableIntStateOf(300) }
    var color by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var bmp by remember { mutableStateOf<Bitmap?>(null) }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScanOptions(dpi, { dpi = it }, color, { color = it })
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    busy = true; msg = "Numérisation en cours…"
                    try {
                        val b = Escl.scan(ip, dpi, color)
                        bytes = b
                        bmp = BitmapFactory.decodeByteArray(b, 0, b.size)
                        msg = ""
                    } catch (e: Exception) {
                        msg = "Échec : ${e.message}"
                    }
                    busy = false
                }
            },
        ) { Text(if (busy) "Patientez…" else "Numériser (document sur la vitre)") }
        if (msg.isNotEmpty()) Text(msg)
        bmp?.let { b ->
            Image(b.asImageBitmap(), "Aperçu", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { bytes?.let { saveJpeg(ctx, it); msg = "Enregistré dans Images/Canon" } }) { Text("Enregistrer") }
                OutlinedButton(onClick = { printBitmap(ctx, "scan", b) }) { Text("Imprimer") }
            }
        }
    }
}

@Composable
fun CopyScreen(m: Modifier, ip: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dpi by remember { mutableIntStateOf(300) }
    var color by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Posez le document sur la vitre. Il est numérisé puis envoyé à l'impression ; vous réglez le nombre de copies dans la fenêtre d'impression.")
        ScanOptions(dpi, { dpi = it }, color, { color = it })
        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    busy = true; msg = "Numérisation en cours…"
                    try {
                        val b = Escl.scan(ip, dpi, color)
                        val bmp = BitmapFactory.decodeByteArray(b, 0, b.size)
                        msg = ""
                        printBitmap(ctx, "copie", bmp)
                    } catch (e: Exception) {
                        msg = "Échec : ${e.message}"
                    }
                    busy = false
                }
            },
        ) { Text(if (busy) "Patientez…" else "Copier") }
        if (msg.isNotEmpty()) Text(msg)
    }
}

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
fun InkScreen(m: Modifier, ip: String) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<Ipp.Info?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun load() {
        scope.launch {
            loading = true; err = null
            try { info = Ipp.query(ip) } catch (e: Exception) { err = e.message ?: "Erreur" }
            loading = false
        }
    }
    LaunchedEffect(ip) { load() }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        err?.let { Text("Imprimante injoignable : $it", color = MaterialTheme.colorScheme.error) }
        info?.let { i ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(i.model, style = MaterialTheme.typography.titleMedium)
                    Text("État : ${i.state}")
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
fun MaintenanceScreen(m: Modifier, ip: String, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MCard(
            "Page de test",
            "Imprime des barres de couleur et des traits fins : une couleur absente ou des traits interrompus indiquent des buses bouchées.",
            "Imprimer la page de test",
        ) { printBitmap(ctx, "test", testBitmap()) }
        MCard("Niveaux d'encre", "À vérifier avant tout nettoyage.", "Voir les niveaux") { go(Screen.Ink) }
        MCard(
            "Réservoirs d'encre",
            "La série G3000 fonctionne avec des réservoirs rechargeables. Contrôlez le niveau à l'œil sur les réservoirs et rechargez avant que l'encre passe sous le repère inférieur : un réservoir trop vide fait entrer de l'air et bouche les buses. Les pourcentages affichés dans l'appli ne sont qu'indicatifs.",
        )
        MCard(
            "Interface web de l'imprimante",
            "Ouvre la page intégrée de l'imprimante dans le navigateur : état, réseau et, selon le modèle, commandes de maintenance.",
            "Ouvrir",
        ) {
            if (ip.isNotBlank()) ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$ip/")))
        }
        MCard("Service d'impression Android", "Active ou règle le module qui permet à Android d'imprimer sur la Canon.", "Ouvrir") {
            ctx.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
        }
        MCard(
            "Nettoyage des têtes",
            "À lancer si la page de test montre des traits manquants ou des couleurs pâles. Sur l'imprimante : menu Maintenance (ou Configuration, puis Maintenance), commande Nettoyage. Refaites ensuite une page de test.",
        )
        MCard(
            "Nettoyage en profondeur",
            "Seulement si deux nettoyages normaux n'ont rien corrigé : il consomme beaucoup d'encre.",
        )
        MCard(
            "Alignement des têtes",
            "À faire si les lignes verticales sont décalées ou le texte flou, depuis le même menu Maintenance.",
        )
        Text("Les intitulés exacts des menus dépendent du modèle. Ces commandes se lancent sur l'imprimante elle-même : aucun protocole standard ne permet de les déclencher depuis un téléphone.")
    }
}

// ---------- Réglages ----------

@Composable
fun SettingsScreen(m: Modifier, ip: String, onSave: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(ip) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(m.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Adresse IP de l'imprimante. Le téléphone doit être sur le même Wi-Fi. Vous la trouvez dans les réglages réseau de l'imprimante.")
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
                        busy = true; msg = "Test en cours…"
                        msg = try {
                            "Connecté : ${Ipp.query(text.trim()).model}"
                        } catch (e: Exception) {
                            "Injoignable : ${e.message}"
                        }
                        busy = false
                    }
                },
            ) { Text("Tester") }
        }
        if (msg.isNotEmpty()) Text(msg)
    }
}
