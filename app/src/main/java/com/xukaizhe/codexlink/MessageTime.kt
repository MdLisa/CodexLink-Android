package com.xukaizhe.codexlink

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun JSONObject.messageMillis(key:String):Long? {
    val number=opt(key) as? Number?:return null
    val value=number.toLong()
    return value.takeIf{it in 1..253402300799999L&&number.toDouble()==it.toDouble()}
}
internal fun Entry.timeMillis():Long? = if(role=="assistant")completedAtMs?:startedAtMs else startedAtMs?:completedAtMs
internal fun Entry.timeLabel(zone:ZoneId=ZoneId.systemDefault()):String = timeMillis()?.let {
    Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
}?:if(live)"回复中 · 时间待记录" else "时间未知"
internal fun Entry.dateLabel(zone:ZoneId=ZoneId.systemDefault()):String? = timeMillis()?.let {
    Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
}
internal fun Entry.fullTimeLabel(zone:ZoneId=ZoneId.systemDefault()):String? = timeMillis()?.let {
    Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss"))
}
internal fun messageDates(entries:List<Entry>,zone:ZoneId=ZoneId.systemDefault()):Map<String,String> {
    var previous:String?=null
    return buildMap { for(entry in entries)if(entry.role!="tool") {
        val date=entry.dateLabel(zone)
        if(date!=null&&date!=previous)put(entry.id,date)
        previous=date
    } }
}
