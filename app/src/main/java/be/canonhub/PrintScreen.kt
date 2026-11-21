@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package be.canonhub

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.ViewQuilt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

// ---------- Pilote intégré : état partagé (Imprimer, Copier) ----------

/** Réglages du pilote Gutenprint (format, papier, qualité, alimentation), mémorisés. */
class DriverState(private val prefs: PrinterPrefs) {
    var sel by mutableStateOf(prefs.gpSel)
    var choices by mutableStateOf<Map<String, List<Gutenprint.Choice>>>(emptyMap())
    var err by mutableStateOf("")

    fun set(p: String, v: String) { sel = sel + (p to v); prefs.gpSel = sel }

    /** Valeur retenue : choix mémorisé s'il existe encore, sinon défaut du pilote. */
    fun cur(p: String): String? = choices[p]?.let { l ->
        sel[p]?.takeIf { v -> l.any { it.name == v } } ?: (l.firstOrNull { it.default } ?: l.firstOrNull())?.name
    }

    fun text(p: String): String? = cur(p)?.let { n -> choices[p]?.firstOrNull { it.name == n }?.text }

    /** Sélection envoyée, avec la couleur de l'appli imposée au pilote (moins d'encre couleur en N&B). */
    fun selFor(color: Boolean): Map<String, String> {
        val l = choices["PrintingMode"].orEmpty()
        val want = if (color) "Color" else "BW"
        return if (l.any { it.name == want }) sel + ("PrintingMode" to want) else sel
    }
}

@Composable
fun rememberDriver(prefs: PrinterPrefs, active: Boolean): DriverState {
    val ctx = LocalContext.current
    val d = remember { DriverState(prefs) }
    val sel = d.sel
    LaunchedEffect(active, sel) {
        if (!active || !Gutenprint.available) return@LaunchedEffect
        d.err = try {
            d.choices = Gutenprint.PARAMS.associate { (p, _) -> p to Gutenprint.choices(ctx, p, sel) }
            ""
        } catch (e: Exception) { "Pilote intégré indisponible : ${e.message}" }
    }
    return d
}

/** Liste déroulante d'un paramètre du pilote (masquée s'il n'en propose pas). */
@Composable
fun DriverPicker(d: DriverState, param: String, title: String, icon: ImageVector) {
    val l = d.choices[param].orEmpty()
    if (l.isNotEmpty()) Picker(title, icon, l.map { it.name to it.text }, d.cur(param)) { d.set(param, it) }
}

/** Imprime un fichier de feuilles A4 (copie, planche) en taille réelle par le pilote intégré. */
suspend fun driverFile(
    ctx: Context, ip: String, d: DriverState, uri: Uri, mime: String, color: Boolean, copies: Int, dpi: Int,
    onStep: (String) -> Unit,
): String {
    if (ip.isBlank()) throw IOException("adresse IP manquante")
    val r = Gutenprint.print(
        ctx, ip, uri, mime, d.selFor(color), borderless = false, fill = false, dpi = dpi,
        truePage = true, job = Gutenprint.Job(copies = copies, orient = Orient.Portrait), onStep = onStep,
    )
    markPrint(ctx)
    return r
}

