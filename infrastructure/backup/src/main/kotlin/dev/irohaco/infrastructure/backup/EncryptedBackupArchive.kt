package dev.irohaco.infrastructure.backup

import dev.irohaco.core.contracts.BackupArchive
import dev.irohaco.core.contracts.BackupContents
import dev.irohaco.core.contracts.BackupManifest
import dev.irohaco.core.contracts.BackupRow
import dev.irohaco.core.contracts.BackupTables
import dev.irohaco.core.contracts.BackupUnreadableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.CharBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The backup file (ADR-0010, UC-BACKUP-01). A plain header names the format and the Argon2id settings;
 * the JSON after it is sealed with AES-256-GCM under a key Argon2id makes from the passphrase, and the
 * header is authenticated with it. Inside, a manifest holds the SHA-256 of every record, and each
 * record is checked against it on reading.
 */
class EncryptedBackupArchive(
    private val openOutput: (String) -> OutputStream,
    private val openInput: (String) -> InputStream,
    private val delete: (String) -> Unit,
    /** Argon2id cost; the tests lower it, the app keeps the default. */
    private val kdf: Kdf = Kdf(),
    private val random: SecureRandom = SecureRandom(),
) : BackupArchive {
    /** RFC 9106's second recommended setting: 64 MiB, 3 passes, 4 lanes. */
    data class Kdf(val iterations: Int = 3, val memoryKiB: Int = 64 * 1024, val parallelism: Int = 4)

    override suspend fun write(target: String, passphrase: CharArray, backupId: String, createdAt: Long, tables: BackupTables): BackupManifest =
        withContext(Dispatchers.IO) {
            val entries = JSONObject()
            val manifestEntries = linkedMapOf<String, Map<String, String>>()
            val records = JSONObject()
            for ((kind, rows) in tables.toSortedMap()) {
                val hashes = linkedMapOf<String, String>()
                val array = JSONArray()
                for (row in rows.sortedBy { it.key }) {
                    val record = record(row)
                    hashes[row.key] = sha256(record.toString())
                    array.put(record)
                }
                manifestEntries[kind] = hashes
                entries.put(kind, JSONObject(hashes as Map<*, *>))
                records.put(kind, array)
            }
            val manifest = JSONObject().put("format_version", FORMAT_VERSION).put("backup_id", backupId).put("created_at", createdAt).put("entries", entries)
            val manifestText = manifest.toString()
            val body = JSONObject().put("manifest", manifestText).put("records", records).toString().toByteArray()

            val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
            val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val header = header(kdf, salt, nonce)
            val sealed = cipher(Cipher.ENCRYPT_MODE, key(passphrase, salt, kdf), nonce, header).doFinal(body)
            openOutput(target).use {
                it.write(header)
                it.write(sealed)
            }
            BackupManifest(sha256(manifestText), manifestEntries)
        }

    override suspend fun read(source: String, passphrase: CharArray): BackupContents = withContext(Dispatchers.IO) {
        val bytes = openInput(source).use { it.readLimited(MAX_BYTES) }
        val input = DataInputStream(bytes.inputStream())
        val kdf: Kdf
        val salt = ByteArray(SALT_BYTES)
        val nonce = ByteArray(NONCE_BYTES)
        try {
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            if (!magic.contentEquals(MAGIC)) throw BackupUnreadableException("Not an Irohaco backup")
            val version = input.readUnsignedByte()
            if (version != CONTAINER_VERSION) throw BackupUnreadableException("Unknown container version $version")
            kdf = Kdf(input.readInt(), input.readInt(), input.readUnsignedByte())
            input.readFully(salt)
            input.readFully(nonce)
        } catch (e: java.io.EOFException) {
            throw BackupUnreadableException("The file is cut short", e)
        }
        // A file must not make the phone spend more than the app itself would (a crafted header could ask for gigabytes).
        if (kdf.iterations !in 1..MAX_ITERATIONS || kdf.memoryKiB !in 8..MAX_MEMORY_KIB || kdf.parallelism !in 1..MAX_LANES) {
            throw BackupUnreadableException("Key settings out of range")
        }
        val headerSize = bytes.size - input.available()
        val body = try {
            cipher(Cipher.DECRYPT_MODE, key(passphrase, salt, kdf), nonce, bytes.copyOf(headerSize)).doFinal(bytes, headerSize, bytes.size - headerSize)
        } catch (e: GeneralSecurityException) {
            throw BackupUnreadableException("Wrong passphrase or a damaged file", e)
        }
        try {
            parse(JSONObject(String(body)))
        } catch (e: JSONException) {
            throw BackupUnreadableException("The records are malformed", e)
        }
    }

    override suspend fun discard(target: String) = withContext(Dispatchers.IO) { delete(target) }

    private fun parse(body: JSONObject): BackupContents {
        val manifestText = body.getString("manifest")
        val manifest = JSONObject(manifestText)
        val formatVersion = manifest.getInt("format_version")
        if (formatVersion != FORMAT_VERSION) throw BackupUnreadableException("Unknown format version $formatVersion")
        val entries = manifest.getJSONObject("entries")
        val records = body.getJSONObject("records")
        if (records.names().toSet() != entries.names().toSet()) throw BackupUnreadableException("The kinds differ from the manifest")
        val manifestEntries = linkedMapOf<String, Map<String, String>>()
        val tables = linkedMapOf<String, List<BackupRow>>()
        for (kind in entries.names().toSet().sorted()) {
            val expected = entries.getJSONObject(kind)
            val array = records.getJSONArray(kind)
            val hashes = linkedMapOf<String, String>()
            val rows = ArrayList<BackupRow>(array.length())
            for (i in 0 until array.length()) {
                val record = array.getJSONObject(i)
                val key = record.getString("key")
                val hash = sha256(record.toString())
                if (expected.optString(key) != hash || key in hashes) throw BackupUnreadableException("A record of $kind does not match the manifest")
                hashes[key] = hash
                rows += row(record)
            }
            if (hashes.size != expected.length()) throw BackupUnreadableException("Records of $kind are missing")
            manifestEntries[kind] = hashes
            tables[kind] = rows
        }
        return BackupContents(manifest.getString("backup_id"), manifest.getLong("created_at"), formatVersion, BackupManifest(sha256(manifestText), manifestEntries), tables)
    }

    /** Columns in name order as [name, value] pairs, so the text and its hash come out the same each time. */
    private fun record(row: BackupRow): JSONObject {
        val columns = JSONArray()
        for ((name, value) in row.values.toSortedMap()) {
            columns.put(
                JSONArray().put(name).put(
                    when (value) {
                        null -> JSONObject.NULL
                        is ByteArray -> JSONObject().put("base64", Base64.getEncoder().encodeToString(value))
                        is String, is Long, is Double -> value
                        is Int -> value.toLong()
                        else -> throw IllegalArgumentException("Cannot export a ${value::class.simpleName}")
                    },
                ),
            )
        }
        return JSONObject().put("key", row.key).put("columns", columns)
    }

    private fun row(record: JSONObject): BackupRow {
        val columns = record.getJSONArray("columns")
        val values = linkedMapOf<String, Any?>()
        for (i in 0 until columns.length()) {
            val pair = columns.getJSONArray(i)
            values[pair.getString(0)] = when (val value = pair.get(1)) {
                JSONObject.NULL -> null
                is JSONObject -> Base64.getDecoder().decode(value.getString("base64"))
                is Int -> value.toLong()
                is Number -> if (value is Long) value else value.toDouble()
                else -> value
            }
        }
        return BackupRow(record.getString("key"), values)
    }

    private fun header(kdf: Kdf, salt: ByteArray, nonce: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            write(MAGIC)
            writeByte(CONTAINER_VERSION)
            writeInt(kdf.iterations)
            writeInt(kdf.memoryKiB)
            writeByte(kdf.parallelism)
            write(salt)
            write(nonce)
        }
    }.toByteArray()

    private fun key(passphrase: CharArray, salt: ByteArray, kdf: Kdf): ByteArray {
        val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(passphrase))
        val password = ByteArray(encoded.remaining()).also(encoded::get)
        try {
            val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(kdf.iterations)
                .withMemoryAsKB(kdf.memoryKiB)
                .withParallelism(kdf.parallelism)
                .withSalt(salt)
                .build()
            return ByteArray(KEY_BYTES).also { Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password, it) }
        } finally {
            password.fill(0)
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, header: ByteArray): Cipher = try {
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header)
        }
    } finally {
        key.fill(0)
    }

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > limit) throw BackupUnreadableException("The file is larger than a backup can be")
            out.write(buffer, 0, n)
        }
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val FORMAT_VERSION = 1
        private const val CONTAINER_VERSION = 1
        private val MAGIC = "IROHACO-BACKUP".toByteArray()
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val KEY_BYTES = 32
        private const val TAG_BITS = 128
        private const val MAX_BYTES = 64 * 1024 * 1024
        private const val MAX_ITERATIONS = 10
        private const val MAX_MEMORY_KIB = 256 * 1024
        private const val MAX_LANES = 16
    }
}

/** The keys [JSONObject.names] lists; it gives null for an empty object. */
private fun JSONArray?.toSet(): Set<String> = if (this == null) emptySet() else (0 until length()).map(::getString).toSet()
