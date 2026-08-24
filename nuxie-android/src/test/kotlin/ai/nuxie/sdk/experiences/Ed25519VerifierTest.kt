package ai.nuxie.sdk.experiences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RFC 8032 section 7.1 test vectors (verification side). */
class Ed25519VerifierTest {
    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // TEST 1: empty message
    private val publicKey1 = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    private val signature1 = hex(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
            "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
    )

    // TEST 2: one-byte message 0x72
    private val publicKey2 = hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c")
    private val message2 = hex("72")
    private val signature2 = hex(
        "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
            "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
    )

    // TEST 3: two-byte message af82
    private val publicKey3 = hex("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025")
    private val message3 = hex("af82")
    private val signature3 = hex(
        "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
            "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
    )

    @Test
    fun rfc8032VectorsVerify() {
        assertTrue(Ed25519Verifier.verify(publicKey1, ByteArray(0), signature1))
        assertTrue(Ed25519Verifier.verify(publicKey2, message2, signature2))
        assertTrue(Ed25519Verifier.verify(publicKey3, message3, signature3))
    }

    @Test
    fun tamperedMessagesFail() {
        assertFalse(Ed25519Verifier.verify(publicKey2, hex("73"), signature2))
        assertFalse(Ed25519Verifier.verify(publicKey3, hex("af83"), signature3))
    }

    @Test
    fun tamperedSignaturesFail() {
        val tampered = signature1.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(Ed25519Verifier.verify(publicKey1, ByteArray(0), tampered))
    }

    @Test
    fun wrongKeyFails() {
        assertFalse(Ed25519Verifier.verify(publicKey3, ByteArray(0), signature1))
    }

    @Test
    fun malformedInputsFail() {
        assertFalse(Ed25519Verifier.verify(ByteArray(31), ByteArray(0), signature1))
        assertFalse(Ed25519Verifier.verify(publicKey1, ByteArray(0), ByteArray(63)))
    }
}
