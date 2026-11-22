package be.canonhub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.roundToInt

/** Réglage fin du pilote intégré : couleurs, netteté, position, vitesse, diagnostic. */
@Composable
fun GpTuneScreen(m: Modifier, prefs: PrinterPrefs, ip: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var t by remember { mutableStateOf(Gutenprint.tune(ctx)) }
    var imgTypes by remember { mutableStateOf<List<Gutenprint.Choice>>(emptyList()) }
    var dithers by remember { mutableStateOf<List<Gutenprint.Choice>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    KeepAwake(busy)
    var msg by remember { mutableStateOf("") }
    var askReset by remember { mutableStateOf(false) }
    var proposal by remember { mutableStateOf<Pair<Gutenprint.Tune, List<String>>?>(null) }
    fun set(n: Gutenprint.Tune) { t = n; Gutenprint.saveTune(ctx, n) }
    var wizard by remember { mutableStateOf(false) }
    if (wizard) CalibWizard(ip, prefs) { wizard = false; t = Gutenprint.tune(ctx) }

    LaunchedEffect(Unit) {
        if (Gutenprint.available) runCatching {
            imgTypes = Gutenprint.choices(ctx, "ImageType", prefs.gpSel)
            dithers = Gutenprint.choices(ctx, "DitherAlgorithm", prefs.gpSel)
        }.onFailure { msg = "Pilote : ${it.message}" }
    }

    if (askReset) {
        AlertDialog(
            onDismissRequest = { askReset = false },
            title = { Text("Revenir aux valeurs d'origine ?") },
            text = { Text("Tous les réglages du pilote seront remis à zéro.") },
            confirmButton = { TextButton(onClick = { set(Gutenprint.Tune(diag = t.diag)); proposal = null; askReset = false; msg = "Réglages d'origine rétablis." }) { Text("Rétablir") } },
            dismissButton = { TextButton(onClick = { askReset = false }) { Text("Annuler") } },
        )
    }

    Column(m.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!Gutenprint.available) {
            Hint("Le pilote Gutenprint n'a pas été compilé dans cet APK.")
            return@Column
        }

        Section("Assistant d'étalonnage", Icons.Filled.AutoFixHigh, sub = "Imprimante, scanner et copie, étape par étape") {
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { wizard = true }) { Text("Lancer l'assistant complet") }
            Hint("Guide détaillé : Réglages > Étalonnage.")
        }

        Section("Page d'essai", Icons.Filled.Print, sub = "Mire en taille réelle, avec les réglages ci-dessous") {
            Button(enabled = !busy && ip.isNotBlank(), onClick = {
                busy = true; msg = ""
                scope.launch {
                    msg = try { Gutenprint.testPage(ctx, ip, prefs.gpSel, 200) { msg = it } } catch (e: Exception) { "Échec : ${e.message}" }
                    busy = false
                }
            }) { Text("Imprimer la page d'essai") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
            Hint("Papier A4 ordinaire. Type de papier et qualité : ceux de l'écran Imprimer.")
        }

        Section("Paramétrage automatique", Icons.Filled.AutoFixHigh, sub = "Mire imprimée puis relue au scanner") {
            if (!t.isDefault()) TextButton(onClick = { askReset = true }) { Text("Repartir des valeurs d'origine") }
            Hint("1. Imprimez la page d'essai (taille réelle). 2. Posez-la face contre la vitre, haut de la mire au coin repère. 3. Numérisez : l'appli calcule échelle, position, gamma, densité et équilibre des couleurs. Répétez une fois pour affiner.")
            Button(enabled = !busy && ip.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                busy = true; msg = "Numérisation de la mire…"; proposal = null
                scope.launch {
                    msg = try {
                        val keep = Calib.state
                        // Scan brut, sans la balance des blancs de l'étalonnage.
                        Calib.save(ctx, keep.copy(scanOn = false))
                        val jpg = try { ScanService.keep(ctx) { Escl.scan(ip, 300, true, Area.A4) } } finally { Calib.save(ctx, keep) }
                        msg = "Analyse…"
                        val r = withContext(Dispatchers.Default) {
                            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.size, o)
                            val b = Images.decode(jpg, 4000) ?: throw IOException("image illisible")
                            Calib.analyze(b, 300f * b.width / o.outWidth.coerceAtLeast(1))
                        }
                        proposal = Gutenprint.autoTune(t, r)
                        "Analyse terminée."
                    } catch (e: Exception) { "Échec : ${e.message}" }
                    busy = false
                }
            }) { Text("Numériser et analyser la page d'essai") }
            proposal?.let { (n, notes) ->
                notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Proposé : échelle ${"%.1f".format(n.scale * 100)} %, décalage ${"%+.1f".format(n.shiftX)} × ${"%+.1f".format(n.shiftY)} mm, " +
                        "gamma ${"%.2f".format(n.gamma)}, densité ${"%.2f".format(n.density)}, " +
                        "C ${"%.2f".format(n.cyan)} M ${"%.2f".format(n.magenta)} J ${"%.2f".format(n.yellow)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { set(n); proposal = null; msg = "Réglages appliqués. Réimprimez la page d'essai pour vérifier." }) { Text("Appliquer") }
                    OutlinedButton(onClick = { proposal = null }) { Text("Ignorer") }
                }
            }
        }

        Section("Couleurs", Icons.Filled.Palette, sub = "1,00 = neutre", trailing = { TextButton(enabled = t != t.resetColors(), onClick = { set(t.resetColors()); msg = "Couleurs d'origine rétablies." }) { Text("Réinitialiser") } }) {
            Knob("Luminosité", t.brightness, 0f..2f) { set(t.copy(brightness = it)) }
            Knob("Contraste", t.contrast, 0f..2f) { set(t.copy(contrast = it)) }
            Knob("Gamma", t.gamma, 0.5f..2f) { set(t.copy(gamma = it)) }
            Knob("Densité d'encre", t.density, 0.5f..1.5f) { set(t.copy(density = it)) }
            Knob("Saturation", t.saturation, 0f..2f) { set(t.copy(saturation = it)) }
            Knob("Cyan", t.cyan, 0.5f..1.5f) { set(t.copy(cyan = it)) }
            Knob("Magenta", t.magenta, 0.5f..1.5f) { set(t.copy(magenta = it)) }
            Knob("Jaune", t.yellow, 0.5f..1.5f) { set(t.copy(yellow = it)) }
            Hint("Trop foncé : baisser densité ou gamma. Dominante : baisser la couleur en trop.")
        }

        Section("Netteté", Icons.Filled.Tune, trailing = { TextButton(enabled = t != t.resetSharp(), onClick = { set(t.resetSharp()); msg = "Netteté d'origine rétablie." }) { Text("Réinitialiser") } }) {
            OptChips("Résolution de rendu", listOf(0, 150, 200, 300, 400), t.dpi,
                { if (it == 0) "Auto" else "$it dpi" }) { set(t.copy(dpi = it)) }
            Hint("Auto : 150/200/300 dpi selon la qualité. Plus haut = plus net, plus lent.")
            if (imgTypes.isNotEmpty()) {
                OptChips("Type de contenu", listOf("") + imgTypes.map { it.name }, t.imageType,
                    { v -> if (v.isEmpty()) "Défaut" else imgTypes.first { it.name == v }.text }) { set(t.copy(imageType = it)) }
            }
            if (dithers.isNotEmpty()) {
                OptChips("Tramage", listOf("") + dithers.map { it.name }, t.dither,
                    { v -> if (v.isEmpty()) "Défaut" else dithers.first { it.name == v }.text }) { set(t.copy(dither = it)) }
            }
        }

        Section("Position", Icons.Filled.Build, sub = "Décalage de l'image sur la feuille", trailing = { TextButton(enabled = t != t.resetPos(), onClick = { set(t.resetPos()); msg = "Position d'origine rétablie." }) { Text("Réinitialiser") } }) {
            Knob("Horizontal (mm, + = droite)", t.shiftX, -10f..10f, step = 0.5f) { set(t.copy(shiftX = it)) }
            Knob("Vertical (mm, + = bas)", t.shiftY, -10f..10f, step = 0.5f) { set(t.copy(shiftY = it)) }
            Knob("Échelle (%)", t.scale * 100, 90f..110f, step = 0.5f) { set(t.copy(scale = it / 100)) }
        }

        Section("Dépannage", Icons.Filled.Settings) {
            Toggle("Afficher le diagnostic après envoi", t.diag) { set(t.copy(diag = it)) }
            Hint("État de l'imprimante et début du flux, à copier en cas de problème.")
            OutlinedButton(enabled = !t.isDefault(), onClick = { askReset = true }) { Text("Tout réinitialiser") }
            Hint(if (t.isDefault()) "Réglages d'origine en cours." else "Réglages personnalisés en cours.")
        }
    }
}

/** Curseur avec valeur et boutons −/+ pour un réglage fin. */
@Composable
private fun Knob(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float = 0.05f, on: (Float) -> Unit) {
    fun snap(v: Float) = ((v / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text("%.2f".format(value), style = MaterialTheme.typography.labelLarge)
        }
        Row {
            TextButton(onClick = { on(snap(value - step)) }) { Text("−") }
            Slider(value = value, onValueChange = { on(snap(it)) }, valueRange = range, modifier = Modifier.weight(1f))
            TextButton(onClick = { on(snap(value + step)) }) { Text("+") }
        }
    }
}
