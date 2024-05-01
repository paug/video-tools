package internal

import java.io.File
import kotlin.math.min
import kotlin.test.Test

class RenameFile {
    private fun renameFiles(
        inputDir: File,
        outputDir: File,
        sanitizeInfoSpeakers: String.() -> String = { sanitize() },
        sanitizeFilename: String.() -> String,
        exceptions: (String) -> String?
    ) {
        val infos = getVideoInfosFromCsv(videoInfosCsv)

        outputDir.mkdirs()
        infos.values.forEach { info ->
            var file = exceptions(info.uid)?.let { inputDir.resolve(it) }
            if (file == null) {
                val match = inputDir.listFiles()!!.map { aspc ->
                    aspc to levenshtein(info.speakers.sanitizeInfoSpeakers(), aspc.nameWithoutExtension.sanitizeFilename())
                }.minBy {
                    it.second
                }

                //println("${info.speakers.sanitizeInfoSpeakers()} -> ${match.first.name.sanitizeFilename()}: ${match.second}")
                println("${info.speakers} -> ${match.first.name}: ${match.second}")
                file = match.first
            }

            file!!.copyTo(outputDir.resolve("${info.uid}.${file.extension}"), overwrite = true)
        }
    }

    @Test
    fun renameAnimatedSpeakerCards() {
        renameFiles(
            animatedSpeakerCards,
            animatedSpeakerCardsById,
            sanitizeFilename = { replace("droidcon-amdc-24-speakercard-", "").sanitize() }
        ) {
            when (it) {
                // Kotlin Multiplatform at Stable and Beyond
                "634070" -> "droidcon-amdc-24-speakercard-Márton Braun-02.mp4"
                else -> null
            }
        }
    }
    @Test
    fun renameThumbnails() {
        renameFiles(
            speakerCards,
            speakerCardsById,
            sanitizeFilename = { replace("droidcon-amdc-24-speakercard-hd", "").sanitize() }
        ) {
            when (it) {
                // Kotlin Multiplatform at Stable and Beyond
                "634070" -> "droidcon-amdc-24-speakercard-hd-Márton-Braun-02.jpg"
                else -> null
            }
        }
    }

    @Test
    fun sortThumbnails() {
        animatedSpeakerCards.listFiles().forEach {
            it.renameTo(
                it.parentFile.resolve(
                    it.name.replace(Regex("^[0-9]_"), "")
                        .replace("hd_", "")
                )
            )
        }
    }
}

private fun String.sanitize(): String {
    return lowercase().replace(Regex("[^0-9a-z]"), "").chars().sorted().let {
        buildString {
            it.forEach {
                append(it.toChar())
            }
        }
    }
}


fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
    if (lhs == rhs) {
        return 0
    }
    if (lhs.isEmpty()) {
        return rhs.length
    }
    if (rhs.isEmpty()) {
        return lhs.length
    }

    val lhsLength = lhs.length + 1
    val rhsLength = rhs.length + 1

    var cost = Array(lhsLength) { it }
    var newCost = Array(lhsLength) { 0 }

    for (i in 1..rhsLength - 1) {
        newCost[0] = i

        for (j in 1..lhsLength - 1) {
            val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1

            val costReplace = cost[j - 1] + match
            val costInsert = cost[j] + 1
            val costDelete = newCost[j - 1] + 1

            newCost[j] = min(min(costInsert, costDelete), costReplace)
        }

        val swap = cost
        cost = newCost
        newCost = swap
    }

    return cost[lhsLength - 1]
}