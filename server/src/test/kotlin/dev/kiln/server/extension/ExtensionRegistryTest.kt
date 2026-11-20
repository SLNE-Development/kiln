package dev.kiln.server.extension

import dev.kiln.api.extension.ExtensionSource
import dev.kiln.protocol.ProtocolLimits
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class ExtensionRegistryTest {
    private val records = ArrayList<LogRecord>()
    private val logger = Logger.getAnonymousLogger().apply {
        useParentHandlers = false
        addHandler(object : Handler() {
            override fun publish(record: LogRecord) { records += record }
            override fun flush() {}
            override fun close() {}
        })
    }

    private val plugin = Proxy.newProxyInstance(Plugin::class.java.classLoader, arrayOf(Plugin::class.java)) { _, method, _ ->
        if (method.name == "getName") "Test" else null
    } as Plugin

    private val messenger = object : ExtensionMessenger {
        override fun sendExtensionMessage(player: org.bukkit.entity.Player, channel: dev.kiln.protocol.ResourceId, json: String) {}
    }

    private fun write(dir: Path, id: String): Path {
        val ext = dir.resolve(id)
        Files.createDirectories(ext)
        Files.writeString(ext.resolve("extension.json"), """{"id":"$id","version":"1","name":"$id","permissions":[]}""")
        Files.writeString(ext.resolve("main.lua"), "")
        return ext
    }

    @Test
    fun `the manifest limit is enforced when registering, not when a player joins`(@TempDir dir: Path) {
        val registry = ExtensionRegistry(logger, messenger, maxExtensions = 3)
        for (i in 1..3) registry.register(plugin, "e$i", ExtensionSource.Directory(write(dir, "e$i")))
        val error = assertThrows<IllegalArgumentException> { registry.register(plugin, "e4", ExtensionSource.Directory(write(dir, "e4"))) }
        assertTrue(error.message!!.contains("at most 3"), error.message)
        assertEquals(3, registry.packages.size, "the rejected package is not delivered")
        assertTrue(registry.delivery.problems.isEmpty())
    }

    @Test
    fun `folder packages above the limit are skipped with a clear log line`(@TempDir dir: Path) {
        val folder = dir.resolve("extensions")
        for (i in 1..4) write(folder, "p$i")
        val registry = ExtensionRegistry(logger, messenger, maxExtensions = 2)
        registry.loadDirectory(plugin, folder)
        assertEquals(listOf("p1", "p2"), registry.packages.map { it.id })
        val severe = records.filter { it.level == Level.SEVERE }.map { it.message }
        assertEquals(2, severe.size, severe.toString())
        assertTrue(severe.all { it.contains("Too many extensions") && it.contains("at most 2") }, severe.toString())
        registry.logProblems()
        assertTrue(records.any { it.message == "2 of at most 2 extensions registered" })
    }

    @Test
    fun `the default limit is the protocol limit`(@TempDir dir: Path) {
        val registry = ExtensionRegistry(logger, messenger)
        registry.register(plugin, "one", ExtensionSource.Directory(write(dir, "one")))
        registry.logProblems()
        assertTrue(records.any { it.message == "1 of at most ${ProtocolLimits.MAX_MANIFEST_EXTENSIONS} extensions registered" })
    }

    @Test
    fun `reserved namespaces are rejected when registering (API and folder packages)`(@TempDir dir: Path) {
        val registry = ExtensionRegistry(logger, messenger)
        for (id in listOf("minecraft", "realms", "kiln", "fabric", "fabric_api", "c", "brigadier", "java", "mojang")) {
            val error = assertThrows<IllegalArgumentException> { registry.register(plugin, id, ExtensionSource.Directory(write(dir, id))) }
            assertTrue(error.message!!.contains("reserved namespace"), error.message)
        }
        assertTrue(registry.packages.isEmpty(), "nothing reserved is delivered")

        val folder = dir.resolve("extensions")
        write(folder, "minecraft")
        write(folder, "kiln_example")
        registry.loadDirectory(plugin, folder)
        assertEquals(listOf("kiln_example"), registry.packages.map { it.id }, "names merely starting with kiln stay allowed")
        assertTrue(records.any { it.level == Level.SEVERE && it.message.contains("'minecraft' is a reserved namespace") })
    }
}
