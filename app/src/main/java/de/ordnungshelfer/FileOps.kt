package de.ordnungshelfer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Dateien mit anderen Apps teilen (WhatsApp, Mail, …). */
fun shareFiles(ctx: Context, uris: List<Uri>, onError: (String) -> Unit) {
    val files = uris.filter { !Docs.isFile(it) || File(it.path!!).isFile }
    if (files.isEmpty()) { onError("Ordner können nicht geteilt werden"); return }
    val shared = ArrayList(files.map { u ->
        if (Docs.isFile(u)) FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(u.path!!)) else u
    })
    val mimes = files.map { u ->
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extensionOf(u.lastPathSegment ?: "")) ?: "*/*"
    }.distinct()
    val type = if (mimes.size == 1) mimes.first() else mimes.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
    val intent = if (shared.size == 1) {
        Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, shared.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, shared)
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(Intent.createChooser(intent, "Teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        onError("Teilen nicht möglich")
    }
}

/** Kleine Vorschaubilder für Listen – zwischengespeichert, damit Scrollen flüssig bleibt. */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

    fun cached(key: String): Bitmap? = cache.get(key)

    fun load(ctx: Context, uri: Uri, name: String, px: Int): Bitmap? {
        val key = "$uri@$px"
        cache.get(key)?.let { return it }
        if (key in failed) return null
        val bmp = try {
            when {
                Categories.of(name) == "Bilder" && extensionOf(name) != "svg" -> image(ctx, uri, px)
                Categories.of(name) == "Videos" -> video(ctx, uri, px)
                extensionOf(name) == "pdf" -> pdf(ctx, uri, px)
                else -> null
            }
        } catch (e: Throwable) {
            null
        }
        if (bmp == null) failed += key else cache.put(key, bmp)
        return bmp
    }

    private fun image(ctx: Context, uri: Uri, px: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
        var s = 1
        while (b.outWidth / (s * 2) >= px && b.outHeight / (s * 2) >= px) s *= 2
        return ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        }?.let { centerCrop(it, px) }
    }

    private fun video(ctx: Context, uri: Uri, px: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.getFrameAtTime(1_000_000)?.let { centerCrop(it, px) }
        } finally {
            runCatching { r.release() }
        }
    }

    private fun pdf(ctx: Context, uri: Uri, px: Int): Bitmap? {
        val fd: ParcelFileDescriptor = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return PdfRenderer(fd).use { r ->
            if (r.pageCount == 0) return@use null
            r.openPage(0).use { p ->
                val h = (px.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(px, h, Bitmap.Config.ARGB_8888).also { b ->
                    b.eraseColor(AndroidColor.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    private fun centerCrop(src: Bitmap, px: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val sq = Bitmap.createBitmap(src, x, y, side, side)
        return if (side > px) Bitmap.createScaledBitmap(sq, px, px, true) else sq
    }
}

/** Vorschaubild oder – wenn es keins gibt – das passende Symbol. */
@Composable
fun FileThumb(uri: Uri, name: String, size: Dp) {
    val ctx = LocalContext.current
    val px = (size.value * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(48)
    val bmp by produceState(Thumbs.cached("$uri@$px"), uri) {
        if (value == null) value = withContext(Dispatchers.IO) { Thumbs.load(ctx, uri, name, px) }
    }
    val b = bmp
    if (b != null) {
        Image(
            b.asImageBitmap(), null,
            Modifier.size(size).clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(iconFor(name), null, Modifier.size(size * 0.6f), tint = MaterialTheme.colorScheme.secondary)
        }
    }
}
