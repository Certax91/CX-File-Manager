package de.ordnungshelfer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.database.ContentObserver
import android.os.FileObserver
import android.os.HandlerThread
import android.provider.MediaStore
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Merkt sich Pfade, die die App selbst gerade angelegt/umbenannt hat – dafür keine Meldung. */
object WatchState {
    /** Pfad → Zeitpunkt, bis zu dem er ignoriert wird */
    private val ignored = ConcurrentHashMap<String, Long>()

    fun ignore(path: String, forMs: Long = 15_000) {
        ignored[path] = System.currentTimeMillis() + forMs
    }

    fun isIgnored(path: String): Boolean {
        val until = ignored[path] ?: return false
        return System.currentTimeMillis() < until
    }

    @Volatile var running = false
}

/**
 * Überwacht den Download-Ordner. Neue Dateien und Ordner werden per Benachrichtigung gemeldet –
 * mit „Umbenennen“ direkt in der Benachrichtigung.
 */
class WatchService : Service() {

    private var observer: FileObserver? = null
    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableMapOf<String, Runnable>()
    private val notified = mutableMapOf<String, Long>()
    /** Dateien, die schon vor dem Start da waren – Änderungen daran sind keine „neuen“ Dateien. */
    private val known = ConcurrentHashMap.newKeySet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels(this)
        val watchDl = Store(this).loadWatch()
        val watchPhotos = SeriesEngine.needsWatching(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val (title, text) = when {
            watchDl && watchPhotos -> "Downloads und Fotos werden überwacht" to "Neue Downloads umbenennen, neue Fotos kommen in die Fotoserie."
            watchPhotos -> "Fotoserie läuft" to "Neue Fotos bekommen automatisch Namen und Tags."
            else -> "Download-Ordner wird überwacht" to "Neue Dateien kannst du direkt umbenennen."
        }
        val n = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        ServiceCompat.startForeground(
            this, ID_SERVICE, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (!Docs.hasAllFilesAccess(this) || (!watchDl && !watchPhotos)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watchDl) {
            if (observer == null) startObserving()
        } else {
            observer?.stopWatching()
            observer = null
        }
        if (watchPhotos) startPhotoWatch() else stopPhotoWatch()
        WatchState.running = true
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.stopWatching()
        observer = null
        stopPhotoWatch()
        handler.removeCallbacksAndMessages(null)
        WatchState.running = false
        super.onDestroy()
    }

    // ---------- Fotoserien: neue Fotos ----------

    private var photoObserver: ContentObserver? = null
    private val worker by lazy { HandlerThread("fotoserie").also { it.start() } }
    private val workHandler by lazy { Handler(worker.looper) }
    private val catchUpRun = Runnable { runCatchUp() }
    private val hourly = object : Runnable {
        override fun run() {
            workHandler.post(catchUpRun)
            handler.postDelayed(this, 60 * 60_000L)
        }
    }

    private fun startPhotoWatch() {
        if (photoObserver != null) {
            workHandler.post(catchUpRun)
            return
        }
        val obs = object : ContentObserver(workHandler) {
            override fun onChange(selfChange: Boolean) {
                // Kamera schreibt das Foto in mehreren Schritten – kurz warten, dann gesammelt bearbeiten
                workHandler.removeCallbacks(catchUpRun)
                workHandler.postDelayed(catchUpRun, 4000)
            }
        }
        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        ).forEach { runCatching { contentResolver.registerContentObserver(it, true, obs) } }
        photoObserver = obs
        workHandler.post(catchUpRun)
        handler.postDelayed(hourly, 60 * 60_000L)
    }

    private fun stopPhotoWatch() {
        photoObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        photoObserver = null
        handler.removeCallbacks(hourly)
    }

    private fun runCatchUp() {
        val done = runCatching { SeriesEngine.catchUp(this) }.getOrDefault(emptyMap())
        done.forEach { (s, n) -> notifySeries(this, s, n) }
        // Serie vorbei? Dann Überwachung anpassen bzw. beenden
        if (!SeriesEngine.needsWatching(this)) handler.post { ensure(this) }
    }

