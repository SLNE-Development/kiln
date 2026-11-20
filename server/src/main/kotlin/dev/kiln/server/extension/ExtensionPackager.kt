package dev.kiln.server.extension

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dev.kiln.protocol.ExtensionInfo
import dev.kiln.protocol.ExtensionPermissions
import dev.kiln.protocol.Ids
import dev.kiln.protocol.ProtocolLimits
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A package that failed validation; the message is meant for server admins / plugin developers. */
class InvalidExtensionException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** A validated, packed extension: what is announced in the manifest and streamed to clients. */
class ExtensionPackage(
    val id: String,
    val version: String,
    val name: String,
    val main: String,
    /** Canonical order ([ExtensionPermissions.ALL]). */
    val permissions: List<String>,
    /** Deterministic zip, files at the root. */
    val zip: ByteArray,
    val sha256: ByteArray,
    /** Paths inside the zip, sorted. */
    val files: List<String>,
    /** Optional consent-screen icon (PNG), sent in the manifest. */
    val icon: ByteArray? = null,
    /** `extension.json` `dependencies` (ids + optional version ranges), in declaration order. */
    val dependencies: List<ExtensionDependency> = emptyList(),
) {
    val sha256Hex: String get() = info.sha256Hex

    val info: ExtensionInfo
        get() = ExtensionInfo(id, version, name, sha256.copyOf(), zip.size, permissions, icon?.copyOf(), dependencies.map { it.id })
}

/**
 * Validates the files of an extension package and builds its deterministic zip (sorted entries, fixed timestamp,
 * fixed compression level, no directory entries or extra fields) plus SHA-256. Pure function, no Bukkit.
 */
object ExtensionPackager {
    const val MANIFEST_FILE = "extension.json"
    const val MAX_FILES = 256
    const val MAX_PATH_LENGTH = 128
    const val MAX_UNCOMPRESSED_BYTES = 16 * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 64 * 1024

    /** Optional `icon` (shown in the client's consent screen): PNG, at most this many bytes and pixels per side. */
    const val MAX_ICON_BYTES = ProtocolLimits.MAX_EXTENSION_ICON_BYTES
    const val MAX_ICON_SIZE = 64
    val ALLOWED_EXTENSIONS = setOf("lua", "png", "json")

    private val SEGMENT = Regex("[a-z0-9_.-]+")
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Fixed entry time: 1980-01-01 00:00, the minimum DOS timestamp (timezone independent via setTimeLocal). */
    private val ENTRY_TIME: LocalDateTime = LocalDateTime.of(1980, 1, 1, 0, 0)

    /**
     * @param expectedId the id the package is registered under (folder/zip name or API argument).
     * @param files relative path (`/`-separated) -> content.
     */
    fun build(expectedId: String, files: Map<String, ByteArray>): ExtensionPackage {
        if (!Ids.isValidLuaExtensionId(expectedId)) {
            throw InvalidExtensionException("Invalid extension id '$expectedId' (allowed: [a-z0-9_]{1,32})")
        }
        // the id is the namespace of the package's textures and translation keys (see Ids.RESERVED_NAMESPACES)
        Ids.reservedNamespaceProblem(expectedId)?.let { throw InvalidExtensionException(it) }
        if (files.size > MAX_FILES) throw InvalidExtensionException("Too many files (${files.size} > $MAX_FILES)")
        var total = 0L
        for ((path, bytes) in files) {
            validatePath(path)
            total += bytes.size
            validateContent(path, bytes)
        }
        if (total > MAX_UNCOMPRESSED_BYTES) {
            throw InvalidExtensionException("Package too large uncompressed ($total > $MAX_UNCOMPRESSED_BYTES bytes)")
        }

        val manifestBytes = files[MANIFEST_FILE] ?: throw InvalidExtensionException("$MANIFEST_FILE is missing")
        val manifest = parseManifest(manifestBytes)

        val id = manifest.string("id", 32)
        if (id != expectedId) throw InvalidExtensionException("$MANIFEST_FILE id '$id' does not match '$expectedId'")
        val version = manifest.string("version", ProtocolLimits.MAX_EXTENSION_VERSION_LENGTH)
        val name = manifest.string("name", ProtocolLimits.MAX_EXTENSION_NAME_LENGTH)
        val main = if (manifest.has("main")) manifest.string("main", MAX_PATH_LENGTH) else "main.lua"
        if (!main.endsWith(".lua")) throw InvalidExtensionException("main '$main' is not a .lua file")
        if (main !in files) throw InvalidExtensionException("main '$main' does not exist")
        val permissions = parsePermissions(manifest)
        val icon = if (manifest.has("icon")) validateIcon(manifest.string("icon", MAX_PATH_LENGTH), files) else null
        val dependencies = parseDependencies(manifest, id)

        val sorted = files.keys.sorted()
        val zip = zip(sorted.map { it to files.getValue(it) })
        if (zip.size > ProtocolLimits.MAX_EXTENSION_PACKAGE_BYTES) {
            throw InvalidExtensionException("Package too large (${zip.size} > ${ProtocolLimits.MAX_EXTENSION_PACKAGE_BYTES} bytes zipped)")
        }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(zip)
        return ExtensionPackage(id, version, name, main, permissions, zip, sha256, sorted, icon, dependencies)
    }

