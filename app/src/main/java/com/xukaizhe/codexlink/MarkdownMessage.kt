package com.xukaizhe.codexlink

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolverDef
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableTheme

internal fun createMessageMarkdown(
    context: Context,
    textColor: Int,
    linkColor: Int,
    codeBackground: Int,
    borderColor: Int,
): Markwon = Markwon.builder(context)
    .usePlugin(StrikethroughPlugin.create())
    .usePlugin(TablePlugin.create(TableTheme.Builder()
        .tableBorderColor(borderColor)
        .tableCellPadding((8 * context.resources.displayMetrics.density).toInt())
        .tableHeaderRowBackgroundColor(codeBackground)
        .build()))
    .usePlugin(object : AbstractMarkwonPlugin() {
        override fun configureTheme(builder: MarkwonTheme.Builder) {
            builder.linkColor(linkColor)
                .codeTextColor(textColor)
                .codeBlockTextColor(textColor)
                .codeBackgroundColor(codeBackground)
                .codeBlockBackgroundColor(codeBackground)
                .codeTypeface(Typeface.MONOSPACE)
                .blockQuoteColor(linkColor)
                .headingTextSizeMultipliers(floatArrayOf(1.6f, 1.4f, 1.2f, 1.1f, 1f, 1f))
        }

        override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
            val resolver = LinkResolverDef()
            builder.linkResolver { view, link ->
                // Computer paths in replies are text, not files this phone can open.
                if (Uri.parse(link).scheme?.lowercase() in setOf("http", "https", "mailto")) {
                    resolver.resolve(view, link)
                }
            }
        }
    })
    .build()

internal fun createMarkdownTextView(context: Context) = TextView(context).apply {
    setTextIsSelectable(true)
    setPadding(0, 0, 0, 0)
    includeFontPadding = false
    // Wrap code to the phone width instead of clipping long lines.
    setHorizontallyScrolling(false)
    movementMethod = TableAwareMovementMethod.create()
}

/** Render the same source for live deltas and saved history; never alter stored messages. */
@Composable
internal fun MarkdownMessage(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val foreground = colors.onSurface.toArgb()
    val link = colors.primary.toArgb()
    val code = colors.surfaceVariant.toArgb()
    val border = colors.outline.toArgb()
    val renderer = remember(context, foreground, link, code, border) {
        createMessageMarkdown(context, foreground, link, code, border)
    }
    val rendered = remember(renderer, markdown) { renderer.toMarkdown(markdown) }
    val density = LocalDensity.current
    val style = MaterialTheme.typography.bodyLarge
    val textSize = with(density) { style.fontSize.toPx() }
    val lineHeight = with(density) { style.lineHeight.toPx() }
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = ::createMarkdownTextView,
        update = { view ->
            view.setTextColor(foreground)
            view.setLinkTextColor(link)
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
            view.setLineSpacing((lineHeight - view.paint.fontSpacing).coerceAtLeast(0f), 1f)
            renderer.setParsedMarkdown(view, rendered)
        },
    )
}
