plugins {
    id("org.jetbrains.kotlin.jvm").version("2.0.0-RC1")
}

dependencies {
    implementation("com.github.ajalt:clikt:2.6.0")
    implementation("com.squareup.okio:okio-jvm:3.4.0")
    implementation("com.google.code.gson:gson:2.8.9")
    implementation("com.univocity:univocity-parsers:2.8.4")
    implementation("com.squareup.okhttp3:okhttp:4.9.3")
    implementation("com.squareup.okhttp3:logging-interceptor:4.9.3")
    implementation("com.squareup.moshi:moshi:1.8.0")
    implementation("org.nanohttpd:nanohttpd:2.2.0")
    testImplementation(kotlin("test"))
}
tasks.withType(Test::class.java) {
    this.testLogging {
        showStandardStreams = true
    }
}