    private fun startObserving() {
        val dir = Downloader.downloadDir()
        known.clear()
        dir.list()?.let { known.addAll(it) }
        val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE or
            FileObserver.DELETE or FileObserver.MOVED_FROM
        val obs = if (Build.VERSION.SDK_INT >= 29) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) = onFsEvent(dir, event, path)
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) = onFsEvent(dir, event, path)
            }
        }
        obs.startWatching()
        observer = obs
    }

    private fun onFsEvent(dir: File, event: Int, name: String?) {
        if (name == null) return
        if (event and (FileObserver.DELETE or FileObserver.MOVED_FROM) != 0) {
            known.remove(name)
            return
        }
        if (isTemporary(name)) return
        val f = File(dir, name)
        // Bestehende Datei wurde nur bearbeitet → nicht melden
        if (event and FileObserver.CLOSE_WRITE != 0 && name in known) return
        // Neu angelegte Dateien melden sich erst mit CLOSE_WRITE (fertig geschrieben); Ordner sofort
        if (event and FileObserver.CREATE != 0 && !f.isDirectory) return
        handler.post { schedule(f, -1L) }
    }

    /** Kurz warten, bis die Datei fertig ist (Größe ändert sich nicht mehr), dann melden. */
    private fun schedule(f: File, lastSize: Long) {
        val key = f.absolutePath
        pending.remove(key)?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            pending.remove(key)
            if (!f.exists() || WatchState.isIgnored(key)) return@Runnable
            val size = if (f.isFile) f.length() else 0L
            if (f.isFile && size != lastSize) {
                schedule(f, size) // wird noch geschrieben
                return@Runnable
            }
            val last = notified[key]
            if (last != null && System.currentTimeMillis() - last < 60_000) return@Runnable
            notified[key] = System.currentTimeMillis()
            known.add(f.name)
            // Passende Regel? Bei „automatisch einsortieren“ direkt verschieben, sonst als Vorschlag anbieten
            val suggestion = if (f.isFile) RuleApply.suggest(this, f) else null
            if (suggestion != null && Store(this).loadAutoSort()) {
                val moved = RuleApply.apply(this, f, suggestion.first, suggestion.second)
                if (moved != null) {
                    notifyDone(this, notificationId(key), "Einsortiert: ${f.name} → ${Docs.prettyPath(Uri.fromFile(moved.parentFile!!))}", 8000, moved)
                    return@Runnable
                }
            }
            notifyNew(this, f)
        }
        pending[key] = r
        handler.postDelayed(r, 2500)
    }

    companion object {
        const val CHANNEL_SERVICE = "waechter"
        const val CHANNEL_NEW = "neue_dateien"
        const val CHANNEL_SERIES = "fotoserien"
        const val ID_SERVICE = 1
        const val KEY_NAME = "neuer_name"
        const val EXTRA_PATH = "pfad"
        const val EXTRA_RULE = "regel"

        private val TEMP_SUFFIX = listOf(".crdownload", ".part", ".partial", ".tmp", ".download", ".opdownload")

        fun isTemporary(name: String): Boolean =
            name.startsWith(".") || name.startsWith("~") || TEMP_SUFFIX.any { name.endsWith(it, ignoreCase = true) }

        fun notificationId(path: String) = 1000 + (path.hashCode() and 0x0FFFFFFF)

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, WatchService::class.java)) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WatchService::class.java))
        }

        /** Startet/aktualisiert die Überwachung, wenn Downloads oder eine laufende Fotoserie sie brauchen – sonst beenden. */
        fun ensure(ctx: Context) {
            val need = Docs.hasAllFilesAccess(ctx) && (Store(ctx).loadWatch() || SeriesEngine.needsWatching(ctx))
            if (need) start(ctx) else stop(ctx)
            SeriesAlarm.schedule(ctx)
        }

        /** Sammel-Meldung „Mallorca: 3 neue Fotos benannt“ (eine je Serie, wird aktualisiert). */
        fun notifySeries(ctx: Context, s: PhotoSeries, count: Int) {
            if (!canNotify(ctx)) return
            val total = SeriesEngine.load(ctx).firstOrNull { it.id == s.id }?.assigned?.size ?: count
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            val what = when {
                s.moveToFolder -> "einsortiert in ${s.folderName}"
                s.rename -> "benannt und getaggt"
                else -> "getaggt"
            }
            val n = NotificationCompat.Builder(ctx, CHANNEL_SERIES)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("Fotoserie „${s.name}“")
                .setContentText("$count neue${if (count == 1) "s Foto" else " Fotos"} $what · insgesamt $total")
                .setContentIntent(open)
                .setSilent(true)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(5000 + (s.id % 10000).toInt(), n) }
        }

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SERVICE, "Überwachung aktiv", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "Dauerhafte Anzeige, solange der Download-Ordner überwacht wird"
                    setShowBadge(false)
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SERIES, "Fotoserien", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Meldet, wenn neue Fotos automatisch benannt und getaggt wurden"
                }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_NEW, "Neue Downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Meldet neue Dateien und Ordner im Download-Ordner"
                }
            )
        }

        fun canNotify(ctx: Context) =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        /** Benachrichtigung „Neu im Download“ mit Umbenennen-Feld. */
        fun notifyNew(ctx: Context, f: File) {
            if (!canNotify(ctx)) return
            val path = f.absolutePath
            val id = notificationId(path)
            val isDir = f.isDirectory

            val reply = PendingIntent.getBroadcast(
                ctx, id,
                Intent(ctx, RenameReceiver::class.java).putExtra(EXTRA_PATH, path),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
            )
            val input = RemoteInput.Builder(KEY_NAME).setLabel("Neuer Name").build()
            val renameAction = NotificationCompat.Action.Builder(R.drawable.ic_notify, "Umbenennen", reply)
                .addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .build()

            val details = PendingIntent.getActivity(
                ctx, id,
                Intent(ctx, ReceiveActivity::class.java)
                    .putExtra(EXTRA_PATH, path)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val n = NotificationCompat.Builder(ctx, CHANNEL_NEW)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(if (isDir) "Neuer Ordner im Download" else "Neu im Download")
                .setContentText(f.name)
                .setStyle(NotificationCompat.BigTextStyle().bigText(f.name + "\n\nTippen für Name, Zielordner und Tags."))
                .setContentIntent(details)
                .addAction(renameAction)
                .also { b ->
                    // Vorschlag aus den Regeln, z. B. „→ Finanzen/Rechnungen“
                    if (!isDir) RuleApply.suggest(ctx, f)?.let { (rule, dir) ->
                        val apply = PendingIntent.getBroadcast(
                            ctx, id + 1,
                            Intent(ctx, ApplyRuleReceiver::class.java)
                                .putExtra(EXTRA_PATH, path)
                                .putExtra(EXTRA_RULE, rule.id),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                        b.addAction(R.drawable.ic_notify, "→ ${dir.name}", apply)
                    }
                }
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
        }

        /** Benachrichtigung nach dem Umbenennen kurz bestätigen und dann ausblenden. */
        fun notifyDone(ctx: Context, id: Int, text: String, timeout: Long = 4000, file: File? = null) {
            if (!canNotify(ctx)) return
            val b = NotificationCompat.Builder(ctx, CHANNEL_NEW)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(text)
                .setSilent(true)
                .setAutoCancel(true)
                .setTimeoutAfter(if (file != null) 15_000 else timeout)
            if (file != null && file.exists()) {
                // „Öffnen“: Datei in passender App öffnen
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionOf(file.name)) ?: "*/*"
                val open = PendingIntent.getActivity(
                    ctx, id + 2,
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                // „Anzeigen“: im Explorer der App zeigen
                val show = PendingIntent.getActivity(
                    ctx, id + 3,
                    Intent(ctx, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_REVEAL, file.absolutePath)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                b.addAction(R.drawable.ic_notify, "Öffnen", open)
                b.addAction(R.drawable.ic_notify, "Anzeigen", show)
                b.setContentIntent(show)
            }
            runCatching { NotificationManagerCompat.from(ctx).notify(id, b.build()) }
        }
    }
}

/** Nimmt den neuen Namen aus der Benachrichtigung entgegen und benennt um. */
class RenameReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val path = intent.getStringExtra(WatchService.EXTRA_PATH) ?: return
        val id = WatchService.notificationId(path)
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(WatchService.KEY_NAME)?.toString()
        val f = File(path)
        if (typed.isNullOrBlank() || !f.exists()) {
            WatchService.notifyDone(ctx, id, if (!f.exists()) "Datei gibt es nicht mehr" else "Kein Name eingegeben")
            return
        }
        val name = if (f.isDirectory) typed.trim().replace('/', '_') else fixFileName(typed, f.name)
        val newUri = Docs.rename(ctx, Uri.fromFile(f), name)
        if (newUri == null) {
            WatchService.notifyDone(ctx, id, "„$name“ gibt es schon")
            return
        }
        // Tags wandern mit
        val store = Store(ctx)
        val tags = store.loadTags()
        tags[Uri.fromFile(f).toString()]?.let { t ->
            store.saveTags(tags - Uri.fromFile(f).toString() + (newUri.toString() to t))
        }
        WatchService.notifyDone(ctx, id, "Umbenannt in „$name“", file = newUri.path?.let { File(it) })
    }
}

