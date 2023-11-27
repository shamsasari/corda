package net.corda.client.rpc

import net.corda.core.messaging.RPCOps
import net.corda.core.utilities.millis
import net.corda.testing.node.internal.RPCDriverDSL
import net.corda.testing.node.internal.rpcDriver
import org.junit.jupiter.api.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import rx.Observable
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class RPCHighThroughputObservableTests : AbstractRPCTest() {

    private fun RPCDriverDSL.testProxy(): TestOps {
        return testProxy<TestOps>(TestOpsImpl(), queueDrainTimeout = 10.millis).ops
    }

    internal interface TestOps : RPCOps {

        fun makeObservable(): Observable<Int>
    }

    internal class TestOpsImpl : TestOps {
        override val protocolVersion = 1000

        override fun makeObservable(): Observable<Int> = Observable.interval(0, TimeUnit.MICROSECONDS).map { it.toInt() + 1 }
    }

    @Test
	fun `simple observable`() {
        rpcDriver {
            val proxy = testProxy()
            // This tests that the observations are transmitted correctly, also check that server side doesn't try to serialize the whole lot
            // till client consumed some of the output produced.
            val observations = proxy.makeObservable()
            val observationsList = observations.take(4).toBlocking().toIterable().toList()
            assertEquals(listOf(1, 2, 3, 4), observationsList)
        }
    }
}
