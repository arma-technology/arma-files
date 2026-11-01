package pl.arma.viewer.text

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Sformatowany widok Markdown. Bloki najwyższego poziomu są elementami
 * leniwej listy, więc długi dokument przewija się płynnie, a spis treści
 * może przewinąć do nagłówka.
 */
@Composable
fun MarkdownView(blocks: List<Markdown.Block>, state: LazyListState, modifier: Modifier = Modifier) {
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(blocks) { _, block -> MarkdownBlock(block) }
    }
}

/** Krótki dokument bez leniwej listy — do wstawienia w przewijany podgląd. */
@Composable
fun MarkdownColumn(blocks: List<Markdown.Block>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { MarkdownBlock(it) }
    }
}

@Composable
private fun MarkdownBlock(block: Markdown.Block) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    when (block) {
        is Markdown.Heading -> {
            val style = when (block.level) {
                1 -> type.headlineSmall
                2 -> type.titleLarge
                3 -> type.titleMedium
                else -> type.titleSmall
            }
            Column {
                Text(rich(block.text), style = style.copy(fontWeight = FontWeight.SemiBold))
                if (block.level <= 2) {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = colors.outlineVariant)
                }
            }
        }
        is Markdown.Paragraph -> Text(rich(block.text), style = type.bodyMedium)
        is Markdown.Code -> Surface(shape = RoundedCornerShape(10.dp), color = colors.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
            Text(
                block.text,
                style = type.bodySmall.copy(fontFamily = FontFamily.Monospace),
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)
            )
        }
        is Markdown.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(colors.primary.copy(alpha = .5f), RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides colors.onSurfaceVariant) {
                    block.blocks.forEach { MarkdownBlock(it) }
                }
            }
        }
        is Markdown.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            block.items.forEachIndexed { index, item ->
                Row {
                    val marker = when {
                        item.checked == true -> "☑"
                        item.checked == false -> "☐"
                        block.ordered -> "${block.start + index}."
                        else -> "•"
                    }
                    Text(marker, style = type.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 22.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.blocks.forEach { MarkdownBlock(it) }
                    }
                }
            }
        }
        Markdown.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.outlineVariant)
        is Markdown.Table -> MarkdownTable(block)
    }
}

@Composable
private fun MarkdownTable(table: Markdown.Table) {
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Surface(shape = RoundedCornerShape(10.dp), color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            // Kolumny o wspólnej szerokości: każda kolumna to osobny stos komórek.
            table.header.indices.forEach { column ->
                Column(Modifier.width(IntrinsicSize.Max)) {
                    val align = when (table.align.getOrNull(column)) {
                        Markdown.Align.CENTER -> TextAlign.Center
                        Markdown.Align.END -> TextAlign.End
                        else -> TextAlign.Start
                    }
                    (listOf(table.header) + table.rows).forEachIndexed { row, cells ->
                        Text(
                            rich(cells.getOrElse(column) { "" }),
                            style = type.bodySmall.copy(fontWeight = if (row == 0) FontWeight.SemiBold else FontWeight.Normal),
                            textAlign = align,
                            // Jedna linia: komórki sąsiednich kolumn zostają w tych samych wierszach.
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (row == 0) colors.surfaceContainerHighest else if (row % 2 == 0) colors.surfaceContainerHigh.copy(alpha = .5f) else Color.Transparent)
                                .widthIn(max = 260.dp)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Tekst z formatowaniem w linii; odnośniki otwiera system. */
@Composable
private fun rich(text: String): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    val codeBackground = colors.surfaceContainerHighest
    val linkColor = colors.primary
    return remember(text, codeBackground, linkColor) {
        buildAnnotatedString {
            Markdown.inline(text).forEach { span ->
                val style = SpanStyle(
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    textDecoration = if (span.strike) TextDecoration.LineThrough else null,
                    fontFamily = if (span.code) FontFamily.Monospace else null,
                    background = if (span.code) codeBackground else Color.Unspecified
                )
                val url = span.link?.takeIf { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("mailto:") }
                if (url != null) {
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                        withStyle(style) { append(span.text) }
                    }
                } else if (span.link != null) {
                    // Odnośnik względny (do innego pliku) — tylko wyróżniony.
                    withStyle(style.merge(SpanStyle(color = linkColor))) { append(span.text) }
                } else {
                    withStyle(style) { append(span.text) }
                }
            }
        }
    }
}