/** Une feuille A4 (image) imprimée par le pilote intégré. */
suspend fun driverSheet(
    ctx: Context, ip: String, d: DriverState, bmp: Bitmap, color: Boolean, copies: Int, dpi: Int,
    onStep: (String) -> Unit,
): String {
    val f = File(ctx.cacheDir, "sheet-${System.currentTimeMillis()}.png")
    withContext(Dispatchers.IO) { f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    return try { driverFile(ctx, ip, d, Uri.fromFile(f), "image/png", color, copies, dpi, onStep) } finally { f.delete() }
}

// ---------- Document choisi ----------

class DocInfo(val name: String, val mime: String, val pages: Int, val thumb: Bitmap?, val wide: Boolean)

fun docInfo(ctx: Context, uri: Uri): DocInfo {
    val mime = ctx.contentResolver.getType(uri) ?: if (uri.path.orEmpty().endsWith(".pdf", true)) "application/pdf" else "image/*"
    val name = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "document"
    return if (mime == "application/pdf") runCatching {
        ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
            PdfRenderer(fd).use { r ->
                r.openPage(0).use { p ->
                    val s = 360f / maxOf(p.width, p.height)
                    val b = Bitmap.createBitmap((p.width * s).toInt().coerceAtLeast(1), (p.height * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    b.eraseColor(android.graphics.Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    DocInfo(name, mime, r.pageCount, b, p.width > p.height)
                }
            }
        }
    }.getOrElse { DocInfo(name, mime, 0, null, false) } else runCatching {
        val raw = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val b = Images.decode(raw, 360)
        DocInfo(name, mime, 1, b, b != null && b.width > b.height)
    }.getOrElse { DocInfo(name, mime, 1, null, false) }
}

/** Carte du document : vignette, nom, pages ; ou zone « choisir un fichier ». */
@Composable
fun DocCard(info: DocInfo?, hasFile: Boolean, onPick: () -> Unit, onClear: () -> Unit) {
    if (!hasFile) {
        val c = MaterialTheme.colorScheme.primary
        Box(
            Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(24.dp))
                .background(c.copy(alpha = 0.06f))
                .drawBehind {
                    drawRoundRect(c.copy(alpha = 0.6f), cornerRadius = CornerRadius(24.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))))
                }
                .clickable(onClick = onPick),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.UploadFile, null, tint = c, modifier = Modifier.size(40.dp))
                Text("Choisir un document", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c)
                Hint("PDF ou photo · ou « Partager » depuis une autre appli")
            }
        }
        return
    }
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(76.dp, 104.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                val t = info?.thumb
                if (t != null) Image(t.asImageBitmap(), "Aperçu", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Icon(if (info?.mime == "application/pdf") Icons.Filled.PictureAsPdf else Icons.Filled.Photo, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(info?.name ?: "Chargement…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                info?.let {
                    Hint((if (it.mime == "application/pdf") "PDF" else "Image") + (if (it.pages > 0) " · ${it.pages} page${if (it.pages > 1) "s" else ""}" else "") +
                        (if (it.wide) " · paysage" else " · portrait"))
                }
                OutlinedButton(onClick = onPick) { Text("Changer") }
            }
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Retirer") }
        }
    }
}

// ---------- Écran Imprimer ----------

fun routeIcon(r: Route): ImageVector = when (r) {
    Route.Driver -> Icons.Filled.Memory
    Route.Android -> Icons.Filled.Android
    Route.CanonApp -> Icons.Filled.OpenInNew
    Route.Ipp -> Icons.Filled.Wifi
}

fun qualityIcon(q: Quality): ImageVector = when (q) {
    Quality.Draft -> Icons.Filled.Speed
    Quality.Standard -> Icons.Filled.Description
    Quality.High -> Icons.Filled.HighQuality
}

fun orientIcon(o: Orient): ImageVector = when (o) {
    Orient.Auto -> Icons.Filled.ScreenRotation
    Orient.Portrait -> Icons.Filled.StayCurrentPortrait
    Orient.Landscape -> Icons.Filled.StayCurrentLandscape
}

