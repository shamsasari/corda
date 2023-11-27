package net.corda.serialization.internal;

import net.corda.core.serialization.SerializedBytes;
import net.corda.core.serialization.internal.CheckpointSerializationContext;
import net.corda.core.serialization.internal.CheckpointSerializer;
import net.corda.nodeapi.internal.serialization.kryo.CordaClosureSerializer;
import net.corda.testing.core.internal.CheckpointSerializationExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.NotSerializableException;
import java.io.Serializable;
import java.util.Collections;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.ThrowableAssert.catchThrowable;

public final class LambdaCheckpointSerializationTest {
    @RegisterExtension
    public final CheckpointSerializationExtension testCheckpointSerialization = new CheckpointSerializationExtension();

    private CheckpointSerializationContext context;
    private CheckpointSerializer serializer;

    @BeforeEach
    public void setup() {
        context = new CheckpointSerializationContextImpl(
                getClass().getClassLoader(),
                AllWhitelist.INSTANCE,
                Collections.emptyMap(),
                true,
                null
        );

        serializer = testCheckpointSerialization.getCheckpointSerializer();
    }

    @Test
    @SuppressWarnings("unchecked")
    public final void serialization_works_for_serializable_java_lambdas() throws Exception {
        String value = "Hey";
        Callable<String> target = (Callable<String> & Serializable) () -> value;

        SerializedBytes<Callable<String>> serialized = serialize(target);
        Callable<String> deserialized = deserialize(serialized, Callable.class);

        assertThat(deserialized.call()).isEqualTo(value);
    }

    @Test
    public final void serialization_fails_for_not_serializable_java_lambdas() {
        String value = "Hey";
        Callable<String> target = () -> value;

        Throwable throwable = catchThrowable(() -> serialize(target));

        assertThat(throwable).isNotNull();
        assertThat(throwable).isInstanceOf(IllegalArgumentException.class);
        assertThat(throwable).hasMessage(CordaClosureSerializer.ERROR_MESSAGE);
    }

    private <T> SerializedBytes<T> serialize(final T target) throws NotSerializableException {
        return serializer.serialize(target, context);
    }

    private <T> T deserialize(final SerializedBytes<? extends T> bytes, final Class<T> type) throws NotSerializableException {
        return serializer.deserialize(bytes, type, context);
    }
}
