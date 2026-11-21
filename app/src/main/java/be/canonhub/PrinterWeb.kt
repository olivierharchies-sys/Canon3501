package be.canonhub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * Réglages et informations lus dans l'interface web de l'imprimante (https://ip/rui/),
 * d'après le code de la page (impl.js, model_table.js) relevé sur la G3501.
 */

private val PowerTimes = listOf(0, 15, 30, 60, 120, 240)
private fun powerLabel(m: Int) = if (m == 0) "Jamais" else if (m < 60) "$m min" else "${m / 60} h"

/** Réglages de l'imprimante : silencieux, arrêt/mise sous tension auto, séchage, marge, détection fin d'encre. */
@Composable
fun PrinterWebSettings(ip: String, prefs: PrinterPrefs) {
    val scope = rememberCoroutineScope()
    var v by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var askInk by remember { mutableStateOf<String?>(null) }
    val pwd = prefs.ruiPassword

    fun load() = scope.launch {
        busy = true; msg = ""
        msg = try {
            v = Rui.read(ip, pwd, "machininfo_data.cgi") +
                runCatching { Rui.read(ip, pwd, "dry_level.cgi") }.getOrDefault(emptyMap()) +
                runCatching { Rui.read(ip, pwd, "print_setting.cgi") }.getOrDefault(emptyMap()) +
                runCatching { Rui.read(ip, pwd, "detect_ink_end.cgi") }.getOrDefault(emptyMap())
            ""
        } catch (e: Exception) { "Lecture impossible : ${e.message}" }
        busy = false
    }

    /** Envoie un réglage puis relit les valeurs. */
    fun set(label: String, path: String, body: String) = scope.launch {
        busy = true
        msg = try { Rui.write(ip, pwd, path, body); "$label : enregistré." } catch (e: Exception) { "$label : ${e.message}" }
        busy = false
        load()
    }

    Section("Réglages de l'imprimante", Icons.Filled.Settings, sub = "Par l'interface web (mot de passe admin)") {
        if (v.isEmpty()) {
            OutlinedButton(enabled = !busy, onClick = { load() }, modifier = Modifier.fillMaxWidth()) { Text("Lire les réglages") }
        } else {
            v["SILENT_MODE"]?.let { s ->
                Toggle("Mode silencieux", s == "ON") { set("Mode silencieux", "silent_mode.cgi", "SILENT_MODE=" + if (it) "ON" else "OFF") }
            }
            v["POWER_SAVE_TIME"]?.toIntOrNull()?.let { t ->
                val on = v["POWER_SAVE_ON"] == "1"
                fun power(time: Int, autoOn: Boolean) = set("Alimentation", "machininfo_data.cgi",
                    "SETINFO=0&POWER_SAVE_OFF=${if (time != 0) 1 else 0}&POWER_SAVE_TIME=$time&POWER_SAVE_ON=${if (autoOn) 1 else 0}")
                OptChips("Arrêt automatique", PowerTimes, t, ::powerLabel) { power(it, on) }
                Toggle("Mise sous tension automatique (à la réception d'un travail)", on) { power(t, it) }
            }
            v["DRY_LEVEL"]?.toIntOrNull()?.let { d ->
                OptChips("Temps de séchage entre les pages", (1..5).toList(), d, { "$it" }) {
                    set("Temps de séchage", "dry_level.cgi", "SETINFO=0&DRY_LEVEL=$it")
                }
            }
            v["LEFT_MARGIN_CA1"]?.toIntOrNull()?.let { mm10 ->
                Stepper("Décalage gauche/droite (×0,1 mm)", mm10, -30..30) {
                    set("Position d'impression", "print_setting.cgi", "SETINFO=0&LEFT_MARGIN_CA1=$it")
                }
            }
            v["DETECT_INK_END"]?.let { d ->
                Toggle("Détection du niveau d'encre restant", d == "1") {
                    if (it) askInk = "SETINFO=0&DETECT_INK_END=1&INK_CNT_RESET=1"
                    else set("Détection d'encre", "detect_ink_end.cgi", "SETINFO=0&DETECT_INK_END=0")
                }
                if (d == "1") OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    onClick = { askInk = "SETINFO=0&INK_CNT_RESET=1" }) { Text("Réservoirs remplis : remettre le compteur à zéro") }
            }
            OutlinedButton(enabled = !busy, onClick = { load() }) { Text("Relire") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
        Hint("Fermez toute session ouverte dans un navigateur : l'imprimante n'en accepte qu'une.")
    }

    askInk?.let { body ->
        AlertDialog(
            onDismissRequest = { askInk = null },
            title = { Text("Compteur d'encre") },
            text = { Text("À faire uniquement quand TOUS les réservoirs viennent d'être remplis jusqu'au trait supérieur. Sinon les alertes d'encre seront fausses.\n\nContinuer ?") },
            confirmButton = { TextButton(onClick = { askInk = null; set("Compteur d'encre", "detect_ink_end.cgi", body) }) { Text("Confirmer") } },
            dismissButton = { TextButton(onClick = { askInk = null }) { Text("Annuler") } },
        )
    }
}

/** Fiche imprimante, compteurs et vérification du firmware, lus dans l'interface web. */
@Composable
fun PrinterWebInfo(ip: String, prefs: PrinterPrefs) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val pwd = prefs.ruiPassword

    fun load() = scope.launch {
        busy = true; msg = ""
        try {
            val sys = Rui.read(ip, pwd, "sysinfo_data.cgi")
            val fw = runCatching { Rui.read(ip, pwd, "firm_version.cgi", "") }.getOrDefault(emptyMap())
            val st = runCatching { Rui.read(ip, pwd, "prninfo_data.cgi") }.getOrDefault(emptyMap())
            runCatching { Rui.read(ip, pwd, "ut_use_record_access_count.cgi") }
            val cnt = runCatching { Rui.read(ip, pwd, "ut_get_use_record.cgi") }.getOrDefault(emptyMap())
            val all = sys + fw + st + cnt
            val state = when (all["BST_STATE"]) { null -> null; "0" -> "Prête"; "3" -> "Erreur (code ${all["PRNDOC"]})"; else -> "Occupée (code ${all["BST_STATE"]})" }
            rows = listOfNotNull(
                all["DEVICE_NAME"]?.let { "Nom" to it },
                all["SERIAL_NUM"]?.let { "N° de série" to it },
                (all["FIRM_CURRENT_VER"] ?: all["ROM_VER"])?.let { "Firmware" to it },
                (all["MAC_ADD_WIRELESS"]?.ifEmpty { null } ?: all["MAC_ADD"])?.let { "Adresse MAC" to it },
                state?.let { "État" to it },
                all["INK_EMPTY_FLG"]?.let { "Encre" to if (it == "1") "Un réservoir est vide" else "OK" },
                all["SIGNAL_STRENGTH"]?.let { "Wi-Fi" to "signal $it %, qualité ${all["LINK_QUALITY"] ?: "?"} %" },
                all["PRODUCT_TOTAL"]?.let { "Pages imprimées (total)" to it },
                all["PRODUCT_MONO"]?.let { "  dont noir et blanc" to it },
                all["PRODUCT_COLOR"]?.let { "  dont couleur" to it },
                all["PRODUCT_PLAIN"]?.let { "  sur papier ordinaire" to it },
                all["PRODUCT_EXCEPT_PLAIN"]?.let { "  sur autres papiers" to it },
                all["COPY_TOTAL"]?.let { "Copies" to it },
                all["SCAN_TOTAL"]?.let { "Numérisations" to it },
                all["OTHER_TOTAL"]?.let { "Autres impressions" to it },
            )
        } catch (e: Exception) { msg = "Lecture impossible : ${e.message}" }
        busy = false
    }

    fun checkFirmware() = scope.launch {
        busy = true; msg = "Vérification en ligne par l'imprimante…"
        msg = try {
            val (end, xml) = Rui.job(ip, pwd, "firm_verify.cgi") { msg = "Vérification : $it" }
            val f = Rui.fields(xml)
            when {
                f["FIRM_VERIFY"] == "0" -> "Firmware à jour."
                f["FIRM_LATEST_VER"] != null -> "Nouvelle version disponible : ${f["FIRM_LATEST_VER"]}. Installez-la depuis le menu de l'imprimante ou sa page web."
                else -> "Vérification : $end"
            }
        } catch (e: Exception) { "Vérification impossible : ${e.message}" }
        busy = false
    }

    Section("Fiche imprimante (interface web)", Icons.Filled.Info, sub = "Firmware, n° de série, compteurs") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { load() }) { Text(if (rows.isEmpty()) "Lire" else "Actualiser") }
            OutlinedButton(enabled = !busy, onClick = { checkFirmware() }) { Text("Mise à jour ?") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall)
        rows.forEach { (k, value) ->
            Column {
                Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
            HorizontalDivider()
        }
    }
}
