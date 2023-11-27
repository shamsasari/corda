package net.corda.coretests.serialization

import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.finance.contracts.CommercialPaper
import net.corda.finance.contracts.asset.Cash
import net.corda.testing.core.SerializationExtension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertEquals

@ExtendWith(SerializationExtension::class)
class CommandsSerializationTests {
    @Test
	fun `test cash move serialization`() {
        val command = Cash.Commands.Move(CommercialPaper::class.java)
        val copiedCommand = command.serialize().deserialize()

        assertEquals(command, copiedCommand)
    }
}
