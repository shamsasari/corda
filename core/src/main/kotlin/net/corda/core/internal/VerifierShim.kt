package net.corda.core.internal

import net.corda.core.flows.FlowLogic
import net.corda.core.node.NetworkParameters

object VerifierShim {
    fun getFlowLogicNetworkParameters(): NetworkParameters? = FlowLogic.currentTopLevel?.serviceHub?.networkParameters
}
