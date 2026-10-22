package la.bsmith.launcher

import android.content.Context
import android.util.Base64
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Restarts the TV. An app cannot reboot Fire OS itself (REBOOT is a system
 * permission), but the TV's adb service can, and it listens on the device
 * too. So this connects to it as the tools on the computer do and asks for
 * the reboot service, as `adb reboot` does.
 *
 * The first time, the TV asks whether to allow this app's key, as it did for
 * the computer; with Always allow ticked it never asks again. Needs ADB
 * debugging on, which the tools already need.
 */
class TvRestart(context: Context) {
    enum class Result { RESTARTING, NO_ADB, NOT_ALLOWED, FAILED }

    private val keyFile = File(context.filesDir, "adbkey")
    private val busy = AtomicBoolean(false)

    /**
     * Blocks while it connects, so call it off the main thread. [onPrompt]
     * runs when the TV is showing its allow prompt for this app's key.
     */
    fun restart(onPrompt: () -> Unit): Result {
        // A second press while the first is still connecting changes nothing.
        if (!busy.compareAndSet(false, true)) return Result.RESTARTING
        try {
            Socket().use { socket ->
                try {
                    socket.connect(InetSocketAddress("127.0.0.1", ADB_PORT), CONNECT_TIMEOUT_MS)
                } catch (_: IOException) {
                    return Result.NO_ADB
                }
                socket.soTimeout = READ_TIMEOUT_MS
                val input = DataInputStream(socket.getInputStream().buffered())
                val output = socket.getOutputStream()
                if (!authenticate(socket, input, output, onPrompt)) return Result.NOT_ALLOWED
                send(output, A_OPEN, LOCAL_ID, 0, "reboot:\u0000".toByteArray())
                // The TV goes down without a word, or says OKAY first. A CLSE
                // before any OKAY is the service refused.
                return try {
                    var result: Result? = null
                    while (result == null) {
                        when (read(input).command) {
                            A_OKAY -> result = Result.RESTARTING
                            A_CLSE -> result = Result.FAILED
                        }
                    }
                    result
                } catch (_: IOException) {
                    Result.RESTARTING
                }
            }
        } catch (_: SocketTimeoutException) {
            return Result.NOT_ALLOWED
        } catch (_: IOException) {
            return Result.FAILED
        } catch (_: java.security.GeneralSecurityException) {
            return Result.FAILED
        } finally {
            busy.set(false)
        }
    }

    /**
     * CNXN, then the AUTH exchange: sign the TV's token with this app's key;
     * a key the TV does not know gets a second token, and then the public key
     * is sent, which shows the TV's allow prompt. True once the TV answers
     * CNXN.
     */
    private fun authenticate(socket: Socket, input: DataInputStream, output: OutputStream, onPrompt: () -> Unit): Boolean {
        send(output, A_CNXN, A_VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray())
        val key = loadOrCreateKey()
        var signed = false
        var sentKey = false
        while (true) {
            val message = read(input)
            when (message.command) {
                A_CNXN -> return true
                A_AUTH -> {
                    if (message.arg0 != AUTH_TOKEN) continue
                    when {
                        !signed -> {
                            send(output, A_AUTH, AUTH_SIGNATURE, 0, sign(key, message.data))
                            signed = true
                        }
                        !sentKey -> {
                            send(output, A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKey(key))
                            sentKey = true
                            // Time for the person to read the prompt and answer it.
                            socket.soTimeout = PROMPT_TIMEOUT_MS
                            onPrompt()
                        }
                        else -> return false
                    }
                }
            }
        }
    }

    private fun loadOrCreateKey(): RSAPrivateCrtKey {
        if (keyFile.exists()) {
            runCatching {
                return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes())) as RSAPrivateCrtKey
            }
        }
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(KEY_BITS)
        val key = generator.generateKeyPair().private as RSAPrivateCrtKey
        val temp = File(keyFile.path + ".tmp")
        temp.writeBytes(key.encoded)
        temp.renameTo(keyFile)
        return key
    }

    /** adbd checks RSA_sign(NID_sha1) over the token: PKCS#1 v1.5 with SHA-1's DigestInfo. */
    private fun sign(key: RSAPrivateCrtKey, token: ByteArray): ByteArray {
        val signature = Signature.getInstance("NONEwithRSA")
        signature.initSign(key)
        signature.update(SHA1_DIGEST_INFO)
        signature.update(token)
        return signature.sign()
    }

    /**
     * The public key in adb's format: base64 of Android's RSAPublicKey struct
     * (word count, -1/n[0] mod 2^32, n and R^2 mod n little-endian, the
     * exponent), then a name, then a NUL.
     */
    private fun publicKey(key: RSAPrivateCrtKey): ByteArray {
        val n = key.modulus
        val words = KEY_BITS / 32
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()
        val rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n)
        val struct = ByteBuffer.allocate(4 + 4 + KEY_BITS / 8 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        struct.putInt(words)
        struct.putInt(n0inv)
        struct.put(littleEndian(n, KEY_BITS / 8))
        struct.put(littleEndian(rr, KEY_BITS / 8))
        struct.putInt(key.publicExponent.toInt())
        val encoded = Base64.encodeToString(struct.array(), Base64.NO_WRAP)
        return "$encoded Launcher@firetv\u0000".toByteArray()
    }

    private fun littleEndian(value: BigInteger, size: Int): ByteArray {
        val bigEndian = value.toByteArray()
        val out = ByteArray(size)
        for (i in 0 until minOf(size, bigEndian.size)) out[i] = bigEndian[bigEndian.size - 1 - i]
        return out
    }

    private class Message(val command: Int, val arg0: Int, val data: ByteArray)

    private fun send(output: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
        // Older adbd checks the byte sum; newer ones ignore it.
        header.putInt(data.sumOf { it.toInt() and 0xff })
        header.putInt(command.inv())
        output.write(header.array())
        output.write(data)
        output.flush()
    }

    private fun read(input: DataInputStream): Message {
        val header = ByteArray(24)
        input.readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        buffer.int // arg1
        val length = buffer.int
        if (length < 0 || length > MAX_PAYLOAD) throw EOFException("bad adb message length $length")
        val data = ByteArray(length)
        input.readFully(data)
        return Message(command, arg0, data)
    }

    private companion object {
        const val ADB_PORT = 5555
        const val CONNECT_TIMEOUT_MS = 3_000
        const val READ_TIMEOUT_MS = 10_000
        const val PROMPT_TIMEOUT_MS = 60_000
        const val KEY_BITS = 2048
        const val LOCAL_ID = 1

        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_VERSION = 0x01000001
        const val MAX_PAYLOAD = 256 * 1024

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
    }
}
