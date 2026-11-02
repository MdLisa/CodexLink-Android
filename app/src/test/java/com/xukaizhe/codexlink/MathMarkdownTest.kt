package com.xukaizhe.codexlink

import org.junit.Assert.*
import org.junit.Test

class MathMarkdownTest {
    private fun dollars(text:String) = text.replace('§','$')
    private fun prepare(text:String) = prepareMathMarkdown(dollars(text))

    @Test fun inlineParenthesesAndSingleDollarsKeepTheirSurroundingText() {
        assertEquals(dollars("已知 §§x^2+y_1§§，求 §§\\frac{1}{2}§§。"),prepare("已知 \\(x^2+y_1\\)，求 §\\frac{1}{2}§。"))
    }
    @Test fun displayBracketsAndDoubleDollarsBecomeStandaloneBlocks() {
        for(source in listOf("\\[\\sqrt{x+1}\\]","§§\\sqrt{x+1}§§","§§\n\\sqrt{x+1}\n§§"))
            assertEquals(dollars("§§\n\\sqrt{x+1}\n§§\n"),prepare(source))
        assertEquals(dollars("答案：\n\n§§\nx=2\n§§\n。"),prepare("答案：\\[x=2\\]。"))
    }
    @Test fun codeFencesInlineCodeAndIndentedCodeAreUnchanged() {
        for(source in listOf("`\\(x\\) §y§`","`` `§x§` \\[y\\] ``","```tex\n\\[x\\]\n§§y§§\n```","~~~text\n§x§\n~~~","    \\(x\\) §y§\n","\t\\[x\\]\n","> ```tex\n> §x§\n> ```"))
            assertEquals(dollars(source),prepare(source))
    }
    @Test fun currencyEscapesAndLinkTargetsAreNotFormulas() {
        for(source in listOf("价格 §5 和 §10。","§5.00 + §10.00","\\§5，\\\\(literal\\\\)","[链接](https://example.com/§token§?x=(1))","<https://example.com/§token§>","https://example.com/§token§","[link]: https://example.com/§token§\n"))
            assertEquals(dollars(source),prepare(source))
        assertEquals(dollars("价格 §5，变量 §§x§§。"),prepare("价格 §5，变量 §x§。"))
    }
    @Test fun unfinishedStreamingMathDoesNotOpenAMathBlock() {
        assertEquals("\\(\\frac{1",prepare("\\(\\frac{1"))
        assertEquals(dollars("\\§\\§\n\\frac{1"),prepare("§§\n\\frac{1"))
        assertEquals(dollars("§§\\frac{1}{2}§§"),prepare("\\(\\frac{1}{2}\\)"))
    }
    @Test fun casesMatricesAndTexRowBreaksArePreserved() {
        val body="\\begin{cases}x+y=3\\\\x-y=1\\end{cases}"
        assertEquals(dollars("§§\n$body\n§§\n"),prepare("\\[$body\\]"))
    }
    @Test fun quoteAndListContainersKeepMathInsideTheirItem() {
        assertEquals(dollars("> §§\n> x=2\n> §§\n"),prepare("> \\[x=2\\]"))
        assertEquals(dollars("> §§\n> x=2\n> §§\n"),prepare("> \\[\n> x=2\n> \\]"))
        assertEquals(dollars("- 结果\n\n  §§\n  x=2\n  §§\n"),prepare("- 结果\\[x=2\\]"))
    }
    @Test fun resourceCommandsAndMacroDefinitionsRemainLiteral() {
        for(body in listOf("\\includegraphics{private.png}","\\input{private.tex}","\\newcommand{\\x}{\\x}")) {
            val converted=prepare("§§$body§§")
            assertFalse(converted.contains(dollars("§§")))
            assertTrue(converted.contains(body.substringAfter('\\').substringBefore('{')))
        }
    }
    @Test fun ordinaryChineseAndEmojiAreUnchanged() {
        val text="你好🙂，今天一起解题。\n\n**结论**与普通文字。"
        assertEquals(text,prepare(text))
    }
}
