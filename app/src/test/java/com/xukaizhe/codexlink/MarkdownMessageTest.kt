package com.xukaizhe.codexlink

import android.graphics.Color
import android.text.Spanned
import android.text.style.StrikethroughSpan
import io.noties.markwon.core.spans.CodeSpan
import io.noties.markwon.core.spans.HeadingSpan
import io.noties.markwon.core.spans.LinkSpan
import io.noties.markwon.core.spans.StrongEmphasisSpan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MarkdownMessageTest {
    private val renderer get() = createMessageMarkdown(
        RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE, Color.LTGRAY, Color.GRAY,
    )

    @Test fun headingsAndBoldBecomeStyledChineseText() {
        val text = renderer.toMarkdown("## 标题\n\n这是一段**加粗**文字。")
        assertEquals("标题\n\n这是一段加粗文字。", text.toString())
        assertEquals(1, text.getSpans(0, text.length, HeadingSpan::class.java).size)
        assertEquals(1, text.getSpans(0, text.length, StrongEmphasisSpan::class.java).size)
    }

    @Test fun codeAndEscapedMarkdownRemainLiteral() {
        val text = renderer.toMarkdown("`**literal**` 和 \\*\\*标题\\*\\*\n\n```kotlin\nval value = \"**literal**\"\n```")
        assertTrue(text.toString().contains("**literal**"))
        assertTrue(text.toString().contains("**标题**"))
        assertTrue(text.toString().contains("val value = \"**literal**\""))
        assertEquals(0, text.getSpans(0, text.length, StrongEmphasisSpan::class.java).size)
        assertEquals(1, text.getSpans(0, text.length, CodeSpan::class.java).size)
    }

    @Test fun listsQuotesLinksAndStrikethroughAreRendered() {
        val text = renderer.toMarkdown("- 第一项\n- 第二项\n\n> 引用\n\n[文档](https://example.com) ~~删除~~")
        assertTrue(text.toString().contains("第一项"))
        assertTrue(text.toString().contains("引用"))
        assertFalse(text.toString().contains("> 引用"))
        assertEquals(1, text.getSpans(0, text.length, LinkSpan::class.java).size)
        assertEquals(1, text.getSpans(0, text.length, StrikethroughSpan::class.java).size)
    }

    @Test fun streamingPartialMarkdownCanBeReplacedByCompletedReply() {
        val renderer = renderer
        val view = createMarkdownTextView(RuntimeEnvironment.getApplication())
        for (source in listOf("", "**正在", "**正在回复**\n\n```", "**正在回复**\n\n```text\n完成\n```")) {
            renderer.setMarkdown(view, source)
            assertTrue(view.isTextSelectable)
        }
        assertTrue(view.text.toString().contains("完成"))
        val text = view.text as Spanned
        assertEquals(1, text.getSpans(0, text.length, StrongEmphasisSpan::class.java).size)
    }

    @Test fun gfmTablesCreateTableSpans() {
        val text = renderer.toMarkdown("| 名称 | 状态 |\n| --- | --- |\n| 文件 | 完成 |")
        assertFalse(text.toString().contains("---"))
        assertTrue(text.getSpans(0, text.length, Any::class.java).any { it.javaClass.simpleName == "TableRowSpan" })
    }

    @Test fun htmlEntitiesDecodeOnlyOutsideCode() {
        val text = renderer.toMarkdown("&#x6807;&#x9898; &amp; `&amp;`")
        // Markwon pads inline code with non-breaking spaces for its background.
        assertEquals("标题 & &amp;", text.toString().replace("\u00a0", ""))
    }
}
