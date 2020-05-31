#!/usr/bin/env kscript
@file:DependsOn("com.github.ajalt:clikt:2.6.0")
@file:DependsOn("com.google.code.gson:gson:2.8.5")
@file:DependsOn("com.univocity:univocity-parsers:2.8.4")

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.univocity.parsers.csv.CsvParser
import com.univocity.parsers.csv.CsvParserSettings
import java.io.File


val SPONSORS_FADE_START_MS = 5000
val SPONSORS_FADE_END_MS = 6000
val INTRO_FADE_START_MS = 8000
val INTRO_FADE_END_MS = 9000

fun doGenerateVideo(video: File,
                    sponsorsImage: File,
                    introImage: File,
                    outDir: File,
                    scratchDir: File,
                    videoId: String,
                    startSec: Int,
                    endSec: Int,
                    skipExisting: Boolean) {
    val path = video.absolutePath
    val outDirPath = outDir.absolutePath
    val scratchDirPath = scratchDir.absolutePath

    scratchDir.mkdirs()

    System.out.println("generateVideo: $videoId")
    val h264Path = "$scratchDirPath/$videoId.h264"
    val sponsorsPath = "$scratchDirPath/sponsors.png"
    val pngPath = "$scratchDirPath/intro.png"
    val h264SponsorsPath = "$scratchDirPath/$videoId.sponsors.h264"
    val h264IntroPath = "$scratchDirPath/$videoId.intro.h264"

    val h264BodyPath = "$scratchDirPath/$videoId.body.h264"
    val h264MergedPath = "$scratchDirPath/$videoId.merged.h264"
    val h264TrimmedPath = "$scratchDirPath/$videoId.trimmed.mp4"
    val aacPath = "$scratchDirPath/$videoId.aac"
    val finalPath = "$outDirPath/$videoId.mp4"

    if (skipExisting && File(finalPath).exists()) {
        System.out.println("skipping existing file: $finalPath")
        return
    }

    val parameters = getParameters(video)
    val resolution = parameters.resolution
    val fps = parameters.fps.toDouble()

    System.out.println("parameters=$parameters")

    System.out.println("--- resize inputs: $videoId")
    execOrDie("convert ${introImage.absolutePath} -resize $resolution $pngPath")
    execOrDie("convert ${sponsorsImage.absolutePath} -resize $resolution $sponsorsPath")

    System.out.println("--- create sponsors.h264: $videoId")
    var fadeStartSec = SPONSORS_FADE_START_MS / 1000
    var fadeDurationSec = (SPONSORS_FADE_END_MS - SPONSORS_FADE_START_MS) / 1000

    //create the sponsors, use the original source, not the h264 stream to get the timestamps
    val durationSec = SPONSORS_FADE_END_MS / 1000
    val sponsorsCommand = "ffmpeg -y" +
            " -loop 1 -framerate $fps -t $durationSec -i $sponsorsPath" +
            " -loop 1 -framerate $fps -t $durationSec -i $pngPath" +
            " -filter_complex [0:v]format=pix_fmts=yuva420p,fade=t=out:st=$fadeStartSec:d=$fadeDurationSec:alpha=1[intro];" +
            "[1:v][intro]overlay" +
            " -b:v 3M $h264SponsorsPath"
    execOrDie(sponsorsCommand)

    System.out.println("--- extract H264 elementary stream: $videoId")
    execOrDie("ffmpeg -y -i $path -vcodec copy -vbsf h264_mp4toannexb $h264Path")

    System.out.println("--- Get volume correction: $videoId")
    val correction = getVolumeCorrection(path)

    System.out.println("--- Find next IFRAME: $videoId")
    val nextIFrame = findNextIFrameInfo(h264Path, startSec + 40, fps)
    // Removed 0.01 to make sure to not include the last extra frame which will be in the body when rounding
    val roundingSecurity = 0.01
    val trimTime = nextIFrame.number.toFloat() / fps - roundingSecurity

    System.out.println("Trim time: $trimTime")

    System.out.println("--- Creating $h264BodyPath")
    execOrDie("dd bs=${nextIFrame.pos} if=$h264Path of=$h264BodyPath skip=1")

    fadeStartSec = (INTRO_FADE_START_MS - SPONSORS_FADE_END_MS) / 1000
    fadeDurationSec = (INTRO_FADE_END_MS - INTRO_FADE_START_MS) / 1000

    //create the intro, use the original source, not the h264 stream to get the timestamps
    System.out.println("--- Creating $h264IntroPath")
    val introCommand = "ffmpeg -y" +
            " -loop 1 -framerate $fps -t 10 -i $pngPath" +
            " -i $path" +
            " -filter_complex [0:v]format=pix_fmts=yuva420p,fade=t=out:st=$fadeStartSec:d=$fadeDurationSec:alpha=1[intro];" +
            "[1:v]trim=$startSec:$trimTime,setpts=PTS-STARTPTS+$fadeStartSec/TB[content];" +
            "[content][intro]overlay" +
            " -b:v 3M $h264IntroPath"
    System.out.println(introCommand)
    execOrDie(introCommand)

    // assemble intro and body
    System.out.println("--- Merge h264 to $h264MergedPath")
    concatFiles(h264MergedPath, h264SponsorsPath, h264IntroPath, h264BodyPath)

    //encode the audio stream, with the fade in and volume filter
    val monoFilter = if (!parameters.mono) {
        "pan=mono|c0=FR,"
    } else {
        "" // nothing to do
    }
    val audioCommand = "ffmpeg -y " +
            "-i $path " +
            "-filter_complex [0:a]${monoFilter}atrim=$startSec,asetpts=PTS-STARTPTS,afade=t=in:st=0:d=1,volume=${correction}dB,adelay=$INTRO_FADE_START_MS" +
            " $aacPath"
    execOrDie(audioCommand)

    //merge audio and video streams
    System.out.println("--- Merge audio and video to $h264TrimmedPath")
    val mergeCommand = "ffmpeg -y -i $aacPath -r $fps -i $h264MergedPath -vcodec copy -acodec copy $h264TrimmedPath"
    execOrDie(mergeCommand)

    //trim audio and video streams and place the output in the final path
    System.out.println("--- Trim merged video to $finalPath")
    val trimCommand = "ffmpeg -y -i $h264TrimmedPath -to ${(endSec - startSec) + INTRO_FADE_START_MS / 1000} -c copy $finalPath"
    execOrDie(trimCommand)
}

