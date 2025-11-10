package internal

import java.io.File

val inDir = File("/Users/mbonnin/Movies/AM24")

val animatedSpeakerCards = File(".").resolve("inputs/animated_speaker_cards")
val animatedSpeakerCardsById = File(".").resolve("inputs/animated_speaker_cards_by_id")
val speakerCards = File(".").resolve("inputs/thumbnails")
val speakerCardsById = File(".").resolve("inputs/speaker_cards_by_id")

val sponsorPath = File(".").resolve("inputs/endscreen.mp4")

val outDir = File(".").resolve("out")
val videoInfosCsv = File("/Users/martinbonnin/Downloads/am2025.csv")

val skipExisting = true
