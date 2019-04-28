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

val doc = """Usage: upload_videos.kts (--clientSecret=SECRET | --accessToken=TOKEN)

    --clientSecret=SECRET  client secret
    --accessToken=TOKEN accessToken
""".trimIndent()

val options = Docopt(doc).parse(args.toList())

val accessToken = options.get("--accessToken") as String? ?: getToken()
val moshi = Moshi.Builder().build()

//uploadVideo("/home/martin/dev/am_videos/out/NTE-5380.mp4")
uploadVideo("/home/martin/Desktop/short.mp4")

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

    val adapter = moshi.adapter<Map<String,Any>>(Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    val snippetBody = RequestBody.create(MediaType.parse("application/json"), adapter.toJson(rootJson))
    val videoBody = RequestBody.create(MediaType.parse("application/octet-stream"), File(path))
    val body = MultipartBody.Builder()
            .addFormDataPart("snippet", adapter.toJson(rootJson))
            .addFormDataPart("video", path.substringAfterLast("/"), videoBody)
            .build()

    val request = Request.Builder()
            .post(body)
            .url("https://www.googleapis.com/upload/youtube/v3/videos?part=snippet")
            .header("Authorization", "Bearer $accessToken")
            .build()

    val response = OkHttpClient()
            .newCall(request)
            .execute()

    System.err.println("response=${response.body()?.string()}")
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
    val port = 9867
    val client_id = "108668004464-pvl0bjnv7kmlnunljom31nolrc1bl5gm.apps.googleusercontent.com"
    val client_secret = options.get("--clientSecret") as String
    val redirect_uri = "http://localhost:$port"
    val scopes = listOf("https://www.googleapis.com/auth/youtube.upload").joinToString(" ")
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
    System.err.println("response=${response.body()?.string()}")

    if (!response.isSuccessful) {
        throw Exception("cannot exchange code")
    }

    return ""
}