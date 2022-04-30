#!/usr/bin/env kotlin

@file:DependsOn("com.squareup.okhttp3:okhttp:3.14.1")
@file:DependsOn("com.squareup.moshi:moshi:1.8.0")
@file:DependsOn("com.github.ajalt:clikt:2.6.0")
@file:DependsOn("org.nanohttpd:nanohttpd:2.2.0")
@file:DependsOn("com.univocity:univocity-parsers:2.8.4")

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.univocity.parsers.csv.CsvParser
import com.univocity.parsers.csv.CsvParserSettings
import fi.iki.elonen.NanoHTTPD
import okhttp3.*
import java.net.URLEncoder
import java.io.File
import kotlin.system.exitProcess


val moshi = Moshi.Builder().build()!!
val mapAdapter = moshi.adapter<Map<String, Any>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))!!
val listAdapter = moshi.adapter<List<Any>>(Types.newParameterizedType(List::class.java, Any::class.java))!!

val configPath = "${System.getenv("HOME")}/.am_youtube_tool"

if (!File(configPath).exists()) {
    System.err.println("""
        am_youtube_tool.kts needs a configured GCP project with the Youtube Data API v3 enabled.
        See https://developers.google.com/youtube/v3/getting-started for how to do this.
        Once done, put your client_id and client_secret in $configPath

        echo '{
            "client_id": "yout_client_id",
            "client_secret": "your_client_secret"
        }' > $configPath

        am_youtube_tool.kts will also store your Youtube token in this file so do not share it
    """.trimIndent())
    exitProcess(1)
}

val config = mapAdapter.fromJson(File(configPath).readText())!!

val clientSecret = try {
    config.get("client_secret") as String
} catch (e: Exception) {
    System.err.println("No client_secret found in $configPath")
    exitProcess(1)
}
val clientId = try {
    config.get("client_id") as String
} catch (e: Exception) {
    System.err.println("No client_id found in $configPath")
    exitProcess(1)
}

fun openBrowser(url: String) {
    val candidates = arrayOf("open", "xdg-open")


    candidates.forEach {
        val exitCode = ProcessBuilder(it, url)
                .start()
                .waitFor()
        if (exitCode == 0) {
            return
        }
    }

    throw Exception("impossible to open $url")
}

fun getToken(): String {
    val token = config.get("access_token") as String?
    val expiresAt = (config.get("expires_at") as Double?)?.toLong() ?: 0L

    if (token != null && System.currentTimeMillis() < expiresAt) {
        return token
    }

    return getNewToken()
}

fun getNewToken(): String {
    val port = 9867
    val client_id = clientId
    val client_secret = clientSecret
    val redirect_uri = "http://localhost:$port"
    val scopes = listOf("https://www.googleapis.com/auth/youtube.upload",
            "https://www.googleapis.com/auth/youtube").joinToString(" ")
    val oauthUrl = "https://accounts.google.com/o/oauth2/v2/auth" +
            "?redirect_uri=${URLEncoder.encode(redirect_uri)}" +
            "&prompt=consent" +
            "&response_type=code" +
            "&client_id=${URLEncoder.encode(client_id)}" +
            "&scope=${URLEncoder.encode(scopes)}" +
            "&access_type=offline"


    var code: String? = null
    val server = object : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val c = session.parms["code"]

            if (c != null && code == null) {
                // we might be called several times (think favicon.ico)
                code = session.parms["code"]
            }

            return newFixedLengthResponse("yay, you've been authorized !")
        }
    }

    server.start()
    openBrowser(oauthUrl)

    while (code == null) {
        Thread.sleep(300)
    }

    val tokenUrl = "https://www.googleapis.com/oauth2/v4/token"

    val body = FormBody.Builder()
            .add("client_id", client_id)
            .add("code", code)
            .add("redirect_uri", redirect_uri)
            .add("client_secret", client_secret)
            .add("grant_type", "authorization_code")
            .build()

    val request = Request.Builder()
            .post(body)
            .url(tokenUrl)
            .build()


    val response: okhttp3.Response

    try {
        response = OkHttpClient()
                .newCall(request)
                .execute()
    } catch (e: Exception) {
        throw Exception("cannot exchange code")
    }
    val responseBody = response.body()?.string()!!
    System.err.println("response=$responseBody")

    if (!response.isSuccessful) {
        throw Exception("cannot exchange code")
    }

    val map = mapAdapter.fromJson(responseBody)!!

    val newConfig = config.toMutableMap()
    newConfig.put("access_token", map.get("access_token") as String)
    newConfig.put("expires_at", (map.get("expires_in") as Double).toLong() * 1000 + System.currentTimeMillis())

    File(configPath).writeText(mapAdapter.toJson(newConfig))
    return newConfig.get("access_token") as String
}

val accessToken by lazy {
    getToken()
}

val okHttpClient by lazy {
    OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $accessToken")
                        .build())
            }.build()
}

class VideoInfo(
        val websiteId: String,
        val youtubeId: String,
        val title: String,
        val description: String,
        val tags: List<String>
)

