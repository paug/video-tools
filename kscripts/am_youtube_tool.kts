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
//DEPS com.opencsv:opencsv:4.0

import org.docopt.Docopt
import java.io.File
import kotlin.system.exitProcess
import com.opencsv.CSVReader


val doc = """Usage:
    am_youtube_tool.kts update --input-data=INPUT --mapping=MAPPING --thumbnails=THUMBNAILS
    am_youtube_tool.kts upload --input-data=INPUT
    am_youtube_tool.kts language --csv-data=INPUT
    am_youtube_tool.kts categories
    am_youtube_tool.kts channels


Options:
    --input-data=INPUT json where the title and metadata are stored
    --mapping=MAPPING  json with sessionId as key and youtube videoId as value
    --csv-data=INPUT as csv file where the language is set
""".trimIndent()

val options = Docopt(doc).parse(args.toList())

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

val accessToken = getToken()

val okHttpClient by lazy {
    OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $accessToken")
                        .build())
            }.build()
}

when {
    options.get("upload") == true -> uploadVideo("/home/martin/Desktop/short.mp4", options.get("--input-data") as String)
    options.get("update") == true -> updateAllMetaData(options.get("--input-data") as String, options.get("--mapping") as String, options.get("--thumbnails") as String)
    options.get("categories") == true -> showCategories()
    options.get("channels") == true -> showChannels()
    options.get("language") == true -> setLanguage(options.get("--csv-data") as String)
}

fun uploadVideo(path: String, inputDataPath: String) {
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

fun updateAllMetaData(inputDataPath: String, mappingPath: String, thumbnailsPath: String) {
    val inputData = listAdapter.fromJson(File(inputDataPath).readText())!!
    val mapping = mapAdapter.fromJson(File(mappingPath).readText())!!

    inputData.forEach { it ->
        val data = it as Map<String, Any>

        val sessionId = (data.get("id website") as Double).toInt().toString()
        System.err.println("Session id =$sessionId")
        val videoId = mapping.get(sessionId) as String?
        if (videoId != null) {
            val thumbnail = File(thumbnailsPath, "$sessionId.png")
            updateMetaData(videoId, data)
            updateThumbnail(videoId, thumbnail)
        }
    }
}

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

fun updateMetaData(videoId: String, data: Map<String, Any>) {
    System.err.println("updateMetaData:  ($videoId): ${data.get("Title")}")
    val rootJson = mapOf(
            "id" to videoId,
            "snippet" to mapOf(
                    "title" to data.get("YoutubeTitle"),
                    "description" to data.get("YoutubeDesc"),
                    "tags" to (data.get("tags") as String).split(",").map { it.trim() },
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

fun setLanguage(csvDataPath: String) {
    val csvReader = CSVReader(File(csvDataPath).bufferedReader())

    val recordList = csvReader.readAll()
            .drop(1) // drop the header row
            .filter {
                // Drop the title rows
                Regex("[A-Z]{3}-[0-9]{4}").matchEntire(it[0]) != null
            }

    recordList.forEach {
        val regex = Regex(".*https://youtube.com/watch\\?v=(.*)")
        val m = regex.matchEntire(it[10])
        if (m == null) {
            System.err.println("${it[10]} does not match")
            return@forEach
        }
        val sessionId = it[0]
        val videoId = m.groupValues[1]
        val language = it[15]
        System.out.println("$sessionId - $videoId - $language")
    }


}
