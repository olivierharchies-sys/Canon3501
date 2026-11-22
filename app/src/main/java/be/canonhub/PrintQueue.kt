package be.canonhub

import android.content.Context
import android.content.Intent
import android.print.PrintJob
import android.print.PrintManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * File d'impression : tâches Android de l'appli (fenêtre d'impression, service Canon)
 * et file de l'imprimante (IPP, LPD, page web).
 * Android ne laisse annuler que les tâches créées par l'appli elle-même.
 */
object PrintQueue {
    private fun pm(ctx: Context) = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager

    private fun open(j: PrintJob) = !j.isCompleted && !j.isCancelled && !j.isFailed

    private fun label(j: PrintJob): String {
        val st = when {
            j.isBlocked -> "bloquée"
            j.isStarted -> "en cours"
            j.isQueued -> "en attente"
            else -> "créée"
        }
        return "${j.info.label} · $st"
    }

    /** Tâches Android de l'appli non terminées. */
    fun pending(ctx: Context): List<String> =
        runCatching { pm(ctx).printJobs.filter(::open).map(::label) }.getOrDefault(emptyList())

    /** Annule les tâches Android de l'appli (thread principal). Retourne le nombre annulé. */
    suspend fun cancelAndroid(ctx: Context): Int = withContext(Dispatchers.Main) {
        runCatching {
            pm(ctx).printJobs.filter(::open).count { j -> runCatching { j.cancel() }.isSuccess }
        }.getOrDefault(0)
    }

    /** Vide tout ce qui est accessible : Android, puis imprimante (IPP, page web, LPD). */
    suspend fun clearAll(ctx: Context, ip: String, pwd: String): String {
        val out = ArrayList<String>()
        val n = cancelAndroid(ctx)
        out += if (n > 0) "Android : $n tâche(s) annulée(s)." else "Android : aucune tâche de l'appli."
        if (ip.isBlank()) return out.joinToString("\n")
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(8000) {
                runCatching {
                    val l = Ipp.jobs(ip)
                    l.forEach { runCatching { Ipp.cancel(ip, it.id) } }
                    out += "IPP : ${l.size} tâche(s) annulée(s)."
                }
            }
            withTimeoutOrNull(8000) {
                runCatching { Rui.cancel(ip, pwd) }.onSuccess { out += "Imprimante : $it." }
            }
            withTimeoutOrNull(8000) {
                runCatching { Lpd.removeAll(ip) }.onSuccess { out += "LPD : ${it.ifBlank { "file vidée" }}." }
            }
        }
        return out.joinToString("\n")
    }

    /** Réglages d'impression Android : tâches des autres applis (Canon PRINT, service Canon…). */
    fun openSettings(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_PRINT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