data class Parameters(val fps: String, val resolution: String, val mono: Boolean)

fun getParameters(video: File): Parameters {
    val process = ProcessBuilder("ffprobe", video.absolutePath)
            .start()
    val reader = process.errorStream.bufferedReader()

    var fps: String? = null
    var resolution: String? = null
    var mono = false

    val resolutionRegex = Regex(".*Video:.* ([0-9]+x[0-9]+)[^0-9].*")
    val fpsRegex = Regex(".*Video:.* (.*) fps,.*")
    val monoRegex = Regex(".*Audio:.*mono,.*")

    while (true) {
        val line = reader.readLine()
        if (line == null) {
            break
        }

        var match = resolutionRegex.matchEntire(line)
        if (match != null) {
            resolution = match.groupValues[1]
        }

        match = fpsRegex.matchEntire(line)
        if (match != null) {
            fps = match.groupValues[1]
        }

        match = monoRegex.matchEntire(line)
        if (match != null) {
            mono = true
        }
    }
    if (fps != null && resolution != null) {
        return Parameters(fps, resolution, mono)
    }

    throw IllegalStateException("Cannot find resolution in ${video.absolutePath}")
}

fun getVolumeCorrection(path: String): Float {
    //volume detection, will output something like this
    // We use mean_volume and not max_volume as typically, clapping is way higher than the rest of the talk
    //[Parsed_volumedetect_0 @ 0x3a5f900] n_samples: 128613376
    //[Parsed_volumedetect_0 @ 0x3a5f900] mean_volume: -48.0 dB
    //[Parsed_volumedetect_0 @ 0x3a5f900] max_volume: -27.1 dB
    //[Parsed_volumedetect_0 @ 0x3a5f900] histogram_27db: 157
    //[Parsed_volumedetect_0 @ 0x3a5f900] histogram_28db: 5479
    //[Parsed_volumedetect_0 @ 0x3a5f900] histogram_29db: 41345
    //[Parsed_volumedetect_0 @ 0x3a5f900] histogram_30db: 72813
    //[Parsed_volumedetect_0 @ 0x3a5f900] histogram_31db: 121176
    val volumeDetectCommand = "ffmpeg -i $path -af volumedetect -vn -sn -dn -f null /dev/null"
    System.out.println("Executing: $volumeDetectCommand")
    val process = ProcessBuilder(volumeDetectCommand.split(" "))
            .start()
    val reader = process.errorStream.bufferedReader()
    val meanVolume = reader.useLines { lines ->
        lines.mapNotNull {
            System.err.println(it)
            val m = Regex(".*mean_volume: ([0-9\\-.]*) dB").matchEntire(it)
            m?.groupValues?.get(1)?.toFloat()
        }.firstOrNull()
    }
    process.destroy()
    if (meanVolume == null) {
        throw Exception("Cannot find volume :-(")
    }
    System.err.println("meanVolume=$meanVolume")

    // Try to have a mean volume around -20dB
    return -20 - meanVolume
}

