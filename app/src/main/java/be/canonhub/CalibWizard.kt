package be.canonhub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Étapes de l'assistant, dans l'ordre : imprimante saine → scanner juste → pilote → copie. */
private enum class W(val title: String, val optional: Boolean, val help: String) {
    Prep("Préparation", false,
        "Chargez au moins 5 feuilles A4 blanches ordinaires (80 g), toutes du même paquet. Nettoyez la vitre et le dessous du capot. " +
            "Imprimante allumée, réservoirs au-dessus du trait minimum. L'assistant vide d'abord la file d'impression."),
    Nozzle("Test des buses", true,
        "L'assistant imprime le motif de test. Regardez-le : lignes continues et grilles complètes dans toutes les couleurs ? " +
            "Sinon, lancez un nettoyage puis refaites le test. Une buse bouchée fausse tout l'étalonnage."),
    Ref("Référence du scanner", true,
        "Avec une échelle de gris du commerce, le scanner est corrigé seul. Le réglage du pilote ne dépend alors plus du scanner. " +
            "Sans échelle, passez : la correction reste bonne pour la copie, approchée pour l'impression de fichiers."),
    Tune1("Réglage du pilote (1re passe)", false,
        "L'assistant imprime la mire par le pilote intégré, en taille réelle. Laissez sécher, posez-la face contre la vitre, " +
            "texte vers le coin repère, capot fermé. L'analyse règle échelle, position, gamma, densité et équilibre C/M/J."),
    Tune2("Réglage du pilote (2e passe)", true,
        "Même opération avec les nouveaux réglages, pour affiner. Facultative si la 1re passe a peu changé."),
    Visual("Contrôle visuel", true,
        "Comparez la dernière mire imprimée à l'écran (luminosité au maximum, lumière du jour). Corrigez une dominante ou un rendu trop sombre. " +
            "L'écran n'est pas étalonné : ne corrigez que les écarts nets."),
    Copy("Copie et scanner", false,
        "L'assistant imprime la mire avec les réglages finaux. Mesurez les lignes à la règle (facultatif), puis numérisez. " +
            "L'analyse règle le blanc du scanner, la courbe de gris et les couleurs des copies."),
    Done("Terminé", false,
        "Refaites l'assistant après un changement de papier, un remplissage d'encre, un nettoyage en profondeur ou si les copies dérivent."),
}

private enum class Ph { Idle, Run, Dry, Place, Ask }

