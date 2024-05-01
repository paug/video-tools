package internal
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.squareup.moshi.Moshi
import com.univocity.parsers.csv.CsvParser
import com.univocity.parsers.csv.CsvParserSettings
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File

val moshi = Moshi.Builder().build()!!
val anyAdapter = moshi.adapter(Any::class.java)

inline fun <reified T> Any?.cast(): T = this as T
fun Any?.asMap() = this.cast<Map<String, Any?>>()
fun Any?.asList() = this.cast<List<Any?>>()

val configPath = "${System.getenv("HOME")}/.am_youtube_tool"

fun getToken(): String {

    if (File(configPath).exists()) {
        return File(configPath).readText()
    }
    println("""
        |This script requires a token with Youtube Data API v3 scope. Go to https://developers.google.com/oauthplayground to get one.
        |This is needlessly convoluted because we need to access the data of a Brand account and brand accounts cannot be registered as test users.
        |1. Create a GCP project and enable Youtube Data API v3
        |2. Configure a simple external oauth consent screen and leave it in 'testing' 
        |3. Create a web application credential and configure the oauth playground as a redirect uri
        |4. In playground, hit the grey cog and configure it to use the client_secret and client_id of the web application just create
        |5. Add Youtube Data API v3 in your scope
        |6. Authorize and exchange your code. This token should be what you copy/paste here
    """.trimMargin())
    print("Token: ")
    val token = readLine() ?: error("a token is required")
    println("")
    File(configPath).writeText(token)
    return token
}

val accessToken by lazy {
    getToken()
}

val okHttpClient by lazy {
    OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $accessToken")
                    .build()
            )
        })
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()
}

class YoutubeVideoInfo(
    val websiteId: String,
    val youtubeId: String,
    val title: String,
    val description: String,
    val tags: List<String>
)

fun getVideoInfos(jsonFile: File, csvFile: File): List<YoutubeVideoInfo> {
    val mapping = csvFile
        .reader()
        .use { reader ->
            CsvParser(CsvParserSettings()).parseAll(reader)
        }
        .drop(1)
        .mapNotNull {
            val websiteId = it[0]

            val youtubeLink = it[9]
            if (youtubeLink == null) {
                // For workshops, we do not upload to YT
                error("No youtubeLink for $websiteId")
            }
            val regex1 = Regex("https://www.youtube.com/watch\\?v=(.*)")
            val regex2 = Regex("https://youtu.be/(.*)")
            var matchResult = regex1.matchEntire(youtubeLink)
            val youtubeId: String
            if (matchResult != null) {
                youtubeId = matchResult.groupValues[1]
            } else {
                matchResult = regex2.matchEntire(youtubeLink)
                if (matchResult != null) {
                    youtubeId = matchResult.groupValues[1]
                } else {
                    error("Cannot find youtube id in ${youtubeLink}")
                }
            }

            websiteId to youtubeId
        }.toMap()


    val records = anyAdapter.fromJson(jsonFile.readText()).asList().map { it.asMap() }

    return mapping.map { entry ->
        val record = records.firstOrNull { it.get("id") == entry.key } ?: error("Cannot find record fo $entry")
        val websiteId = record["id"].cast<String?>() ?: error("No websiteId found for ${entry.key}")
        YoutubeVideoInfo(
            youtubeId = entry.value,
            websiteId = websiteId,
            description = record["description"].cast() ?: error("No youtubeId found for $websiteId"),
            title = record["title"].cast() ?: error("No youtubeId found for $websiteId"),
            tags = record["tags"].asList().map { it.cast<String>().trim() }
        )
    }
}

fun updateYoutube() {
    val videoInfos = getVideoInfos(videoMetadataJson, videoInfosCsv)

    videoInfos.forEach {
        if (!setOf("642744").contains(it.websiteId) ) {
            return@forEach
        }

        val thumbnail = speakerCardsById.media(it.websiteId)

        updateMetaData(it)
        updateThumbnail(it.youtubeId, thumbnail)
        // rate limiting
        Thread.sleep(20_000)
    }
}



fun updateMetaData(videoInfo: YoutubeVideoInfo) {
    val rootJson = mapOf(
        "id" to videoInfo.youtubeId,
        "snippet" to mapOf(
            "title" to videoInfo.title,
            "description" to videoInfo.description,
            "tags" to videoInfo.tags,
            "categoryId" to "28"
        )
    )

    val snippetBody = anyAdapter.toJson(rootJson).toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
        .put(snippetBody)
        .url("https://www.googleapis.com/youtube/v3/videos?part=snippet")
        .build()

    val response = okHttpClient
        .newCall(request)
        .execute()

    response.use {
        if (!it.isSuccessful) {
            error("error: ${it.body?.string()}")
        }
    }
}

fun updateThumbnail(videoId: String, thumbnailFile: File) {
    val imageBytes = thumbnailFile.readBytes()

    val response = okHttpClient.newCall(
        Request.Builder()
            .post(imageBytes.toRequestBody("image/png".toMediaType()))
            .url("https://www.googleapis.com/upload/youtube/v3/thumbnails/set?videoId=$videoId")
            .build()
    )
        .execute()

    response.use {
        if (!it.isSuccessful) {
            error("error: ${it.body?.string()}")
        }
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

        val snippetBody = anyAdapter.toJson(rootJson).toRequestBody("application/json".toMediaType())
        val videoBody = File(path).asRequestBody("application/octet-stream".toMediaTypeOrNull())
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

        val responseBody = response.body?.string()

        val ytVideo = anyAdapter.fromJson(responseBody)!!.asMap()
        val inputData = anyAdapter.fromJson(File(inputDataPath!!).readText())!!.asList()

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
        File(inputDataPath).writeText(anyAdapter.toJson(newInputData))
    }
}


fun showCategories() {
    val response = okHttpClient
        .newCall(
            Request.Builder()
                .get()
                .url("https://www.googleapis.com/youtube/v3/videoCategories?part=snippet&regionCode=US")
                .build()
        )
        .execute()

    val responseBody = response.body?.string()
}

fun showChannels() {
    val response = okHttpClient
        .newCall(
            Request.Builder()
                .get()
                .url("https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true")
                .build()
        )
        .execute()

    val responseBody = response.body?.string()
}


