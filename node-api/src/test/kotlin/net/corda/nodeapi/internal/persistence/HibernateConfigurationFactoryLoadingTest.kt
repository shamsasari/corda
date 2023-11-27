package net.corda.nodeapi.internal.persistence

import org.mockito.kotlin.mock
import net.corda.core.internal.NamedCacheFactory
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class HibernateConfigurationFactoryLoadingTest {
    @Test
    fun checkErrorMessageForMissingFactory() {
        val jdbcUrl = "jdbc:madeUpNonense:foobar.com:1234"
        val presentFactories = listOf("H2", "PostgreSQL")
        try {
            val cacheFactory = mock<NamedCacheFactory>()
            HibernateConfiguration(
                    emptySet(),
                    false,
                    emptyList(),
                    jdbcUrl,
                    cacheFactory)
            Assert.fail("Expected exception not thrown")
        } catch (e: HibernateConfigException) {
            Assert.assertEquals("Failed to find a SessionFactoryFactory to handle $jdbcUrl - factories present for ${presentFactories}", e.message)
        }
    }
}
