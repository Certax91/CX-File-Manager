package de.ordnungshelfer

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Das Wort, das gerade getippt wird (nach dem letzten Komma). */
fun currentTagToken(text: String): String = text.substringAfterLast(',').trim().removePrefix("#").trim()

/** Tag-Feld mit Komma-Liste: angefangenes Wort durch den gewählten Tag ersetzen. */
fun completeTagList(text: String, tag: String): String {
    val before = parseTags(text.substringBeforeLast(',', ""))
    return (before + tag).joinToString(", ") + ", "
}

/**
 * Vorschläge aus den vorhandenen Tags, passend zu dem, was gerade getippt wird:
 * zuerst Tags, die mit den Buchstaben beginnen, dann Tags, die sie enthalten.
 * Ohne Eingabe werden die häufigsten Tags gezeigt.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagAutocomplete(
    typed: String,
    allTags: List<String>,
    exclude: List<String>,
    onPick: (String) -> Unit,
    max: Int = 8,
) {
    val token = typed.trim().removePrefix("#").trim()
    val candidates = allTags.filter { t ->
        exclude.none { it.equals(t, ignoreCase = true) } && !t.equals(token, ignoreCase = true)
    }
    val matches = if (token.isEmpty()) {
        candidates.take(max)
    } else {
        (candidates.filter { it.startsWith(token, ignoreCase = true) } +
            candidates.filter { !it.startsWith(token, ignoreCase = true) && it.contains(token, ignoreCase = true) })
            .take(max)
    }
    if (matches.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (token.isEmpty()) "Vorhandene Tags" else "Vorschläge",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            matches.forEach { t ->
                SuggestionChip(
                    onClick = { onPick(t) },
                    icon = { Icon(Icons.Filled.Sell, null, Modifier.size(16.dp)) },
                    label = { Text(highlight(t, token)) },
                )
            }
        }
    }
}

/** Getippte Buchstaben im Vorschlag fett hervorheben. */
private fun highlight(tag: String, token: String) = buildAnnotatedString {
    val i = if (token.isEmpty()) -1 else tag.indexOf(token, ignoreCase = true)
    if (i < 0) {
        append(tag)
    } else {
        append(tag.substring(0, i))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(tag.substring(i, i + token.length)) }
        append(tag.substring(i + token.length))
    }
}
