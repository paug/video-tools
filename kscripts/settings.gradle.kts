listOf(pluginManagement.repositories, dependencyResolutionManagement.repositories).forEach {
    it.apply {
        mavenCentral()
    }
}