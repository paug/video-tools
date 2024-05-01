import internal.runBatch
import internal.updateYoutube
import kotlin.test.Test

class Main {

    @Test
    fun batch() {
        runBatch()
    }

    @Test
    fun youtube() {
        updateYoutube()
    }
}