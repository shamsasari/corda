package net.corda.core.internal

import net.corda.core.contracts.TimeWindow
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

open class InternalUtils1Test {

    @Test(timeout=300_000)
	fun kotlinObjectInstance() {
        assertThat(InternalUtils1Test.PublicObject::class.java.kotlinObjectInstance).isSameAs(InternalUtils1Test.PublicObject)
        assertThat(InternalUtils1Test.PrivateObjectX::class.java.kotlinObjectInstance).isSameAs(InternalUtils1Test.PrivateObjectX)
        assertThat(InternalUtils1Test.ProtectedObject::class.java.kotlinObjectInstance).isSameAs(InternalUtils1Test.ProtectedObject)
        assertThat(TimeWindow::class.java.kotlinObjectInstance).isNull()
        assertThat(PrivateClass123::class.java.kotlinObjectInstance).isNull()
    }

    object PublicObject
    private object PrivateObjectX
    protected object ProtectedObject

    private class PrivateClass123
}