    fun validatePath(path: String) {
        if (path.isEmpty() || path.length > MAX_PATH_LENGTH) throw InvalidExtensionException("Invalid path length: '$path'")
        if (path.startsWith("/") || path.contains('\\')) throw InvalidExtensionException("Invalid path '$path'")
        for (segment in path.split('/')) {
            if (segment.isEmpty() || segment == "." || segment == "..") throw InvalidExtensionException("Invalid path '$path'")
            if (!SEGMENT.matches(segment)) {
                throw InvalidExtensionException("Invalid characters in path '$path' (allowed per segment: [a-z0-9_.-])")
            }
        }
        val extension = path.substringAfterLast('/').substringAfterLast('.', "")
        if (extension !in ALLOWED_EXTENSIONS) {
            throw InvalidExtensionException("File type not allowed: '$path' (allowed: ${ALLOWED_EXTENSIONS.joinToString()})")
        }
    }

    private fun validateContent(path: String, bytes: ByteArray) {
        if (path.endsWith(".png")) {
            if (bytes.size < PNG_MAGIC.size || !bytes.copyOf(PNG_MAGIC.size).contentEquals(PNG_MAGIC)) {
                throw InvalidExtensionException("'$path' is not a PNG file")
            }
        } else {
            try {
                Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
            } catch (e: CharacterCodingException) {
                throw InvalidExtensionException("'$path' is not valid UTF-8", e)
            }
        }
    }

    private fun validateIcon(icon: String, files: Map<String, ByteArray>): ByteArray {
        if (!icon.endsWith(".png")) throw InvalidExtensionException("icon '$icon' is not a .png file")
        val bytes = files[icon] ?: throw InvalidExtensionException("icon '$icon' does not exist")
        if (bytes.size > MAX_ICON_BYTES) throw InvalidExtensionException("icon '$icon' too large (${bytes.size} > $MAX_ICON_BYTES bytes)")
        // IHDR: magic (8) + length (4) + "IHDR" (4) + width (4) + height (4)
        if (bytes.size < 24 || String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR") {
            throw InvalidExtensionException("icon '$icon' is not a valid PNG")
        }
        val buffer = ByteBuffer.wrap(bytes, 16, 8)
        val width = buffer.int
        val height = buffer.int
        if (width !in 1..MAX_ICON_SIZE || height !in 1..MAX_ICON_SIZE) {
            throw InvalidExtensionException("icon '$icon' is ${width}x$height, at most ${MAX_ICON_SIZE}x$MAX_ICON_SIZE allowed")
        }
        return bytes
    }

    private fun parseManifest(bytes: ByteArray): JsonObject {
        if (bytes.size > MAX_MANIFEST_BYTES) throw InvalidExtensionException("$MANIFEST_FILE too large")
        val element = try {
            JsonParser.parseString(bytes.toString(Charsets.UTF_8))
        } catch (e: JsonParseException) {
            throw InvalidExtensionException("$MANIFEST_FILE is not valid JSON: ${e.message}", e)
        }
        return element as? JsonObject ?: throw InvalidExtensionException("$MANIFEST_FILE must be a JSON object")
    }

