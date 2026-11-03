package dev.irohaco

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.irohaco.core.contracts.CredentialStore
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys encrypted with an AES key that never leaves the Android Keystore, one file each under
 * noBackupFilesDir (API-008, ARCHITECTURE §9.2). The file holds the IV and then the ciphertext.
 */
class KeystoreCredentials(context: Context, private val alias: String = "irohaco-api-keys") : CredentialStore {
    private val dir = File(context.noBackupFilesDir, "credentials")

    override fun save(secret: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ref = UUID.randomUUID().toString()
        dir.mkdirs()
        File(dir, ref).writeBytes(cipher.iv + cipher.doFinal(secret.toByteArray()))
        return ref
    }

    override fun read(ref: String): String? {
        val bytes = file(ref)?.takeIf { it.exists() }?.readBytes() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES)) }
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }

    override fun delete(ref: String) {
        file(ref)?.delete()
    }

    // A reference is only ever a UUID this class made; anything else could name a file outside the folder.
    private fun file(ref: String) = runCatching { UUID.fromString(ref) }.getOrNull()?.let { File(dir, it.toString()) }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as SecretKey?)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
