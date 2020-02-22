#!/usr/bin/env kscript

@file:DependsOn("com.github.ajalt:clikt:2.4.0")
@file:CompilerOpts("-jvm-target 1.8")

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import java.io.File
import java.lang.Exception
import java.nio.file.Files
import java.time.Duration

fun withTemporaryDir(block: (File) -> Unit) {
    val temporaryDir = Files.createTempDirectory(null).toFile()
    try {
        temporaryDir.mkdirs()
        block(temporaryDir)
    } finally {
        temporaryDir.deleteRecursively()
    }
}

fun withDebugDir(block: (File) -> Unit) {
    val temporaryDir = File("debug")
    temporaryDir.mkdirs()
    block(temporaryDir)
}


fun generateVideo(inputFile: File, outputFile: File, pngIntroFile: File, startSec: Long) = withTemporaryDir { temporaryDir ->
    val name = outputFile.nameWithoutExtension
    println("generateVideo: $name")
    
    val temporaryPath = temporaryDir.absolutePath

    val h264Path = "$temporaryPath/$name.h264"
    val h264IntroPath = "$temporaryPath/$name.intro.h264"
    val h264BodyPath = "$temporaryPath/$name.body.h264"
    val h264MergedPath = "$temporaryPath/$name.merged.h264"
    val aacPath = "$temporaryPath/$name.aac"

    val pngPath = pngIntroFile.absolutePath
    val inputPath = inputFile.absolutePath
    val outputPath = outputFile.absolutePath

    //extract H264 elementary stream
    execOrDie("ffmpeg",
            "-y",
            "-i", inputPath,
            "-vcodec", "copy",
            "-vbsf", "h264_mp4toannexb",
            h264Path)

    val correction = getVolumeCorrection(inputPath)

    val nextIFrame = findNextIFrameInfo(h264Path, startSec + 4)
    // Removed 0.01 to make sure to not include the last extra frame which will be in the body when rounding
    val roundingSecurity = 0.01
    val trimTime = nextIFrame.number.toFloat() / 30 - roundingSecurity

    println("Trim time: $trimTime")

    //cut the beginning of the video
    execOrDie("dd",
            "bs=${nextIFrame.pos}",
            "if=$h264Path",
            "of=$h264BodyPath",
            "skip=1")

    //create the intro, use the original source, not the h264 stream to get the timestamps
    val filter = "[0:v]format=pix_fmts=yuva420p,fade=t=out:st=3:d=1:alpha=1[intro];" +
            "[1:v]trim=$startSec:$trimTime,setpts=PTS-STARTPTS+3/TB[content];" +
            "[content][intro]overlay"

    execOrDie("ffmpeg",
            "-y",
            "-loop", "1",
            "-framerate", "30",
            "-t", "5",
            "-i", pngPath,
            "-i", inputPath,
            "-filter_complex", filter,
            "-b:v", "3M",
            h264IntroPath)

    // assemble intro and body
    concatFiles(h264IntroPath, h264BodyPath, h264MergedPath)

    //encode the audio stream, with the fade in and volume filter
    execOrDie("ffmpeg",
            "-y",
            "-i", inputPath,
            "-filter_complex", "[0:a]pan=mono|c0=FR,atrim=$startSec,asetpts=PTS-STARTPTS,afade=t=in:st=0:d=1,volume=${correction}dB,adelay=3s",
            aacPath)

    //merge audio and video streams
    execOrDie("ffmpeg",
            "-y",
            "-i", aacPath,
            "-r", "30",
            "-i", h264MergedPath,
            "-vcodec", "copy",
            "-acodec", "copy",
            outputPath)
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
    val args = listOf(
            "ffmpeg",
            "-i", path,
            "-af", "volumedetect",
            "-vn",
            "-sn",
            "-dn",
            "-f",
            "null",
            "/dev/null"
    )
    println("Executing: ${args.joinToString(",")}")

    val process = ProcessBuilder(args)
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

fun findNextIFrameInfo(h264Path: String, sec: Long): FrameInfo {
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
                if (number > sec * 30 && isKey) {
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

fun execOrDie(vararg args: String) {
    val command = args.joinToString(",")
    println("Executing: $command")
    val exitCode = ProcessBuilder(*args)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
            .waitFor()
    if (exitCode != 0) {
        throw Exception("'$command': failed with exitCode=$exitCode")
    }
}

fun concatFiles(in1: String, in2: String, out: String) {
    val outStream = File(out).outputStream()

    File(in1).inputStream().use {
        it.copyTo(outStream)
    }
    File(in2).inputStream().use {
        it.copyTo(outStream)
    }

    outStream.flush()
    outStream.close()
}

val batchCommand = object : CliktCommand(name = "batch", help = """Generate multiple videos from an input directory.
""".trimMargin()) {
    val inputDir by option(help = """
        directory where the video files are. Video files should be named {videoId}-start-{mm}-{ss}.{ext} where ext is usually flv or mov. 
    """.trimIndent()).required()
    val outputDir by option(help = """
        directory where the output will be written.
    """.trimIndent()).required()
    val skipExisting by option().flag()

    override fun run() {
        File(inputDir).listFiles().forEach {
            File(outputDir).mkdirs()
            val start = System.currentTimeMillis()
            val regex = Regex("[0-9]{2}-[0-9]{2}-[0-9]{2}-([a-zA-Z]{3}-[0-9]{4})-start-([0-9]{2})-([0-9]{2})\\.[a-zA-Z]*")
            val matchResult = regex.matchEntire(it.name)
            check(matchResult != null) {
                "File '${it.absolutePath}' doesn't match ${regex.pattern}"
            }
            val videoId = matchResult.groupValues[1]
            val startSec = matchResult.groupValues[2].toInt() * 60 + matchResult.groupValues[3].toInt()

            val output = File(outputDir, "$videoId.mp4")
            if (output.exists() && skipExisting) {
                println("skipping existing file: ${output.absolutePath}")
                return@forEach
            }

            val pngIntroFile = Files.createTempFile(null, ".png").toFile()
            //get the thumbnail
            try {
                execOrDie("wget",
                        "https://raw.githubusercontent.com/loutry/tmpAndroidMakersVisuals/2019/THUMBNAIL/thumbnail_${videoId.replace("-", "_")}.png",
                        "-O",
                        pngIntroFile.absolutePath)
                generateVideo(it, output, pngIntroFile, startSec.toLong())
            } finally {
                pngIntroFile.delete()
            }

            System.err.println("Generating video took ${(System.currentTimeMillis() - start) / 1000}s")
        }
    }
}

val generateCommand = object : CliktCommand(name = "generate", help = "Generate a single video") {
    val input by option().required()
    val output by option().required()
    val intro by option().required()
    val startTime by option(help = "the time in input where the content actually statrts. Specified as a ISO-8601 duration: PnDTnHnMn.nS")
            .convert {
                Duration.parse(it)
            }.required()

    override fun run() {
        generateVideo(File(input), File(output), File(intro), startTime.seconds)
    }
}

val mainCommand = object : CliktCommand() {
    override fun run() {
    }
}


mainCommand.subcommands(batchCommand, generateCommand)
        .main(args)
