package be.canonhub

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fenêtre d'enregistrement commune (scan, copie) : type de fichier, type de document
 * (dossier de rangement) et nom. [guess] : classement proposé (OCR) ou « Document ».
 */
@Composable
fun SaveDocDialog(guess: Classify.Guess, type0: OutType, onDismiss: () -> Unit, onSave: (OutType, DocType?, String) -> Unit) {
    var t by remember { mutableStateOf(type0) }
    var dt by remember { mutableStateOf<DocType?>(guess.type) }
    var name by remember { mutableStateOf(guess.name()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enregistrer") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OptChips("Type de fichier", OutType.entries, t, { it.label }) { t = it }
                Hint(t.sub)
                Text("Type de document", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = dt == null, onClick = { dt = null }, label = { Text("Sans classer") })
                    DocType.entries.forEach { d ->
                        FilterChip(selected = dt == d, onClick = { dt = d; name = guess.name(d) }, label = { Text(d.label) })
                    }
                }
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Nom du fichier") })
                Hint("Dossier : ${if (t == OutType.PDF || t == OutType.TIFF) "Téléchargements" else "Images"}/Canon" + (dt?.let { "/${it.folder}" } ?: ""))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(t, dt, Classify.clean(name).ifBlank { Output.name(Output.DEFAULT_NAME) }) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

object SaveDoc {
    /**
     * Enregistre des images (chargées une à une, [dpi] pour la taille réelle) :
     * PDF ou TIFF multipage, sinon un fichier par image.
     */
    suspend fun write(
        ctx: Context, t: OutType, folder: String?, base: String, quality: Int, dpi: List<Int>, load: (Int) -> Bitmap,
    ): List<Uri> = withContext(Dispatchers.IO) {
        val n = dpi.size
        if (n == 0) return@withContext emptyList()
        when (t) {
            OutType.PDF -> listOf(Output.save(ctx, t, folder, base) { o ->
                val pdf = PdfOut(o)
                for (k in 0 until n) {
                    val b = load(k)
                    val pw = b.width * 72f / dpi[k]; val ph = b.height * 72f / dpi[k]
                    pdf.page(pw, ph, Images.jpeg(b, quality), b.width, b.height, 0f, 0f, pw, ph)
                }
                pdf.close()
            })
            OutType.TIFF -> listOf(Output.save(ctx, t, folder, base) { o ->
                Tiff.write(o, n) { k -> Output.tiffPage(load(k), dpi[k]) }
            })
            else -> (0 until n).map { k ->
                Output.save(ctx, t, folder, if (n > 1) "${base}_${k + 1}" else base) { o -> o.write(Output.encode(load(k), t, quality)) }
            }
        }
    }
}
