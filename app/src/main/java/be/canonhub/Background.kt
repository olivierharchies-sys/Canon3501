package be.canonhub

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

object Notif {
    private const val CHANNEL = "canon"
    const val INK = 1
    const val CLOG = 2
    const val TOOL = 3

    fun show(ctx: Context, id: Int, title: String, text: String, nozzleAction: Boolean = false, screen: Screen = Screen.Ink) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Imprimante", NotificationManager.IMPORTANCE_DEFAULT))
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, screen, id))
        if (nozzleAction) b.addAction(0, "Test des buses", ToolReceiver.pending(ctx, ToolReceiver.NOZZLE))
        NotificationManagerCompat.from(ctx).notify(id, b.build())
    }

    fun openApp(ctx: Context, screen: Screen, code: Int): PendingIntent = PendingIntent.getActivity(
        ctx, 100 + code,
        Intent(ctx, MainActivity::class.java).putExtra("screen", screen.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Vérification périodique : niveaux d'encre, rappel anti-bouchage, widget. */
class WatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val p = PrinterPrefs(ctx)
        if (p.ip.isBlank()) return Result.success()
        p.loadHints()
        val now = System.currentTimeMillis()
        val day = 24 * 3600 * 1000L

        runCatching { Ipp.query(p.ip) }.getOrNull()?.let { info ->
            p.lastInk = Ipp.inkText(info)
            InkWidget.update(ctx)
            val low = info.inks.filter { it.level in 0..p.inkThreshold }
            if (p.inkAlert && low.isNotEmpty() && now - p.lastInkNotify > day) {
                p.lastInkNotify = now
                Notif.show(
                    ctx, Notif.INK, "Encre faible",
                    low.joinToString(", ") { "${it.name} : ${it.level} %" } + ". Rechargez le réservoir.",
                )
            }
        }

        if (p.clogReminder) {
            if (p.lastPrint == 0L) p.lastPrint = now
            val days = (now - p.lastPrint) / day
            if (days >= p.clogDays && now - p.lastClogNotify > day) {
                p.lastClogNotify = now
                Notif.show(
                    ctx, Notif.CLOG, "Imprimante inutilisée depuis $days jours",
                    "Imprimez un test des buses pour éviter que l'encre sèche.",
                    nozzleAction = true, screen = Screen.Maintenance,
                )
            }
        }
        return Result.success()
    }

    companion object {
        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            val req = PeriodicWorkRequestBuilder<WatchWorker>(12, TimeUnit.HOURS).setConstraints(net).build()
            wm.enqueueUniquePeriodicWork("watch", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun now(ctx: Context) {
            WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<WatchWorker>().setConstraints(net).build())
        }
    }
}

/** Actions depuis notification ou widget. */
class ToolReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        when (intent.action) {
            REFRESH -> WatchWorker.now(app)
            NOZZLE -> {
                val pr = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    val p = PrinterPrefs(app)
                    val log = ToolLog(app)
                    val text = try {
                        if (p.ip.isBlank()) throw IllegalStateException("adresse IP non configurée")
                        val via = CanonTools.run(p.ip, Tool.NozzleCheck, log.transport, p.ruiPassword)
                        log.mark(Tool.NozzleCheck)
                        p.lastPrint = System.currentTimeMillis()
                        "Commande envoyée ($via)."
                    } catch (e: Exception) {
                        "Échec : ${e.message}"
                    }
                    NotificationManagerCompat.from(app).cancel(Notif.CLOG)
                    Notif.show(app, Notif.TOOL, "Test des buses", text, screen = Screen.Maintenance)
                    pr.finish()
                }
            }
        }
    }

    companion object {
        const val NOZZLE = "be.canonhub.NOZZLE"
        const val REFRESH = "be.canonhub.REFRESH"

        fun pending(ctx: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, action.hashCode(),
            Intent(ctx, ToolReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Widget : niveaux d'encre + raccourcis. */
class InkWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        update(ctx)
        WatchWorker.now(ctx)
    }

    companion object {
        fun update(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, InkWidget::class.java))
            if (ids.isEmpty()) return
            val ink = PrinterPrefs(ctx).lastInk.ifBlank { "Encre : appuyez sur ↻" }
            val v = RemoteViews(ctx.packageName, R.layout.widget_ink).apply {
                setTextViewText(R.id.w_ink, ink)
                setOnClickPendingIntent(R.id.w_title, Notif.openApp(ctx, Screen.Home, 10))
                setOnClickPendingIntent(R.id.w_ink, Notif.openApp(ctx, Screen.Ink, 11))
                setOnClickPendingIntent(R.id.w_scan, Notif.openApp(ctx, Screen.Scan, 12))
                setOnClickPendingIntent(R.id.w_test, ToolReceiver.pending(ctx, ToolReceiver.NOZZLE))
                setOnClickPendingIntent(R.id.w_refresh, ToolReceiver.pending(ctx, ToolReceiver.REFRESH))
            }
            mgr.updateAppWidget(ids, v)
        }
    }
}

/** Tuile des réglages rapides : ouvre directement l'écran Numériser. */
class ScanTile : android.service.quicksettings.TileService() {
    override fun onClick() {
        super.onClick()
        val i = Intent(this, MainActivity::class.java)
            .putExtra("screen", Screen.Scan.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 40, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}

/**
 * Service au premier plan pendant une numérisation : sans lui, Android (HyperOS surtout)
 * coupe le réseau de l'appli dès qu'on passe à une autre appli (« connection abort »).
 */
class ScanService : android.app.Service() {
    private var wake: android.os.PowerManager.WakeLock? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Numérisation", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Numérisation en cours")
            .setContentText("Vous pouvez changer d'appli.")
            .setOngoing(true)
            .setContentIntent(Notif.openApp(this, Screen.Scan, 9))
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID, n)
            }
        }
        if (wake == null) {
            wake = getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "canonhub:scan")
                .apply { acquire(10 * 60 * 1000L) }
            @Suppress("DEPRECATION")
            wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "canonhub:scan")
                .apply { setReferenceCounted(false); acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { if (wake?.isHeld == true) wake?.release() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }

    companion object {
        private const val CH = "scan"
        private const val ID = 7

        /** Exécute [block] en gardant l'appli active (service au premier plan). */
        suspend fun <T> keep(ctx: Context, block: suspend () -> T): T {
            val i = Intent(ctx, ScanService::class.java)
            val t0 = System.currentTimeMillis()
            runCatching { ContextCompat.startForegroundService(ctx, i) }
            try {
                return block()
            } finally {
                // Laisse au service le temps de passer au premier plan avant l'arrêt (sinon plantage Android).
                val dt = System.currentTimeMillis() - t0
                if (dt < 1500) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { kotlinx.coroutines.delay(1500 - dt) }
                runCatching { ctx.stopService(i) }
            }
        }
    }
}
