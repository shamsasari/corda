package net.corda.client.rpc

import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.RPCOps
import net.corda.node.internal.rpc.proxies.RpcAuthHelper.methodFullName
import net.corda.node.services.rpc.rpcContext
import net.corda.testing.node.User
import net.corda.testing.node.internal.RPCDriverDSL
import net.corda.testing.node.internal.rpcDriver
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.assertFailsWith

@RunWith(Parameterized::class)
class RPCPermissionsTests : AbstractRPCTest() {
    companion object {
        const val DUMMY_FLOW = "StartFlow.net.corda.flows.DummyFlow"
        const val WILDCARD_FLOW = "StartFlow.net.corda.flows.*"
        const val ALL_ALLOWED = "ALL"
    }

    /*
     * RPC operation.
     */
    interface TestOps : RPCOps {
        fun validatePermission(method: String, target: String? = null)
    }

    private class TestOpsImpl : TestOps {
        override val protocolVersion = 1000
        override fun validatePermission(method: String, target: String?) {
            val methodFullName = methodFullName(CordaRPCOps::class.java, method)
            val authorized = if (target == null) {
                rpcContext().isPermitted(methodFullName)
            } else {
                rpcContext().isPermitted(methodFullName, target)
            }
            if (!authorized) {
                throw PermissionException("RPC user not authorized for: $method")
            }
        }
    }

    /**
     * Create an RPC proxy for the given user.
     */
    private fun RPCDriverDSL.testProxyFor(rpcUser: User) = testProxy<TestOps>(TestOpsImpl(), rpcUser).ops

    private fun userOf(name: String, permissions: Set<String>) = User(name, "password", permissions)

    @Test
	fun `empty user cannot use any flows`() {
        rpcDriver {
            val emptyUser = userOf("empty", emptySet())
            val proxy = testProxyFor(emptyUser)
            assertNotAllowed {
                proxy.validatePermission("startFlowDynamic", "net.corda.flows.DummyFlow")
            }
            assertNotAllowed {
                proxy.validatePermission("startFlow", "net.corda.flows.DummyFlow")
            }
        }
    }

    @Test
	fun `admin user can use any flow`() {
        rpcDriver {
            val adminUser = userOf("admin", setOf(ALL_ALLOWED))
            val proxy = testProxyFor(adminUser)
            proxy.validatePermission("startFlowDynamic", "net.corda.flows.DummyFlow")
            proxy.validatePermission("startTrackedFlowDynamic", "net.corda.flows.DummyFlow")
            proxy.validatePermission("startFlowDynamicWithClientId", "net.corda.flows.DummyFlow")
            proxy.validatePermission("startFlow", "net.corda.flows.DummyFlow")
            proxy.validatePermission("startTrackedFlow", "net.corda.flows.DummyFlow")
            proxy.validatePermission("startFlowWithClientId", "net.corda.flows.DummyFlow")
        }
    }

    @Test
	fun `joe user is allowed to use DummyFlow`() {
        rpcDriver {
            val joeUser = userOf("joe", setOf(DUMMY_FLOW))
            val proxy = testProxyFor(joeUser)
            proxy.validatePermission("startFlowDynamic", "net.corda.flows.DummyFlow")
            proxy.validatePermission("startTrackedFlowDynamic", "net.corda.flows.DummyFlow")
            proxy.validatePermission("startFlowDynamicWithClientId", "net.corda.flows.DummyFlow")
            proxy.validatePermission("startFlow", "net.corda.flows.DummyFlow")
            proxy.validatePermission("startTrackedFlow", "net.corda.flows.DummyFlow")
            proxy.validatePermission("startFlowWithClientId", "net.corda.flows.DummyFlow")
        }
    }

    @Test
	fun `joe user is not allowed to use OtherFlow`() {
        rpcDriver {
            val joeUser = userOf("joe", setOf(DUMMY_FLOW))
            val proxy = testProxyFor(joeUser)
            assertNotAllowed {
                proxy.validatePermission("startFlowDynamic", "net.corda.flows.OtherFlow")
            }
            assertNotAllowed {
                proxy.validatePermission("startTrackedFlowDynamic", "net.corda.flows.OtherFlow")
            }
            assertNotAllowed {
                proxy.validatePermission("startFlowDynamicWithClientId", "net.corda.flows.OtherFlow")
            }
            assertNotAllowed {
                proxy.validatePermission("startFlow", "net.corda.flows.OtherFlow")
            }
            assertNotAllowed {
                proxy.validatePermission("startTrackedFlow", "net.corda.flows.OtherFlow")
            }
            assertNotAllowed {
                proxy.validatePermission("startFlowWithClientId", "net.corda.flows.OtherFlow")
            }
        }
    }

    @Test
	fun `joe user is not allowed to call other RPC methods`() {
        rpcDriver {
            val joeUser = userOf("joe", setOf(DUMMY_FLOW))
            val proxy = testProxyFor(joeUser)
            assertNotAllowed {
                proxy.validatePermission("nodeInfo")
            }
            assertNotAllowed {
                proxy.validatePermission("networkMapFeed")
            }
        }
    }

    @Test
	fun `joe user can call different methods matching to a wildcard`() {
        rpcDriver {
            val joeUser = userOf("joe", setOf(WILDCARD_FLOW))
            val proxy = testProxyFor(joeUser)
            assertNotAllowed {
                proxy.validatePermission("nodeInfo")
            }

            proxy.validatePermission("startFlowDynamic", "net.corda.flows.OtherFlow")
            proxy.validatePermission("startFlowDynamic", "net.corda.flows.DummyFlow")
            proxy.validatePermission("startTrackedFlowDynamic", "net.corda.flows.DummyFlow")
            proxy.validatePermission("startTrackedFlowDynamic", "net.corda.flows.OtherFlow")
            proxy.validatePermission("startFlowDynamicWithClientId", "net.corda.flows.OtherFlow")
            proxy.validatePermission("startFlowDynamicWithClientId", "net.corda.flows.DummyFlow")

            proxy.validatePermission("startFlow", "net.corda.flows.OtherFlow")
            proxy.validatePermission("startFlow", "net.corda.flows.DummyFlow")
            proxy.validatePermission("startTrackedFlow", "net.corda.flows.DummyFlow")
            proxy.validatePermission("startTrackedFlow", "net.corda.flows.OtherFlow")
            proxy.validatePermission("startFlowWithClientId", "net.corda.flows.OtherFlow")
            proxy.validatePermission("startFlowWithClientId", "net.corda.flows.DummyFlow")

            assertNotAllowed {
                proxy.validatePermission("startTrackedFlowDynamic", "net.banned.flows.OtherFlow")
            }
            assertNotAllowed {
                proxy.validatePermission("startTrackedFlowDynamic", "net.banned.flows")
            }

        }
    }

    @Test
	fun `checking invokeRpc permissions entitlements`() {
        rpcDriver {
            val joeUser = userOf("joe", setOf("InvokeRpc.networkMapFeed"))
            val proxy = testProxyFor(joeUser)
            assertNotAllowed {
                proxy.validatePermission("nodeInfo")
            }
            assertNotAllowed {
                proxy.validatePermission("startTrackedFlowDynamic", "net.corda.flows.OtherFlow")
            }
            proxy.validatePermission("networkMapFeed")
        }
    }

    private fun assertNotAllowed(action: () -> Unit) {
        assertFailsWith(PermissionException::class, "User should not be allowed to perform this action.", action)
    }
}
