#!/usr/bin/env kotlin

import java.io.File

val current = File(".")
current.walk().filter { it.isFile }.forEach {
    it.renameTo(current.resolve(it.name))
}