/** Regeln auf eine einzelne neue Datei anwenden (Vorschlag in der Benachrichtigung oder automatisch). */
object RuleApply {
    /** Erste passende Regel und der Zielordner, oder null. Nur Regeln mit direktem Pfad als Ziel. */
    fun suggest(ctx: Context, f: File): Pair<Rule, File>? {
        val node = Node(Uri.fromFile(f), f.name, "", f.length(), f.lastModified())
        val rule = Store(ctx).loadRules().firstOrNull { it.matches(node) && (it.targetSegments.isNotEmpty() || it.targetBase != null) }
            ?: return null
        val base = rule.targetBase?.let { b ->
            val u = Uri.parse(b)
            if (u.scheme != "file") return null
            File(u.path!!)
        } ?: (f.parentFile ?: return null)
        val dir = rule.targetSegments.fold(base) { acc, seg -> File(acc, seg) }
        if (dir.absolutePath == f.parentFile?.absolutePath) return null
        return rule to dir
    }

    /** Verschiebt die Datei; Tags der Regel werden vergeben. Liefert die neue Datei. */
    fun apply(ctx: Context, f: File, rule: Rule, dir: File): File? {
        WatchState.ignore(dir.absolutePath)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val parent = f.parentFile ?: return null
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionOf(f.name)) ?: "application/octet-stream"
        val newUri = Docs.move(ctx, Uri.fromFile(f), f.name, mime, Uri.fromFile(parent), Uri.fromFile(dir), f.name) ?: return null
        val store = Store(ctx)
        val tags = store.loadTags().toMutableMap()
        val old = tags.remove(Uri.fromFile(f).toString()).orEmpty()
        val merged = normalizeTags(old + rule.tags)
        if (merged.isNotEmpty()) tags[newUri.toString()] = merged
        store.saveTags(tags)
        return File(newUri.path!!)
    }
}

