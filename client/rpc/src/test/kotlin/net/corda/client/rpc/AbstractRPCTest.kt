package net.corda.client.rpc

import net.corda.core.internal.concurrent.flatMap
import net.corda.core.internal.concurrent.map
import net.corda.core.messaging.RPCOps
import net.corda.core.utilities.seconds
import net.corda.node.services.rpc.RPCServerConfiguration
import net.corda.testing.core.SerializationExtension
import net.corda.testing.node.User
import net.corda.testing.node.internal.RPCDriverDSL
import net.corda.testing.node.internal.rpcTestUser
import net.corda.testing.node.internal.startInVmRpcClient
import net.corda.testing.node.internal.startRpcClient
import org.apache.activemq.artemis.api.core.client.ClientSession
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.runners.Parameterized
import java.time.Duration

@ExtendWith(SerializationExtension::class)
open class AbstractRPCTest {
    enum class RPCTestMode {
        InVm,
        Netty
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "Mode = {0}")
        fun defaultModes() = modes(RPCTestMode.InVm, RPCTestMode.Netty)

        fun modes(vararg modes: RPCTestMode) = listOf(*modes).map { arrayOf(it) }
    }

    @Parameterized.Parameter
    lateinit var mode: RPCTestMode

    data class TestProxy<out I : RPCOps>(
            val ops: I,
            val createSession: () -> ClientSession
    )

    inline fun <reified I : RPCOps> RPCDriverDSL.testProxy(
            ops: I,
            rpcUser: User = rpcTestUser,
            clientConfiguration: CordaRPCClientConfiguration = CordaRPCClientConfiguration.DEFAULT,
            serverConfiguration: RPCServerConfiguration = RPCServerConfiguration.DEFAULT,
            queueDrainTimeout: Duration = 5.seconds
            ): TestProxy<I> {
        return when (mode) {
            RPCTestMode.InVm ->
                startInVmRpcServer(ops = ops, rpcUser = rpcUser, configuration = serverConfiguration, queueDrainTimeout = queueDrainTimeout).flatMap {
                    startInVmRpcClient<I>(rpcUser.username, rpcUser.password, clientConfiguration).map {
                        TestProxy(it) { startInVmArtemisSession(rpcUser.username, rpcUser.password) }
                    }
                }
            RPCTestMode.Netty ->
                startRpcServer(ops = ops, rpcUser = rpcUser, configuration = serverConfiguration).flatMap { (broker) ->
                    startRpcClient<I>(broker.hostAndPort!!, rpcUser.username, rpcUser.password, clientConfiguration).map {
                        TestProxy(it) { startArtemisSession(broker.hostAndPort!!, rpcUser.username, rpcUser.password) }
                    }
                }
        }.get()
    }
}
