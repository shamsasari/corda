package net.corda.core.internal;

import net.corda.core.node.NetworkParameters;

import javax.annotation.Nullable;

// This is used when the verifier does it's own compilation of the core module under Kotlin 1.2
//@SuppressWarnings("unused")
public class VerifierShim {
    @Nullable
    public static NetworkParameters getFlowLogicNetworkParameters() {
        return null;
    }
}