class FrameInfo(val pos: Long, val number: Int)

fun findNextIFrameInfo(h264Path: String, sec: Int, fps: Double): FrameInfo {
    //find the 1st IFrame after sec
    //will output something like this
    //    [FRAME]
    //    media_type=video
    //    stream_index=0
    //    key_frame=0
    //    pkt_pts=N/A
    //    pkt_pts_time=N/A
    //    pkt_dts=N/A
    //    pkt_dts_time=N/A
    //    best_effort_timestamp=N/A
    //    best_effort_timestamp_time=N/A
    //    pkt_duration=40000
    //    pkt_duration_time=0.033333
    //    pkt_pos=127753874
    //    pkt_size=388
    //    width=1920
    //    height=1080
    //    pix_fmt=yuv420p
    //    sample_aspect_ratio=N/A
    //    pict_type=B
    //    coded_picture_number=12253
    //    display_picture_number=0
    //    interlaced_frame=0
    //    top_field_first=0
    //    repeat_pict=0
    //    color_range=unknown
    //    color_space=unknown
    //    color_primaries=unknown
    //    color_transfer=unknown

    val command = "ffprobe -show_frames $h264Path"
    System.out.println("Executing: $command")
    val process = ProcessBuilder(command.split(" "))
            .start()
    val reader = process.inputStream.bufferedReader()
    val frameInfo = reader.useLines { lines ->
        var pos: Long = 0
        var isKey: Boolean = false
        var number: Int = 0

        val posRegex = Regex("pkt_pos=([0-9]*)")
        val numberRegex = Regex("coded_picture_number=([0-9]*)")
        val iterator = lines.iterator()

        while (iterator.hasNext()) {
            val line = iterator.next()
            if (line == "[FRAME]") {
                pos = 0
                isKey = false
                continue
            }
            if (line == "pict_type=I") {
                isKey = true
                continue
            }
            var m = posRegex.matchEntire(line)
            if (m != null) {
                pos = m.groupValues[1].toLong()
                continue
            }

            m = numberRegex.matchEntire(line)
            if (m != null) {
                number = m.groupValues[1].toInt()

                System.err.print("\r$number")
                if (number > sec * fps && isKey) {
                    break
                }
            }

        }

        if (pos == 0L || number == 0) {
            throw Exception("cannot find position")
        }
        FrameInfo(pos, number)
    }
    process.destroy()
    System.err.println("\npacket_pos=${frameInfo.pos}")
    return frameInfo
}

fun execOrDie(command: String) {
    println("""

        **********************************************
        Executing: $command
    """.trimIndent())
    val exitCode = ProcessBuilder(command.split(" "))
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
            .waitFor()
    if (exitCode != 0) {
        throw Exception("'$command': failed with exitCode=$exitCode")
    }
}

fun concatFiles(out: String, vararg inputs: String) {
    val outStream = File(out).outputStream()

    inputs.forEach {
        File(it).inputStream().use {
            it.copyTo(outStream)
        }
    }

    outStream.flush()
    outStream.close()
}

