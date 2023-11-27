package net.corda.core.crypto

import net.corda.core.crypto.internal.DigestAlgorithmFactory
import net.corda.core.internal.BLAKE2s256DigestAlgorithm
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class Blake2s256DigestServiceTest {

    private val service = DigestService("BLAKE_TEST")

    @Before
    fun before() {
        DigestAlgorithmFactory.registerClass(BLAKE2s256DigestAlgorithm::class.java.name)
    }

    @Test
    fun testBlankHash() {
        assertEquals(
                "BLAKE_TEST:C59F682376D137F3F255E671E207D1F2374EBE504E9314208A52D9F88D69E8C8",
                service.hash(byteArrayOf()).toString()
        )
        assertEquals("C59F682376D137F3F255E671E207D1F2374EBE504E9314208A52D9F88D69E8C8", service.hash(byteArrayOf()).toHexString())
    }

    @Test
    fun testHashBytes() {
        val hash = service.hash(byteArrayOf(0x64, -0x13, 0x42, 0x3a))
        assertEquals("BLAKE_TEST:9EEA14092257E759ADAA56539A7A88DA1F68F03ABE3D9552A21D4731F4E6ECA0", hash.toString())
        assertEquals("9EEA14092257E759ADAA56539A7A88DA1F68F03ABE3D9552A21D4731F4E6ECA0", hash.toHexString())
    }

    @Test
    fun testHashString() {
        val hash = service.hash("test")
        assertEquals("BLAKE_TEST:AB76E8F7EEA1968C183D343B756EC812E47D4BC7A3F061F4DDE8948B3E05DAF2", hash.toString())
        assertEquals("AB76E8F7EEA1968C183D343B756EC812E47D4BC7A3F061F4DDE8948B3E05DAF2", hash.toHexString())
    }

    @Test
    fun testGetAllOnesHash() {
        assertArrayEquals(service.allOnesHash.bytes, ByteArray(32) { 0xFF.toByte() })
    }

    @Test
    fun testGetZeroHash() {
        assertArrayEquals(service.zeroHash.bytes, ByteArray(32))
    }

    @Test
    fun `Blake2s256 does not retain state between same-thread invocations`() {
        assertEquals(service.hash("abc"), service.hash("abc"))
    }
}