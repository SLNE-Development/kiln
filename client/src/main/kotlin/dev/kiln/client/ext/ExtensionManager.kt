package dev.kiln.client.ext

import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.NativeImage
import dev.kiln.client.keybind.DynamicTranslations
import dev.kiln.client.keybind.ServerKeybinds
import dev.kiln.client.lua.ExtensionHost
import dev.kiln.client.lua.InventorySlot
import dev.kiln.client.lua.ItemRef
import dev.kiln.client.lua.LuaExtension
import dev.kiln.client.lua.LuaLimits
import dev.kiln.client.lua.LuaScreenSession
import dev.kiln.client.lua.LuaScriptException
import dev.kiln.client.lua.PlayerSnapshot
import dev.kiln.client.lua.ViewportInfo
import dev.kiln.client.screen.ItemStacks
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Util
import dev.kiln.client.toast.KilnToast
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.locale.Language
import net.minecraft.resources.Identifier
import dev.kiln.client.mixin.TextureManagerAccessor
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

/** Textures of the loaded extensions (`<ext>:textures/...png` -> size). */
interface ExtensionTextures {
    fun size(id: String): IntArray?
}

/**
 * The extensions of the current connection, on the Minecraft side: registers their textures (as [DynamicTexture]s
 * under `<ext>:textures/...`) and language entries, runs their Lua ([LuaExtension]), draws their HUD layers,
 * routes keybinds, channel messages and widget creation, and removes everything again on [unloadAll].
 *
 * [sender] delivers channel messages to the server (`false` outside the play phase).
 */