@Composable
fun PrintScreen(m: Modifier, prefs: PrinterPrefs, ip: String, shared: Uri?, clearShared: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(shared) { if (shared != null) { file = shared; clearShared() } }
    val info by produceState<DocInfo?>(null, file) {
        value = null
        value = file?.let { u -> withContext(Dispatchers.IO) { docInfo(ctx, u) } }
    }

    var route by remember { mutableStateOf(prefs.printRoute.let { if (it == Route.Driver && !Gutenprint.available) Route.Android else it }) }
    fun setRoute(r: Route) { route = r; prefs.printRoute = r }
    var opts by remember { mutableStateOf(printOptsOf(prefs.printOpts)) }
    fun setOpts(o: PrintOpts) { opts = o; prefs.printOpts = o.save() }
    var copies by remember { mutableIntStateOf(1) }
    var collate by remember { mutableStateOf(true) }
    var range by remember { mutableStateOf("") }
    var reverse by remember { mutableStateOf(false) }
    var duplex by remember { mutableStateOf(false) }
    var versoRev by remember { mutableStateOf(false) }
    var verso by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val drv = rememberDriver(prefs, route == Route.Driver)

    /** Pilote intégré : brouillon, N&B ou plusieurs pages par feuille passent d'abord par un PDF préparé. */
    suspend fun driverPrint(uri: Uri, mime: String, oneCopy: Boolean): String {
        if (ip.isBlank()) throw IOException("adresse IP manquante")
        val prep = opts.quality == Quality.Draft || opts.nUp > 1 || !opts.color
        val r = if (oneCopy) "" else range
        val (u, mm, rest) = if (prep) {
            val f = withContext(Dispatchers.IO) { PrintPrep.prepare(ctx, uri, mime, opts, r) }.first
            Triple(Uri.fromFile(f), "application/pdf", "")
        } else Triple(uri, mime, r)
        val dpi = when (opts.quality) { Quality.Draft -> 150; Quality.Standard -> 200; Quality.High -> 300 }
        val job = if (oneCopy) Gutenprint.Job(orient = opts.orient)
            else Gutenprint.Job(copies, collate, rest, opts.orient, reverse)
        val res = Gutenprint.print(ctx, ip, u, mm, drv.selFor(opts.color), opts.borderless, opts.fill, dpi, job = job) { msg = it }
        markPrint(ctx)
        return res
    }

    /** Ouvre le fichier (préparé : format, brouillon, pages par feuille) dans l'appli Canon PRINT. */
    suspend fun toCanonPrint(uri: Uri, mime: String): String {
        val (u, mm) = if (PrintPrep.needsPrep(mime, opts) || range.isNotBlank()) {
            val f = withContext(Dispatchers.IO) { PrintPrep.prepare(ctx, uri, mime, opts, range) }.first
            androidx.core.content.FileProvider.getUriForFile(ctx, "be.canonhub.files", f) to "application/pdf"
        } else uri to mime
        val i = Intent(Intent.ACTION_SEND).setType(mm).putExtra(Intent.EXTRA_STREAM, u)
            .setPackage(CANON_PRINT).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            ctx.startActivity(i); markPrint(ctx)
            "Ouvert dans Canon PRINT : type de papier et qualité s'y règlent."
        } catch (e: android.content.ActivityNotFoundException) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$CANON_PRINT")))
            "Canon PRINT n'est pas installée : page du Play Store ouverte."
        }
    }

    suspend fun output(uri: Uri, mime: String, oneCopy: Boolean = false): String = when (route) {
        Route.Driver -> try { driverPrint(uri, mime, oneCopy) } catch (e: Exception) {
            "Pilote intégré : échec (${e.message}). " + androidPrintOpts(ctx, uri, mime, opts)
        }
        Route.Ipp -> try { directPrint(ctx, ip, uri, mime, if (oneCopy) 1 else copies, opts.color) } catch (e: Exception) {
            "Impression directe refusée (${e.message}). " + androidPrintOpts(ctx, uri, mime, opts)
        }
        Route.CanonApp -> toCanonPrint(uri, mime)
        Route.Android -> androidPrintOpts(ctx, uri, mime, opts)
    }

    fun go(uri: Uri) {
        val mime = ctx.contentResolver.getType(uri) ?: info?.mime ?: "application/octet-stream"
        scope.launch {
            busy = true; msg = "Préparation…"
            msg = try {
                if (duplex && mime == "application/pdf" && route != Route.CanonApp) {
                    val (r, v) = withContext(Dispatchers.IO) {
                        Images.splitPdf(ctx, uri, true, false) to Images.splitPdf(ctx, uri, false, versoRev)
                    }
                    if (r == null) throw IOException("PDF illisible")
                    val s = output(Uri.fromFile(r), "application/pdf", oneCopy = true)
                    verso = v
                    if (v == null) "$s Une seule page : pas de verso."
                    else "$s Recto imprimé ? Retournez la pile, remettez-la dans le bac, puis « Imprimer le verso »."
                } else output(uri, mime)
            } catch (e: Exception) { "Échec : ${e.message}" }
            busy = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { file = uri; verso = null; msg = "" }
    }
    fun pick() = picker.launch(arrayOf("application/pdf", "image/*"))

    // Planche photos : format de case en cm (largeur × hauteur, portrait).
    val sheetSizes = listOf("Identité 3,5×4,5" to (3.5f to 4.5f), "5×5" to (5f to 5f), "6×9" to (6f to 9f), "9×13" to (8.9f to 12.7f), "10×15" to (10.2f to 15.2f))
    var sheet by remember { mutableIntStateOf(0) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true; msg = "Préparation de la planche…"
            msg = try {
                val (w, h) = sheetSizes[sheet].second
                val bmp = withContext(Dispatchers.IO) {
                    val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("fichier illisible")
                    val b = Images.decode(raw, 3000) ?: throw IOException("image illisible")
                    Images.photoSheet(b, w, h)
                }
                if (route == Route.Driver) driverSheet(ctx, ip, drv, bmp, opts.color, copies, 300) { msg = it }
                else { printBitmap(ctx, "planche", bmp); "Planche prête. Au premier essai, mesurez une photo à la règle." }
            } catch (e: Exception) { "Échec : ${e.message}" }
            busy = false
        }
    }

    val pages = info?.pages ?: 0
    val multi = route == Route.Driver || route == Route.Ipp
    ActionScreen(m, bottom = {
        StatusMsg(msg, busy)
        verso?.let { v ->
            BigButton("Imprimer le verso", Icons.Filled.FlipToBack, Brand.Copy, busy = busy) {
                scope.launch {
                    busy = true
                    msg = try { output(Uri.fromFile(v), "application/pdf", oneCopy = true) } catch (e: Exception) { "Échec : ${e.message}" }
                    verso = null; busy = false
                }
            }
        }
        val f = file
        BigButton(
            when {
                f == null -> "Choisir un document"
                route == Route.CanonApp -> "Ouvrir dans Canon PRINT"
                multi && copies > 1 -> "Imprimer · $copies exemplaires"
                else -> "Imprimer"
            },
            if (f == null) Icons.Filled.UploadFile else Icons.Filled.Print, Brand.Print, busy = busy,
        ) { if (f == null) pick() else go(f) }
    }) {
        Hero(
            "Imprimer",
            "${route.label} · ${if (route == Route.Driver) drv.text("PageSize") ?: "A4" else opts.paper.label} · " +
                (if (opts.color) "Couleur" else "N&B"),
            Icons.Filled.Print, Brand.Print,
        )
        DocCard(info, file != null, ::pick) { file = null; verso = null; msg = "" }

        Section("Envoyer avec", Icons.Filled.Tune, sub = route.sub) {
            ChoiceGrid(
                Route.entries, route, 2, { it.label }, { it.sub }, ::routeIcon,
                enabled = { it != Route.Driver || Gutenprint.available },
            ) { setRoute(it) }
            when (route) {
                Route.Driver -> if (!Gutenprint.available) Hint("Pilote absent de cette version (étape CI « Gutenprint »).")
                    else Hint("Sans service Canon : papier, qualité, copies et pages envoyés à l'imprimante (BJNP). G3000 expérimentale.")
                Route.Android -> Hint("Fenêtre Android : format, orientation et couleur transmis ; type de papier et qualité à confirmer dans « Plus d'options ».")
                Route.CanonApp -> Hint("Le document préparé (format, brouillon, pages par feuille, plage) s'ouvre dans l'appli Canon.")
                Route.Ipp -> Hint("Envoi direct sans fenêtre, si l'imprimante accepte IPP. Sinon, repli sur la fenêtre Android.")
            }
        }

        if (route != Route.Ipp) Section("Papier", Icons.Filled.Layers, sub = if (route == Route.Driver) drv.text("MediaType") else opts.type.label) {
            if (route == Route.Driver) {
                if (drv.err.isNotEmpty()) Text(drv.err, color = MaterialTheme.colorScheme.error)
                DriverPicker(drv, "PageSize", "Format", Icons.Filled.CropFree)
                DriverPicker(drv, "MediaType", "Type de papier", Icons.Filled.Style)
                DriverPicker(drv, "InputSlot", "Alimentation", Icons.Filled.Input)
                DriverPicker(drv, "Resolution", "Résolution de l'imprimante", Icons.Filled.HighQuality)
            } else {
                Picker("Format", Icons.Filled.CropFree, Paper.entries.map { it.name to it.label }, opts.paper.name) { setOpts(opts.copy(paper = Paper.valueOf(it))) }
                Picker("Type de papier", Icons.Filled.Style, MediaType.entries.map { it.name to it.label }, opts.type.name) { setOpts(opts.copy(type = MediaType.valueOf(it))) }
                Hint(opts.reminder())
            }
        }

        if (route != Route.Ipp) Section("Mise en page", Icons.Filled.ViewQuilt) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val land = when (opts.orient) { Orient.Landscape -> true; Orient.Portrait -> false; Orient.Auto -> info?.wide == true }
                SheetGlyph(land, opts.nUp, opts.color, Modifier.size(88.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("Orientation")
                    Segmented(Orient.entries, opts.orient, { it.label }, ::orientIcon) { setOpts(opts.copy(orient = it)) }
                }
            }
            Label("Pages par feuille")
            Segmented(listOf(1, 2, 4), opts.nUp, { "$it par feuille" }) { setOpts(opts.copy(nUp = it)) }
            Switcher("Sans bordure", opts.borderless, "Photo jusqu'au bord", Icons.Filled.CropFree) { setOpts(opts.copy(borderless = it)) }
            Switcher("Remplir la page", opts.fill, "Photo recadrée pour couvrir la case", Icons.Filled.Crop) { setOpts(opts.copy(fill = it)) }
        }

        Section("Qualité et couleur", Icons.Filled.Palette) {
            if (route != Route.Ipp) {
                Segmented(Quality.entries, opts.quality, { it.label }, ::qualityIcon) { setOpts(opts.copy(quality = it)) }
                if (opts.quality == Quality.Draft) Hint("Brouillon : couleurs éclaircies, environ 40 % d'encre en moins.")
            }
            Segmented(listOf(true, false), opts.color, { if (it) "Couleur" else "Noir et blanc" },
                { if (it) Icons.Filled.Palette else Icons.Filled.FilterBAndW }) { setOpts(opts.copy(color = it)) }
        }

        if (multi) Section("Copies et pages", Icons.Filled.ContentCopy, sub = if (pages > 0) "Document : $pages page${if (pages > 1) "s" else ""}" else null) {
            Counter("Exemplaires", copies, 1..99, Icons.Filled.Collections) { copies = it }
            if (route == Route.Driver) {
                if (copies > 1) Switcher("Assembler", collate, if (collate) "1-2-3, 1-2-3" else "1-1, 2-2, 3-3", Icons.Filled.Layers) { collate = it }
                OutlinedTextField(
                    range, { range = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Pages (vide = toutes)") }, placeholder = { Text("ex. 1-3, 5") },
                    leadingIcon = { Icon(Icons.Filled.FormatListNumbered, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                Switcher("Ordre inverse", reverse, "Dernière page d'abord", Icons.Filled.SwapVert) { reverse = it }
            }
        }

        if (route != Route.CanonApp) Fold("Recto-verso manuel", Icons.Filled.FlipToBack, sub = if (duplex) "Activé · impaires puis paires" else "PDF : impaires puis paires") {
            Switcher("Activer", duplex) { duplex = it }
            if (duplex) {
                Switcher("Verso en ordre inverse", versoRev) { versoRev = it }
                Hint("Recto-verso : une copie, toutes les pages. Faites un essai sur 2 feuilles pour trouver le bon sens.")
            }
        }

        Fold("Planche photos", Icons.Filled.PhotoLibrary, sub = "Une photo répétée sur une feuille A4", accent = Brand.Docs[0]) {
            Chips(sheetSizes.indices.toList(), sheet, { sheetSizes[it].first }) { sheet = it }
            OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { photoPicker.launch(arrayOf("image/*")) }) {
                Text("Choisir la photo")
            }
            Hint("Photo recadrée au centre, traits de coupe gris. Papier photo A4 conseillé.")
        }

        Fold("Autres options", Icons.Filled.MoreHoriz) {
            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Service d'impression Android")
            }
            Hint("Astuce : depuis Galerie ou Fichiers, « Partager » puis « Imprimer avec la Canon ».")
        }
    }
}