/** Assistant d'étalonnage complet : enchaîne et applique automatiquement chaque étape. */
@Composable
fun CalibWizard(ip: String, prefs: PrinterPrefs, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(W.Prep) }
    var ph by remember { mutableStateOf(Ph.Idle) }
    var msg by remember { mutableStateOf("") }
    var dry by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var notes by remember { mutableStateOf<List<String>>(emptyList()) }
    var tuneBefore by remember { mutableStateOf(Gutenprint.tune(ctx)) }
    var mmH by remember { mutableStateOf("") }
    var mmV by remember { mutableStateOf("") }
    val log = remember { mutableStateListOf<String>() }
    val busy = ph == Ph.Run
    val ok = ip.isNotBlank() && Gutenprint.available

    fun go(s: W) { step = s; ph = Ph.Idle; msg = ""; notes = emptyList(); tick++ }
    fun next() = go(W.entries[(step.ordinal + 1).coerceAtMost(W.entries.lastIndex)])
    fun run(block: suspend () -> String, after: Ph = Ph.Ask) {
        ph = Ph.Run; msg = ""
        scope.launch {
            ph = try { msg = block(); after } catch (e: Exception) { msg = "Échec : ${e.message}"; Ph.Idle }
        }
    }

    /** Mire par le pilote, puis séchage. */
    fun printChart() = run({
        tuneBefore = Gutenprint.tune(ctx)
        Gutenprint.testPage(ctx, ip, prefs.gpSel, 300) { msg = it }
    }, Ph.Dry)

    // Actions lancées automatiquement à l'entrée de chaque étape.
    LaunchedEffect(step, tick) {
        when (step) {
            W.Prep -> run({
                if (!ok) throw Exception(if (ip.isBlank()) "adresse IP manquante" else "pilote intégré absent de cet APK")
                val q = PrintQueue.clearAll(ctx, ip, prefs.ruiPassword)
                val st = runCatching { Bjnp.readable(Bjnp.status(ip)) }.getOrElse { "illisible" }
                "File vidée.\n$q\nÉtat : $st"
            })
            W.Nozzle -> run({ "Motif envoyé (${CanonTools.run(ip, Tool.NozzleCheck, Transport.Bjnp, prefs.ruiPassword)})." })
            W.Tune1, W.Tune2, W.Copy -> printChart()
            else -> Unit
        }
    }
    // Séchage de l'encre avant numérisation.
    LaunchedEffect(ph) {
        if (ph == Ph.Dry) {
            for (s in 60 downTo 1) { dry = s; delay(1000) }
            if (ph == Ph.Dry) ph = Ph.Place
        }
    }

    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Étape ${step.ordinal + 1}/${W.entries.size} · ${step.title}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(progress = { step.ordinal / (W.entries.size - 1f) }, modifier = Modifier.fillMaxWidth())
                Section("Instructions", Icons.Filled.Info) { Text(step.help) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodyMedium)

                when (step) {
                    W.Prep -> if (ph == Ph.Ask) Button(onClick = { next() }, Modifier.fillMaxWidth()) { Text("Commencer") }
                    else if (ph == Ph.Idle) OutlinedButton(onClick = { go(W.Prep) }, Modifier.fillMaxWidth()) { Text("Réessayer") }

                    W.Nozzle -> if (ph == Ph.Ask) {
                        Text("Le motif est-il complet ?", fontWeight = FontWeight.Bold)
                        Button(onClick = { log += "Test des buses : correct."; next() }, Modifier.fillMaxWidth()) { Text("Oui, motif complet") }
                        OutlinedButton(onClick = {
                            run({
                                CanonTools.run(ip, Tool.CleanAll, Transport.Bjnp, prefs.ruiPassword)
                                ToolLog(ctx).mark(Tool.CleanAll); log += "Nettoyage des têtes lancé."
                                delay(90_000)
                                "Nettoyage terminé. Nouveau motif envoyé (${CanonTools.run(ip, Tool.NozzleCheck, Transport.Bjnp, prefs.ruiPassword)})."
                            })
                        }, Modifier.fillMaxWidth()) { Text("Non : nettoyer puis refaire le test") }
                    } else if (ph == Ph.Idle) OutlinedButton(onClick = { go(W.Nozzle) }, Modifier.fillMaxWidth()) { Text("Réessayer") }

                    W.Ref -> ScanRefSection(ip) { log += it; next() }

                    W.Tune1, W.Tune2, W.Copy -> {
                        if (ph == Ph.Dry) {
                            Text("Séchage de l'encre : $dry s", fontWeight = FontWeight.Bold)
                            TextButton(onClick = { ph = Ph.Place }) { Text("La feuille est sortie et sèche") }
                        }
                        if (ph == Ph.Place) {
                            Text("Posez la mire face contre la vitre, texte vers le coin repère, puis fermez le capot.", fontWeight = FontWeight.Bold)
                            if (step == W.Copy) {
                                Hint("Facultatif, avant de numériser : longueur imprimée des lignes (mm).")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(mmH, { mmH = it }, Modifier.weight(1f), label = { Text("Horiz. (170)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                    OutlinedTextField(mmV, { mmV = it }, Modifier.weight(1f), label = { Text("Vert. (150)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                                }
                            }
                            Button(onClick = {
                                run({
                                    msg = "Numérisation de la mire…"
                                    val r = Calib.scanChart(ctx, ip)
                                    if (step == W.Copy) {
                                        Calib.applyReport(ctx, r, viaDriver = true)
                                        val out = ArrayList<String>()
                                        out += "Copie : blanc R${r.white[0]} V${r.white[1]} B${r.white[2]}, courbe de gris et couleurs appliquées."
                                        // Règle : ne corrige l'échelle du pilote qu'au-delà de 0,5 mm.
                                        val h = mmH.replace(',', '.').toFloatOrNull(); val v = mmV.replace(',', '.').toFloatOrNull()
                                        val ratios = listOfNotNull(h?.takeIf { abs(it - 170f) > 0.5f && it in 150f..190f }?.let { 170f / it },
                                            v?.takeIf { abs(it - 150f) > 0.5f && it in 130f..170f }?.let { 150f / it })
                                        if (ratios.isNotEmpty()) {
                                            val t = Gutenprint.tune(ctx)
                                            val sc = (t.scale * ratios.average().toFloat()).coerceIn(0.9f, 1.1f)
                                            Gutenprint.saveTune(ctx, t.copy(scale = sc))
                                            out += "Règle : échelle du pilote corrigée à ${"%.1f".format(sc * 100)} %."
                                        } else if (h != null || v != null) out += "Règle : mesures conformes (±0,5 mm)."
                                        out += r.warnings
                                        notes = out
                                    } else {
                                        val (n, ns) = Gutenprint.autoTune(Gutenprint.tune(ctx), r)
                                        Gutenprint.saveTune(ctx, n)
                                        notes = ns
                                        val small = abs(n.scale - tuneBefore.scale) < 0.003f && abs(n.shiftX - tuneBefore.shiftX) < 0.6f &&
                                            abs(n.shiftY - tuneBefore.shiftY) < 0.6f && abs(n.gamma - tuneBefore.gamma) < 0.04f
                                        if (step == W.Tune1 && small) notes = ns + "Écarts faibles : la 2e passe est facultative."
                                    }
                                    log += "${step.title} : appliqué."
                                    "Réglages appliqués automatiquement."
                                })
                            }, Modifier.fillMaxWidth()) { Text("Numériser et analyser") }
                        }
                        if (ph == Ph.Ask) {
                            notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                            Button(onClick = { next() }, Modifier.fillMaxWidth()) { Text("Continuer") }
                            if (step != W.Copy) OutlinedButton(onClick = {
                                Gutenprint.saveTune(ctx, tuneBefore); log += "${step.title} : annulé."; next()
                            }, Modifier.fillMaxWidth()) { Text("Annuler ces réglages et continuer") }
                        }
                        if (ph == Ph.Idle) OutlinedButton(onClick = { printChart() }, Modifier.fillMaxWidth()) { Text("Réimprimer la mire") }
                    }

                    W.Visual -> VisualCheck(enabled = !busy, onPrint = { printChart() }, onAdjust = { log += it; msg = it })

                    W.Done -> {
                        Text("Résumé", fontWeight = FontWeight.Bold)
                        log.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        Button(onClick = onClose, Modifier.fillMaxWidth()) { Text("Fermer") }
                    }
                }

                // Navigation commune.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (step.ordinal > 0 && step != W.Done) TextButton(enabled = !busy, onClick = { go(W.entries[step.ordinal - 1]) }) { Text("Précédent") }
                    if (step.optional) TextButton(enabled = !busy, onClick = { log += "${step.title} : passé."; next() }) {
                        Text(if (step == W.Visual) "Rendu correct, continuer" else "Passer")
                    }
                    if (step != W.Done) TextButton(enabled = !busy, onClick = onClose) { Text("Quitter") }
                }
            }
        }
    }
}