class ExtensionManager(
    private val sender: (channel: String, json: String) -> Boolean,
    /** `true` while Lua screens may be opened (play phase of a completed kiln connection). */
    private val inWorld: () -> Boolean = { true },
) : ExtensionHost, ExtensionTextures, HudElement {
    private val logger = LoggerFactory.getLogger("kiln")
    private val minecraft: Minecraft get() = Minecraft.getInstance()

    private val extensions = LinkedHashMap<String, LuaExtension>()
    private val textures = HashMap<String, IntArray>()
    private val translations = ArrayList<String>()
    private var serverKey = ""

    val loaded: Collection<LuaExtension> get() = extensions.values

    // ---- loading / unloading ------------------------------------------------------------------------------

    /** Starts a fresh set of extensions for [server] (address, key for keybind persistence). */
    fun beginLoad(server: String) {
        unloadAll()
        serverKey = server
        ServerKeybinds.setServer(server)
    }

    /**
     * Loads one package (textures, language entries, Lua `main`). Throws [ExtensionLoadException] with a readable
     * reason; the caller unloads everything then ([unloadAll]).
     */
    fun load(pkg: ExtensionPackage) {
        try {
            // the manifest was checked already (ExtensionHandshake); again here so no load path can skip it
            ExtensionNamespaces.problem(pkg.id, FabricLoader.getInstance()::isModLoaded)?.let { throw ExtensionPackageException(it) }
            registerTextures(pkg)
            registerTranslations(pkg)
            val extension = LuaExtension(pkg, this, LuaLimits())
            extensions[pkg.id] = extension
            extension.load()
            logger.info("Loaded extension {} {} ({})", pkg.id, pkg.version, pkg.permissions.joinToString())
        } catch (e: LuaScriptException) {
            throw ExtensionLoadException(e.message ?: "script error")
        } catch (e: ExtensionPackageException) {
            throw ExtensionLoadException(e.message ?: "invalid package")
        }
    }

    /** Removes every trace of the extensions (disconnect). */
    fun unloadAll() {
        closeLuaScreen { true }
        for (extension in extensions.values) {
            extension.stop()
            ServerKeybinds.removeExtension(extension.id)
        }
        extensions.clear()
        val textureManager = minecraft.textureManager
        for (id in textures.keys) Identifier.tryParse(id)?.let(textureManager::release)
        textures.clear()
        translations.forEach(DynamicTranslations::remove)
        translations.clear()
    }

    private fun registerTextures(pkg: ExtensionPackage) {
        for ((path, bytes) in pkg.files) {
            if (!path.startsWith("textures/") || !path.endsWith(".png")) continue
            val size = PngHeader.size(bytes) ?: throw ExtensionPackageException("${pkg.id}: $path is not a PNG")
            if (size[0] !in 1..MAX_TEXTURE_SIZE || size[1] !in 1..MAX_TEXTURE_SIZE) {
                throw ExtensionPackageException("${pkg.id}: $path is larger than ${MAX_TEXTURE_SIZE}x$MAX_TEXTURE_SIZE")
            }
            val image = try {
                NativeImage.read(bytes)
            } catch (e: Exception) {
                throw ExtensionPackageException("${pkg.id}: cannot decode $path")
            }
            val id = Identifier.fromNamespaceAndPath(pkg.id, path)
            ExtensionNamespaces.checkTexture(
                pkg.id, id.toString(),
                registered = { id.toString() in textures || (minecraft.textureManager as TextureManagerAccessor).`kiln$getByPath`().containsKey(id) },
                inResources = { minecraft.resourceManager.getResource(id).isPresent },
            )
            minecraft.textureManager.register(id, DynamicTexture({ "kiln extension $id" }, image))
            textures[id.toString()] = intArrayOf(image.width, image.height)
        }
    }

    /** `lang/en_us.json` and the current language; only keys in the extension's namespace (`<id>.…`). */
    private fun registerTranslations(pkg: ExtensionPackage) {
        val selected = minecraft.languageManager.selected
        val language = Language.getInstance()
        val own = HashSet<String>()
        for (locale in linkedSetOf("en_us", selected)) {
            val bytes = pkg.file("lang/$locale.json") ?: continue
            val json = try {
                JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
            } catch (e: Exception) {
                throw ExtensionPackageException("${pkg.id}: lang/$locale.json is not a JSON object")
            }
            var count = 0
            for ((key, value) in json.entrySet()) {
                if (!key.startsWith(pkg.id + ".") || !value.isJsonPrimitive || ++count > 2_048) continue
                ExtensionNamespaces.checkTranslation(pkg.id, key, own, language::has)
                DynamicTranslations.put(key, value.asString.take(1_024))
                own += key
                translations += key
            }
        }
    }

    // ---- runtime hooks ------------------------------------------------------------------------------------

    /** A widget instance for `Custom` type [type] (`<ext>:<name>`), or `null` (placeholder). */
    fun createWidget(type: Identifier): LuaExtension.WidgetInstance? {
        val extension = extensions[type.namespace] ?: return null
        if (!extension.alive || !extension.hasWidget(type.path)) return null
        return extension.createWidget(type.path)
    }

    /** `ExtensionMessage` from the server. */
    fun onChannelMessage(channel: String, json: String) {
        val ns = channel.substringBefore(':')
        val name = channel.substringAfter(':')
        val extension = extensions[ns] ?: return logger.debug("No extension for channel {}", channel)
        extension.onChannelMessage(name, json)
    }

    // ---- HUD ----------------------------------------------------------------------------------------------

    override fun extractRenderState(graphics: GuiGraphicsExtractor, deltaTracker: DeltaTracker) {
        if (extensions.isEmpty()) return
        val window = minecraft.window
        val screenW = window.guiScaledWidth
        val screenH = window.guiScaledHeight
        val target = GuiDrawTarget(graphics, minecraft.font, this, -1, -1)
        for (extension in extensions.values.toList()) {
            for (layer in extension.hudLayers.toList()) {
                if (!extension.alive) break
                val x = when (layer.anchor) {
                    "top_left", "left", "bottom_left" -> HUD_MARGIN
                    "top", "center", "bottom" -> (screenW - layer.width) / 2
                    else -> screenW - layer.width - HUD_MARGIN
                }
                val y = when (layer.anchor) {
                    "top_left", "top", "top_right" -> HUD_MARGIN
                    "left", "center", "right" -> (screenH - layer.height) / 2
                    else -> screenH - layer.height - HUD_MARGIN
                }
                graphics.enableScissor(x, y, x + layer.width, y + layer.height)
                try {
                    extension.renderHud(layer, target, x, y)
                } finally {
                    graphics.disableScissor()
                }
            }
        }
    }

    // ---- ExtensionHost ------------------------------------------------------------------------------------

    override fun send(channel: String, json: String): Boolean = sender(channel, json)

    override fun toast(extension: LuaExtension, title: String, text: String) {
        minecraft.gui.toastManager().addToast(KilnToast(Component.literal(title), Component.literal(text).takeIf { text.isNotEmpty() }, null, 5_000))
    }

    override fun bindKey(extension: LuaExtension, id: String, name: String, defaultKey: String) {
        ServerKeybinds.registerExtension(extension.id, id, name, extension.name, defaultKey, object : ServerKeybinds.Handler {
            override fun onKey(id: String, pressed: Boolean) = extension.onKey(id, pressed)
        })
    }

    override fun time(): Double {
        val level = minecraft.level ?: return 0.0
        return (level.gameTime + minecraft.deltaTracker.getGameTimeDeltaPartialTick(false)) / 20.0
    }

    override fun player(): PlayerSnapshot? {
        val player = minecraft.player ?: return null
        return PlayerSnapshot(player.name.string, player.x, player.y, player.z)
    }

    override fun log(extension: LuaExtension, message: String) {
        logger.info("[{}] {}", extension.id, message)
    }

    override fun failed(extension: LuaExtension, error: LuaScriptException) {
        logger.warn("Extension {} stopped: {}", extension.id, error.message)
        ServerKeybinds.removeExtension(extension.id)
        closeLuaScreen { it.extension === extension }
        // extensions importing it stop as well (no fallbacks)
        for (dependent in dependentsOf(extension.id)) {
            if (!dependent.alive) continue
            logger.warn("Extension {} stopped: its dependency {} was unloaded", dependent.id, extension.id)
            dependent.stop()
            ServerKeybinds.removeExtension(dependent.id)
            closeLuaScreen { it.extension === dependent }
        }
        minecraft.gui.toastManager().addToast(
            KilnToast(
                Component.literal("Erweiterung „${extension.name}“ entladen"),
                Component.literal(error.message ?: "Skriptfehler"),
                null,
                10_000,
            ),
        )
    }

    /** Loaded extensions that (transitively) import [id]. */
    fun dependentsOf(id: String): List<LuaExtension> {
        val result = LinkedHashSet<LuaExtension>()
        var changed = true
        while (changed) {
            changed = false
            for (e in extensions.values) {
                if (e !in result && e.dependencies.any { it == id || it in result.map(LuaExtension::id) }) {
                    result += e
                    changed = true
                }
            }
        }
        return result.toList()
    }

    // ---- Lua API v2 host functions ------------------------------------------------------------------------

    override fun extension(id: String): LuaExtension? = extensions[id]

    override fun openScreen(session: LuaScreenSession): Boolean {
        if (!inWorld() || minecraft.player == null) return false
        minecraft.gui.setScreen(LuaScreen(session, this))
        return (minecraft.gui.screen() as? LuaScreen)?.session === session
    }

    override fun closeScreen(session: LuaScreenSession) {
        val screen = minecraft.gui.screen() as? LuaScreen ?: return
        if (screen.session === session) minecraft.gui.setScreen(null)
    }

    /** Closes the shown Lua screen without running Lua if [which] matches its session. */
    private fun closeLuaScreen(which: (LuaScreenSession) -> Boolean) {
        val screen = minecraft.gui.screen() as? LuaScreen ?: return
        if (!which(screen.session)) return
        screen.silent = true
        minecraft.gui.setScreen(null)
    }

    override fun decodeItem(nbt: ByteArray): ItemRef? = ItemStacks.decodeBytes(nbt)?.let(::StackItemRef)

    override fun inventory(): List<InventorySlot>? {
        val inventory = minecraft.player?.inventory ?: return null
        val result = ArrayList<InventorySlot>()
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (stack.isEmpty) continue
            result += InventorySlot(slot, StackItemRef(stack.copyWithCount(1)), stack.count)
        }
        return result
    }

    override fun playSound(name: String, pitch: Float) {
        val sound = when (name) {
            "click" -> SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, pitch)
            "pling" -> SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, pitch)
            "success" -> SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, pitch)
            "error" -> SimpleSoundInstance.forUI(SoundEvents.VILLAGER_NO, pitch)
            "page" -> SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, pitch)
            "toast" -> SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_IN, pitch)
            else -> return
        }
        minecraft.soundManager.play(sound)
    }

    override fun clock(): Double = Util.getNanos() / 1e9

    override fun viewport(): ViewportInfo {
        val window = minecraft.window
        return ViewportInfo(window.guiScaledWidth, window.guiScaledHeight, window.guiScale.toDouble())
    }

    // ---- ExtensionTextures --------------------------------------------------------------------------------

    override fun size(id: String): IntArray? = textures[id]

    private companion object {
        const val HUD_MARGIN = 4
        const val MAX_TEXTURE_SIZE = 2048
    }
}

class ExtensionLoadException(message: String) : Exception(message)

/** Width/height from a PNG's IHDR chunk, checked before decoding (no decompression bombs). */
object PngHeader {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    fun size(bytes: ByteArray): IntArray? {
        if (bytes.size < 24) return null
        for (i in SIGNATURE.indices) if (bytes[i] != SIGNATURE[i]) return null
        if (String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR") return null
        fun int(at: Int) = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
        return intArrayOf(int(16), int(20))
    }
}
