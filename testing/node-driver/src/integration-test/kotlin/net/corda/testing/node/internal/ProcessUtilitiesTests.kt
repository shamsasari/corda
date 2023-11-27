package net.corda.testing.node.internal

import net.corda.core.internal.readText
import net.corda.core.internal.writeText
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessUtilitiesTests {
    companion object {
        private val tmpString = ProcessUtilitiesTests::class.java.name

        @JvmStatic
        fun main(args: Array<String>) {
            Paths.get(args[0]).writeText(tmpString)
        }
    }

    @Test
	fun `test dummy process can be started`(@TempDir tempDir: Path) {
        val tmpFile = createTempFile(tempDir)
        val startedProcess = ProcessUtilities.startJavaProcess<ProcessUtilitiesTests>(listOf(tmpFile.absolutePathString()))
        assertTrue { startedProcess.waitFor(20, TimeUnit.SECONDS) }
        assertEquals(tmpString, tmpFile.toPath().readText())
    }
}