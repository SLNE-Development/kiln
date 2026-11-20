package dev.kiln.client.lua

import dev.kiln.client.ext.ExtensionPackage
import dev.kiln.protocol.ExtensionPermissions
import dev.kiln.protocol.Ids
import dev.kiln.protocol.ProtocolLimits
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

/** Read-only snapshot of the local player handed to Lua (`kiln.player()`). */
data class PlayerSnapshot(val name: String, val x: Double, val y: Double, val z: Double)

/** What an extension needs from the client runtime (Minecraft side). All calls happen on the client thread. */
interface ExtensionHost {
    /** Sends [json] on channel `<ext>:<name>`; `false` if not possible right now (not in play, too large). */
    fun send(channel: String, json: String): Boolean

    fun toast(extension: LuaExtension, title: String, text: String)

    /** Registers keybind [id] of [extension]; edges are delivered through [LuaExtension.onKey]. */
    fun bindKey(extension: LuaExtension, id: String, name: String, defaultKey: String)

    /** Game time in seconds (ticks / 20, with partial tick). */
    fun time(): Double

    fun player(): PlayerSnapshot?

    fun log(extension: LuaExtension, message: String)

    /** [extension] failed at runtime (Lua error or limit) and has stopped; the host unloads it. */
    fun failed(extension: LuaExtension, error: LuaScriptException)

    // ---- Lua API v2 (defaults: not available, e.g. in tests) ----

    /** A loaded extension by id (for `kiln.import`), or `null`. */
    fun extension(id: String): LuaExtension? = null

    /** Shows [session] (replacing any open screen; the replaced Lua screen gets `close(..., "replaced")`). */
    fun openScreen(session: LuaScreenSession): Boolean = false

    /** Closes [session] if it is the screen shown. */
    fun closeScreen(session: LuaScreenSession) {}

    /** Decodes gzip NBT of `ItemStack.serializeAsBytes()`; `null` if it is no valid item. */
    fun decodeItem(nbt: ByteArray): ItemRef? = null

    /** Non-empty slots of the local player's inventory (copies), or `null` without a player. */
    fun inventory(): List<InventorySlot>? = null

    /** Plays UI sound [name] (one of [LuaExtension.SOUNDS]) with [pitch]. */
    fun playSound(name: String, pitch: Float) {}

    /** Monotonic real time in seconds (animations). */
    fun clock(): Double = System.nanoTime() / 1e9

    fun viewport(): ViewportInfo? = null
}

/**
 * One loaded extension: its sandboxed Lua environment ([LuaRuntime]) plus the `kiln` API (v2) bound to it.
 *
 * Permissions from the manifest decide which API parts exist (`nil` otherwise):
 * `ui.widget`/`ui.tooltip` need `widgets`, `ui.toast` needs `toasts`, `hud` needs `hud`, `keys` needs `keybinds`,
 * `net` needs `channels`, `ui.screen/open/close/sound` need `screens`, `player().inventory` needs `inventory`;
 * `ui.lerpColor`, `ui.viewport`, `items`, `time`, `clock`, `player`, `require`, `export`, `import`, `readonly` are
 * always there.
 *
 * **No borrowing of permissions:** every permission-gated function requires the permission from this extension, from
 * the *principal* of the running call ([LuaExecution.principal], the extension the host called) and from the
 * extension whose code called it ([LuaExecution.caller]), see [checkCaller]: an extension without `channels` that
 * calls an imported function which sends on the API extension's channel gets a Lua error, and so does one that got
 * hold of another extension's raw `ch.send`. The principal also pays the instruction budget of everything that runs
 * in its call.
 *
 * After a runtime error the extension is dead ([alive] = false): nothing of it runs again, the host is notified once.
 */
