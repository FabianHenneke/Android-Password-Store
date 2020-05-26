package com.zeapo.pwdstore.git.config

import android.util.Base64
import com.github.ajalt.timberkt.d
import com.github.ajalt.timberkt.e
import com.github.ajalt.timberkt.w
import com.zeapo.pwdstore.utils.androidKeystore
import com.zeapo.pwdstore.utils.getPrivateKey
import com.zeapo.pwdstore.utils.getPublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer.BufferException
import net.schmizz.sshj.common.Buffer.PlainBuffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SSHRuntimeException
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.signature.Signature
import net.schmizz.sshj.transport.verification.FingerprintVerifier
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1OutputStream
import org.bouncycastle.asn1.DERSequence
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RemoteSession
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.FS
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.IllegalStateException
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.Key
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.SignatureException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine


sealed class SshAuthData {
    class Password(val passwordFinder: InteractivePasswordFinder) : SshAuthData()
    class PublicKeyFile(val keyFile: File, val passphraseFinder: InteractivePasswordFinder) : SshAuthData()
    class AndroidKeystoreKey(val keyAlias: String) : SshAuthData()
}

class InteractivePasswordFinder(val askForPassword: (cont: Continuation<String?>, isRetry: Boolean) -> Unit) : PasswordFinder {

    var isRetry = false

    override fun reqPassword(resource: Resource<*>?): CharArray {
        return runBlocking(Dispatchers.Main) {
            (suspendCoroutine { cont ->
                askForPassword(cont, isRetry)
            } ?: "").toCharArray()
        }.also { isRetry = true }
    }

    override fun shouldRetry(resource: Resource<*>?) = true
}

class SshjSessionFactory(private val username: String, private val authData: SshAuthData, private val hostKeyFile: File) : SshSessionFactory() {

    override fun getSession(uri: URIish, credentialsProvider: CredentialsProvider?, fs: FS?, tms: Int): RemoteSession {
        return SshjSession(uri, username, authData, hostKeyFile).connect()
    }
}

private class AndroidKeystoreKeyProvider(val keyAlias: String) : KeyProvider {

    override fun getPublic(): PublicKey = try {
        androidKeystore.getPublicKey(keyAlias)!!
    } catch (error: Exception) {
        e(error)
        throw IOException("Failed to get public key '$keyAlias' from Android Keystore")
    }

    override fun getType(): KeyType = KeyType.fromKey(public)

    override fun getPrivate(): PrivateKey = try {
        androidKeystore.getPrivateKey(keyAlias)!!
    } catch (error: Exception) {
        e(error)
        throw IOException("Failed to access private key '$keyAlias' from Android Keystore")
    }
}

private abstract class AndroidKeystoreAbstractSignature : Signature {

    lateinit var signature: java.security.Signature

    abstract fun getAlgorithm(key: Key): String

    fun initBasedOnKeyType(key: Key) {
        signature = try {
            if (key.javaClass.simpleName.startsWith("AndroidKeyStore")) {
                java.security.Signature.getInstance(getAlgorithm(key))
            } else {
                SecurityUtils.getSignature(getAlgorithm(key))
            }
        } catch (e: GeneralSecurityException) {
            throw SSHRuntimeException(e)
        }
    }

    override fun update(H: ByteArray) {
        update(H, 0, H.size)
    }

    override fun update(H: ByteArray, off: Int, len: Int) {
        try {
            signature.update(H, off, len)
        } catch (e: SignatureException) {
            throw SSHRuntimeException(e)
        }
    }

    override fun initSign(privateKey: PrivateKey) {
        initBasedOnKeyType(privateKey)
        try {
            signature.initSign(privateKey)
        } catch (e: InvalidKeyException) {
            throw SSHRuntimeException(e)
        }
    }

    override fun sign(): ByteArray {
        return try {
            signature.sign()
        } catch (e: SignatureException) {
            throw SSHRuntimeException(e)
        }
    }

