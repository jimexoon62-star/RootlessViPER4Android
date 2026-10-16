package me.timschneeberger.rootlessjamesdsp.utils
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class DiagnosticLogStoreTest {
    private fun withFile(test: (File) -> Unit) {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try { test(File(dir, "audio-diagnostics.log")) } finally { dir.deleteRecursively() }
    }
    @Test fun freshLogExportContainsPipelineMeasurement() = withFile { file ->
        val store = DiagnosticLogStore(file)
        store.append("DIAGNOSTIC_START code=129")
        store.append("AUDIO_RECOVERY read=512 inputRms=0.1 outputRms=0.2")
        assertTrue(store.snapshot().contains("AUDIO_RECOVERY read=512"))
        assertTrue(file.readText().contains("outputRms=0.2"))
    }
    @Test fun survivesReopening() = withFile { file ->
        DiagnosticLogStore(file).append("previous failure")
        assertTrue(DiagnosticLogStore(file).snapshot().contains("previous failure"))
    }
    @Test fun concurrentWritesAreComplete() = withFile { file ->
        val store = DiagnosticLogStore(file)
        val workers = (0..3).map { n -> Thread { repeat(40) { store.append("WORKER-$n-$it") } }.also { it.start() } }
        workers.forEach { it.join() }
        assertEquals(160, store.snapshot().lines().count { it.contains("WORKER-") })
    }
    @Test fun rotationRetainsLatestFailure() = withFile { file ->
        val store = DiagnosticLogStore(file, 512)
        repeat(50) { store.append("sample-" + "x".repeat(30)) }
        store.append("LATEST_AUDIO_FAILURE")
        assertTrue(store.snapshot().contains("LATEST_AUDIO_FAILURE"))
        assertTrue(file.length() <= 512)
    }
}
