package net.corda.core

import kotlin.annotation.AnnotationRetention.BINARY
import kotlin.annotation.AnnotationTarget.*

/**
 * These methods and annotations are not part of Corda's API compatibility guarantee and applications should not use them.
 *
 * These are only meant to be used by Corda internally, and are not intended to be part of the public API.
 */
@Target(PROPERTY_GETTER, PROPERTY_SETTER, PROPERTY, FUNCTION, ANNOTATION_CLASS, CLASS)
@Retention(BINARY)
@MustBeDocumented
annotation class CordaInternal