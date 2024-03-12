package org.bouncycastle.jcajce.provider.asymmetric.ec

import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.jcajce.provider.asymmetric.util.KeyUtil
import org.bouncycastle.jce.spec.ECParameterSpec

class CachingBCECPublicKey(key: BCECPublicKey) : BCECPublicKey(key.algorithm, key) {
    private val cachedEngineSpec: ECParameterSpec = super.engineGetSpec()
    private val cachedEncoded: ByteArray = internalGetEncoded()

    private fun internalGetEncoded(): ByteArray {
        val algId = AlgorithmIdentifier(
                X9ObjectIdentifiers.id_ecPublicKey,
                ECUtils.getDomainParametersFromName(params, false))

        val pubKeyOctets = engineGetKeyParameters().q.getEncoded(false)
        return KeyUtil.getEncodedSubjectPublicKeyInfo(algId, pubKeyOctets)
    }

    override fun engineGetSpec(): ECParameterSpec {
        return cachedEngineSpec
    }

    override fun getEncoded(): ByteArray {
        return cachedEncoded
    }
}