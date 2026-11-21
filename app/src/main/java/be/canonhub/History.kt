package be.canonhub

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fichiers créés par l'appli (Téléchargements/Canon et Images/Canon). */
data class Saved(val uri: Uri, val name: String, val mime: String, val size: Long, val date: Long)

object SavedFiles {
    private fun query(ctx: Context, base: Uri, path: String): List<Saved> {
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED,
        )
        val out = mutableListOf<Saved>()
        runCatching {
            ctx.contentResolver.query(
                base, cols, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$path%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Saved(
                        ContentUris.withAppendedId(base, c.getLong(0)), c.getString(1) ?: "?", c.getString(2) ?: "",
                        c.getLong(3), c.getLong(4) * 1000,
                    )
                }
            }
        }
        return out
    }

    fun list(ctx: Context): List<Saved> =
        (query(ctx, MediaStore.Downloads.EXTERNAL_CONTENT_URI, "Download/Canon") +
            query(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/Canon")).sortedByDescending { it.date }

    fun rename(ctx: Context, s: Saved, name: String): Boolean = runCatching {
        val ext = s.name.substringAfterLast('.', "")
        val n = if (ext.isNotEmpty() && !name.endsWith(".$ext")) "$name.$ext" else name
        ctx.contentResolver.update(s.uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, n) }, null, null) > 0
    }.getOrDefault(false)

    fun delete(ctx: Context, s: Saved): Boolean = runCatching { ctx.contentResolver.delete(s.uri, null, null) > 0 }.getOrDefault(false)

    fun open(ctx: Context, s: Saved) {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, s.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    }
}

@Composable
fun HistoryScreen(m: Modifier) {
    val ctx = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    var files by remember { mutableStateOf<List<Saved>?>(null) }
    var renaming by remember { mutableStateOf<Saved?>(null) }
    var deleting by remember { mutableStateOf<Saved?>(null) }
    LaunchedEffect(reload) { files = withContext(Dispatchers.IO) { SavedFiles.list(ctx) } }

    val list = files
    if (list != null && list.isEmpty()) {
        EmptyState(m, Icons.Filled.FolderOpen, "Aucun document", "Les scans et copies enregistrés apparaîtront ici.")
    } else {
        LazyColumn(
            m.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list ?: emptyList(), key = { it.uri.toString() }) { f ->
                ElevatedCard(onClick = { SavedFiles.open(ctx, f) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (f.mime == "application/pdf") Icons.Filled.PictureAsPdf else Icons.Filled.Image, null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp),
                        )
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                DateUtils.getRelativeTimeSpanString(f.date).toString() + " · " + Formatter.formatShortFileSize(ctx, f.size),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { share(ctx, f.uri, f.mime) }) { Icon(Icons.Filled.Share, "Partager") }
                        IconButton(onClick = { renaming = f }) { Icon(Icons.Filled.Edit, "Renommer") }
                        IconButton(onClick = { deleting = f }) { Icon(Icons.Filled.Delete, "Supprimer") }
                    }
                }
            }
        }
    }

    renaming?.let { f ->
        var name by remember(f) { mutableStateOf(f.name.substringBeforeLast('.')) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renommer") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { SavedFiles.rename(ctx, f, name.trim()); renaming = null; reload++ }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Annuler") } },
        )
    }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer ?") },
            text = { Text(f.name) },
            confirmButton = {
                TextButton(onClick = { SavedFiles.delete(ctx, f); deleting = null; reload++ }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } },
        )
    }
}

/** État vide illustré (grande icône, titre, explication). */
@Composable
fun EmptyState(m: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String) {
    Column(
        m.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(72.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
        )
    }
}
