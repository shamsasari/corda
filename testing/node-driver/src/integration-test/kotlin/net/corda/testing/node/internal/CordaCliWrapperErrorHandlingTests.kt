package net.corda.testing.node.internal

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.stream.Collectors.joining

class CordaCliWrapperErrorHandlingTests {
    companion object {
        private const val CLASS_NAME = "net.corda.testing.node.internal.SampleCordaCliWrapper"

        @JvmStatic
        fun data(): List<Array<out Any>> {
            val stackTraceRegex = "^.+Exception[^\\n]++(\\s+at .++)+[\\s\\S]*"
            val exceptionWithoutStackTraceRegex ="(\\?\\[31m)*\\Q${CLASS_NAME}\\E(\\?\\[0m)*(\\s+.+)"
            val emptyStringRegex = "^$"
            return listOf(
                    arrayOf(listOf("--throw-exception", "--verbose"), stackTraceRegex),
                    arrayOf(listOf("--throw-exception"), exceptionWithoutStackTraceRegex),
                    arrayOf(listOf("--sample-command"), emptyStringRegex)
            )
        }
    }

    @ParameterizedTest
    @MethodSource("data")
    fun `Run CordaCliWrapper sample app with arguments and check error output matches regExp`(arguments: List<String>, outputRegexPattern: String) {
        val process = ProcessUtilities.startJavaProcess(
                className = CLASS_NAME,
                arguments = arguments,
                inheritIO = false)

        process.waitFor()

        val processErrorOutput = BufferedReader(
                InputStreamReader(process.errorStream))
                .lines()
                .filter { "Exception" in it || "at " in it || "exception" in it }
                .collect(joining("\n"))

        assertThat(processErrorOutput, matchesPattern(outputRegexPattern))
    }
}
