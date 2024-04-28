#!/usr/bin/env kotlin
@file:DependsOn("com.github.ajalt:clikt:2.6.0")
@file:DependsOn("com.squareup.okio:okio-jvm:3.3.0")
@file:DependsOn("com.google.code.gson:gson:2.8.5")
@file:DependsOn("com.univocity:univocity-parsers:2.8.4")
@file:Suppress("PropertyName")

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
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import java.io.File
import kotlin.math.pow


val INTRO_FADE_START = 3.0
val INTRO_FADE_END = 4.0
val OUTRO_DURATION = 17.0

/**
 * @param sponsors a png or mp4 file containing the sponsors. Will be looped and resized as needed to match video
 * @param sponsorsDuration the time to display the sponsor (not including fade)
 * @param video a h264 file containing the video
 *
 * @return the path to a raw h264 stream using parameters
 */
fun createOutro(
    sponsors: File,
    sponsorsDuration: Double,
    video: File,
    delay: Double,
    scratchPath: String,
    fadeDuration: Double,
    parameters: Parameters,
): String {
    val h264path = "$scratchPath/outro.h264"

    val fps = parameters.fps.toDouble()

    /**
     * loop sponsors and intro forever
     */
    val sponsorsArg = if (sponsors.extension == "png") {
        " -stream_loop -1 -framerate $fps -i ${sponsors.absolutePath}"
    } else {
        " -stream_loop -1 -i ${sponsors.absolutePath}"
    }

    val videoArg = " -framerate $fps -i ${video.absolutePath}"

    val command = "ffmpeg -y" + sponsorsArg  + videoArg +
            " -filter_complex " +
            // normalize resolution on sponsors
            "[0:v]scale=size=${parameters.resolution}[sponsors];" +
            //
            "[1:v]setpts=PTS-STARTPTS[video];" +
            // fade out sponsors
            "[sponsors]format=pix_fmts=yuva420p,fade=t=in:st=$delay:d=${fadeDuration}:alpha=1[sponsors_faded];" +
            // video is below intro, revealing it when sponsors get faded out
            "[video][sponsors_faded]overlay,trim=duration=${sponsorsDuration + delay}" +
            " -b:v 3M $h264path"

    execOrDie(command)

    return h264path
}

/**
 * @param intro a png or mp4 file containing the sponsors. Will be looped and resized as needed to match video
 * @param introDuration the time to display the intro (not including fade)
 * @param video a h264 file containing the video
 * @param skipFrames the number of frames to skip at the beginning of [video]
 *
 * @return the path to a raw h264 stream using parameters
 */
fun createIntro(
    intro: File,
    introDuration: Double,
    video: File,
    skipFrames: Int,
    scratchPath: String,
    fadeDuration: Double,
    parameters: Parameters,
): String {
    val h264path = "$scratchPath/intro.h264"

    val fps = parameters.fps.toDouble()

    val introArg = if (intro.extension == "png") {
        " -stream_loop -1 -framerate $fps -t 10 -i ${intro.absolutePath}"
    } else {
        " -stream_loop -1 -i ${intro.absolutePath}"
    }
    val image = " -stream_loop -1 -framerate $fps -t 10 -i /Users/mbonnin/git/video-tools/out/tmp/sponsors.png"

    val videoArg = " -framerate $fps -i ${video.absolutePath}"

    val fade1Start = introDuration

    val command = "ffmpeg -y" + introArg + videoArg + image +
            " -filter_complex " +
            // normalize resolution on intro
            "[0:v]scale=size=${parameters.resolution},setpts=PTS-STARTPTS[intro];" +
            //
            "[1:v]trim=start_frame=$skipFrames,setpts=PTS-STARTPTS[video];" +
            // delay0 is never displayed, it just serves as padding
            "[2:v]scale=size=${parameters.resolution},trim=start=0:end=$fade1Start[delay0];" +
            // delay video
            "[delay0][video]concat[delayed_video];" +
            // fade out sponsors
            "[intro]format=pix_fmts=yuva420p,fade=t=out:st=$fade1Start:d=${fadeDuration}:alpha=1[intro_faded];" +
            // intro is below sponsors, revealing it when sponsors get faded out
            "[delayed_video][intro_faded]overlay=shortest=1" +
            " -b:v 3M $h264path"

    execOrDie(command)

    return h264path
}

val Double.inMillis
    get() = times(1000).toLong()

val Long.inSeconds
    get() = toDouble().div(1000)

