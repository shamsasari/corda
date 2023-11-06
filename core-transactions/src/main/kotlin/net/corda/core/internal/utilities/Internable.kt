package net.corda.core.internal.utilities

interface Internable<T> {
    val interner: PrivateInterner<T>
}

interface IternabilityVerifier<T> {
    // If a type being interned has a slightly dodgy equality check, the more strict rules you probably
    // want to apply to interning can be enforced here.
    fun choose(original: T, interned: T): T
}

class AlwaysInternableVerifier<T> : IternabilityVerifier<T> {
    override fun choose(original: T, interned: T): T = interned
}