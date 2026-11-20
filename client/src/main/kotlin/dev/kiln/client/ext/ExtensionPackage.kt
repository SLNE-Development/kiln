package dev.kiln.client.ext

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.kiln.protocol.ExtensionPermissions
import dev.kiln.protocol.Ids
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** A package violates the format rules; [message] is shown to the player (and sent to the server). */
class ExtensionPackageException(message: String) : Exception(message)

/**
 * A verified extension package, read entirely into memory. Nothing of it is ever written to disk under its entry
 * names (the cache stores the zip as `<sha256>.zip`), so path traversal ("zip slip") cannot touch the file system;
 * the names are validated anyway because they become resource identifiers and `kiln.require` targets.
 */
class ExtensionPackage(
    val id: String,
    val version: String,
    val name: String,
    val main: String,
    val permissions: Set<String>,
    /** Path inside the package (`main.lua`, `textures/a/b.png`, ...) -> content. */
    val files: Map<String, ByteArray>,
    /** Ids of `extension.json` `dependencies` (strings or `{"id", "version"}` objects; versions are the server's job). */
    val dependencies: List<String> = emptyList(),
) {
    fun file(path: String): ByteArray? = files[path]

    companion object {
        const val MANIFEST = "extension.json"
        const val MAX_ZIP_BYTES = 4 * 1024 * 1024
        const val MAX_FILES = 256
        const val MAX_UNPACKED_BYTES = 16L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 64 * 1024

        private val SEGMENT = Regex("[a-z0-9_.-]+")
        private val ALLOWED_EXTENSIONS = setOf("lua", "png", "json")

        /** `true` if [path] is a permitted entry name: `[a-z0-9_.-]` segments separated by `/`, allowed suffix. */
        fun isValidPath(path: String): Boolean {
            if (path.isEmpty() || path.length > 256 || path.startsWith("/") || path.endsWith("/")) return false
            val segments = path.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." || !SEGMENT.matches(it) }) return false
            val ext = path.substringAfterLast('.', "")
            return ext in ALLOWED_EXTENSIONS && segments.last().length > ext.length + 1
        }

        /**
         * Reads and validates a package zip. Sizes are enforced while inflating (header sizes are not trusted), so
         * a zip bomb stops at [MAX_UNPACKED_BYTES]. Rejects duplicate entries, forbidden names and suffixes, more
         * than [MAX_FILES] files and invalid `extension.json` content.
         */
        fun read(zip: ByteArray, expectedId: String? = null): ExtensionPackage {
            if (zip.size > MAX_ZIP_BYTES) fail("package is larger than ${MAX_ZIP_BYTES / 1024 / 1024} MiB")
            val files = LinkedHashMap<String, ByteArray>()
            var total = 0L
            try {
                ZipInputStream(ByteArrayInputStream(zip)).use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val name = entry.name
                        if (entry.isDirectory) {
                            if (!isValidPath(name.trimEnd('/') + "/x.lua")) fail("invalid directory entry '${printable(name)}'")
                            continue
                        }
                        if (!isValidPath(name)) fail("forbidden file name '${printable(name)}'")
                        if (name in files) fail("duplicate file '$name'")
                        if (files.size >= MAX_FILES) fail("more than $MAX_FILES files")
                        val out = java.io.ByteArrayOutputStream()
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_UNPACKED_BYTES) fail("unpacked content is larger than ${MAX_UNPACKED_BYTES / 1024 / 1024} MiB")
                            out.write(buffer, 0, n)
                        }
                        files[name] = out.toByteArray()
                    }
                }
            } catch (e: ZipException) {
                fail("corrupt zip: ${e.message}")
            } catch (e: IOException) {
                fail("cannot read zip: ${e.message}")
            }
            return fromFiles(files, expectedId)
        }

        private fun fromFiles(files: Map<String, ByteArray>, expectedId: String?): ExtensionPackage {
            val manifestBytes = files[MANIFEST] ?: fail("$MANIFEST missing")
            if (manifestBytes.size > MAX_MANIFEST_BYTES) fail("$MANIFEST too large")
            val json = try {
                JsonParser.parseString(String(manifestBytes, Charsets.UTF_8)).asJsonObject
            } catch (e: Exception) {
                fail("$MANIFEST is not a JSON object")
            }
            val id = json.string("id") ?: fail("$MANIFEST: id missing")
            if (!Ids.isValidLuaExtensionId(id)) fail("$MANIFEST: invalid id '${printable(id)}'")
            Ids.reservedNamespaceProblem(id)?.let { fail("$MANIFEST: $it") }
            if (expectedId != null && id != expectedId) fail("$MANIFEST: id '$id' does not match the announced id '$expectedId'")
            val version = json.string("version") ?: fail("$MANIFEST: version missing")
            if (version.isEmpty() || version.length > 64) fail("$MANIFEST: version must have 1-64 characters")
            val name = json.string("name") ?: fail("$MANIFEST: name missing")
            if (name.isEmpty() || name.length > 128) fail("$MANIFEST: name must have 1-128 characters")
            val main = if (json.has("main")) json.string("main") ?: fail("$MANIFEST: main must be a string") else "main.lua"
            if (!main.endsWith(".lua") || main !in files) fail("$MANIFEST: main file '${printable(main)}' missing")
            val permissions = LinkedHashSet<String>()
            json.get("permissions")?.let { element ->
                if (!element.isJsonArray) fail("$MANIFEST: permissions must be an array")
                for (p in element.asJsonArray) {
                    val perm = if (p.isJsonPrimitive && p.asJsonPrimitive.isString) p.asString else fail("$MANIFEST: permissions must be strings")
                    if (perm !in ExtensionPermissions.ALL) fail("$MANIFEST: unknown permission '${printable(perm)}'")
                    if (!permissions.add(perm)) fail("$MANIFEST: duplicate permission '$perm'")
                }
            }
            val dependencies = ArrayList<String>()
            json.get("dependencies")?.let { element ->
                if (!element.isJsonArray) fail("$MANIFEST: dependencies must be an array")
                for (d in element.asJsonArray) {
                    val depId = when {
                        d.isJsonPrimitive && d.asJsonPrimitive.isString -> d.asString
                        d.isJsonObject -> d.asJsonObject.string("id") ?: fail("$MANIFEST: dependency objects need a string id")
                        else -> fail("$MANIFEST: dependencies must be ids or objects")
                    }
                    if (!Ids.isValidLuaExtensionId(depId) || depId == id || depId in dependencies) fail("$MANIFEST: invalid dependency '${printable(depId)}'")
                    dependencies += depId
                }
            }
            return ExtensionPackage(id, version, name, main, permissions, files, dependencies)
        }

        private fun JsonObject.string(key: String): String? {
            val e = get(key) ?: return null
            return if (e.isJsonPrimitive && e.asJsonPrimitive.isString) e.asString else null
        }

        private fun printable(s: String): String = s.take(64).map { if (it.code in 0x20..0x7E) it else '?' }.joinToString("")

        private fun fail(message: String): Nothing = throw ExtensionPackageException(message)
    }
}
