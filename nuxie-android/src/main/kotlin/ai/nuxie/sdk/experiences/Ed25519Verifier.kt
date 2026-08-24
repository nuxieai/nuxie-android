package ai.nuxie.sdk.experiences

import java.math.BigInteger
import java.security.MessageDigest

/**
 * RFC 8032 Ed25519 signature VERIFICATION (no signing), implemented over
 * BigInteger + platform SHA-512 because minSdk 23 has no Ed25519 provider
 * and the dependency policy admits no crypto library. Verify-only keeps the
 * surface small; correctness is pinned by RFC 8032 test vectors and the
 * golden signed release fixture. Performance is irrelevant at one descriptor
 * per admission.
 */
internal object Ed25519Verifier {
    // BigInteger.TWO is Java 9+; Android libcore below newer API levels lacks it.
    private val TWO = BigInteger.valueOf(2)

    private val P = TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val L = TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))
    private val D = BigInteger("-121665")
        .multiply(BigInteger.valueOf(121_666).modInverse(P))
        .mod(P)
    private val I = TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)

    // Base point B.
    private val BY = BigInteger.valueOf(4)
        .multiply(BigInteger.valueOf(5).modInverse(P))
        .mod(P)
    private val BX = recoverX(BY, 0)
        ?: error("Ed25519 base point recovery failed")
    private val B = Point(BX, BY, BigInteger.ONE, BX.multiply(BY).mod(P))
    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    /** Extended homogeneous coordinates (x, y, z, t) with x*y = z*t. */
    private class Point(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger,
    )

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decodePoint(publicKey) ?: return false
        val r = decodePoint(signature.copyOfRange(0, 32)) ?: return false
        val s = decodeScalar(signature.copyOfRange(32, 64))
        if (s >= L) return false

        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(signature, 0, 32)
        digest.update(publicKey)
        digest.update(message)
        val h = decodeWideScalar(digest.digest()).mod(L)

        // [S]B == R + [h]A
        val left = scalarMultiply(B, s)
        val right = add(r, scalarMultiply(a, h))
        return pointEquals(left, right)
    }

    // MARK: group operations

    private fun add(p: Point, q: Point): Point {
        // RFC 8032 5.1.4 add.
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = p.t.multiply(TWO).multiply(D).multiply(q.t).mod(P)
        val dd = p.z.multiply(TWO).multiply(q.z).mod(P)
        val e = b.subtract(a).mod(P)
        val f = dd.subtract(c).mod(P)
        val g = dd.add(c).mod(P)
        val hh = b.add(a).mod(P)
        return Point(
            e.multiply(f).mod(P),
            g.multiply(hh).mod(P),
            f.multiply(g).mod(P),
            e.multiply(hh).mod(P),
        )
    }

    private fun scalarMultiply(point: Point, scalar: BigInteger): Point {
        var result = IDENTITY
        var addend = point
        var remaining = scalar
        while (remaining.signum() > 0) {
            if (remaining.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            remaining = remaining.shiftRight(1)
        }
        return result
    }

    private fun pointEquals(p: Point, q: Point): Boolean {
        // x1/z1 == x2/z2 and y1/z1 == y2/z2, projectively.
        val x = p.x.multiply(q.z).subtract(q.x.multiply(p.z)).mod(P)
        val y = p.y.multiply(q.z).subtract(q.y.multiply(p.z)).mod(P)
        return x.signum() == 0 && y.signum() == 0
    }

    // MARK: decoding

    private fun decodePoint(encoded: ByteArray): Point? {
        val yBytes = encoded.copyOf()
        val signBit = (yBytes[31].toInt() shr 7) and 1
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()
        val y = decodeScalar(yBytes)
        if (y >= P) return null
        val x = recoverX(y, signBit) ?: return null
        if (x.signum() == 0 && signBit == 1) return null
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    private fun recoverX(y: BigInteger, signBit: Int): BigInteger? {
        // x^2 = (y^2 - 1) / (d*y^2 + 1)
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)
        // Candidate root: (u/v)^((p+3)/8) computed as u*v^3 * (u*v^7)^((p-5)/8).
        val v3 = v.multiply(v).mod(P).multiply(v).mod(P)
        val candidateBase = u.multiply(v3).mod(P)
            .multiply(
                u.multiply(v3.multiply(v3).mod(P).multiply(v).mod(P)).mod(P)
                    .modPow(P.subtract(BigInteger.valueOf(5)).divide(BigInteger.valueOf(8)), P),
            ).mod(P)
        var x = candidateBase
        val check = x.multiply(x).mod(P).multiply(v).mod(P)
        x = when (check) {
            u -> x
            u.negate().mod(P) -> x.multiply(I).mod(P)
            else -> return null
        }
        if (x.testBit(0) != (signBit == 1)) {
            x = x.negate().mod(P)
        }
        return x
    }

    private fun decodeScalar(littleEndian: ByteArray): BigInteger {
        val bigEndian = ByteArray(littleEndian.size)
        for (index in littleEndian.indices) {
            bigEndian[index] = littleEndian[littleEndian.size - 1 - index]
        }
        return BigInteger(1, bigEndian)
    }

    private fun decodeWideScalar(littleEndian: ByteArray): BigInteger = decodeScalar(littleEndian)
}