/** Référence à l'écran et corrections rapides du pilote. */
@Composable
private fun VisualCheck(enabled: Boolean, onPrint: () -> Unit, onAdjust: (String) -> Unit) {
    val ctx = LocalContext.current
    Text("Référence (à comparer à la mire imprimée) :", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        (0..10).forEach { k -> val n = 255 * k / 10; Box(Modifier.weight(1f).height(28.dp).background(Color(n, n, n))) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Calib.patchRgb.forEachIndexed { i, c ->
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(28.dp).background(Color(c)))
                Text(Calib.patchNames[i], style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    fun adj(label: String, f: (Gutenprint.Tune) -> Gutenprint.Tune) {
        val t = f(Gutenprint.tune(ctx)); Gutenprint.saveTune(ctx, t)
        onAdjust("Contrôle visuel : $label (gamma ${"%.2f".format(t.gamma)}, densité ${"%.2f".format(t.density)}, " +
            "C ${"%.2f".format(t.cyan)} M ${"%.2f".format(t.magenta)} J ${"%.2f".format(t.yellow)}).")
    }
    fun c(v: Float, lo: Float, hi: Float) = v.coerceIn(lo, hi)
    val rows = listOf(
        "Trop sombre" to { t: Gutenprint.Tune -> t.copy(gamma = c(t.gamma * 1.05f, 0.5f, 2f)) },
        "Trop clair" to { t: Gutenprint.Tune -> t.copy(gamma = c(t.gamma / 1.05f, 0.5f, 2f)) },
        "Noir trop pâle" to { t: Gutenprint.Tune -> t.copy(density = c(t.density + 0.05f, 0.5f, 1.5f)) },
        "Dominante cyan" to { t: Gutenprint.Tune -> t.copy(cyan = c(t.cyan - 0.05f, 0.5f, 1.5f)) },
        "Dominante magenta" to { t: Gutenprint.Tune -> t.copy(magenta = c(t.magenta - 0.05f, 0.5f, 1.5f)) },
        "Dominante jaune" to { t: Gutenprint.Tune -> t.copy(yellow = c(t.yellow - 0.05f, 0.5f, 1.5f)) },
    )
    rows.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { (l, f) -> OutlinedButton(enabled = enabled, onClick = { adj(l, f) }, modifier = Modifier.weight(1f)) { Text(l) } }
        }
    }
    Hint("Chaque appui corrige un peu (5 %). Réimprimez pour vérifier.")
    Button(enabled = enabled, onClick = onPrint, modifier = Modifier.fillMaxWidth()) { Text("Réimprimer la mire") }
}

/** Guide écrit : ordre et règles de l'étalonnage. */
@Composable
fun CalibGuide() {
    var open by remember { mutableStateOf(false) }
    Section("Guide d'étalonnage", Icons.Filled.Info, trailing = {
        TextButton(onClick = { open = !open }) { Text(if (open) "Masquer" else "Afficher") }
    }) {
        if (!open) Hint("Ordre des étapes, matériel et quand refaire l'étalonnage.")
        else W.entries.forEachIndexed { i, s ->
            Text("${i + 1}. ${s.title}${if (s.optional) " (facultatif)" else ""}", fontWeight = FontWeight.Bold)
            Hint(s.help)
        }
        if (open) {
            Text("Où trouver chaque étape", fontWeight = FontWeight.Bold)
            Hint("Test des buses : Entretien. Référence du scanner, Copie et scanner : Réglages > Étalonnage. " +
                "Réglage du pilote et corrections manuelles : Réglages > Réglage du pilote. L'assistant enchaîne tout.")
            Text("Bonnes pratiques", fontWeight = FontWeight.Bold)
            Hint("Même papier que pour vos impressions habituelles. Mire bien à plat, capot fermé, pas de lumière directe. " +
                "Ne modifiez pas le pilote après l'étape « Copie et scanner », sinon refaites-la.")
        }
    }
}