fun getVideoInfosFromCsv(file: File): List<VideoInfo> {
    val records = file.reader().use { reader ->
        CsvParser(CsvParserSettings()).parseAll(reader)
    }

    return records.drop(1) // drop the headers
            .mapNotNull {
                //println(it.joinToString("!"))
                val track = it['F' - 'A']
                val websiteId = it['G' - 'A']
                val youtubeId = it['H' - 'A']?.substring("https://youtu.be/".length)
                val title = it['M' - 'A']
                val description = it['N' - 'A']
                val tags = it['O' - 'A']?.split(",")?.map { it.trim() } ?: emptyList()

                println("$title - $track - $websiteId - $youtubeId")
                if (track == null || websiteId == null || youtubeId == null) {
                    null
                } else {
                    VideoInfo(youtubeId = youtubeId,
                            websiteId = websiteId,
                            description = description,
                            title = title,
                            tags = tags
                    )
                }
            }
}

val updateCommand = object : CliktCommand(name = "update") {
    val infosCsv by option().required()
    val thumbnails by option().required()

    override fun run() {
        val videoInfos = getVideoInfosFromCsv(File(infosCsv))

        videoInfos.forEach { it ->
            val thumbnail = File(thumbnails, "${it.websiteId}.png")
            println("hello $it")
            updateMetaData(it)
            updateThumbnail(it.youtubeId, thumbnail)
        }
    }

    fun updateMetaData(videoInfo: VideoInfo) {
        System.err.println("updateMetaData:  $videoInfo")
        val rootJson = mapOf(
                "id" to videoInfo.youtubeId,
                "snippet" to mapOf(
                        "title" to videoInfo.title,
                        "description" to videoInfo.description,
                        "tags" to videoInfo.tags,
                        "categoryId" to "28"
                )
        )

        val snippetBody = RequestBody.create(MediaType.parse("application/json"), mapAdapter.toJson(rootJson))
        val request = Request.Builder()
                .put(snippetBody)
                .url("https://www.googleapis.com/youtube/v3/videos?part=snippet")
                .build()

        val response = okHttpClient
                .newCall(request)
                .execute()

        val responseBody = response.body()?.string()
        System.err.println("response=$responseBody")
    }

    fun updateThumbnail(videoId: String, thumbnailFile: File) {
        val imageBytes = thumbnailFile.readBytes()

        val response = okHttpClient.newCall(
                Request.Builder()
                        .post(RequestBody.create(MediaType.parse("image/png"), imageBytes))
                        .url("https://www.googleapis.com/upload/youtube/v3/thumbnails/set?videoId=$videoId")
                        .build())
                .execute()

        val responseBody = response.body()?.string()
        System.err.println("response=$responseBody")
    }
}

val uploadCommand = object : CliktCommand(name = "upload") {
    val inputDataPath by option().required()
    val path by option().required()

    override fun run() {
        val rootJson = mapOf(
                "snippet" to mapOf(
                        "title" to "title",
                        "description" to "description"
                ),
                "status" to mapOf(
                        "privacyStatus" to "unlisted"
                )
        )

        val snippetBody = RequestBody.create(MediaType.parse("application/json"), mapAdapter.toJson(rootJson))
        val videoBody = RequestBody.create(MediaType.parse("application/octet-stream"), File(path))
        val body = MultipartBody.Builder()
                .addFormDataPart("snippet", "snippet", snippetBody)
                .addFormDataPart("video", path.substringAfterLast("/"), videoBody)
                .build()

        val request = Request.Builder()
                .post(body)
                .url("https://www.googleapis.com/upload/youtube/v3/videos?part=snippet,status")
                .build()

        val response = okHttpClient
                .newCall(request)
                .execute()

        val responseBody = response.body()?.string()
        System.err.println("response=$responseBody")

        val ytVideo = mapAdapter.fromJson(responseBody)!!
        val inputData = listAdapter.fromJson(File(inputDataPath!!).readText())!!

        val newInputData = inputData.map {
            val e = it as Map<String, Any>
            if (e.get("da") == path.substringAfterLast("/").substringBefore(".")) {
                val m = it.toMutableMap()
                m.put("ytId", ytVideo.get("id") as String)
                m
            } else {
                it
            }
        }

        // save the youtubeId somewhere
        File(inputDataPath!!).writeText(listAdapter.toJson(newInputData))
    }
}


object : CliktCommand() {
    override fun run() {
    }
}.subcommands(updateCommand,
        uploadCommand)
        .main(args)

fun showCategories() {
    val response = okHttpClient
            .newCall(Request.Builder()
                    .get()
                    .url("https://www.googleapis.com/youtube/v3/videoCategories?part=snippet&regionCode=US")
                    .build())
            .execute()

    val responseBody = response.body()?.string()
    System.err.println("response=$responseBody")
}

fun showChannels() {
    val response = okHttpClient
            .newCall(Request.Builder()
                    .get()
                    .url("https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true")
                    .build())
            .execute()

    val responseBody = response.body()?.string()
    System.err.println("response=$responseBody")
}


