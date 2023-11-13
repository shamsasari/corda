package net.corda.core.flows

import net.corda.core.DoNotImplement
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party

/**
 * An abstraction for flow session destinations. A flow can send to and receive from objects which implement this interface. The specifics
 * of how the messages are routed depend on the implementation.
 *
 * Corda currently only supports a fixed set of destination types, namely [Party] and [AnonymousParty]. New destination types will be added
 * in future releases.
 */
@DoNotImplement
interface Destination
