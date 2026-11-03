package com.xukaizhe.codexlink

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MessageTimeTest {
    private val zone=ZoneId.of("Asia/Hong_Kong")
    private fun ms(text:String)=Instant.parse(text).toEpochMilli()
    @Test fun nativeMillisecondsAndCompletionAreUsedWithoutGuessingTheLoadTime() {
        val start=ms("2026-11-02T15:59:00Z");val end=ms("2026-11-02T16:01:00Z")
        val user=BridgeCodec.item("turn",JSONObject().put("type","userMessage").put("id","u").put("startedAtMs",start).put("completedAtMs",end))!!
        val ai=BridgeCodec.item("turn",JSONObject().put("type","agentMessage").put("id","a").put("text","answer").put("startedAtMs",start).put("completedAtMs",end))!!
        assertEquals("23:59",user.timeLabel(zone));assertEquals("00:01",ai.timeLabel(zone))
        assertEquals(mapOf(user.id to "2026年11月2日",ai.id to "2026年11月3日"),messageDates(listOf(user,ai),zone))
        assertEquals("2026年11月2日",ai.dateLabel(ZoneId.of("UTC")))
        assertEquals("2026年11月3日 00:01:00",ai.fullTimeLabel(zone))
        assertEquals("2026年11月2日 16:01:00",ai.fullTimeLabel(ZoneId.of("UTC")))
    }
    @Test fun missingAndMalformedTimesStayUnknown() {
        for(value in listOf<Any>(JSONObject.NULL,"2026-11-02T16:00:00Z",0,-1,1.5,Long.MAX_VALUE)) {
            val entry=BridgeCodec.item("t",JSONObject().put("id","a").put("type","agentMessage").put("startedAtMs",value))!!
            assertNull(entry.timeMillis());assertEquals("时间未知",entry.timeLabel(zone));assertTrue(messageDates(listOf(entry),zone).isEmpty())
            assertNull(entry.fullTimeLabel(zone))
        }
        assertEquals("回复中 · 时间待记录",Entry("a","t","assistant","",live=true).timeLabel(zone))
    }
    @Test fun sameDayPaginationAndUnknownMessagesKeepHonestDateBoundaries() {
        val a=Entry("a","t","user","",startedAtMs=ms("2026-11-03T00:00:00Z"))
        val b=a.copy(id="b");val unknown=Entry("u","t","assistant","")
        assertEquals(mapOf("a" to "2026年11月3日"),messageDates(listOf(a,b),zone))
        assertEquals(mapOf("a" to "2026年11月3日","b" to "2026年11月3日"),messageDates(listOf(a,unknown,b),zone))
        assertEquals(a.startedAtMs,a.copy(text="streamed reply",live=true).startedAtMs)
    }
}