fun doGenerateVideo(
    video: File,
    sponsors: File,
    intro: File,
    outDir: File,
    scratchDir: File,
    videoId: String,
    startSec: Int,
    endSec: Int,
    skipExisting: Boolean
) {
    val path = video.absolutePath
    val outDirPath = outDir.absolutePath
    val scratchDirPath = scratchDir.absolutePath

    scratchDir.mkdirs()

    println("generateVideo: $videoId")
    val h264Path = "$scratchDirPath/$videoId.h264"

    val h264IntroPath = "$scratchDirPath/$videoId.intro.h264"
    val h264OutroPath = "$scratchDirPath/$videoId.outro.h264"
    val h264BodyPath = "$scratchDirPath/$videoId.body.h264"
    val h264MergedPath = "$scratchDirPath/$videoId.merged.h264"
    val mp4MergedPath = "$scratchDirPath/$videoId.trimmed.mp4"
    val aacPath = "$scratchDirPath/$videoId.aac"
    val finalPath = "$outDirPath/$videoId.mp4"

    if (skipExisting && File(finalPath).exists()) {
        println("skipping existing file: $finalPath")
        return
    }

    val parameters = getParameters(video)
    val fps = parameters.fps.toDouble()

    println("parameters=$parameters")

    println("--- extract H264 elementary stream: $videoId")
    execOrDie("ffmpeg -y -i $path -vcodec copy -vbsf h264_mp4toannexb $h264Path")

    println("--- Find IFRAME: $videoId")
    val segment = findH264Info(h264Path, startSec, endSec, fps)

    println("--- Creating $h264IntroPath")
    slice(h264Path, segment.keyFrameBeforeStart.pos, segment.keyFrameAfterStart.pos, h264IntroPath)

    println("--- Creating $h264BodyPath")
    slice(h264Path, segment.keyFrameAfterStart.pos, segment.keyFrameBeforeEnd.pos, h264BodyPath)

    println("--- Creating $h264OutroPath")
    slice(h264Path, segment.keyFrameBeforeEnd.pos, -1, h264OutroPath)

    println("--- create outro.h264: $videoId")

    val h264Outro = createOutro(
        sponsors = sponsors,
        sponsorsDuration = OUTRO_DURATION,
        video = File(h264OutroPath),
        delay = (segment.end.number - segment.keyFrameBeforeEnd.number)/fps,
        scratchPath = scratchDirPath,
        fadeDuration = INTRO_FADE_END - INTRO_FADE_START,
        parameters = parameters
    )

    println("--- create intro.h264: $videoId")

    val h264Intro = createIntro(
        intro = intro,
        introDuration = INTRO_FADE_START,
        video = File(h264IntroPath),
        skipFrames = segment.start.number - segment.keyFrameBeforeStart.number,
        scratchPath = scratchDirPath,
        fadeDuration = INTRO_FADE_END - INTRO_FADE_START,
        parameters = parameters
    )


    // assemble intro and body
    println("--- Merge h264 to $h264MergedPath")
    concatFiles(h264MergedPath, h264Intro, h264BodyPath, h264Outro)

    println("--- Get volume correction: $videoId")
    val correction = getVolumeCorrection(path)

    //encode the audio stream, with the fade in and volume filter
    val monoFilter = if (!parameters.mono) {
        "pan=mono|c0=FL,"
    } else {
        "" // nothing to do
    }
    val audioCommand = "ffmpeg -y " +
            "-i $path " +
            "-i /Users/mbonnin/dev/am2023/music.wav"
            "-filter_complex [0:a]${monoFilter}atrim=$startSec,asetpts=PTS-STARTPTS,afade=t=in:st=0:d=1,afade=t=out:st=${endSec-startSec}:d=4,volume=${correction}dB,adelay=${INTRO_FADE_START.inMillis}[main];" +
                    "[1:a]afade=t=in:st=0:d=4,adelay=${(endSec - startSec) + INTRO_FADE_START}[music]" +
                    "[main][music]amix" +
            " $aacPath"
    execOrDie(audioCommand)

    //merge audio and video streams
    println("--- Merge audio and video to $mp4MergedPath")
    val mergeCommand = "ffmpeg -y -i $aacPath -r $fps -i $h264MergedPath -vcodec copy -acodec copy $mp4MergedPath"
    execOrDie(mergeCommand)

    //trim audio and video streams and place the output in the final path
    if (endSec > 0) {
        println("--- Trim merged video to $finalPath")
        val trimCommand =
            "ffmpeg -y -i $mp4MergedPath -to ${(endSec - startSec) + INTRO_FADE_START + OUTRO_DURATION} -c copy $finalPath"
        execOrDie(trimCommand)
    } else {
        File(mp4MergedPath).copyTo(File(finalPath))
    }
}

data class Parameters(val fps: String, val resolution: String, val mono: Boolean)