    private fun JsonObject.string(key: String, maxLength: Int): String {
        val value = get(key)
        if (value !is JsonPrimitive || !value.isString) throw InvalidExtensionException("$MANIFEST_FILE: '$key' must be a string")
        val text = value.asString
        if (text.isBlank() || text.length > maxLength) {
            throw InvalidExtensionException("$MANIFEST_FILE: '$key' must be 1..$maxLength characters")
        }
        return text
    }

    private fun parsePermissions(manifest: JsonObject): List<String> {
        val raw = manifest.get("permissions") ?: return emptyList()
        if (raw !is JsonArray) throw InvalidExtensionException("$MANIFEST_FILE: 'permissions' must be an array")
        val requested = LinkedHashSet<String>()
        for (entry in raw) {
            if (entry !is JsonPrimitive || !entry.isString) throw InvalidExtensionException("$MANIFEST_FILE: permissions must be strings")
            val permission = entry.asString
            if (!ExtensionPermissions.isValid(permission)) {
                throw InvalidExtensionException("Unknown permission '$permission' (allowed: ${ExtensionPermissions.ALL.joinToString()})")
            }
            if (!requested.add(permission)) throw InvalidExtensionException("Duplicate permission '$permission'")
        }
        return ExtensionPermissions.ALL.filter { it in requested }
    }

    /**
     * `dependencies`: array of `"<id>"` or `{ "id": "<id>", "version": "<range>" }` (see [VersionRange]); at most
     * [ProtocolLimits.MAX_EXTENSION_DEPENDENCIES], no duplicates, not the own id. Existence and cycles are checked
     * over all registered packages ([ExtensionDependencies]).
     */
    private fun parseDependencies(manifest: JsonObject, ownId: String): List<ExtensionDependency> {
        val raw = manifest.get("dependencies") ?: return emptyList()
        if (raw !is JsonArray) throw InvalidExtensionException("$MANIFEST_FILE: 'dependencies' must be an array")
        if (raw.size() > ProtocolLimits.MAX_EXTENSION_DEPENDENCIES) {
            throw InvalidExtensionException("$MANIFEST_FILE: at most ${ProtocolLimits.MAX_EXTENSION_DEPENDENCIES} dependencies")
        }
        val result = ArrayList<ExtensionDependency>()
        for (entry in raw) {
            val dependency = when {
                entry is JsonPrimitive && entry.isString -> ExtensionDependency(entry.asString)
                entry is JsonObject -> {
                    val id = entry.get("id")
                    if (id !is JsonPrimitive || !id.isString) throw InvalidExtensionException("$MANIFEST_FILE: dependency objects need a string 'id'")
                    val version = entry.get("version")
                    val range = when {
                        version == null -> null
                        version is JsonPrimitive && version.isString -> try {
                            VersionRange.parse(version.asString)
                        } catch (e: IllegalArgumentException) {
                            throw InvalidExtensionException("$MANIFEST_FILE: dependency '${id.asString}': ${e.message}", e)
                        }
                        else -> throw InvalidExtensionException("$MANIFEST_FILE: dependency 'version' must be a string")
                    }
                    ExtensionDependency(id.asString, range)
                }
                else -> throw InvalidExtensionException("$MANIFEST_FILE: dependencies must be ids or {\"id\", \"version\"} objects")
            }
            if (!Ids.isValidLuaExtensionId(dependency.id)) throw InvalidExtensionException("$MANIFEST_FILE: invalid dependency id '${dependency.id}'")
            if (dependency.id == ownId) throw InvalidExtensionException("$MANIFEST_FILE: an extension cannot depend on itself")
            if (result.any { it.id == dependency.id }) throw InvalidExtensionException("$MANIFEST_FILE: duplicate dependency '${dependency.id}'")
            result += dependency
        }
        return result
    }

    /** Deterministic zip of [entries] (already sorted). */
    fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setMethod(ZipOutputStream.DEFLATED)
            zip.setLevel(Deflater.BEST_COMPRESSION)
            for ((path, bytes) in entries) {
                val entry = ZipEntry(path)
                entry.setTimeLocal(ENTRY_TIME)
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
