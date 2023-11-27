package net.corda.node.services.vault

import net.corda.core.internal.packageName
import net.corda.core.node.services.VaultQueryException
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria.VaultCustomQueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.finance.GBP
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.test.SampleCashSchemaV3
import net.corda.testing.core.SerializationExtension
import net.corda.testing.internal.vault.DummyLinearStateSchemaV1
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.RegisterExtension

@ExtendWith(SerializationExtension::class)
class VaultQueryExceptionsTests : VaultQueryParties by extension {
    companion object {
        @RegisterExtension
        @JvmField
        val extension = object : VaultQueryExtension(persistentServices = false) {
            override val cordappPackages = listOf(
                    "net.corda.testing.contracts",
                    "net.corda.finance.contracts",
                    DummyLinearStateSchemaV1::class.packageName)
        }
    }

    @RegisterExtension
    @JvmField
    val rollbackRule = VaultQueryRollbackExtension(this)

    @Test
	fun `query attempting to use unregistered schema`() {
        database.transaction {
            // CashSchemaV3 NOT registered with NodeSchemaService
            val logicalExpression = builder { SampleCashSchemaV3.PersistentCashState::currency.equal(GBP.currencyCode) }
            val criteria = VaultCustomQueryCriteria(logicalExpression)

            assertThatThrownBy {
                vaultService.queryBy<Cash.State>(criteria)
            }.isInstanceOf(VaultQueryException::class.java).hasMessageContaining("Please register the entity")
        }
    }
}