/**
 * Find resolution and fps of the source video to generate intro & sponsors using matching parameters
 */
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
    println("Executing: $volumeDetectCommand")
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

data class FrameInfo(val pos: Long, val number: Int)

/**
 * start: IFrame at the beginning of the segment
 * middle: Frame (not necessarily IFrame) just after start
 * end: Iframe at the end of the segment
 */
data class H264Info(val keyFrameBeforeStart: FrameInfo, val start: FrameInfo, val keyFrameAfterStart: FrameInfo, val keyFrameBeforeEnd: FrameInfo, val end: FrameInfo)

/**
 * find the iframes containing at least [start] and [end]
 *
 * This works on raw h264 so requires fixed fps
 */
fun findH264Info(h264Path: String, start: Int, end: Int, fps: Double): H264Info {
    println("find H264 info: start=$start end=$end")
    // ffprobe outputs something like this
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
    println("Executing: $command")

    var beforeStart = FrameInfo(0, 0)
    var afterStart: FrameInfo? = null
    var startInfo: FrameInfo? = null
    var beforeEnd: FrameInfo? = null
    var endInfo: FrameInfo? = null

    val process = ProcessBuilder(command.split(" "))
        .start()
    val reader = process.inputStream.bufferedReader()
    reader.useLines { lines ->
        var pos: Long = 0
        var isKey = false
        var number = 0

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
                if (isKey) {
                    if (number <= start * fps) {
                        beforeStart = FrameInfo(pos, number)
                    }
                    if (startInfo == null && number >= start * fps) {
                        startInfo = FrameInfo(pos, number)
                    }
                    if (afterStart == null && number > (start + 10) * fps) {
                        afterStart = FrameInfo(pos, number)
                    }
                    if (number <= end * fps) {
                        beforeEnd = FrameInfo(pos, number)
                    }
                    if (endInfo == null && number >= end * fps) {
                        endInfo = FrameInfo(pos, number)
                        break
                    }
                }
            }

        }

        if (pos == 0L || number == 0) {
            throw Exception("cannot find position")
        }
    }
    process.destroy()

    check(startInfo != null)

    println()
    println("beforeStart=$beforeStart")
    println("startInfo=$startInfo")
    println("afterStart=$afterStart")
    println("beforeEnd=$beforeEnd")
    println("endInfo=$endInfo")
    return H264Info(beforeStart, startInfo!!, afterStart!!, beforeEnd!!, endInfo!!)
}

