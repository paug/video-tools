package internal

import java.io.File

fun File.media(uid: String): File {
    return listFiles().firstOrNull {
        it.nameWithoutExtension == uid
    } ?: error("Cannot find media $uid")
}