/** Tipp auf „→ Ziel“ in der Benachrichtigung: Datei nach der passenden Regel einsortieren. */
class ApplyRuleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val path = intent.getStringExtra(WatchService.EXTRA_PATH) ?: return
        val id = WatchService.notificationId(path)
        val f = File(path)
        val s = if (f.exists()) RuleApply.suggest(ctx, f) else null
        if (s == null) {
            WatchService.notifyDone(ctx, id, "Datei oder Regel nicht mehr vorhanden")
            return
        }
        val moved = RuleApply.apply(ctx, f, s.first, s.second)
        WatchService.notifyDone(
            ctx, id,
            if (moved != null) "Verschoben nach ${Docs.prettyPath(Uri.fromFile(moved.parentFile!!))}" else "Verschieben nicht möglich",
            file = moved,
        )
    }
}

/** Startet die Überwachung nach einem Neustart des Handys wieder, falls eingeschaltet. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) WatchService.ensure(ctx)
    }
}

/** Weckt die Überwachung am ersten Tag einer geplanten Fotoserie (ungefähr um Mitternacht). */
object SeriesAlarm {
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(android.app.AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, 77, Intent(ctx, SeriesAlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val today = java.time.LocalDate.now().toEpochDay()
        val next = SeriesEngine.load(ctx).filter { it.autoNew && it.start > today }.minOfOrNull { it.start }
        if (next == null) {
            am.cancel(pi)
            return
        }
        val at = java.time.LocalDate.ofEpochDay(next).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + 60_000
        // Exakter Wecker darf die Überwachung auch aus dem Hintergrund starten
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

class SeriesAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        WatchService.ensure(ctx)
    }
}
