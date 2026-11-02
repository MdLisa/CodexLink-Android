package com.xukaizhe.codexlink

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.text.Spanned
import android.util.TypedValue
import android.view.View
import io.noties.markwon.image.AsyncDrawableSpan
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MathMessageTest {
    // Config.NONE skips the library's manifest initialization provider in this isolated test.
    @Before fun initializeMath() = ru.noties.jlatexmath.JLatexMathAndroid.init(RuntimeEnvironment.getApplication())
    private val directExecutor=object:AbstractExecutorService(){
        override fun execute(command:Runnable)=command.run()
        override fun shutdown(){}
        override fun shutdownNow()=mutableListOf<Runnable>()
        override fun isShutdown()=false
        override fun isTerminated()=false
        override fun awaitTermination(timeout:Long,unit:TimeUnit)=true
    }
    private fun renderer(size:Float=22f,color:Int=Color.BLACK)=createMessageMarkdown(RuntimeEnvironment.getApplication(),color,Color.BLUE,Color.LTGRAY,Color.GRAY,size,directExecutor)
    private fun mathSpans(text:Spanned)=text.getSpans(0,text.length,AsyncDrawableSpan::class.java)

    @Test fun allFourDelimiterStylesProduceFormulaSpans() {
        val result=renderer().toMarkdown("分数 \\(\\frac{1}{2}\\)，根号 ${'$'}\\sqrt{3}${'$'}。\n\n\\[x^2+y_1=5\\]\n\n${'$'}${'$'}z=2${'$'}${'$'}")
        assertEquals(4,mathSpans(result).size)
        assertTrue(result.toString().contains("分数"))
    }
    @Test fun codeAndUnsafeMathHaveNoFormulaSpans() {
        for(source in listOf("`\\(x\\)`","```tex\n${'$'}${'$'}x${'$'}${'$'}\n```","${'$'}${'$'}\\includegraphics{private.png}${'$'}${'$'}"))
            assertEquals(0,mathSpans(renderer().toMarkdown(source)).size)
    }
    @Test fun quotedAndListedDisplayFormulasStayRenderable() {
        val result=renderer().toMarkdown("> \\[x=2\\]\n\n- 结果\\[y=3\\]\n- 第二项")
        assertEquals(2,mathSpans(result).size)
        assertTrue(result.toString().contains("第二项"))
    }
    @Test fun streamingCompletionAndInvalidFormulaKeepTheMessageReadable() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val view=createMarkdownTextView(activity.get());activity.get().setContentView(view)
            val renderer=renderer()
            fun show(source:String) {
                renderer.setMarkdown(view,source)
                repeat(3) {
                    shadowOf(Looper.getMainLooper()).idle()
                    view.measure(View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                    view.layout(0,0,540,view.measuredHeight)
                }
            }
            show("计算中：${'$'}${'$'}\\frac{1")
            assertEquals(0,mathSpans(view.text as Spanned).size)
            assertTrue(view.text.contains("frac{1"))
            show("结果：${'$'}${'$'}\\frac{1}{2}${'$'}${'$'}\n\n**完成**")
            assertTrue(mathSpans(view.text as Spanned).single().drawable.hasResult())
            assertTrue(view.text.contains("完成"))
            show("> \\[\n> \\frac{1}{2}\n> \\]")
            val quoted=mathSpans(view.text as Spanned).single().drawable
            assertTrue(quoted.hasResult())
            assertFalse(quoted.destination.contains('>'))
            show("错误公式 \\(\\notAValidMathCommand{1}\\) 后续内容仍在")
            assertFalse(mathSpans(view.text as Spanned).single().drawable.hasResult())
            assertTrue(view.text.contains("notAValidMathCommand"))
            assertTrue(view.text.contains("后续内容仍在"))
        } finally { activity.pause().stop().destroy() }
    }
    @Test fun nativeDrawablesRenderFractionsRootsCasesMatricesAndDarkTheme() {
        val source="""
            ## 高中数学

            行内分数 \(\frac{1}{2}\)，根号 \(\sqrt{3}\)，上下标 \(a_1^2\)。

            \[
            x=\frac{-b\pm\sqrt{b^2-4ac}}{2a}
            \]

            \[
            \begin{cases}x+y=3\\x-y=1\end{cases}
            \]

            \[
            \begin{pmatrix}1&2\\3&4\end{pmatrix}\quad\sum_{i=1}^{n}i=\frac{n(n+1)}{2}
            \]
        """.trimIndent()
        render(source,Color.BLACK,Color.WHITE,22f,540,"light")
        render(source,Color.WHITE,Color.rgb(15,28,21),22f,540,"dark")
        render(source,Color.BLACK,Color.WHITE,34f,340,"large-font")
    }
    private fun render(source:String,foreground:Int,background:Int,size:Float,width:Int,name:String) {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val view=createMarkdownTextView(activity.get());view.setTextColor(foreground);view.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);view.setBackgroundColor(background);view.setPadding(20,20,20,20)
            activity.get().setContentView(view)
            val renderer=renderer(size,foreground);renderer.setMarkdown(view,source)
            repeat(3){
                view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));view.layout(0,0,width,view.measuredHeight)
                view.draw(Canvas(Bitmap.createBitmap(width,view.measuredHeight.coerceAtLeast(1),Bitmap.Config.ARGB_8888)))
                shadowOf(Looper.getMainLooper()).idle()
            }
            val spans=mathSpans(view.text as Spanned);assertEquals(6,spans.size)
            for(span in spans){assertTrue("Formula should have a rendered drawable: ${span.drawable.destination}",span.drawable.hasResult());assertTrue(span.drawable.bounds.height()>0);assertTrue(span.drawable.bounds.width()<=width-40)}
            val bitmap=Bitmap.createBitmap(width,view.measuredHeight,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);view.draw(canvas)
            assertTrue(view.isTextSelectable)
            val directory=File("build/reports/math-previews").apply{mkdirs()};File(directory,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }finally{activity.pause().stop().destroy()}
    }
}
