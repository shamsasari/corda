package net.corda.testing.node.internal

import net.corda.cliutils.ExitCodes
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.assertEquals

class RunCordaNodeReturnCodeTests {
    companion object {
        @JvmStatic
        fun data() = listOf(
                arrayOf("--nonExistingOption", ExitCodes.FAILURE),
                arrayOf("--help", ExitCodes.SUCCESS),
                arrayOf("validate-configuration", ExitCodes.FAILURE),//Should fail as there is no node.conf
                arrayOf("initial-registration", ExitCodes.FAILURE) //Missing required option
        )
    }

    @ParameterizedTest
    @MethodSource("data")
    fun runCordaWithArgumentAndAssertExitCode(argument: String, exitCode: Int) {
        val process = ProcessUtilities.startJavaProcess(
                className = "net.corda.node.Corda",
                arguments = listOf(argument)
        )
        process.waitFor()
        assertEquals(exitCode, process.exitValue())
    }
}