data class VideoInfo(
        @SerializedName("id website") val uid: String,
        @SerializedName("videoStart (mm:ss)") private val startTimeStr: String?,
        @SerializedName("videoEnd (mm:ss)") private val endTimeStr: String?
) {

    val startTime: Int
        get() = startTimeStr!!.split(":").let { 60 * it[0].toInt() + it[1].toInt() }

    val endTime: Int
        get() = endTimeStr!!.split(":").let {
            it.foldIndexed(0) { index, acc, value ->
                acc + value.toInt() * Math.pow(60.toDouble(), (it.size - 1 - index).toDouble()).toInt()
            }
        }
}

fun getVideoInfosFromJson(file: File): Map<String, VideoInfo> {
    val videoInfoStr = file.readText()
    val gson = Gson()
    val sType = object : TypeToken<List<VideoInfo>>() {}.type
    val videoInfos: List<VideoInfo> = gson.fromJson(videoInfoStr, sType)
    return videoInfos.map { it.uid to it }.toMap()
}

fun getVideoInfosFromCsv(file: File): Map<String, VideoInfo> {
    val records = file.reader().use { reader ->
        CsvParser(CsvParserSettings()).parseAll(reader)
    }

    return records.drop(1) // drop the headers
            .mapNotNull {
                println(it.joinToString("!"))

                val uid = it[6]
                val start = it[9]
                val end = it[10]
                if (uid == null) {
                    null
                } else {
                    VideoInfo(uid = uid,
                            startTimeStr = start,
                            endTimeStr = end
                    )
                }
            }.groupBy { it.uid }
            .mapValues { it.value.first() }
}

val generate = object : CliktCommand(name = "generate") {
    val video by option().required()
    val sponsorsImage by option().required()
    val introImage by option().required()
    val startSec by option().int().required()
    val outDir by option().required()
    val videoId by option().required()

    override fun run() {
        doGenerateVideo(
                video = File(video),
                sponsorsImage = File(sponsorsImage),
                introImage = File(introImage),
                outDir = File(outDir),
                scratchDir = File("$outDir/tmp"),
                videoId = videoId,
                startSec = startSec,
                endSec = 0,
                skipExisting = false
        )
    }
}

val batch = object : CliktCommand(name = "batch") {
    val inDir by option().required()
    val introDir by option().required()
    val sponsorPath by option().required()
    val infosPath by option()
    val infosCsv by option()
    val outDir by option().required()
    val skipExisting by option().flag()

    override fun run() {
        val outDirFile = File(outDir)
        val inDirFile = File(inDir)
        val sponsorFile = File(sponsorPath)

        val videoInfos = when {
            infosPath != null -> {
                getVideoInfosFromJson(File(infosPath))
            }
            infosCsv != null -> {
                getVideoInfosFromCsv(File(infosCsv))
            }
            else -> {
                throw IllegalArgumentException("Provide either --infos-path or --infos-csv")
            }
        }

        outDirFile.mkdirs()
        for (file in inDirFile.listFiles()) {
            if (file.extension != "mp4") {
                continue
            }

            val scratchDir = File(outDir, "/tmp")
            scratchDir.mkdirs()
            try {
                val start = System.currentTimeMillis()

                val videoId = file.nameWithoutExtension
                System.err.println("Generating video $videoId")

                val videoInfo = videoInfos.getValue(videoId)
                val introFile = File(introDir, "$videoId.png")

                doGenerateVideo(
                        video = file,
                        sponsorsImage = sponsorFile,
                        introImage = introFile,
                        outDir = outDirFile,
                        scratchDir = scratchDir,
                        videoId = videoId,
                        startSec = videoInfo.startTime,
                        endSec = videoInfo.endTime,
                        skipExisting = skipExisting)
                System.err.println("Generating video $videoId took ${(System.currentTimeMillis() - start) / 1000}s")
            } catch (e: Exception) {
                throw e
            } finally {
                scratchDir.deleteRecursively()
            }
        }
    }
}

object : CliktCommand() {
    override fun run() {
    }
}.subcommands(batch, generate)
        .main(args)