package net.corda.testing

import org.junit.jupiter.api.Test

class CliBackwardsCompatibleTest(private val clazz: Class<*>) {
    @Test
	fun `should always be backwards compatible`() {
        checkBackwardsCompatibility(clazz)
    }

    private fun checkBackwardsCompatibility(clazz: Class<*>) {
        val checker = CommandLineCompatibilityChecker()
        val checkResults = checker.checkCommandLineIsBackwardsCompatible(clazz)

        if (checkResults.isNotEmpty()) {
            val exceptionMessage = checkResults.joinToString(separator = "\n") { it.message }
            throw AssertionError("Command line is not backwards compatible:\n$exceptionMessage")
        }
    }
}