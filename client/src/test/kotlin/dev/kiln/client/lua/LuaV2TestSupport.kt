package dev.kiln.client.lua

import dev.kiln.client.ext.ExtensionPackage
import dev.kiln.protocol.NumericRange
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream

/** A host with the Lua API v2 parts (extensions by id, screens shown immediately, fake items). */
class V2Host : ExtensionHost {
    val extensions = LinkedHashMap<String, LuaExtension>()
    val sent = ArrayList<Pair<String, String>>()
    val failures = ArrayList<String>()
    val logs = ArrayList<String>()
    val sounds = ArrayList<String>()
    var shown: LuaScreenSession? = null
    var view: FakeView = FakeView()
    var clockSeconds = 0.0
    var decodes = 0
    var inventorySlots: List<InventorySlot> = emptyList()
    /** Keybinds registered by the extensions: "<ext>:<id>" -> default key name. */
    val keybinds = LinkedHashMap<String, String>()

    fun load(pkg: ExtensionPackage, limits: LuaLimits = LuaLimits()): LuaExtension {
        val ext = LuaExtension(pkg, this, limits)
        extensions[pkg.id] = ext
        ext.load()
        return ext
    }

    override fun send(channel: String, json: String): Boolean { sent += channel to json; return true }
    override fun toast(extension: LuaExtension, title: String, text: String) {}
    override fun bindKey(extension: LuaExtension, id: String, name: String, defaultKey: String) { keybinds["${extension.id}:$id"] = defaultKey }
    override fun time(): Double = 0.0
    override fun player(): PlayerSnapshot = PlayerSnapshot("Steve", 0.0, 0.0, 0.0)
    override fun log(extension: LuaExtension, message: String) { logs += message }
    override fun failed(extension: LuaExtension, error: LuaScriptException) { failures += "${extension.id}: ${error.message}" }
    override fun extension(id: String): LuaExtension? = extensions[id]
    override fun clock(): Double = clockSeconds
    override fun playSound(name: String, pitch: Float) { sounds += name }
    override fun inventory(): List<InventorySlot> = inventorySlots

    override fun decodeItem(nbt: ByteArray): ItemRef? {
        decodes++
        val text = java.util.zip.GZIPInputStream(nbt.inputStream()).readBytes().toString(Charsets.UTF_8)
        if (!text.startsWith("item:")) return null
        return FakeItem(text.removePrefix("item:"))
    }

    /** Like the runtime: replacing a shown screen removes it first, then the new one is laid out (init). */
    override fun openScreen(session: LuaScreenSession): Boolean {
        shown?.let { old -> shown = null; old.extension.screenRemoved(old) }
        shown = session
        view = FakeView()
        session.view = view
        session.layout(400, 300, 2.0)
        return true
    }

    override fun closeScreen(session: LuaScreenSession) {
        if (shown !== session) return
        shown = null
        session.extension.screenRemoved(session)
    }

    /** Escape as the runtime handles it: Lua first, then close. */
    fun escape(shift: Boolean = false): LuaScreenSession.KeyResult {
        val session = shown!!
        val result = session.keyPressed("escape", LuaScreenSession.Mods(shift, false, false))
        if (result == LuaScreenSession.KeyResult.CLOSE) closeScreen(session)
        return result
    }

    companion object {
        fun pkg(id: String, permissions: Set<String> = emptySet(), deps: List<String> = emptyList(), vararg files: Pair<String, String>) =
            ExtensionPackage(id, "1", id, "main.lua", permissions, files.associate { it.first to it.second.toByteArray() }, deps)

        /** What `ExtensionItems.encode` would send for a fake item [id]. */
        fun item(id: String): String {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write("item:$id".toByteArray()) }
            return Base64.getEncoder().encodeToString(out.toByteArray())
        }
    }
}

class FakeItem(override val id: String) : ItemRef {
    override val name: String = id.substringAfter(':').replace('_', ' ')
    override val maxStackSize: Int = 64
}

class FakeBox : TextBox {
    private var current: String = ""
    override val value: String get() = current
    override var focused: Boolean = false
    var maxLength = 0
    var placeholder = ""
    var numeric: NumericRange? = null
    var enabled = true
    var rect = IntArray(4)
    var draws = 0
    var onEdit: (String) -> Unit = {}

    /** Order of setValue / place calls (a vanilla EditBox must know its width before a value is set). */
    val calls = ArrayList<String>()

    override fun setValue(value: String) { calls += "value"; current = value.take(maxLength.takeIf { it > 0 } ?: Int.MAX_VALUE) }
    override fun configure(maxLength: Int, placeholder: String, numeric: NumericRange?, enabled: Boolean) {
        this.maxLength = maxLength; this.placeholder = placeholder; this.numeric = numeric; this.enabled = enabled
    }
    override fun place(x: Int, y: Int, width: Int, height: Int) { calls += "place"; rect = intArrayOf(x, y, width, height) }
    override fun draw(target: DrawTarget, mouseX: Int, mouseY: Int) { draws++ }
    override fun mouseClicked(x: Double, y: Double, button: Int, doubleClick: Boolean) {}

    /** The player types: the value changes and the edit is reported. */
    fun type(text: String) {
        current = (current + text).take(maxLength)
        onEdit(current)
    }
}

class FakeView : ScreenView {
    val boxes = ArrayList<FakeBox>()
    override fun createTextBox(onEdit: (String) -> Unit): TextBox = FakeBox().also { it.onEdit = onEdit; boxes += it }
}

/** Counts draw calls (cheap, for benchmarks) and records a few for assertions. */
class CountingTarget : DrawTarget {
    var fills = 0
    var texts = 0
    var items = 0
    var clips = 0
    val cursors = ArrayList<String>()
    val tooltips = ArrayList<String>()
    override fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) { fills++ }
    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Int) {}
    override fun text(text: String, x: Int, y: Int, color: Int, shadow: Boolean) { texts++ }
    override fun textWidth(text: String): Int = text.length * 6
    override fun textureSize(id: String): IntArray? = null
    override fun texture(id: String, x: Int, y: Int, w: Int, h: Int, u: Float, v: Float, regionWidth: Int, regionHeight: Int, textureWidth: Int, textureHeight: Int) {}
    override fun item(itemId: String, x: Int, y: Int, size: Int, count: Int, decorations: Boolean) { items++ }
    override fun itemStack(ref: ItemRef, x: Int, y: Int, size: Int, count: Int, decorations: Boolean) { items++ }
    override fun tooltip(text: String) { tooltips += text }
    override fun itemTooltip(ref: ItemRef, x: Int, y: Int, extra: List<String>) { tooltips += "item:${ref.id}" }
    override fun pushClip(x0: Int, y0: Int, x1: Int, y1: Int) { clips++ }
    override fun cursor(name: String) { cursors += name }
}