    protected fun extractSig(sig: ByteArray, expectedKeyAlgorithm: String): ByteArray {
        val buffer = PlainBuffer(sig)
        return try {
            val algo = buffer.readString()
            if (expectedKeyAlgorithm != algo) {
                throw SSHRuntimeException("Expected '$expectedKeyAlgorithm' key algorithm, but got: $algo")
            }
            buffer.readBytes()
        } catch (e: BufferException) {
            throw SSHRuntimeException(e)
        }
    }

    override fun initVerify(publicKey: PublicKey) {
        initBasedOnKeyType(publicKey)
        try {
            signature.initVerify(publicKey)
        } catch (e: InvalidKeyException) {
            throw SSHRuntimeException(e)
        }
    }
}

private object AndroidKeystoreRsaSignatureFactory : net.schmizz.sshj.common.Factory.Named<Signature> {
    override fun getName() = KeyType.RSA.toString()

    override fun create(): Signature {
        return object : AndroidKeystoreAbstractSignature() {

            override fun getAlgorithm(key: Key) = "SHA1withRSA"

            override fun verify(sig: ByteArray): Boolean {
                val extractedSig = extractSig(sig, name)
                return try {
                    signature.verify(extractedSig)
                } catch (e: SignatureException) {
                    throw SSHRuntimeException(e)
                }
            }

            override fun encode(signature: ByteArray) = signature
        }
    }
}

private class AndroidKeystoreEcdsaSignatureFactory(val keyType: KeyType) : net.schmizz.sshj.common.Factory.Named<Signature> {

    init {
        require(keyType == KeyType.ECDSA256 || keyType == KeyType.ECDSA384 || keyType == KeyType.ECDSA521)
    }

    override fun getName() = keyType.toString()

    override fun create(): Signature {
        return object : AndroidKeystoreAbstractSignature() {

            override fun getAlgorithm(key: Key) = when (keyType) {
                KeyType.ECDSA256 -> "SHA256withECDSA"
                KeyType.ECDSA384 -> "SHA384withECDSA"
                KeyType.ECDSA521 -> "SHA512withECDSA"
                else -> throw IllegalStateException()
            }

            override fun encode(sig: ByteArray): ByteArray {
                var rIndex = 3
                val rLen: Int = sig[rIndex++].toInt() and 0xff
                val r = ByteArray(rLen)
                System.arraycopy(sig, rIndex, r, 0, r.size)
                var sIndex = rIndex + rLen + 1
                val sLen: Int = sig[sIndex++].toInt() and 0xff
                val s = ByteArray(sLen)
                System.arraycopy(sig, sIndex, s, 0, s.size)
                System.arraycopy(sig, 4, r, 0, rLen)
                System.arraycopy(sig, 6 + rLen, s, 0, sLen)
                val buf = PlainBuffer()
                buf.putMPInt(BigInteger(r))
                buf.putMPInt(BigInteger(s))
                return buf.compactData
            }

            override fun verify(sig: ByteArray?): Boolean {
                return try {
                    val sigBlob = extractSig(sig!!, name)
                    signature.verify(asnEncode(sigBlob))
                } catch (e: SignatureException) {
                    throw SSHRuntimeException(e)
                } catch (e: IOException) {
                    throw SSHRuntimeException(e)
                }
            }

            private fun asnEncode(sigBlob: ByteArray): ByteArray? {
                val sigbuf = PlainBuffer(sigBlob)
                val r = sigbuf.readBytes()
                val s = sigbuf.readBytes()
                val vector = ASN1EncodableVector()
                vector.add(ASN1Integer(r))
                vector.add(ASN1Integer(s))
                val baos = ByteArrayOutputStream()
                val asnOS = ASN1OutputStream(baos)
                asnOS.writeObject(DERSequence(vector))
                asnOS.flush()
                return baos.toByteArray()
            }
        }
    }
}