fun execOrDie(command: String) {
    println(
        """

        **********************************************
        Executing: $command
    """.trimIndent()
    )
    val exitCode = ProcessBuilder(command.split(" "))
        .inheritIO()
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

fun String.toSeconds(): Int {
    return split(":").let {
        it.foldIndexed(0) { index, acc, value ->
            acc + value.toInt() * 60.toDouble().pow((it.size - 1 - index).toDouble()).toInt()
        }
    }
}

data class VideoInfo(
    @SerializedName("id website") val uid: String,
    @SerializedName("videoStart (mm:ss)") private val startTimeStr: String?,
    @SerializedName("videoEnd (mm:ss)") private val endTimeStr: String?
) {

    val startTime: Int
        get() = startTimeStr!!.toSeconds()

    val endTime: Int
        get() = endTimeStr!!.toSeconds()
}

fun getVideoInfosFromJson(file: File): Map<String, VideoInfo> {
    val videoInfoStr = file.readText()
    val gson = Gson()
    val sType = object : TypeToken<List<VideoInfo>>() {}.type
    val videoInfos: List<VideoInfo> = gson.fromJson(videoInfoStr, sType)
    return videoInfos.associateBy { it.uid }
}

fun getVideoInfosFromCsv(file: File): Map<String, VideoInfo> {
    val records = file.reader().use { reader ->
        CsvParser(CsvParserSettings()).parseAll(reader)
    }

    return records.drop(1) // drop the headers
        .mapNotNull {
            val uid = it[0]
            val start = it[7]
            val end = it[8]
            println("got $uid - $start - $end")
            if (uid == null || start == null || end == null) {
                null
            } else {
                VideoInfo(
                    uid = uid,
                    startTimeStr = start,
                    endTimeStr = end
                )
            }
        }.groupBy { it.uid }
        .mapValues { it.value.first() }
}

val generate = object : CliktCommand(
    name = "generate",
    help = """
        Generate a single video.
        Use for testing
    """.trimIndent()
) {
    val video by option(
        help = """
            Path to the mkv file
        """.trimIndent()
    ).required()
    val sponsors by option(
        help = """
            Path to the sponsors image or video. Will be resized to the video dimensions and/or looped if needed
        """.trimIndent()
    ).required()
    val intro by option(
        help = """
            Path to the intro image or video. Will be resized to the video dimensions and/or looped if needed
        """.trimIndent()
    ).required()
    val startSec by option(
        help = """
            Integer number of seconds before the video starts 
        """.trimIndent()
    ).int().required()
    val endSec by option(
        help = """
            Integer number of seconds when the video ends 
        """.trimIndent()
    ).int().required()
    val outDir by option(
        help = """
            Output directory
        """.trimIndent()
    ).required()
    val videoId by option(
        help = """
            Video id. Only used to name temporary files
        """.trimIndent()
    ).required()

    override fun run() {
        doGenerateVideo(
            video = File(video),
            sponsors = File(sponsors),
            intro = File(intro),
            outDir = File(outDir),
            scratchDir = File("$outDir/tmp"),
            videoId = videoId,
            startSec = startSec,
            endSec = endSec,
            skipExisting = false
        )
    }
}

val batch = object : CliktCommand(name = "batch") {
    val inDir by option(
        help = """
            The input directory with all the video named ${'$'}videoId.[mp4|mkv]
        """.trimIndent()
    ).required()
    val introDir by option(
        help = """
            The directory with the intro pngs
        """.trimIndent()
    ).required()
    val sponsorPath by option(
        help = """
            The path to the sponsors image or video used in the end screen
        """.trimIndent()
    ).required()
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
                getVideoInfosFromJson(File(infosPath!!))
            }

            infosCsv != null -> {
                getVideoInfosFromCsv(File(infosCsv!!))
            }

            else -> {
                throw IllegalArgumentException("Provide either --infos-path or --infos-csv")
            }
        }

        outDirFile.mkdirs()
        videoInfos.keys.forEach {
            if (inDirFile.resolve("$it.mkv").exists() || inDirFile.resolve("$it.mp4").exists() || inDirFile.resolve("$it.mov").exists()) {
                return@forEach
            }
            error("No video found for $it")
        }
        for (file in inDirFile.listFiles()!!) {
            if (file.extension != "mp4" && file.extension != "mkv") {
                continue
            }

            val scratchDir = File(outDir, "/tmp")
            scratchDir.mkdirs()
            try {
                val start = System.currentTimeMillis()

                val videoId = file.nameWithoutExtension
                System.err.println("Generating video $videoId")

                val videoInfo = videoInfos[videoId]

                if (videoInfo == null) {
                    println("No videoInfo found for $videoId")
                    continue
                }
                val introFile = File(introDir, "$videoId.png")

                doGenerateVideo(
                    video = file,
                    sponsors = sponsorFile,
                    intro = introFile,
                    outDir = outDirFile,
                    scratchDir = scratchDir,
                    videoId = videoId,
                    startSec = videoInfo.startTime,
                    endSec = videoInfo.endTime,
                    skipExisting = skipExisting
                )
                System.err.println("Generating video $videoId took ${(System.currentTimeMillis() - start) / 1000}s")
            } catch (e: Exception) {
                throw e
            } finally {
                scratchDir.deleteRecursively()
            }
        }
    }
}

val postproc = object : CliktCommand(name = "postproc") {

    override fun run() {
        val command = "ffmpeg -i 172401.old.mp4 -i gradle_keynote.m4v -map 0:a -filter_complex " +
                "[0:v]crop=640:480:0:120,scale=200:150[left_webcam];" +
                "[0:v]crop=640:480:640:120,scale=960:720[right_slides];" +
                //"[0:v]crop=480:360:120:0,scale=200:150[top_left_webcam];" +
                //"[0:v]crop=640:360:0:360,scale=1280:720[bottom_left_slides];" +
                "[0:v]drawbox=:x=0:y=0:w=1280:h=720:color=black:t=fill[black];" +
                "[1:v]setpts=PTS+608/TB[delayed_slides];" +
                "[0:v][delayed_slides]overlay[slides];" +
                "[black][right_slides]overlay=160:0[questions];" +
                "[slides][questions]overlay=enable='gte(t,1972)'[background];" +
                "[background][left_webcam]overlay=enable='gte(t,608)':x=main_w-overlay_w-10:y=main_h-overlay_h-10" +
                " output.mp4"

        execOrDie(command)
    }
}

object : CliktCommand() {
    override fun run() {
    }
}.subcommands(batch, generate, postproc)
    .main(args)

fun slice(input: String, start: Long, end: Long, output: String) {
    FileSystem.SYSTEM.sink(output.toPath()).buffer().use { sink ->
        FileSystem.SYSTEM.source(input.toPath()).buffer().use { source ->
            source.skip(start)
            if (end > 0) {
                sink.write(source, end - start)
            } else {
                sink.writeAll(source)
            }
        }
    }
}
