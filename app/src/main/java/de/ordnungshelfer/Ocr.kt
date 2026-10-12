package de.ordnungshelfer

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Texterkennung auf dem Gerät (nichts wird hochgeladen). Muss im Hintergrund aufgerufen werden. */
object Ocr {
    fun recognize(ctx: Context, pages: List<Uri>): String {
        val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            pages.mapNotNull { p ->
                runCatching { Tasks.await(client.process(InputImage.fromFilePath(ctx, p))).text }.getOrNull()
            }.joinToString("\n\n")
        } finally {
            client.close()
        }
    }
}

/** Schlägt aus dem erkannten Text einen Dateinamen und Tags vor, z. B. „Rechnung_Telekom_2026-10-03“. */
object DocNamer {
    private val TYPES = listOf(
        "Rechnung", "Gutschrift", "Mahnung", "Kontoauszug", "Vertrag", "Kündigung", "Bescheid", "Quittung",
        "Angebot", "Lieferschein", "Bestellung", "Auftragsbestätigung", "Zeugnis", "Attest", "Rezept",
        "Gehaltsabrechnung", "Lohnabrechnung", "Versicherungsschein", "Police", "Bußgeld", "Urkunde",
        "Anmeldung", "Bestätigung", "Einladung", "Garantie", "Beleg",
    )
    private val SENDER_HINT = Regex("""\b(GmbH|AG|e\.\s?V\.|KG|OHG|Bank|Sparkasse|Versicherung|Stadtwerke|Finanzamt|Stadt|Kreis)\b""", RegexOption.IGNORE_CASE)
    private val DATE = Regex("""\b(\d{1,2})\.\s?(\d{1,2})\.\s?(\d{4}|\d{2})\b""")

    data class Suggestion(val name: String, val tags: List<String>)

    fun suggest(text: String): Suggestion {
        val lower = text.lowercase(Locale.GERMANY)
        // Dokumenttyp: der Begriff, der im Text am weitesten oben steht
        val type = TYPES.mapNotNull { t -> lower.indexOf(t.lowercase(Locale.GERMANY)).takeIf { it >= 0 }?.let { t to it } }
            .minByOrNull { it.second }?.first
        // Absender: erste Zeile mit „GmbH“, „Bank“ o. ä.
        val sender = text.lines().map { it.trim() }.firstOrNull { it.length in 3..60 && SENDER_HINT.containsMatchIn(it) }
            ?.let { clean(it).take(30).trim('_') }?.takeIf { it.isNotEmpty() }
        val date = DATE.findAll(text).mapNotNull { m ->
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            var y = m.groupValues[3].toInt()
            if (y < 100) y += 2000
            if (d in 1..31 && mo in 1..12 && y in 1990..2100) "%04d-%02d-%02d".format(y, mo, d) else null
        }.firstOrNull() ?: SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date())
        val name = listOfNotNull(type ?: "Scan", sender, date).joinToString("_")
        return Suggestion(name, listOfNotNull(type))
    }

    private fun clean(s: String) = s.replace(Regex("""[^\p{L}\p{N}]+"""), "_")
}