private fun makeTofuHostKeyVerifier(hostKeyFile: File): HostKeyVerifier {
    if (!hostKeyFile.exists()) {
        return HostKeyVerifier { _, _, key ->
            val digest = try {
                SecurityUtils.getMessageDigest("SHA-256")
            } catch (e: GeneralSecurityException) {
                throw SSHRuntimeException(e)
            }
            digest.update(PlainBuffer().putPublicKey(key).compactData)
            val digestData = digest.digest()
            val hostKeyEntry = "SHA256:${Base64.encodeToString(digestData, Base64.NO_WRAP)}"
            d { "Trusting host key on first use: $hostKeyEntry" }
            hostKeyFile.writeText(hostKeyEntry)
            true
        }
    } else {
        val hostKeyEntry = hostKeyFile.readText()
        d { "Pinned host key: $hostKeyEntry" }
        return FingerprintVerifier.getInstance(hostKeyEntry)
    }
}

private class SshjSession(private val uri: URIish, private val username: String, private val authData: SshAuthData, private val hostKeyFile: File) : RemoteSession {

    private lateinit var ssh: SSHClient
    private var currentCommand: Session? = null

    fun connect(): SshjSession {
        if (Security.getProvider("BC") != null) {
            Security.removeProvider("BC")
            Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 0)
        }
        val config = DefaultConfig().apply {
            val toPatch = mapOf(
                KeyType.RSA.toString() to AndroidKeystoreRsaSignatureFactory,
                KeyType.ECDSA256.toString() to AndroidKeystoreEcdsaSignatureFactory(KeyType.ECDSA256),
                KeyType.ECDSA384.toString() to AndroidKeystoreEcdsaSignatureFactory(KeyType.ECDSA384),
                KeyType.ECDSA521.toString() to AndroidKeystoreEcdsaSignatureFactory(KeyType.ECDSA521)
            )
            for ((name, factory) in toPatch) {
                val index = signatureFactories.indexOfFirst { it.name == name }
                if (index == -1)
                    continue
                signatureFactories.set(index, factory)
            }
            d { signatureFactories.joinToString { "${it.name}: ${it::class.simpleName}" } }
        }
        ssh = SSHClient(config)
        ssh.addHostKeyVerifier(makeTofuHostKeyVerifier(hostKeyFile))
        ssh.connect(uri.host, uri.port.takeUnless { it == -1 } ?: 22)
        if (!ssh.isConnected)
            throw IOException()
        when (authData) {
            is SshAuthData.Password -> {
                ssh.authPassword(username, authData.passwordFinder)
            }
            is SshAuthData.PublicKeyFile -> {
                ssh.authPublickey(username, ssh.loadKeys(authData.keyFile.absolutePath, authData.passphraseFinder))
            }
            is SshAuthData.AndroidKeystoreKey -> {
                ssh.authPublickey(username, AndroidKeystoreKeyProvider(authData.keyAlias))
            }
        }
        return this
    }

    override fun exec(commandName: String?, timeout: Int): Process {
        if (currentCommand != null) {
            w { "Killing old session" }
            currentCommand?.close()
            currentCommand = null
        }
        val session = ssh.startSession()
        currentCommand = session
        return SshjProcess(session.exec(commandName), timeout.toLong())
    }

    override fun disconnect() {
        currentCommand?.close()
        ssh.close()
    }
}

private class SshjProcess(private val command: Session.Command, private val timeout: Long) : Process() {

    override fun waitFor(): Int {
        command.join(timeout, TimeUnit.SECONDS)
        command.close()
        return exitValue()
    }

    override fun destroy() = command.close()

    override fun getOutputStream(): OutputStream = command.outputStream

    override fun getErrorStream(): InputStream = command.errorStream

    override fun exitValue(): Int = command.exitStatus

    override fun getInputStream(): InputStream = command.inputStream
}
