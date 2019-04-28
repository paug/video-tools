#!/usr/bin/env kscript
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import fi.iki.elonen.NanoHTTPD
import okhttp3.*
import java.net.URLEncoder

//DEPS com.squareup.okhttp3:okhttp:3.14.1
//DEPS com.squareup.moshi:moshi:1.8.0
//DEPS org.nanohttpd:nanohttpd:2.2.0
//DEPS com.offbytwo:docopt:0.6.0.20150202

import org.docopt.Docopt
import java.io.File
import kotlin.system.exitProcess

val doc = """Usage: upload_videos.kts upload --input-data=INPUT
    upload_videos.kts metadata --input-data=INPUT

    --input-data=INPUT json where the title and metadata are stored
""".trimIndent()

val options = Docopt(doc).parse(args.toList())

val moshi = Moshi.Builder().build()!!
val mapAdapter = moshi.adapter<Map<String, Any>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))!!
val listAdapter = moshi.adapter<List<Any>>(Types.newParameterizedType(List::class.java, Any::class.java))!!

val configPath = "${System.getenv("HOME")}/.amYoutube"
val config = mapAdapter.fromJson(File(configPath).readText())!!
val inputDataPath = options.get("--input-data") as String

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

//uploadVideo("/home/martin/dev/am_videos/out/NTE-5380.mp4")
val accessToken = getToken()

if (options.get("upload") == true) {
    uploadVideo("/home/martin/Desktop/short.mp4")
} else if (options.get("metadata") == true) {
    updateMetaData()
}

fun uploadVideo(path: String) {
    val rootJson = mapOf(
            "snippet" to mapOf(
                    "title" to "title",
                    "description" to "description"
            ),
            "status" to mapOf(
                    "privacyStatus" to "private"
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
            .header("Authorization", "Bearer $accessToken")
            .build()

    val response = OkHttpClient()
            .newCall(request)
            .execute()

    val responseBody = response.body()?.string()
    System.err.println("response=$responseBody")

    val ytVideo = mapAdapter.fromJson(responseBody)!!
    val inputData = listAdapter.fromJson(File(inputDataPath).readText())!!

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
    File(inputDataPath).writeText(listAdapter.toJson(newInputData))
}


fun openBrowser(url: String) {
    val candidates = arrayOf("xdg-open", "open")


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
    val responseBody = response.body()?.string()
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

fun updateMetaData() {
    val inputData = listAdapter.fromJson(File(inputDataPath).readText())!!

    inputData.forEach { it ->
        val data = it as Map<String, Any>
        val ytId = data.get("ytId") as String?
        if (ytId != null) {
            System.err.println("updating ${data.get("da")} ($ytId): ${data.get("title")}")
            val rootJson = mapOf(
                    "id" to ytId,
                    "snippet" to mapOf(
                            "title" to data.get("Youtube title"),
                            "description" to data.get("Desc"),
                            "tags" to (data.get("tags") as String).split(",").map { it.trim() }
                    )
            )

            val snippetBody = RequestBody.create(MediaType.parse("application/json"), mapAdapter.toJson(rootJson))
            val request = Request.Builder()
                    .put(snippetBody)
                    .url("https://www.googleapis.com/youtube/v3/videos?part=snippet")
                    .header("Authorization", "Bearer $accessToken")
                    .build()

            val response = OkHttpClient()
                    .newCall(request)
                    .execute()

            val responseBody = response.body()?.string()
            System.err.println("response=$responseBody")
        }
    }
}
