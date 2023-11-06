package net.corda.core.serialization

import net.corda.core.CordaRuntimeException
import net.corda.core.crypto.SecureHash

@CordaSerializable
class MissingAttachmentsRuntimeException(val ids: List<SecureHash>, message: String?, cause: Throwable?)
    : CordaRuntimeException(message, cause) {

    @Suppress("unused")
    constructor(ids: List<SecureHash>, message: String?) : this(ids, message, null)

    @Suppress("unused")
    constructor(ids: List<SecureHash>) : this(ids, null, null)
}
