package net.corda.core.utilities

import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.internal.declaredField
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException
import kotlin.test.assertEquals

class ByteArraysTest {
    @Test
	fun `slice works`() {
        byteArrayOf(9, 9, 0, 1, 2, 3, 4, 9, 9).let {
            sliceWorksImpl(it, OpaqueBytesSubSequence(it, 2, 5))
        }
        byteArrayOf(0, 1, 2, 3, 4).let {
            sliceWorksImpl(it, OpaqueBytes(it))
        }
    }

    private fun sliceWorksImpl(array: ByteArray, seq: ByteSequence) {
        // Python-style negative indices can be implemented later if needed:
        assertSame(IllegalArgumentException::class.java, catchThrowable { seq.slice(-1) }.javaClass)
        assertSame(IllegalArgumentException::class.java, catchThrowable { seq.slice(end = -1) }.javaClass)
        fun check(expected: ByteArray, actual: ByteBuffer) {
            assertEquals(ByteBuffer.wrap(expected), actual)
            assertSame(ReadOnlyBufferException::class.java, catchThrowable { actual.array() }.javaClass)
            assertSame(array, actual.declaredField<ByteArray>(ByteBuffer::class, "hb").value)
        }
        check(byteArrayOf(0, 1, 2, 3, 4), seq.slice())
        check(byteArrayOf(0, 1, 2, 3, 4), seq.slice(0, 5))
        check(byteArrayOf(0, 1, 2, 3, 4), seq.slice(0, 6))
        check(byteArrayOf(0, 1, 2, 3), seq.slice(0, 4))
        check(byteArrayOf(1, 2, 3), seq.slice(1, 4))
        check(byteArrayOf(1, 2, 3, 4), seq.slice(1, 5))
        check(byteArrayOf(1, 2, 3, 4), seq.slice(1, 6))
        check(byteArrayOf(4), seq.slice(4))
        check(byteArrayOf(), seq.slice(5))
        check(byteArrayOf(), seq.slice(6))
        check(byteArrayOf(2), seq.slice(2, 3))
        check(byteArrayOf(), seq.slice(2, 2))
        check(byteArrayOf(), seq.slice(2, 1))
    }

    @Test
	fun `test hex parsing strictly uppercase`() {
        val HEX_REGEX = "^[0-9A-F]+\$".toRegex()

        val privacySalt = net.corda.core.contracts.PrivacySalt()
        val privacySaltAsHexString = privacySalt.bytes.toHexString()
        assertTrue(privacySaltAsHexString.matches(HEX_REGEX))

        val stateRef = StateRef(SecureHash.randomSHA256(), 0)
        val txhashAsHexString = stateRef.txhash.bytes.toHexString()
        assertTrue(txhashAsHexString.matches(HEX_REGEX))
    }
}
