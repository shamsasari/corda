package net.corda.testing.common.internal

import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith

/**
 *
 */
@TestTemplate
@ExtendWith(ParameterizedClassTestExtension::class)
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ParameterizedClassTest
