package dev.kiln.client.ext

import dev.kiln.protocol.ExtensionChunk
import dev.kiln.protocol.ExtensionInfo
import dev.kiln.protocol.ExtensionManifest
import dev.kiln.protocol.ExtensionRequest
import dev.kiln.protocol.ExtensionsReady
import dev.kiln.protocol.PROTOCOL_VERSION
import dev.kiln.protocol.ProtocolLimits
import dev.kiln.protocol.ServerboundMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import dev.kiln.client.ext.ExtensionHandshake.Phase
import dev.kiln.client.ext.ExtensionHandshake.Status

class ExtensionHandshakeTest {
    private class FakeIo(var trusted: Boolean = false, val cache: MutableMap<String, ByteArray> = HashMap()) : ExtensionHandshake.Io {
        val sent = ArrayList<ServerboundMessage>()
        val events = ArrayList<String>()
        var failLoad: String? = null

        override fun send(message: ServerboundMessage) { sent += message; events += "send:${message::class.simpleName}" }
        override fun isTrusted(grants: List<TrustStore.Grant>) = trusted
        override fun trust(grants: List<TrustStore.Grant>) { events += "trust:${grants.map { it.id }}" }
        override fun cached(info: ExtensionInfo) = cache[info.id]
        override fun store(info: ExtensionInfo, zip: ByteArray) { cache[info.id] = zip; events += "store:${info.id}" }
        override fun askConsent(infos: List<ExtensionInfo>) { events += "consent:${infos.map { it.id }}" }
        override fun showProgress() { events += "progress" }
        override fun beginLoad() { events += "begin" }
        override fun load(pkg: ExtensionPackage) {
            failLoad?.let { if (pkg.id == it) throw ExtensionLoadException("$it: boom") }
            events += "load:${pkg.id}"
        }
        override fun unloadAll() { events += "unload" }
        override fun disconnect(reason: String) { events += "disconnect:$reason" }
        val mods = HashSet<String>()
        override fun isModLoaded(id: String) = id in mods
    }

    private fun zip(id: String, padding: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.setLevel(0)
            z.putNextEntry(ZipEntry("extension.json"))
            z.write("""{"id":"$id","version":"1","name":"$id","permissions":["hud"]}""".toByteArray())
            z.putNextEntry(ZipEntry("main.lua"))
            z.write(ByteArray(padding) { 'x'.code.toByte() }.let { "--".toByteArray() + it })
        }
        return out.toByteArray()
    }

    private fun info(id: String, data: ByteArray) = ExtensionInfo(id, "1", id, Sha256.of(data), data.size, listOf("hud"))

    private fun chunks(info: ExtensionInfo, data: ByteArray): List<ExtensionChunk> {
        val size = ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES
        return (0 until info.chunkCount).map { ExtensionChunk(info.id, it, info.chunkCount, data.copyOfRange(it * size, minOf(data.size, (it + 1) * size))) }
    }

    private val a = zip("a")
    private val b = zip("b", ProtocolLimits.MAX_EXTENSION_CHUNK_BYTES + 100)
    private val manifest = ExtensionManifest(listOf(info("a", a), info("b", b)))

    private fun runToEnd(h: ExtensionHandshake) {
        repeat(20) { h.tick() }
    }

    @Test
    fun `nothing is requested before consent, then download, verify and start`() {
        val io = FakeIo(cache = mutableMapOf("a" to a))
        val h = ExtensionHandshake(io)
        h.onWelcome(PROTOCOL_VERSION)
        h.onManifest(manifest)
        assertEquals(Phase.CONSENT, h.phase)
        assertTrue(io.sent.isEmpty(), "no ExtensionRequest before consent")
        assertEquals(listOf("consent:[a, b]"), io.events)

        h.consent(true)
        assertEquals(Phase.DOWNLOADING, h.phase)
        assertEquals(listOf(ExtensionRequest(listOf("b"))), io.sent) // only what is not cached
        assertEquals(Status.CACHED, h.items[0].status)
        assertEquals(Status.DOWNLOADING, h.items[1].status)
        assertEquals(a.size.toLong(), h.receivedBytes)

        val parts = chunks(manifest.extensions[1], b)
        h.onChunk(parts[0])
        assertTrue(h.receivedBytes > a.size)
        h.onChunk(parts[1])
        assertEquals(Phase.LOADING, h.phase)
        assertTrue("store:b" in io.events)

        h.tick() // verify a
        assertEquals(Status.STARTING, h.items[0].status)
        runToEnd(h)
        assertEquals(Phase.DONE, h.phase)
        assertEquals(ExtensionsReady(true), io.sent.last())
        assertEquals(listOf("begin", "load:a", "load:b"), io.events.filter { it == "begin" || it.startsWith("load:") })
        assertTrue(h.items.all { it.status == Status.DONE })
        assertEquals(1f, h.progress)
    }

    @Test
    fun `trusted servers skip the consent screen`() {
        val io = FakeIo(trusted = true, cache = mutableMapOf("a" to a, "b" to b))
        val h = ExtensionHandshake(io)
        h.onWelcome(PROTOCOL_VERSION)
        h.onManifest(manifest)
        assertFalse(io.events.any { it.startsWith("consent") })
        assertEquals(ExtensionRequest(emptyList()), io.sent.first())
        runToEnd(h)
        assertEquals(Phase.DONE, h.phase)
    }

    @Test
    fun `declining disconnects without downloading anything`() {
        val io = FakeIo()
        val h = ExtensionHandshake(io)
        h.onWelcome(PROTOCOL_VERSION)
        h.onManifest(manifest)
        h.consent(false)
        h.consent(true) // too late, ignored
        assertEquals(Phase.DECLINED, h.phase)
        assertTrue(io.sent.isEmpty())
        assertEquals("disconnect:Server-Erweiterungen abgelehnt", io.events.last())
    }

    @Test
    fun `failures are reported to the server`() {
        // tampered download
        val io = FakeIo()
        val h = ExtensionHandshake(io)
        h.onWelcome(PROTOCOL_VERSION)
        h.onManifest(ExtensionManifest(listOf(info("a", a))))
        h.consent(true)
        h.onChunk(ExtensionChunk("a", 0, 1, a.copyOf().also { it[10] = (it[10].toInt() xor 0x55).toByte() }))
        assertEquals(Phase.FAILED, h.phase)
        assertEquals(Status.FAILED, h.items[0].status)
        assertFalse((io.sent.last() as ExtensionsReady).ok)

        // script error while starting
        val io2 = FakeIo(trusted = true, cache = mutableMapOf("a" to a, "b" to b)).also { it.failLoad = "b" }
        val h2 = ExtensionHandshake(io2)
        h2.onWelcome(PROTOCOL_VERSION)
        h2.onManifest(manifest)
        runToEnd(h2)
        assertEquals(Phase.FAILED, h2.phase)
        assertEquals(ExtensionsReady(false, "b: boom"), io2.sent.last())
        assertTrue("unload" in io2.events)

        // wrong protocol / unexpected order
        val h3 = ExtensionHandshake(FakeIo())
        h3.onManifest(manifest)
        assertEquals(Phase.FAILED, h3.phase)
    }

    @Test
    fun `cancel on the loading screen disconnects`() {
        val io = FakeIo(trusted = true)
        val h = ExtensionHandshake(io)
        h.onWelcome(PROTOCOL_VERSION)
        h.onManifest(manifest)
        h.cancel()
        assertEquals(Phase.DECLINED, h.phase)
        assertTrue(io.events.last().startsWith("disconnect:"))
    }

    // ---- consent list ---------------------------------------------------------------------------------------

    @Test
    fun `accordion toggles, animates the chevron and changes the layout`() {
        val list = ConsentListState(3, headerHeight = 20, detailHeight = 50, gap = 2)
        list.setViewport(100)
        assertEquals(20 * 3 + 2 * 2, list.contentHeight)
        assertEquals(0, list.maxScroll)
        assertEquals(1, list.headerAt(25.0))
        assertEquals(-1, list.headerAt(21.0)) // in the gap

        list.toggle(0)
        assertTrue(list.isExpanded(0))
        assertEquals(70, list.entryHeight(0))
        assertEquals(72, list.entryTop(1))
        assertEquals(-1, list.headerAt(30.0)) // details of entry 0, not a header
        assertEquals(1, list.headerAt(75.0))

        assertEquals(0f, list.chevron(0))
        list.animate(75f)
        assertEquals(0.5f, list.chevron(0), 0.01f)
        list.animate(1000f)
        assertEquals(1f, list.chevron(0))
        list.toggle(0)
        list.animate(1000f)
        assertEquals(0f, list.chevron(0))
    }

    @Test
    fun `scrolling is clamped to the content`() {
        val list = ConsentListState(10, headerHeight = 20, detailHeight = 50, gap = 0)
        list.setViewport(100)
        assertEquals(100, list.maxScroll)
        list.scrollBy(-50.0)
        assertEquals(0.0, list.scroll)
        list.scrollBy(500.0)
        assertEquals(100.0, list.scroll)
        assertEquals(9, list.headerAt(99.0))
        list.toggle(9)
        list.scrollBy(1000.0)
        assertEquals(150.0, list.scroll)
        list.toggle(9) // collapsing shrinks the content: scroll is clamped again
        assertEquals(100.0, list.scroll)
        list.setViewport(1000)
        assertEquals(0.0, list.scroll)
    }

    @Test
    fun `permission rows list every permission with its state`() {
        val rows = PermissionTexts.rows(listOf("hud", "widgets"))
        assertEquals(listOf("widgets", "keybinds", "hud", "channels", "toasts", "screens", "inventory"), rows.map { it.permission })
        assertEquals(listOf(true, false, true, false, false, false, false), rows.map { it.requested })
        assertTrue(rows.all { it.label.isNotBlank() && it.label != it.permission })
        assertEquals(listOf("UI-Kit (ui_kit)", "icons"), PermissionTexts.dependencyLabels(listOf("ui_kit", "icons"), mapOf("ui_kit" to "UI-Kit")))
        assertEquals(0, PermissionTexts.dependencyHeight(0))
        assertTrue(PermissionTexts.dependencyHeight(2) > PermissionTexts.dependencyHeight(1))
    }

    private fun depZip(id: String, deps: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("extension.json"))
            z.write("""{"id":"$id","version":"1","name":"$id","dependencies":[$deps]}""".toByteArray())
            z.putNextEntry(ZipEntry("main.lua"))
            z.write("--".toByteArray())
        }
        return out.toByteArray()
    }

    @Test
    fun `dependencies must match the manifest and a failing dependency fails its dependents`() {
        val kit = depZip("ui_kit", "")
        val shop = depZip("shop", "{\"id\":\"ui_kit\",\"version\":\">=1\"}")
        fun infoOf(id: String, data: ByteArray, deps: List<String>) = ExtensionInfo(id, "1", id, Sha256.of(data), data.size, emptyList(), dependencies = deps)

        // matching dependencies: loaded in manifest order
        val io = FakeIo(trusted = true, cache = mutableMapOf("ui_kit" to kit, "shop" to shop))
        val ok = ExtensionHandshake(io)
        ok.onWelcome(PROTOCOL_VERSION)
        ok.onManifest(ExtensionManifest(listOf(infoOf("ui_kit", kit, emptyList()), infoOf("shop", shop, listOf("ui_kit")))))
        repeat(10) { ok.tick() }
        assertEquals(Phase.DONE, ok.phase)
        assertEquals(listOf("load:ui_kit", "load:shop"), io.events.filter { it.startsWith("load:") })

        // the manifest claims no dependency, the package has one
        val io2 = FakeIo(trusted = true, cache = mutableMapOf("ui_kit" to kit, "shop" to shop))
        val mismatch = ExtensionHandshake(io2)
        mismatch.onWelcome(PROTOCOL_VERSION)
        mismatch.onManifest(ExtensionManifest(listOf(infoOf("ui_kit", kit, emptyList()), infoOf("shop", shop, emptyList()))))
        repeat(10) { mismatch.tick() }
        assertEquals(Phase.FAILED, mismatch.phase)
        assertTrue(mismatch.error!!.contains("dependencies"), mismatch.error)

        // ui_kit fails to load -> shop fails too, the reason names it
        val io3 = FakeIo(trusted = true, cache = mutableMapOf("ui_kit" to kit, "shop" to shop)).also { it.failLoad = "ui_kit" }
        val failing = ExtensionHandshake(io3)
        failing.onWelcome(PROTOCOL_VERSION)
        failing.onManifest(ExtensionManifest(listOf(infoOf("ui_kit", kit, emptyList()), infoOf("shop", shop, listOf("ui_kit")))))
        repeat(10) { failing.tick() }
        assertEquals(Phase.FAILED, failing.phase)
        assertTrue(failing.error!!.contains("needed by shop"), failing.error)
        assertEquals(Status.FAILED, failing.items[1].status)
        assertEquals(listOf("shop"), failing.dependentsOf("ui_kit"))
    }

    @Test
    fun `reserved namespaces fail the handshake before consent or download`() {
        for (id in listOf("minecraft", "realms", "kiln", "fabric", "fabric_api", "c", "brigadier", "java", "mojang")) {
            val io = FakeIo(trusted = true)
            val h = ExtensionHandshake(io)
            h.onWelcome(PROTOCOL_VERSION)
            val data = zip(id)
            h.onManifest(ExtensionManifest(listOf(info("a", a), info(id, data))))
            assertEquals(Phase.FAILED, h.phase, id)
            val ready = io.sent.single() as ExtensionsReady
            assertFalse(ready.ok)
            assertTrue(ready.error!!.contains("reserved namespace") && ready.error!!.contains("'$id'"), ready.error)
            assertTrue(io.events.none { it.startsWith("consent") || it == "progress" }, io.events.toString())
        }
    }

    @Test
    fun `ids of installed Fabric mods fail the handshake`() {
        val io = FakeIo(trusted = true)
        io.mods += "sodium"
        val h = ExtensionHandshake(io)
        h.onWelcome(PROTOCOL_VERSION)
        val data = zip("sodium")
        h.onManifest(ExtensionManifest(listOf(info("sodium", data))))
        assertEquals(Phase.FAILED, h.phase)
        val ready = io.sent.single() as ExtensionsReady
        assertTrue(ready.error!!.contains("installed mod"), ready.error)
    }

    @Test
    fun `a package whose extension json claims a reserved id is rejected`() {
        val error = org.junit.jupiter.api.assertThrows<ExtensionPackageException> { ExtensionPackage.read(zip("minecraft")) }
        assertTrue(error.message!!.contains("reserved namespace"), error.message)
    }

    @Test
    fun `textures and translation keys must not replace existing ones`() {
        val existingTextures = setOf("minecraft:textures/gui/title/minecraft.png")
        ExtensionNamespaces.checkTexture("shop", "shop:textures/icon.png", { it in existingTextures }, { false })
        val texture = org.junit.jupiter.api.assertThrows<ExtensionPackageException> {
            ExtensionNamespaces.checkTexture("shop", "shop:textures/icon.png", { false }, { it == "shop:textures/icon.png" })
        }
        assertTrue(texture.message!!.contains("already exists"), texture.message)
        org.junit.jupiter.api.assertThrows<ExtensionPackageException> {
            ExtensionNamespaces.checkTexture("shop", "shop:textures/icon.png", { it == "shop:textures/icon.png" }, { false })
        }

        val language = setOf("gui.done", "key.forward")
        // an extension called "gui" cannot rename the vanilla "Done" button ...
        val key = org.junit.jupiter.api.assertThrows<ExtensionPackageException> {
            ExtensionNamespaces.checkTranslation("gui", "gui.done", emptySet()) { it in language }
        }
        assertTrue(key.message!!.contains("gui.done"), key.message)
        // ... but may add new keys, and overwrite its own (en_us first, then the selected language)
        ExtensionNamespaces.checkTranslation("gui", "gui.shop_title", emptySet()) { it in language }
        ExtensionNamespaces.checkTranslation("shop", "shop.title", setOf("shop.title")) { true }
    }
}
