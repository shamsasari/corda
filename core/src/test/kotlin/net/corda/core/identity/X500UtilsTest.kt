package net.corda.core.identity

import net.corda.core.internal.isEquivalentTo
import org.junit.jupiter.api.Test
import javax.security.auth.x500.X500Principal
import kotlin.test.assertTrue

class X500UtilsTest {

    @Test
	fun `X500Principal equalX500NameParts matches regardless the order`() {
        // given
        val orderingA = "O=Bank A, OU=Organisation Unit, L=New York, C=US"
        val orderingB = "OU=Organisation Unit, O=Bank A, L=New York, C=US"
        val orderingC = "L=New York, O=Bank A, C=US, OU=Organisation Unit"

        // when
        val principalA = X500Principal(orderingA)
        val principalB = X500Principal(orderingB)
        val principalC = X500Principal(orderingC)

        // then
        assertTrue { principalA.isEquivalentTo(principalB) }
        assertTrue { principalB.isEquivalentTo(principalC) }
    }
}
