package net.corda.serialization.internal.carpenter;

import net.corda.core.serialization.SerializableCalculatedProperty;
import net.corda.core.serialization.SerializationContext;
import net.corda.core.serialization.SerializationFactory;
import net.corda.core.serialization.SerializedBytes;
import net.corda.serialization.internal.AllWhitelist;
import net.corda.serialization.internal.amqp.DeserializationInput;
import net.corda.serialization.internal.amqp.Envelope;
import net.corda.serialization.internal.amqp.ObjectAndEnvelope;
import net.corda.serialization.internal.amqp.SerializerFactory;
import net.corda.serialization.internal.amqp.testutils.TestSerializationContext;
import net.corda.serialization.internal.model.RemoteTypeInformation;
import net.corda.serialization.internal.model.TypeIdentifier;
import net.corda.testing.core.SerializationExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static net.corda.serialization.internal.amqp.testutils.AMQPTestUtilsKt.testDefaultFactoryNoEvolution;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class JavaCalculatedValuesToClassCarpenterTest extends AmqpCarpenterBase {
    public JavaCalculatedValuesToClassCarpenterTest() {
        super(AllWhitelist.INSTANCE);
    }

    public interface Parent {
        @SerializableCalculatedProperty
        int getDoubled();
    }

    public static final class C implements Parent {
        private final int i;

        public C(int i) {
            this.i = i;
        }

        @SerializableCalculatedProperty
        public String getSquared() {
            return Integer.toString(i * i);
        }

        @Override
        public int getDoubled() {
            return i * 2;
        }

        public int getI() {
            return i;
        }
    }

    @RegisterExtension
    public final SerializationExtension serializationExtension = new SerializationExtension();
    private SerializationContext context;

    @BeforeEach
    public void initSerialization() {
        SerializationFactory factory = serializationExtension.getSerializationFactory();
        context = factory.getDefaultContext();
    }

    @Test
    public void calculatedValues() throws Exception {
        SerializerFactory factory = testDefaultFactoryNoEvolution();
        SerializedBytes<C> serialized = serialise(new C(2));
        ObjectAndEnvelope<C> objAndEnv = new DeserializationInput(factory)
                .deserializeAndReturnEnvelope(serialized, C.class, context);

        TypeIdentifier typeToMangle = TypeIdentifier.Companion.forClass(C.class);
        Envelope env = objAndEnv.getEnvelope();
        RemoteTypeInformation typeInformation = getTypeInformation(env).values().stream()
                .filter(it -> it.getTypeIdentifier().equals(typeToMangle))
                .findFirst()
                .orElseThrow(IllegalStateException::new);

        RemoteTypeInformation renamed = rename(typeInformation, typeToMangle, mangle(typeToMangle));

        Class<?> pinochio = load(renamed, TestSerializationContext.testSerializationContext);
        Object p = pinochio.getConstructors()[0].newInstance(4, 2, "4");

        assertEquals(2, pinochio.getMethod("getI").invoke(p));
        assertEquals("4", pinochio.getMethod("getSquared").invoke(p));
        assertEquals(4, pinochio.getMethod("getDoubled").invoke(p));

        Parent upcast = (Parent) p;
        assertEquals(4, upcast.getDoubled());
    }
}
