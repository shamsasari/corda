package net.corda.client.rpc;

import net.corda.core.contracts.Amount;
import net.corda.core.identity.Party;
import net.corda.core.messaging.CordaRPCOps;
import net.corda.core.messaging.FlowHandle;
import net.corda.core.utilities.OpaqueBytes;
import net.corda.finance.flows.AbstractCashFlow;
import net.corda.finance.flows.CashIssueFlow;
import net.corda.finance.flows.CashPaymentFlow;
import net.corda.finance.schemas.CashSchemaV1;
import net.corda.node.internal.NodeWithInfo;
import net.corda.testing.internal.InternalTestUtilsKt;
import net.corda.testing.node.User;
import net.corda.testing.node.internal.NodeBasedTest;
import net.corda.testing.node.internal.TestCordappInternal;
import org.junit.After;
import org.junit.Before;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static kotlin.test.AssertionsKt.assertEquals;
import static net.corda.finance.Currencies.DOLLARS;
import static net.corda.finance.workflows.GetBalances.getCashBalance;
import static net.corda.node.services.Permissions.invokeRpc;
import static net.corda.node.services.Permissions.startFlow;
import static net.corda.testing.core.TestConstants.ALICE_NAME;
import static net.corda.testing.core.TestConstants.DUMMY_NOTARY_NAME;
import static net.corda.testing.node.internal.InternalTestUtilsKt.FINANCE_CORDAPPS;
import static net.corda.testing.node.internal.InternalTestUtilsKt.cordappWithPackages;

public class CordaRPCJavaClientTest extends NodeBasedTest {
    public CordaRPCJavaClientTest() {
        super(cordapps(), Collections.singletonList(DUMMY_NOTARY_NAME));
    }

    private static Set<TestCordappInternal> cordapps() {
        Set<TestCordappInternal> cordapps = new HashSet<>(FINANCE_CORDAPPS);
        cordapps.add(cordappWithPackages(CashSchemaV1.class.getPackage().getName()));
        return cordapps;
    }

    private List<String> perms = Arrays.asList(
            startFlow(CashPaymentFlow.class),
            startFlow(CashIssueFlow.class),
            invokeRpc("nodeInfo"),
            invokeRpc("vaultQueryBy"),
            invokeRpc("vaultQueryByCriteria"));
    private Set<String> permSet = new HashSet<>(perms);
    private User rpcUser = new User("user1", "test", permSet);

    private CordaRPCClient client;
    private RPCConnection<CordaRPCOps> connection = null;
    private CordaRPCOps rpcProxy;

    private void login(String username, String password) {
        connection = client.start(username, password);
        rpcProxy = connection.getProxy();
    }

    @Before
    @Override
    public void setUp() {
        super.setUp();
        NodeWithInfo node = startNode(ALICE_NAME, 1000, singletonList(rpcUser));
        client = new CordaRPCClient(requireNonNull(node.getNode().getConfiguration().getRpcOptions().getAddress()));
    }

    @After
    public void done() throws IOException {
        connection.close();
    }

    @Test
    public void testLogin() {
        login(rpcUser.getUsername(), rpcUser.getPassword());
    }

    @Test
    public void testCashBalances() throws ExecutionException, InterruptedException {
        login(rpcUser.getUsername(), rpcUser.getPassword());

        Party notaryIdentity = InternalTestUtilsKt.chooseIdentity(getNotaryNodes().get(0).getInfo());
        FlowHandle<AbstractCashFlow.Result> flowHandle = rpcProxy.startFlowDynamic(CashIssueFlow.class,
                DOLLARS(123), OpaqueBytes.of((byte)0),
                notaryIdentity);
        System.out.println("Started issuing cash, waiting on result");
        flowHandle.getReturnValue().get();

        Amount<Currency> balance = getCashBalance(rpcProxy, Currency.getInstance("USD"));
        System.out.print("Balance: " + balance + "\n");

        assertEquals(DOLLARS(123), balance, "matching");
    }
}
