#!/usr/bin/env kotlin

@file:DependsOn("com.opencsv:opencsv:3.8")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
@file:DependsOn("com.squareup.okhttp3:okhttp:4.12.0")

import com.opencsv.CSVWriter
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File


private val json = Json {
    ignoreUnknownKeys = true
}

fun JsonElement.toAny(): Any? = when (this) {
    is JsonArray -> {
        this.map { it.toAny() }
    }
    is JsonObject -> {
        this.keys.map { it to get(it)?.toAny() }.toMap()
    }
    JsonNull -> null
    is JsonPrimitive -> {
        when  {
            isString -> this.content
            content == "true" -> true
            content == "false" -> false
            content.contains(".") -> content.toDouble()
            else -> content.toInt()
        }
    }
}

inline fun <reified T> Any?.cast() = this as T
fun Any?.asMap(): Map<String, *> = this.cast()
fun Any?.asList(): List<*> = this.cast()
fun Any?.asString(): String = this.cast()
fun Any?.asInt(): Int = this.cast()
fun Any?.asDouble(): Double = this.cast()
fun Any?.asBoolean(): Boolean = this.cast()

fun sessionizeData(): Map<String, Any?> {
    val url = "https://sessionize.com/api/v2/ok1n6jgj/view/All"

    return Request.Builder()
        .get()
        .url(url)
        .build()
        .let {
            OkHttpClient()
                .newCall(it)
                .execute()
        }.let {
            json.parseToJsonElement(it.body!!.string()).toAny().asMap()
        }
}

val data = sessionizeData()

class Session(
    val id: String,
    val room: String,
    val title: String,
    val speakers: String,
    val startsAt: String,
    val endsAt: String
)
val rooms = data.get("rooms").asList().map { it.asMap() }
val speakers = data.get("speakers").asList().map { it.asMap() }
val sessions = data.get("sessions").asList().map { it.asMap() }.mapNotNull {
    if (it.get("isServiceSession").asBoolean()) {
        return@mapNotNull null
    }
    Session(
        it.get("id").cast(),
        it.get("roomId").let { roomId ->
            rooms.firstOrNull { it.get("id") == roomId }?.get("name") ?: "unknown"
        }.cast(),
        it.get("title").cast(),
        it.get("speakers").asList().map { it.asString() }.let { speakerIds ->
            speakers.filter { speakerIds.contains(it.get("id")) }.map { it.get("fullName") }.joinToString(",")
        },
        it.get("startsAt").cast(),
        it.get("endsAt").cast(),
    )
}

File("output.csv").outputStream().writer().use {
    CSVWriter(it).apply {
        writeNext(arrayOf("id", "room", "title", "speakers", "startsAt", "endsAt"))
        sessions.groupBy { it.room }
            .mapValues {
                it.value.sortedBy { it.startsAt }
            }
            .entries
            .flatMap {
                it.value
            }
            .forEach {
                writeNext(arrayOf(it.id, it.room, it.title, it.speakers, it.startsAt, it.endsAt))
            }
    }
}
