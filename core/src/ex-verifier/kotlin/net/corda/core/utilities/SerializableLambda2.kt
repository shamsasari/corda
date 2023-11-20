package net.corda.core.utilities

import java.io.Serializable

/** Functional interfaces for Serializeable Lambdas */
fun interface SerializableLambda2<S, T, R> : (S, T) -> R, Serializable
