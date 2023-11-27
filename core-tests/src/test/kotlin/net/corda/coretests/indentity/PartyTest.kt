package net.corda.coretests.indentity

import net.corda.core.crypto.entropyToKeyPair
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.testing.core.ALICE_NAME
import org.junit.jupiter.api.Test
import java.math.BigInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PartyTest {
    @Test
	fun equality() {
        val key = entropyToKeyPair(BigInteger.valueOf(20170207L)).public
        val differentKey = entropyToKeyPair(BigInteger.valueOf(7201702L)).public
        val anonymousParty = AnonymousParty(key)
        val party = Party(ALICE_NAME, key)
        assertEquals<AbstractParty>(party, anonymousParty)
        assertEquals<AbstractParty>(anonymousParty, party)
        assertNotEquals<AbstractParty>(AnonymousParty(differentKey), anonymousParty)
        assertNotEquals<AbstractParty>(AnonymousParty(differentKey), party)
    }
}