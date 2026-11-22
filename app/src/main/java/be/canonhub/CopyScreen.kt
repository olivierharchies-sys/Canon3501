@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package be.canonhub

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterBAndW
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewQuilt
import androidx.compose.material.icons.filled.Article
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

fun areaIcon(a: Area): ImageVector = when (a) {
    Area.A4 -> Icons.Filled.Description
    Area.A5 -> Icons.Filled.Article
    Area.Photo -> Icons.Filled.Photo
    Area.Card -> Icons.Filled.CreditCard
}

fun areaLabel(a: Area): String = when (a) {
    Area.A4 -> "Document A4"
    Area.A5 -> "Document A5"
    Area.Photo -> "Photo 10×15"
    Area.Card -> "Carte ID"
}

fun areaSub(a: Area): String = when (a) {
    Area.A4 -> "Courrier, facture"
    Area.A5 -> "Demi-feuille"
    Area.Photo -> "Tirage photo"
    Area.Card -> "Recto + verso, 1 feuille"
}

/** Étape de la copie de carte (recto puis verso). */
@Composable
fun CardSteps(frontDone: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        listOf("Recto" to frontDone, "Verso" to false).forEachIndexed { i, (t, done) ->
            val active = if (i == 0) !frontDone else frontDone
            val c = when {
                done -> Color(0xFF12B76A)
                active -> Brand.Copy[1]
                else -> MaterialTheme.colorScheme.outline
            }
            Box(Modifier.size(30.dp).background(c, CircleShape), contentAlignment = Alignment.Center) {
                if (done) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                else Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Text(t, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
            if (i == 0) {
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(32.dp, 2.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Spacer(Modifier.width(10.dp))
            }
        }
    }
}

@Composable
fun CopyScreen(m: Modifier, prefs: PrinterPrefs, ip: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var area by remember { mutableStateOf(Area.A4) }
    var dpi by remember { mutableIntStateOf(300) }
    var color by remember { mutableStateOf(true) }
    var copies by remember { mutableIntStateOf(1) }
    var quality by remember { mutableStateOf(prefs.copyQuality) }
    var route by remember { mutableStateOf(prefs.copyRoute.let { if (it == Route.Driver && !Gutenprint.available) Route.Android else it }) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var front by remember { mutableStateOf<Bitmap?>(null) }
    var out by remember { mutableStateOf(OutFmt()) }
    var filt by remember { mutableStateOf(Filter.None) }
    var batch by remember { mutableStateOf(prefs.copyBatch) }
    var layout by remember { mutableStateOf(prefs.copyLayout) }
    var straight by remember { mutableStateOf(prefs.bool("copyStraight", false)) }
    var corr by remember { mutableStateOf(Fix()) }
    var preview by remember { mutableStateOf(prefs.bool("copyPreview", false)) }
    var cropIdx by remember { mutableStateOf<Int?>(null) }
    var queue by remember { mutableStateOf<List<CopyItem>>(emptyList()) }
    var sel by remember { mutableIntStateOf(0) }
    var auto by remember { mutableStateOf(prefs.bool("copyAuto", false)) }
    var keep by remember { mutableStateOf(prefs.bool("copyKeep", true)) }
    val drv = rememberDriver(prefs, route == Route.Driver)
    val gpDpi = when (quality) { Quality.Draft -> 150; Quality.Standard -> 200; Quality.High -> 300 }

    /**
     * Marges à compenser pour garder la taille d'origine hors pilote intégré (le service d'impression
     * réduit la feuille à sa zone imprimable). Null si inutile ou déjà corrigé par l'étalonnage.
     */
    suspend fun margins(): FloatArray? = if (!keep || route == Route.Driver || Calib.state.printOn) null
    else runCatching { Gutenprint.margins(ctx, drv.sel) }.getOrElse { floatArrayOf(3.4f, 3f, 3.4f, 5f) }

    /** Une feuille déjà mise en page : pilote intégré, IPP direct ou fenêtre Android. */
    suspend fun printSheetRaw(bmp: Bitmap): String = when (route) {
        Route.Driver -> try {
            driverSheet(ctx, ip, drv, bmp, color, copies, gpDpi, truePage = keep) { msg = it }
        } catch (e: Exception) {
            printBitmap(ctx, "copie", bmp)
            "Pilote intégré : échec (${e.message}). Fenêtre Android ouverte."
        }
        Route.Ipp -> try {
            val data = withContext(Dispatchers.Default) { Images.jpeg(Calib.fixA4(bmp)) }
            val id = Ipp.printDoc(ip, data, "image/jpeg", "Copie", copies, color)
            markPrint(ctx)
            "Copie envoyée (tâche $id)."
        } catch (e: Exception) {
            printBitmap(ctx, "copie", bmp)
            "Impression directe refusée (${e.message}) : fenêtre Android ouverte."
        }
        else -> {
            printBitmap(ctx, "copie", bmp)
            "Fenêtre d'impression ouverte : réglez le nombre de copies."
        }
    }

    /** Met en page et imprime une liste de documents. */
    suspend fun printAll(items: List<CopyItem>, lay: PageLayout = layout): String {
        val f = File(ctx.cacheDir, "copie.pdf")
        var first: Bitmap? = null
        val fix = route != Route.Driver
        val mg = margins()
        val n = withContext(Dispatchers.Default) {
            val doc = android.graphics.pdf.PdfDocument()
            var k = 0
            CopyLayout.render(items, lay, out.target(), filt, straight, corr, keep) { b0 ->
                val b = mg?.let { CopyLayout.compensate(b0, it) } ?: b0
                k++
                if (k == 1) first = b
                CopyLayout.addPdfPage(doc, b, k, fix)
                if (k > 1) b.recycle()
            }
            if (k > 1) f.outputStream().use { doc.writeTo(it) }
            doc.close()
            k
        }
        if (n == 0) throw IOException("rien à imprimer")
        PrinterPrefs(ctx).bump("copies", items.size)
        val docs = items.size
        if (n == 1) {
            val r = printSheetRaw(first!!)
            return if (docs > 1) "$docs documents sur 1 feuille. $r" else r
        }
        val r = if (route == Route.Driver) try {
            driverFile(ctx, ip, drv, Uri.fromFile(f), "application/pdf", color, copies, gpDpi, truePage = keep) { msg = it }
        } catch (e: Exception) {
            androidPrint(ctx, Uri.fromFile(f), "application/pdf")
            "Pilote intégré : échec (${e.message}). Fenêtre Android ouverte."
        } else {
            androidPrint(ctx, Uri.fromFile(f), "application/pdf")
            "Fenêtre d'impression ouverte."
        }
        return "$docs document(s) sur $n feuilles. $r"
    }

    suspend fun add(items: List<CopyItem>, lay: PageLayout = layout): String = when {
        batch -> {
            queue = queue + items; sel = queue.size - 1
            "Ajouté au lot (${queue.size}). Posez le document suivant."
        }
        preview -> {
            queue = items; sel = 0
            "Aperçu prêt : choisissez la zone si besoin, puis « Imprimer »."
        }
        else -> printAll(items, lay)
    }

    suspend fun add(item: CopyItem): String = add(listOf(item))

    /**
     * Détection automatique : prénumérisation rapide (75 dpi), choix de la plus petite zone
     * qui contient les documents, numérisation, puis un document par image détectée.
     */
    suspend fun autoCopy(): String {
        msg = "Prénumérisation…"
        val pre = ScanService.keep(ctx) { Escl.scan(ip, 75, true, Area.A4) }
        val ext = withContext(Dispatchers.Default) {
            Images.decode(pre, 1000)?.let { b -> Images.docExtents(b, 75f * b.width / (Area.A4.w * 75 / 300).coerceAtLeast(1)) }
        } ?: throw IOException("prénumérisation illisible")
        if (ext.isEmpty()) {
            msg = "Page entière détectée. Numérisation…"
            return add(CopyItem(Page(ScanService.keep(ctx) { Escl.scan(ip, dpi, color, Area.A4) }, dpi)))
        }
        // Plus petite zone (depuis le coin repère) qui contient tout, avec 3 mm de marge.
        val r = ext.maxOf { it.right } + 3f; val btm = ext.maxOf { it.bottom } + 3f
        val zone = listOf(Area.Card, Area.Photo, Area.A5, Area.A4)
            .firstOrNull { it.w / 300f * 25.4f >= r && it.h / 300f * 25.4f >= btm } ?: Area.A4
        msg = "${ext.size} document(s) détecté(s), zone ${zone.label}. Numérisation…"
        val b = ScanService.keep(ctx) { Escl.scan(ip, dpi, color, zone) }
        val docs = withContext(Dispatchers.Default) {
            val img = Images.decode(b, 4000) ?: throw IOException("image illisible")
            val d = (dpi * img.width / (zone.w * dpi / 300).coerceAtLeast(1)).coerceAtLeast(1)
            Images.documents(img).map { Page(Images.jpeg(it, 92), d) }
        }
        if (docs.isEmpty()) return add(CopyItem(Page(b, dpi)))
        msg = "Mise en page…"
        // Plusieurs documents : regroupés à taille réelle ; un seul : une feuille.
        return add(docs.map { CopyItem(it) }, if (docs.size > 1) PageLayout.Pack else PageLayout.One)
    }

    fun scanNow() {
        scope.launch {
            busy = true; msg = "Numérisation en cours…"
            msg = try {
                if (auto && area != Area.Card) { msg = autoCopy(); busy = false; return@launch }
                val b = ScanService.keep(ctx) { Escl.scan(ip, dpi, color, area) }
                val f = front
                if (area != Area.Card) {
                    msg = "Mise en page…"
                    add(CopyItem(Page(b, dpi)))
                } else if (f == null) {
                    front = withContext(Dispatchers.Default) { Images.decode(b, 3508) } ?: throw IOException("image illisible")
                    "Recto lu. Retournez la carte au même endroit, puis « Lire le verso »."
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
    }

    ActionScreen(m, bottom = {
        StatusMsg(msg, busy)
        if ((batch || preview) && queue.isNotEmpty()) {
            BigButton(if (batch) "Imprimer le lot (${queue.size})" else "Imprimer", Icons.Filled.Print, Brand.Print, busy = busy) {
                scope.launch {
                    busy = true; msg = "Mise en page…"
                    msg = try {
                        val r = printAll(queue)
                        queue = emptyList(); sel = 0
                        r
                    } catch (e: Exception) { "Échec : ${e.message}" }
                    busy = false
                }
            }
        }
        BigButton(
            when {
                area == Area.Card && front == null -> "Lire le recto"
                area == Area.Card -> "Lire le verso"
                batch -> "Numériser et ajouter (${queue.size + 1})"
                copies > 1 && route != Route.Android -> "Copier · $copies exemplaires"
                else -> "Copier"
            },
            Icons.Filled.ContentCopy, Brand.Copy, busy = busy,
        ) { scanNow() }
    }) {
        Hero(
            "Copier",
            "${areaLabel(area)} · ${if (color) "Couleur" else "N&B"} · ${route.label}",
            Icons.Filled.ContentCopy, Brand.Copy,
        )

        Section("Que copiez-vous ?", Icons.Filled.Layers, accent = Brand.Copy[1]) {
            Switcher("Détection automatique", auto, "Prénumérisation rapide : taille et nombre de documents détectés, une image par document.") {
                auto = it; prefs.setBool("copyAuto", it)
            }
            ChoiceGrid(Area.entries, area, 2, ::areaLabel, ::areaSub, ::areaIcon, accent = Brand.Copy[1]) { area = it; front = null }
            if (auto && area != Area.Card) Hint("Format choisi automatiquement (sauf carte recto/verso).")
            if (area == Area.Card) CardSteps(front != null)
            else if (area != Area.A4) Hint("Placez le document contre le coin repère de la vitre.")
        }

        Section("Réglages", Icons.Filled.Tune, accent = Brand.Copy[1]) {
            if (route != Route.Android) Counter("Exemplaires", copies, 1..99, Icons.Filled.Collections) { copies = it }
            else Hint("Nombre d'exemplaires : dans la fenêtre d'impression.")
            Segmented(listOf(true, false), color, { if (it) "Couleur" else "Noir et blanc" },
                { if (it) Icons.Filled.Palette else Icons.Filled.FilterBAndW }) { color = it }
            if (route == Route.Driver) {
                Segmented(Quality.entries, quality, { it.label }, ::qualityIcon) { quality = it; prefs.copyQuality = it }
                if (drv.err.isNotEmpty()) Text(drv.err, color = MaterialTheme.colorScheme.error)
                DriverPicker(drv, "MediaType", "Type de papier", Icons.Filled.Style)
                DriverPicker(drv, "InputSlot", "Alimentation", Icons.Filled.Input)
            }
        }

        Section("Mode", Icons.Filled.Inventory2, accent = Brand.Copy[1]) {
            Segmented(listOf(false, true), batch, { if (it) "Par lot" else "Immédiate" }) { batch = it; prefs.copyBatch = it }
            Hint(if (batch) "Numérisez plusieurs documents, puis imprimez l'ensemble." else "Chaque document est imprimé juste après le scan.")
            Switcher("Aperçu avant impression", preview, "Voir le scan, choisir une zone et sa taille, puis imprimer.") {
                preview = it; prefs.setBool("copyPreview", it)
            }
            if ((batch || preview) && queue.isNotEmpty()) {
                val item = queue[sel.coerceIn(0, queue.size - 1)]
                val img by androidx.compose.runtime.produceState<Bitmap?>(null, item) {
                    value = withContext(Dispatchers.Default) {
                        Images.decode(item.page.bytes, 1000)?.let { Images.cropArea(Images.rotate(it, item.page.rot), item.page.crop) }
                    }
                }
                img?.let {
                    androidx.compose.foundation.Image(
                        it.asImageBitmap(), "Aperçu",
                        Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
                if (!item.sheet) OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { cropIdx = sel.coerceIn(0, queue.size - 1) }) {
                    Text(if (item.page.crop != null) "Modifier la zone et la taille" else "Choisir une zone et sa taille")
                }
                if (batch) PageStrip(queue.map { it.page }, sel.coerceIn(0, queue.size - 1)) { sel = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                        queue = queue.toMutableList().also { it.removeAt(sel.coerceIn(0, queue.size - 1)) }
                        sel = (sel - 1).coerceAtLeast(0)
                    }) { Text("Retirer") }
                    OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { queue = emptyList(); sel = 0 }) { Text("Vider") }
                }
            }
        }

        if (area != Area.Card) Fold("Mise en page et format", Icons.Filled.ViewQuilt, sub = layout.label, accent = Brand.Copy[1]) {
            Switcher(
                "Taille d'origine (100 %)", keep,
                if (keep) "Aucune réduction. Les bords à moins de 3 à 5 mm du papier ne s'impriment pas."
                else "Ajustée à la zone imprimable : réduite d'environ 3 %, rien n'est coupé.",
            ) { keep = it; prefs.setBool("copyKeep", it) }
            Chips(PageLayout.entries, layout, { it.label }) { layout = it; prefs.copyLayout = it }
            Hint(
                when (layout) {
                    PageLayout.One -> "Une feuille par document."
                    PageLayout.Pack -> "Documents recadrés, à taille réelle, regroupés sur le moins de feuilles (tickets, cartes)."
                    PageLayout.Two -> "Deux documents réduits par feuille A4 (A5 chacun)."
                },
            )
            FormatPicker(out, { out = it })
            if (out.fmt != Fmt.Original) Hint("Format imposé : une feuille par document, redressé et recadré.")
        }

        Fold("Retouche", Icons.Filled.AutoFixHigh, sub = filt.label + (if (corr.mode != Enhance.Mode.None) " · ${corr.mode.label}" else "") + if (straight) " · redressé" else "", accent = Brand.Copy[1]) {
            Switcher("Redresser automatiquement", straight, "Document posé de travers remis droit, taille réelle gardée.") {
                straight = it; prefs.setBool("copyStraight", it)
            }
            FixPicker(corr) { corr = it }
            FilterChips(filt) { filt = it }
            if (filt == Filter.Doc || filt == Filter.BW) Hint("Fond blanchi : économise l'encre.")
        }

        cropIdx?.let { ci ->
            queue.getOrNull(ci)?.let { it2 ->
                val p = it2.page
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(p.bytes, 0, p.bytes.size, o)
                val cm = 2.54f / p.dpi
                CropDialog(
                    p.crop, o.outWidth * cm to o.outHeight * cm, out, { out = it },
                    load = { Images.decode(p.bytes, 1400)?.let { Images.rotate(it, p.rot) } },
                    onDone = { c -> queue = queue.toMutableList().also { l -> l[ci] = CopyItem(p.withCrop(c), it2.sheet) }; cropIdx = null },
                    onCancel = { cropIdx = null },
                )
            }
        }

        Fold("Numérisation et envoi", Icons.Filled.Print, sub = "$dpi dpi · ${route.label}", accent = Brand.Copy[1]) {
            Label("Résolution du scan")
            Segmented(listOf(150, 300, 600), dpi, { "$it dpi" }) { dpi = it }
            Label("Imprimer avec")
            ChoiceGrid(
                listOf(Route.Driver, Route.Android, Route.Ipp), route, 3, { it.label }, { null }, ::routeIcon,
                accent = Brand.Copy[1], enabled = { it != Route.Driver || Gutenprint.available },
            ) { route = it; prefs.copyRoute = it }
            Hint(
                when (route) {
                    Route.Driver -> "Pilote intégré : copie à taille réelle, sans fenêtre, papier et qualité réglés ici."
                    Route.Ipp -> "IPP direct : si l'imprimante l'accepte, sinon fenêtre Android."
                    else -> "Fenêtre Android : réglages dans le service Canon."
                },
            )
        }
    }
}