class LuaExtension(
    val pkg: ExtensionPackage,
    private val host: ExtensionHost,
    limits: LuaLimits = LuaLimits(),
) {
    val id: String get() = pkg.id
    val name: String get() = pkg.name

    val runtime = LuaRuntime(pkg.id, limits, print = { host.log(this, it) })

    var alive: Boolean = true
        private set

    private var loading = false

    /** Ids of the extensions this one may import (manifest `dependencies`). */
    val dependencies: List<String> get() = pkg.dependencies

    /** Value given to `kiln.export` (`nil` = nothing exported). */
    var exported: LuaValue = LuaValue.NIL
        private set

    /** Read-only views of this extension's values for importers. */
    val exports = LuaExports(this)

    val items = LuaItems({ host.decodeItem(it) })

    private val screenDefs = HashMap<String, LuaTable>()

    /** This extension's Lua screen while it is shown. */
    var screenSession: LuaScreenSession? = null
        private set

    private var inCloseCallback = false

    /** [ExtensionHost.clock] until which `ui.open` refuses (after the player forced a screen of this extension shut). */
    private var openBlockedUntil = Double.NEGATIVE_INFINITY
    private val openTimes = ArrayDeque<Long>()
    private val soundTimes = ArrayDeque<Long>()

    /** Widget definitions by name (`<ext>:<name>` -> name). */
    private val widgetDefs = HashMap<String, LuaTable>()
    private val instances = Collections.newSetFromMap(WeakHashMap<WidgetInstance, Boolean>())

    class HudLayer(val id: String, val anchor: String, val width: Int, val height: Int, val render: LuaValue)

    private val hud = LinkedHashMap<String, HudLayer>()
    val hudLayers: Collection<HudLayer> get() = hud.values

    private val keyHandlers = HashMap<String, Pair<LuaValue, LuaValue>>()
    private val channels = HashMap<String, Channel>()
    private val modules = HashMap<String, LuaValue>()
    private val modulesLoading = HashSet<String>()
    private val sendTimes = ArrayDeque<Long>()
    private val toastTimes = ArrayDeque<Long>()

    private val graphics = LuaGraphics(this)

    init {
        runtime.owner = this
        runtime.memoryDependencies = { dependencies.mapNotNull { host.extension(it)?.runtime } }
        runtime.extraRoots = {
            val roots = ArrayList<LuaValue>()
            roots += exported
            roots += screenDefs.values
            screenSession?.let { session ->
                roots += session.self
                roots += session.screen
                session.textInputs.forEach { roots += it.field }
            }
            roots += widgetDefs.values
            synchronized(instances) { instances.forEach { roots += it.self } }
            hud.values.forEach { roots += it.render }
            keyHandlers.values.forEach { roots += it.first; roots += it.second }
            channels.values.forEach { roots += it.table; roots.addAll(it.handlers) }
            roots += modules.values
            roots
        }
        runtime.globals.set("kiln", createApi())
    }

    private fun has(permission: String) = permission in pkg.permissions

    /**
     * Permission check of every gated API function of this extension; a Lua error unless **all** of these hold
     * [permission] (effective rights = intersection, a function never runs with more rights than its owner):
     * - this extension, the owner of the API function (always true for functions that exist, checked anyway);
     * - the principal of the running call ([LuaExecution.principal], the extension the host called); there must be
     *   one: outside a runtime call no gated function runs;
     * - the extension whose Lua code called the function directly ([LuaExecution.caller]): a raw API function handed
     *   to (or stolen by) another extension cannot be used by that extension's code, also not inside a call of this
     *   extension (e.g. a callback of the other extension that this one runs).
     */
    internal fun checkCaller(permission: String) {
        if (!has(permission)) throw LuaError("permission '$permission' missing: extension '$id' does not have it")
        val principal = LuaExecution.principal?.owner as? LuaExtension
            ?: throw LuaError("permission '$permission': API of '$id' used outside a call of the runtime")
        if (principal !== this && permission !in principal.pkg.permissions) {
            throw LuaError("permission '$permission' missing: extension '${principal.id}' cannot use it through '$id'")
        }
        when (val caller = LuaExecution.caller()) {
            LuaExecution.Caller.Host -> {}
            LuaExecution.Caller.Unknown -> throw LuaError("permission '$permission': caller of '$id' API is unknown")
            is LuaExecution.Caller.Script -> {
                val owner = caller.runtime.owner as? LuaExtension
                    ?: throw LuaError("permission '$permission': caller of '$id' API is unknown")
                if (owner !== this && permission !in owner.pkg.permissions) {
                    throw LuaError("permission '$permission' missing: code of extension '${owner.id}' cannot use it through '$id'")
                }
            }
        }
    }

    // ---- loading ------------------------------------------------------------------------------------------

    /** Runs the main file. Throws [LuaScriptException] (load failure = kick). */
    fun load() {
        val source = pkg.file(pkg.main) ?: throw LuaScriptException("$id: main file ${pkg.main} missing")
        loading = true
        try {
            runtime.load(source, pkg.main.removeSuffix(".lua"))
            runtime.checkMemory()
        } catch (e: LuaScriptException) {
            alive = false
            throw e
        } finally {
            loading = false
        }
    }

    /** Stops the extension for good (unload on disconnect or after an error elsewhere). */
    fun stop() {
        alive = false
        screenSession?.dispose()
        screenSession = null
    }

    /** Runs [function] with the per-call budget; on failure the extension dies and the host is told. */
    internal fun invoke(function: LuaValue, args: Varargs = LuaValue.NONE): Varargs? {
        if (!alive || !function.isfunction()) return null
        val principal = LuaExecution.principal?.owner
        if (principal != null && principal !== this && !loading) {
            // a callback of this extension triggered inside another extension's call (e.g. a screen replaced by
            // ui.open runs its close callback): budget limits stay the caller's, but a Lua error only stops this one
            return try {
                function.invoke(args)
            } catch (e: LuaError) {
                die(LuaScriptException("$id: ${(e.message ?: "error").lineSequence().first().take(300)}"))
                null
            }
        }
        return try {
            runtime.call(function, args)
        } catch (e: LuaScriptException) {
            if (loading) throw e
            die(e)
            null
        }
    }

    /** Like [invoke] with the per-frame budget of screen rendering (not counted in the one-second window). */
    internal fun invokeFrame(function: LuaValue, args: Varargs): Varargs? {
        if (!alive || !function.isfunction()) return null
        return try {
            runtime.call(function, args, runtime.limits.instructionsPerFrame, countInWindow = false)
        } catch (e: LuaScriptException) {
            die(e)
            null
        }
    }

    private fun die(e: LuaScriptException) {
        alive = false
        val session = screenSession
        session?.dispose()
        host.failed(this, e)
        if (session != null) host.closeScreen(session)
        screenSession = null
    }

    // ---- widgets ------------------------------------------------------------------------------------------

    fun hasWidget(name: String): Boolean = name in widgetDefs

    /** A new instance of widget [name] (state table `self`, `init` already run), or `null` if unknown/dead. */
    fun createWidget(name: String): WidgetInstance? {
        if (!alive) return null
        val def = widgetDefs[name] ?: return null
        val instance = WidgetInstance(def)
        synchronized(instances) { instances += instance }
        invoke(def.rawget("init"), instance.self)
        return if (alive) instance else null
    }

    /** One `Custom` element rendered by a Lua widget. [self] survives `UpdateScreen`s (kept per element id). */
    inner class WidgetInstance(private val def: LuaTable) {
        val self = LuaTable()
        private var propsSource: Map<String, String>? = null
        private var props: LuaTable = LuaTable()

        val extension: LuaExtension get() = this@LuaExtension

        private fun w(width: Int, height: Int, properties: Map<String, String>, hovered: Boolean, mouseX: Double, mouseY: Double): LuaTable {
            if (propsSource != properties) {
                propsSource = HashMap(properties)
                props = propsTable(properties)
            }
            val w = LuaTable()
            w.set("width", width)
            w.set("height", height)
            w.set("props", props)
            w.set("hovered", LuaValue.valueOf(hovered))
            w.set("mouseX", mouseX)
            w.set("mouseY", mouseY)
            return w
        }

        /** Draws the widget at ([x], [y]) with size [width] x [height]; mouse coordinates are absolute. */
        fun render(target: DrawTarget, x: Int, y: Int, width: Int, height: Int, properties: Map<String, String>, hovered: Boolean, mouseX: Double, mouseY: Double) {
            val render = def.rawget("render")
            if (!render.isfunction()) return
            val w = w(width, height, properties, hovered, mouseX - x, mouseY - y)
            graphics.draw(target, x, y, allowTooltip = true) {
                invoke(render, LuaValue.varargsOf(self, graphics.table, w))
            }
        }

        /** Mouse button pressed inside the widget: `press` then `click`; returns `true` to report a `ScreenClick`. */
        fun mouseClicked(width: Int, height: Int, properties: Map<String, String>, mx: Double, my: Double, button: Int): Boolean {
            val w = w(width, height, properties, true, mx, my)
            def.rawget("press").takeIf { it.isfunction() }?.let {
                invoke(it, LuaValue.varargsOf(arrayOf(self, w, LuaValue.valueOf(mx), LuaValue.valueOf(my), LuaValue.valueOf(button))))
            }
            val click = def.rawget("click")
            if (!click.isfunction()) return true
            val result = invoke(click, LuaValue.varargsOf(self, w, LuaValue.valueOf(button))) ?: return false
            return result.arg1().toboolean()
        }

        /** Mouse button released after a press inside this widget (coordinates relative, may be outside). */
        fun mouseReleased(width: Int, height: Int, properties: Map<String, String>, mx: Double, my: Double, button: Int) {
            val release = def.rawget("release")
            if (!release.isfunction()) return
            val inside = mx >= 0 && my >= 0 && mx < width && my < height
            val w = w(width, height, properties, inside, mx, my)
            invoke(release, LuaValue.varargsOf(arrayOf(self, w, LuaValue.valueOf(mx), LuaValue.valueOf(my), LuaValue.valueOf(button))))
        }

        /** Wheel over the widget; `true` if the script consumed it. */
        fun mouseScrolled(width: Int, height: Int, properties: Map<String, String>, mx: Double, my: Double, amount: Double): Boolean {
            val scroll = def.rawget("scroll")
            if (!scroll.isfunction()) return false
            val w = w(width, height, properties, true, mx, my)
            val result = invoke(scroll, LuaValue.varargsOf(arrayOf(self, w, LuaValue.valueOf(mx), LuaValue.valueOf(my), LuaValue.valueOf(amount)))) ?: return false
            return result.arg1().toboolean()
        }
    }

    // ---- HUD ----------------------------------------------------------------------------------------------

    /** Draws [layer] into the area at ([x], [y]). */
    fun renderHud(layer: HudLayer, target: DrawTarget, x: Int, y: Int) {
        val area = LuaTable()
        area.set("width", layer.width)
        area.set("height", layer.height)
        graphics.draw(target, x, y, allowTooltip = false) {
            invoke(layer.render, LuaValue.varargsOf(graphics.table, area))
        }
    }

    // ---- keybinds -----------------------------------------------------------------------------------------

    fun onKey(id: String, pressed: Boolean) {
        val (press, release) = keyHandlers[id] ?: return
        invoke(if (pressed) press else release)
    }

    // ---- channels -----------------------------------------------------------------------------------------

    private inner class Channel(val name: String) {
        val handlers = ArrayList<LuaValue>()
        val table = LuaTable()
    }

    /** A message from the server on `<id>:<channel>`. Invalid JSON is dropped (logged), not a script error. */
    fun onChannelMessage(channel: String, json: String) {
        val ch = channels[channel] ?: return
        if (ch.handlers.isEmpty() || !alive) return
        val message = try {
            LuaJson.decode(json, INCOMING_JSON_LIMITS)
        } catch (e: LuaError) {
            host.log(this, "dropping invalid message on $id:$channel: ${e.message}")
            return
        }
        for (handler in ch.handlers.toList()) {
            if (!alive) return
            invoke(handler, message)
        }
    }

    // ---- API ----------------------------------------------------------------------------------------------

    private fun createApi(): LuaTable {
        val api = LuaTable()
        val ui = LuaTable()
        ui.set("lerpColor", fn { args -> LuaValue.valueOf(unsigned(lerpColor(color(args.arg(1)), color(args.arg(2)), args.checkdouble(3)))) })
        ui.set("viewport", fn { _ ->
            val v = host.viewport()
            if (v == null) LuaValue.NIL else LuaTable().apply { set("width", v.width); set("height", v.height); set("scale", v.scale) }
        })
        if (has(ExtensionPermissions.WIDGETS)) {
            ui.set("widget", gated(ExtensionPermissions.WIDGETS) { args -> defineWidget(args.checkjstring(1), args.checktable(2)); LuaValue.NONE })
            ui.set("tooltip", gated(ExtensionPermissions.WIDGETS) { args -> graphics.tooltip(args.checkjstring(1).take(MAX_TEXT)); LuaValue.NONE })
        }
        if (has(ExtensionPermissions.TOASTS)) {
            ui.set("toast", gated(ExtensionPermissions.TOASTS) { args -> toast(args.checkjstring(1), args.optjstring(2, "")); LuaValue.NONE })
        }
        if (has(ExtensionPermissions.SCREENS)) {
            ui.set("screen", gated(ExtensionPermissions.SCREENS) { args -> defineScreen(args.checkjstring(1), args.checktable(2)); LuaValue.NONE })
            ui.set("open", gated(ExtensionPermissions.SCREENS) { args -> LuaValue.valueOf(openScreen(args.checkjstring(1), args.arg(2))) })
            ui.set("close", gated(ExtensionPermissions.SCREENS) { _ -> LuaValue.valueOf(screenSession?.requestClose() ?: false) })
            ui.set("sound", gated(ExtensionPermissions.SCREENS) { args -> playSound(args.checkjstring(1), args.optdouble(2, 1.0)); LuaValue.NONE })
        }
        api.set("ui", ui)

        if (has(ExtensionPermissions.HUD)) {
            val hudApi = LuaTable()
            hudApi.set("layer", gated(ExtensionPermissions.HUD) { args -> defineHud(args.checkjstring(1), args.checktable(2), args.checkfunction(3)); LuaValue.NONE })
            api.set("hud", hudApi)
        }
        if (has(ExtensionPermissions.KEYBINDS)) {
            val keys = LuaTable()
            keys.set("bind", gated(ExtensionPermissions.KEYBINDS) { args -> bindKey(args.checkjstring(1), args.checktable(2)); LuaValue.NONE })
            api.set("keys", keys)
        }
        if (has(ExtensionPermissions.CHANNELS)) {
            val net = LuaTable()
            net.set("channel", gated(ExtensionPermissions.CHANNELS) { args -> channel(args.checkjstring(1)) })
            api.set("net", net)
        }
        val itemsApi = LuaTable()
        itemsApi.set("decode", fn { args -> items.decode(args.checkjstring(1)) })
        api.set("items", itemsApi)
        api.set("time", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.time())
        })
        api.set("clock", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.clock())
        })
        api.set("player", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                val p = host.player() ?: return LuaValue.NIL
                val t = LuaTable()
                t.set("name", p.name)
                t.set("x", p.x)
                t.set("y", p.y)
                t.set("z", p.z)
                if (has(ExtensionPermissions.INVENTORY)) t.set("inventory", gated(ExtensionPermissions.INVENTORY) { _ -> inventory() })
                return t
            }
        })
        api.set("require", object : OneArgFunction() {
            override fun call(arg: LuaValue): LuaValue = require(arg.checkjstring())
        })
        api.set("export", fn { args -> export(args.arg1()); LuaValue.NONE })
        api.set("import", fn { args -> import(args.checkjstring(1)) })
        api.set("readonly", fn { args -> exports.wrap(args.checktable(1)) })
        api.set("id", id)
        api.set("apiVersion", API_VERSION)
        return api
    }

    private fun fn(body: (Varargs) -> Varargs): LuaFunction = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs = body(args)
    }

    /** An API function that needs [permission] from this extension (it exists only then) and from the caller. */
    private fun gated(permission: String, body: (Varargs) -> Varargs): LuaFunction = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            checkCaller(permission)
            return body(args)
        }
    }

    // ---- exports / imports --------------------------------------------------------------------------------

    private fun export(value: LuaValue) {
        if (!loading) throw LuaError("kiln.export is only allowed while the extension loads")
        if (!exported.isnil()) throw LuaError("kiln.export may be called only once")
        if (value.isnil()) throw LuaError("kiln.export needs a value")
        exported = value
    }

    private fun import(depId: String): LuaValue {
        if (depId !in dependencies) throw LuaError("'$depId' is not a dependency of '$id' (add it to \"dependencies\" in extension.json)")
        val dependency = host.extension(depId) ?: throw LuaError("dependency '$depId' is not loaded")
        if (!dependency.alive) throw LuaError("dependency '$depId' is unloaded")
        if (dependency.exported.isnil()) throw LuaError("'$depId' exports nothing (kiln.export)")
        return dependency.exports.wrap(dependency.exported)
    }

    // ---- screens ------------------------------------------------------------------------------------------

    private fun defineScreen(type: String, def: LuaTable) {
        val name = ownName(type, "screen")
        if (name !in screenDefs && screenDefs.size >= MAX_SCREENS) throw LuaError("too many screens (limit $MAX_SCREENS)")
        for (key in LuaScreenSession.CALLBACKS) {
            val v = def.rawget(key)
            if (!v.isnil() && !v.isfunction()) throw LuaError("screen '$type': $key must be a function")
        }
        val fullscreen = def.rawget("fullscreen")
        if (!fullscreen.isnil() && !fullscreen.isboolean()) throw LuaError("screen '$type': fullscreen must be a boolean")
        if (!fullscreen.toboolean()) {
            for (key in listOf("width", "height")) {
                val v = def.rawget(key)
                if (!v.isnil() && (!v.isnumber() || v.toint() !in 1..LuaScreenSession.MAX_SIZE)) throw LuaError("screen '$type': $key must be 1..${LuaScreenSession.MAX_SIZE}")
            }
        }
        screenDefs[name] = def
    }

    private fun openScreen(type: String, args: LuaValue): Boolean {
        val name = ownName(type, "screen")
        val def = screenDefs[name] ?: throw LuaError("unknown screen '$type' (define it with kiln.ui.screen)")
        if (loading) throw LuaError("screens cannot be opened while the extension loads")
        if (inCloseCallback) throw LuaError("screens cannot be opened from a close callback")
        if (host.clock() < openBlockedUntil) return false
        val now = System.nanoTime()
        while (openTimes.isNotEmpty() && now - openTimes.first() >= 10_000_000_000L) openTimes.removeFirst()
        if (openTimes.size >= MAX_OPENS_PER_10S) throw LuaError("too many screens opened (limit $MAX_OPENS_PER_10S per 10 s)")
        openTimes.addLast(now)
        val session = LuaScreenSession(this, name, def, if (args.isnil()) LuaTable() else args, graphics, host)
        val previous = screenSession
        screenSession = session
        if (!host.openScreen(session)) {
            if (screenSession === session) screenSession = previous?.takeIf { it.state != LuaScreenSession.State.CLOSED }
            return false
        }
        return true
    }

    /**
     * The player used the guaranteed way out of this extension's screen (Shift+Escape / repeated Escape): it may not
     * open a screen again for [FORCED_OUT_SECONDS], so it cannot trap the player by reopening at once (HUD render,
     * channel handler, ...).
     */
    internal fun forcedOut() {
        openBlockedUntil = host.clock() + FORCED_OUT_SECONDS
    }

    /** The host removed [session] from the screen (Escape, close, replaced). */
    fun screenRemoved(session: LuaScreenSession) {
        inCloseCallback = true
        try {
            session.removed()
        } finally {
            inCloseCallback = false
        }
        if (screenSession === session) screenSession = null
    }

    internal fun playSound(name: String, pitch: Double) {
        if (name !in SOUNDS) throw LuaError("unknown sound '$name' (one of ${SOUNDS.joinToString()})")
        val now = System.nanoTime()
        while (soundTimes.isNotEmpty() && now - soundTimes.first() >= 1_000_000_000L) soundTimes.removeFirst()
        if (soundTimes.size >= MAX_SOUNDS_PER_SECOND) return
        soundTimes.addLast(now)
        host.playSound(name, pitch.coerceIn(0.5, 2.0).toFloat())
    }

    private fun inventory(): LuaValue {
        val slots = host.inventory() ?: return LuaValue.NIL
        val result = LuaTable()
        for ((i, slot) in slots.withIndex()) {
            val entry = LuaTable()
            entry.set("slot", slot.slot)
            entry.set("handle", ItemHandle(slot.item))
            entry.set("count", slot.count)
            entry.set("id", slot.item.id)
            entry.set("name", slot.item.name)
            result.set(i + 1, entry)
        }
        return result
    }

    private fun defineWidget(type: String, def: LuaTable) {
        val name = ownName(type, "widget type")
        if (name !in widgetDefs && widgetDefs.size >= MAX_WIDGETS) throw LuaError("too many widget types (limit $MAX_WIDGETS)")
        for (key in listOf("init", "render", "click", "press", "release", "scroll")) {
            val v = def.rawget(key)
            if (!v.isnil() && !v.isfunction()) throw LuaError("widget '$type': $key must be a function")
        }
        widgetDefs[name] = def
    }

    private fun defineHud(layerId: String, options: LuaTable, render: LuaValue) {
        if (!Ids.isValidExtensionName(layerId)) throw LuaError("invalid HUD layer id '$layerId' ([a-z0-9_]{1,32})")
        if (layerId !in hud && hud.size >= MAX_HUD_LAYERS) throw LuaError("too many HUD layers (limit $MAX_HUD_LAYERS)")
        val anchor = options.get("anchor").optjstring("top_left")
        if (anchor !in ANCHORS) throw LuaError("invalid anchor '$anchor' (one of ${ANCHORS.joinToString()})")
        val width = options.get("width").checkint()
        val height = options.get("height").checkint()
        if (width !in 1..MAX_HUD_SIZE || height !in 1..MAX_HUD_SIZE) throw LuaError("HUD layer size must be 1..$MAX_HUD_SIZE")
        hud[layerId] = HudLayer(layerId, anchor, width, height, render)
    }

    private fun bindKey(keyId: String, options: LuaTable) {
        if (!loading) throw LuaError("keys.bind is only allowed while the extension loads")
        if (!Ids.isValidExtensionName(keyId)) throw LuaError("invalid keybind id '$keyId' ([a-z0-9_]{1,32})")
        if (keyId in keyHandlers) throw LuaError("keybind '$keyId' is already bound")
        if (keyHandlers.size >= MAX_KEYBINDS) throw LuaError("too many keybinds (limit $MAX_KEYBINDS)")
        val displayName = options.get("name").optjstring(keyId).take(64)
        val default = defaultKeyName(options.get("default").optjstring(""))
        val press = options.get("press")
        val release = options.get("release")
        if (!press.isnil() && !press.isfunction()) throw LuaError("keybind '$keyId': press must be a function")
        if (!release.isnil() && !release.isfunction()) throw LuaError("keybind '$keyId': release must be a function")
        keyHandlers[keyId] = press to release
        host.bindKey(this, keyId, displayName, default)
    }

    private fun channel(channelName: String): LuaValue {
        if (!Ids.isValidExtensionName(channelName)) throw LuaError("invalid channel name '$channelName' ([a-z0-9_]{1,32})")
        channels[channelName]?.let { return it.table }
        if (channels.size >= MAX_CHANNELS) throw LuaError("too many channels (limit $MAX_CHANNELS)")
        val ch = Channel(channelName)
        ch.table.set("name", "$id:$channelName")
        ch.table.set("send", gated(ExtensionPermissions.CHANNELS) { args ->
            val message = if (args.arg1() === ch.table) args.arg(2) else args.arg1()
            LuaValue.valueOf(send(channelName, message))
        })
        ch.table.set("on", gated(ExtensionPermissions.CHANNELS) { args ->
            val handler = (if (args.arg1() === ch.table) args.arg(2) else args.arg1()).checkfunction()
            if (ch.handlers.size >= MAX_HANDLERS) throw LuaError("too many handlers on channel '$channelName'")
            ch.handlers += handler
            LuaValue.NONE
        })
        channels[channelName] = ch
        return ch.table
    }

    private fun send(channelName: String, message: LuaValue): Boolean {
        val json = LuaJson.encode(message, JSON_LIMITS)
        val now = System.nanoTime()
        while (sendTimes.isNotEmpty() && now - sendTimes.first() >= 1_000_000_000L) sendTimes.removeFirst()
        if (sendTimes.size >= MAX_SENDS_PER_SECOND) return false
        if (!host.send("$id:$channelName", json)) return false
        sendTimes.addLast(now)
        return true
    }

    private fun toast(title: String, text: String) {
        val now = System.nanoTime()
        while (toastTimes.isNotEmpty() && now - toastTimes.first() >= 10_000_000_000L) toastTimes.removeFirst()
        if (toastTimes.size >= MAX_TOASTS_PER_10S) return
        toastTimes.addLast(now)
        host.toast(this, title.take(64), text.take(MAX_TEXT))
    }

    private fun require(file: String): LuaValue {
        val module = file.removeSuffix(".lua")
        if (!MODULE.matches(module)) throw LuaError("invalid module name '$file'")
        modules[module]?.let { return it }
        val path = "$module.lua"
        val source = pkg.file(path) ?: throw LuaError("module '$module' not found in the package")
        if (!modulesLoading.add(module)) throw LuaError("module '$module' requires itself")
        try {
            val chunk = LuaSandbox.compile(runtime.globals, source, module)
            val result = runtime.call(chunk).arg1()
            val value = if (result.isnil()) LuaValue.TRUE else result
            modules[module] = value
            return value
        } finally {
            modulesLoading.remove(module)
        }
    }

    /** `"<ext>:<name>"` (own namespace) or a bare name -> name. */
    private fun ownName(type: String, what: String): String {
        val ns = type.substringBefore(':', id)
        val name = type.substringAfter(':')
        if (ns != id) throw LuaError("$what '$type' must be in the extension's namespace '$id'")
        if (!Ids.isValidExtensionName(name)) throw LuaError("invalid $what '$type' (name [a-z0-9_]{1,32})")
        return name
    }

    private fun propsTable(properties: Map<String, String>): LuaTable {
        val t = LuaTable()
        for ((k, v) in properties) t.set(k, v)
        return t
    }

    companion object {
        val JSON_LIMITS = LuaJson.Limits(maxDepth = 16, maxEntries = 2_048, maxChars = ProtocolLimits.MAX_EXTENSION_JSON_LENGTH)

        /** Server -> client messages: larger (item lists of a shop page). */
        val INCOMING_JSON_LIMITS = LuaJson.Limits(maxDepth = 16, maxEntries = 32_768, maxChars = ProtocolLimits.MAX_CLIENTBOUND_EXTENSION_JSON_LENGTH)
        const val API_VERSION = 2
        const val MAX_SCREENS = 32
        const val MAX_OPENS_PER_10S = 10

        /** Seconds an extension cannot open screens after the player forced one of them shut. */
        const val FORCED_OUT_SECONDS = 10.0
        const val MAX_SOUNDS_PER_SECOND = 10

        /** `kiln.ui.sound` / `s:sound` names (mapped to vanilla sound events by the runtime). */
        val SOUNDS = listOf("click", "pling", "success", "error", "page", "toast")
        val ANCHORS = listOf("top_left", "top", "top_right", "left", "center", "right", "bottom_left", "bottom", "bottom_right")
        const val MAX_WIDGETS = 64
        const val MAX_HUD_LAYERS = 16
        const val MAX_HUD_SIZE = 512
        const val MAX_KEYBINDS = 32
        const val MAX_CHANNELS = 32
        const val MAX_HANDLERS = 8
        const val MAX_SENDS_PER_SECOND = 40
        const val MAX_TOASTS_PER_10S = 5
        const val MAX_TEXT = 512
        private val MODULE = Regex("[a-z0-9_]+(/[a-z0-9_]+)*")


        /** `"J"` -> `key.keyboard.j`, `"key.mouse.middle"` stays, `"left.shift"` -> `key.keyboard.left.shift`. */
        fun defaultKeyName(spec: String): String {
            val s = spec.trim().lowercase()
            return when {
                s.isEmpty() -> "key.keyboard.unknown"
                s.startsWith("key.") -> s
                else -> "key.keyboard.$s"
            }
        }

        /** Lua number -> ARGB int (numbers above 2^31 such as `0xFF2A2F2C` wrap). */
        fun color(v: LuaValue, default: Int = -1): Int = if (v.isnumber()) v.todouble().toLong().toInt() else default

        fun unsigned(argb: Int): Double = (argb.toLong() and 0xFFFFFFFFL).toDouble()

        fun lerpColor(a: Int, b: Int, t: Double): Int {
            val f = t.coerceIn(0.0, 1.0)
            fun ch(shift: Int): Int {
                val x = (a ushr shift) and 0xFF
                val y = (b ushr shift) and 0xFF
                return (x + (y - x) * f + 0.5).toInt().coerceIn(0, 255) shl shift
            }
            return ch(24) or ch(16) or ch(8) or ch(0)
        }

        /**
         * Pixel runs of a gauge arc: the **upper half** of a ring around ([cx], [cy]) (outer [radius], [thickness]),
         * from fraction [from] to [to], where 0 is the left end, 0.5 the top and 1 the right end. Emits vertical runs
         * `(x, y0, y1)` (y1 exclusive); nothing is drawn below [cy].
         */
        fun arcRuns(cx: Double, cy: Double, radius: Double, thickness: Double, from: Double, to: Double, emit: (Int, Int, Int) -> Unit) {
            val lo = from.coerceIn(0.0, 1.0)
            val hi = to.coerceIn(0.0, 1.0)
            if (radius <= 0 || thickness <= 0 || hi <= lo) return
            val outer = radius
            val inner = (radius - thickness).coerceAtLeast(0.0)
            val x0 = floor(cx - outer).toInt()
            val x1 = floor(cx + outer).toInt()
            val y0 = floor(cy - outer).toInt()
            val yEnd = floor(cy).toInt() // exclusive
            for (px in x0..x1) {
                val dx = px + 0.5 - cx
                var runStart = Int.MIN_VALUE
                for (py in y0..yEnd) {
                    var inside = false
                    if (py < yEnd) {
                        val dy = cy - (py + 0.5) // > 0 above the center
                        val d = sqrt(dx * dx + dy * dy)
                        if (dy >= 0 && d in inner..outer) {
                            val t = atan2(dy, -dx) / Math.PI // 0 left, 0.5 top, 1 right
                            inside = t >= lo && t <= hi
                        }
                    }
                    if (inside && runStart == Int.MIN_VALUE) runStart = py
                    if (!inside && runStart != Int.MIN_VALUE) {
                        emit(px, runStart, py)
                        runStart = Int.MIN_VALUE
                    }
                }
            }
        }
    }
}
