package dev.kiln.client.lua

import dev.kiln.client.screen.NumericInput
import dev.kiln.protocol.NumericRange
import dev.kiln.protocol.ProtocolLimits
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import kotlin.math.floor

/** A native text field of a Lua screen (vanilla `EditBox` in the runtime, a fake in tests). */
interface TextBox {
    val value: String

    /** Replaces the content without reporting an edit. */
    fun setValue(value: String)

    var focused: Boolean

    fun configure(maxLength: Int, placeholder: String, numeric: NumericRange?, enabled: Boolean)

    /** Absolute GUI rectangle. */
    fun place(x: Int, y: Int, width: Int, height: Int)

    /** Draws the field now (inside the Lua render call, so it is layered and clipped like Lua drawing). */
    fun draw(target: DrawTarget, mouseX: Int, mouseY: Int)

    /** A click inside the field (cursor placement, selection). */
    fun mouseClicked(x: Double, y: Double, button: Int, doubleClick: Boolean)
}

/** What a [LuaScreenSession] needs from the Minecraft screen showing it. */
interface ScreenView {
    /** A new text field; [onEdit] is called on every edit by the player (not for [TextBox.setValue]). */
    fun createTextBox(onEdit: (String) -> Unit): TextBox
}

/**
 * State and Lua side of one open Lua screen (`kiln.ui.open`), independent of Minecraft (the runtime's
 * `LuaScreen` forwards input and rendering; tests drive it directly).
 *
 * Callbacks of the definition (all optional, all get the per-screen state `self` and the screen object `s`):
 * `init(self, s, args)`, `render(self, g, s)`, `resize(self, s)`, `close(self, s, reason)`,
 * `key(self, s, key, mods) -> bool`, `char(self, s, ch) -> bool`, `click(self, s, mx, my, button, mods) -> bool`,
 * `release(self, s, mx, my, button)`, `drag(self, s, mx, my, button, dx, dy)`, `scroll(self, s, mx, my, dy, dx) -> bool`.
 * Mouse coordinates are relative to the screen origin (`s.x`, `s.y`), buttons 0/1/2 = left/right/middle.
 *
 * Lifecycle: [layout] (first time: `init`, afterwards `resize`) -> frames ([render]) and input -> [removed] (once:
 * `close` with the reason `"escape"`, `"closed"` (ui.close / s:close) or `"replaced"` (another screen opened)) or
 * [dispose] (disconnect / extension unloaded: no Lua runs any more).
 */
class LuaScreenSession internal constructor(
    val extension: LuaExtension,
    /** Screen name inside the extension's namespace. */
    val name: String,
    private val def: LuaTable,
    private val args: LuaValue,
    private val graphics: LuaGraphics,
    private val host: ExtensionHost,
) {
    enum class State { NEW, OPEN, CLOSED }

    enum class KeyResult { HANDLED, CLOSE, PASS }

    var state: State = State.NEW
        private set

    val self = LuaTable()
    val screen = LuaTable()

    val fullscreen: Boolean = def.rawget("fullscreen").toboolean()
    private val defWidth: Int = if (fullscreen) 0 else def.rawget("width").optint(DEFAULT_WIDTH)
    private val defHeight: Int = if (fullscreen) 0 else def.rawget("height").optint(DEFAULT_HEIGHT)

    /** Absolute origin and size of the screen area (fullscreen: the whole window). */
    var x = 0; private set
    var y = 0; private set
    var width = 0; private set
    var height = 0; private set

    var view: ScreenView? = null

    private var closeReason: String? = null
    private var openedAt = 0.0
    private var lastFrame = -1.0
    private var frame = 0
    private var rendering = false
    private val pressed = HashSet<Int>()
    private var mods = Mods(false, false, false)
    private var mouseAbsX = 0
    private var mouseAbsY = 0

    private val inputs = LinkedHashMap<String, TextInput>()

    /** Text input with keyboard focus (gets keys and characters before Lua). */
    var focused: TextInput? = null
        private set

    init {
        if (!fullscreen && (defWidth !in 1..MAX_SIZE || defHeight !in 1..MAX_SIZE)) throw LuaError("screen size must be 1..$MAX_SIZE")
        screen.rawset("id", LuaValue.valueOf("${extension.id}:$name"))
        screen.rawset("fullscreen", LuaValue.valueOf(fullscreen))
        method("textInput") { a -> textInput(a.checkjstring(1), a.opttable(2, LuaTable())) }
        method("cursor") { a ->
            if (!rendering) throw LuaError("s:cursor can only be used while rendering")
            val name = a.checkjstring(1)
            if (name !in CURSORS) throw LuaError("unknown cursor '$name' (one of ${CURSORS.joinToString()})")
            graphics.currentTarget().cursor(name)
            LuaValue.NONE
        }
        method("close") { _ -> LuaValue.valueOf(requestClose()) }
        method("sound") { a -> extension.playSound(a.checkjstring(1), a.optdouble(2, 1.0)); LuaValue.NONE }
        method("isMouseDown") { a -> LuaValue.valueOf(a.optint(1, 0) in pressed) }
        method("focused") { _ -> focused?.let { LuaValue.valueOf(it.id) } ?: LuaValue.NIL }
        method("blur") { _ -> setFocus(null); LuaValue.NONE }
    }

    data class Mods(val shift: Boolean, val ctrl: Boolean, val alt: Boolean)

    // ---- lifecycle ----------------------------------------------------------------------------------------

    /** Window (re)sized to [viewportWidth] x [viewportHeight] GUI pixels: places the screen, runs `init` / `resize`. */
    fun layout(viewportWidth: Int, viewportHeight: Int, guiScale: Double) {
        if (state == State.CLOSED) return
        if (fullscreen) {
            x = 0; y = 0; width = viewportWidth; height = viewportHeight
        } else {
            width = defWidth; height = defHeight
            x = (viewportWidth - width) / 2; y = (viewportHeight - height) / 2
        }
        screen.rawset("x", x); screen.rawset("y", y)
        screen.rawset("width", width); screen.rawset("height", height)
        screen.rawset("viewportWidth", viewportWidth); screen.rawset("viewportHeight", viewportHeight)
        screen.rawset("guiScale", guiScale)
        if (state == State.NEW) {
            state = State.OPEN
            openedAt = host.clock()
            screen.rawset("time", 0.0); screen.rawset("delta", 0.0)
            call("init", self, screen, args)
        } else {
            call("resize", self, screen)
        }
    }

    /** Draws one frame; [mouseX] / [mouseY] absolute. */
    fun render(target: DrawTarget, mouseX: Int, mouseY: Int) {
        if (state != State.OPEN || !extension.alive) return
        val now = host.clock()
        screen.rawset("time", now - openedAt)
        screen.rawset("delta", if (lastFrame < 0) 0.0 else now - lastFrame)
        lastFrame = now
        setMouse(mouseX.toDouble(), mouseY.toDouble())
        frame++
        val render = def.rawget("render")
        if (render.isfunction()) {
            rendering = true
            try {
                graphics.draw(target, x, y, allowTooltip = true) {
                    extension.invokeFrame(render, LuaValue.varargsOf(self, graphics.table, screen))
                }
            } finally {
                rendering = false
            }
        }
        // inputs not declared in this frame disappear (and lose focus)
        val gone = inputs.values.filter { it.frame != frame }
        for (input in gone) {
            inputs.remove(input.id)
            if (focused === input) setFocus(null)
        }
    }

    /** The screen went away (Escape, ui.close, replaced). Runs `close` once. */
    fun removed() {
        if (state != State.OPEN) {
            state = State.CLOSED
            return
        }
        state = State.CLOSED
        inputs.clear()
        focused = null
        call("close", self, screen, LuaValue.valueOf(closeReason ?: "replaced"))
    }

    /** Disconnect / unload: closed without running Lua. */
    fun dispose() {
        state = State.CLOSED
        inputs.clear()
        focused = null
    }

    /** `ui.close()` / `s:close()`: asks the host to close this screen; `close` runs with reason `"closed"`. */
    fun requestClose(): Boolean {
        if (state != State.OPEN) return false
        closeReason = "closed"
        host.closeScreen(this)
        return true
    }

    /** The player pressed Escape and Lua did not handle it. */
    fun closingByEscape() {
        closeReason = "escape"
    }

    private val escapeTimes = ArrayDeque<Double>()

    /** Records an Escape press; `true` if it must close the screen regardless of Lua (see [keyPressed]). */
    private fun forcedEscape(mods: Mods): Boolean {
        if (mods.shift) return true
        val now = host.clock()
        while (escapeTimes.isNotEmpty() && now - escapeTimes.first() > FORCED_ESCAPE_SECONDS) escapeTimes.removeFirst()
        escapeTimes.addLast(now)
        return escapeTimes.size >= FORCED_ESCAPE_PRESSES
    }

    // ---- input --------------------------------------------------------------------------------------------

    /**
     * Key press not consumed by the focused text input. [key] is the short key name (`escape`, `enter`, `tab`,
     * `a`, `left.shift`, `keypad.enter`, ...).
     *
     * **Guaranteed way out** (the script cannot block it): Shift+Escape, or the [FORCED_ESCAPE_PRESSES]th Escape
     * within [FORCED_ESCAPE_SECONDS], closes the screen without asking Lua's `key` (`close` still runs with reason
     * `"escape"`). Otherwise an extension could keep the player in its screen by returning `true` for every Escape.
     */
    fun keyPressed(key: String, mods: Mods): KeyResult {
        if (state != State.OPEN) return KeyResult.PASS
        setMods(mods)
        if (key == "escape" && forcedEscape(mods)) {
            extension.forcedOut()
            closingByEscape()
            return KeyResult.CLOSE
        }
        if (callBool("key", self, screen, LuaValue.valueOf(key), modsTable(mods))) return KeyResult.HANDLED
        if (state != State.OPEN) return KeyResult.HANDLED
        if (key == "tab") {
            cycleFocus(backwards = mods.shift)
            return KeyResult.HANDLED
        }
        if (key == "escape") {
            closingByEscape()
            return KeyResult.CLOSE
        }
        return KeyResult.PASS
    }

    /** Character typed while no text input has focus. */
    fun charTyped(ch: String): Boolean {
        if (state != State.OPEN) return false
        return callBool("char", self, screen, LuaValue.valueOf(ch))
    }

    /** Mouse press at absolute ([mx], [my]); text inputs first (topmost = declared last), then Lua `click`. */
    fun mouseClicked(mx: Double, my: Double, button: Int, mods: Mods, doubleClick: Boolean = false): Boolean {
        if (state != State.OPEN) return false
        setMods(mods)
        setMouse(mx, my)
        if (button >= 0) pressed += button
        val hit = inputs.values.lastOrNull { it.hit(mx, my) }
        if (hit != null) {
            setFocus(hit)
            hit.box.mouseClicked(mx, my, button, doubleClick)
            return true
        }
        setFocus(null)
        return callBool("click", self, screen, LuaValue.valueOf(mx - x), LuaValue.valueOf(my - y), LuaValue.valueOf(button), modsTable(mods))
    }

    fun mouseReleased(mx: Double, my: Double, button: Int) {
        if (state != State.OPEN) return
        pressed -= button
        setMouse(mx, my)
        call("release", self, screen, LuaValue.valueOf(mx - x), LuaValue.valueOf(my - y), LuaValue.valueOf(button))
    }

    fun mouseDragged(mx: Double, my: Double, button: Int, dx: Double, dy: Double) {
        if (state != State.OPEN) return
        setMouse(mx, my)
        call("drag", self, screen, LuaValue.valueOf(mx - x), LuaValue.valueOf(my - y), LuaValue.valueOf(button), LuaValue.valueOf(dx), LuaValue.valueOf(dy))
    }

    fun mouseScrolled(mx: Double, my: Double, dx: Double, dy: Double): Boolean {
        if (state != State.OPEN) return false
        setMouse(mx, my)
        return callBool("scroll", self, screen, LuaValue.valueOf(mx - x), LuaValue.valueOf(my - y), LuaValue.valueOf(dy), LuaValue.valueOf(dx))
    }

    // ---- text inputs --------------------------------------------------------------------------------------

    /** One `s:textInput` field: the native box plus the Lua handle table `field`. */
    inner class TextInput internal constructor(val id: String, val box: TextBox) {
        val field = LuaTable()
        internal var frame = 0
        private var lastOptsValue: String? = null
        private var onChange: LuaValue = LuaValue.NIL
        private var numeric: NumericRange? = null
        internal var enabled = true
            private set
        private var rect = IntArray(4)
        private var clip: IntArray? = null

        init {
            field.rawset("id", LuaValue.valueOf(id))
            field.rawset("focus", fn { setFocus(this); LuaValue.NONE })
            field.rawset("blur", fn { if (focused === this) setFocus(null); LuaValue.NONE })
            field.rawset("setValue", fn { a ->
                val v = (if (a.arg1() === field) a.arg(2) else a.arg1()).checkjstring()
                box.setValue(v)
                sync(box.value)
                LuaValue.NONE
            })
        }

        internal fun declare(opts: LuaTable, fresh: Boolean, target: DrawTarget, mouseX: Int, mouseY: Int) {
            val ax = x + floor(opts.rawget("x").checkdouble()).toInt()
            val ay = y + floor(opts.rawget("y").checkdouble()).toInt()
            val w = opts.rawget("w").checkint()
            val h = opts.rawget("h").optint(DEFAULT_INPUT_HEIGHT)
            if (w !in 1..MAX_SIZE || h !in 1..MAX_SIZE) throw LuaError("text input '$id': w/h must be 1..$MAX_SIZE")
            val maxLength = opts.rawget("maxLength").optint(DEFAULT_MAX_LENGTH)
            if (maxLength !in 1..ProtocolLimits.MAX_TEXT_FIELD_LENGTH) throw LuaError("text input '$id': maxLength must be 1..${ProtocolLimits.MAX_TEXT_FIELD_LENGTH}")
            numeric = numericOf(opts.rawget("numeric"))
            enabled = opts.rawget("enabled").let { it.isnil() || it.toboolean() }
            onChange = opts.rawget("onChange").also { if (!it.isnil() && !it.isfunction()) throw LuaError("text input '$id': onChange must be a function") }
            box.configure(maxLength, opts.rawget("placeholder").optjstring("").take(LuaExtension.MAX_TEXT), numeric, enabled)
            // size first: a vanilla EditBox scrolls the text for its current width when the value is set (width 0 on a
            // fresh box would scroll a preset value out of view)
            box.place(ax, ay, w, h)

            val value = opts.rawget("value").let { if (it.isnil()) null else it.checkjstring() }
            if (fresh) {
                box.setValue(value ?: "")
                lastOptsValue = value
                if (opts.rawget("focus").toboolean()) setFocus(this)
            } else if (value != null && value != lastOptsValue) {
                // the script changed the value it passes: a programmatic change (never reported as an edit)
                lastOptsValue = value
                if (box.value != value) box.setValue(value)
            }
            if (!enabled && focused === this) setFocus(null)
            sync(box.value)
            rect = intArrayOf(ax, ay, ax + w, ay + h)
            clip = graphics.currentClip()
            box.draw(target, mouseX, mouseY)
            frame = this@LuaScreenSession.frame
        }

        internal fun hit(mx: Double, my: Double): Boolean {
            if (!enabled || frame != this@LuaScreenSession.frame) return false
            if (mx < rect[0] || my < rect[1] || mx >= rect[2] || my >= rect[3]) return false
            val c = clip ?: return true
            return mx >= c[0] && my >= c[1] && mx < c[2] && my < c[3]
        }

        /** The player edited the box. */
        internal fun edited(value: String) {
            sync(value)
            val change = onChange
            if (field.rawget("valid").toboolean() && change.isfunction()) extension.invoke(change, LuaValue.varargsOf(field, LuaValue.valueOf(value)))
        }

        private fun sync(value: String) {
            field.rawset("value", LuaValue.valueOf(value))
            val range = numeric
            field.rawset("valid", LuaValue.valueOf(range == null || NumericInput.reportable(value, range)))
            field.rawset("focused", LuaValue.valueOf(focused === this))
        }

        internal fun focusChanged() {
            field.rawset("focused", LuaValue.valueOf(focused === this))
        }
    }

    private fun textInput(id: String, opts: LuaTable): LuaValue {
        if (!rendering) throw LuaError("s:textInput can only be used while rendering")
        if (!INPUT_ID.matches(id)) throw LuaError("invalid text input id '$id' ([A-Za-z0-9_.:-]{1,64})")
        val view = view ?: throw LuaError("screen is not shown")
        var input = inputs[id]
        val fresh = input == null
        if (input == null) {
            if (inputs.size >= MAX_INPUTS) throw LuaError("too many text inputs (limit $MAX_INPUTS)")
            var created: TextInput? = null
            val box = view.createTextBox { value -> created?.edited(value) }
            input = TextInput(id, box)
            created = input
            inputs[id] = input
        } else if (input.frame == frame) {
            throw LuaError("text input '$id' declared twice in one frame")
        }
        input.declare(opts, fresh, graphics.currentTarget(), mouseAbsX, mouseAbsY)
        return input.field
    }

    /** Text inputs declared in the last frame, in declaration (= Tab) order. */
    val textInputs: Collection<TextInput> get() = inputs.values

    fun setFocus(input: TextInput?) {
        val old = focused
        if (old === input) return
        focused = input
        old?.box?.focused = false
        input?.box?.focused = true
        old?.focusChanged()
        input?.focusChanged()
    }

    /** Tab / Shift+Tab: next enabled input in declaration order (wrapping). */
    fun cycleFocus(backwards: Boolean) {
        val candidates = inputs.values.filter { it.enabled && it.frame == frame }
        if (candidates.isEmpty()) return
        val index = candidates.indexOf(focused)
        val next = when {
            index < 0 -> if (backwards) candidates.last() else candidates.first()
            backwards -> candidates[(index - 1 + candidates.size) % candidates.size]
            else -> candidates[(index + 1) % candidates.size]
        }
        setFocus(next)
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private fun setMouse(mx: Double, my: Double) {
        screen.rawset("mouseX", mx - x)
        screen.rawset("mouseY", my - y)
        mouseAbsX = floor(mx).toInt()
        mouseAbsY = floor(my).toInt()
    }

    private fun setMods(m: Mods) {
        mods = m
        screen.rawset("shift", LuaValue.valueOf(m.shift))
        screen.rawset("ctrl", LuaValue.valueOf(m.ctrl))
        screen.rawset("alt", LuaValue.valueOf(m.alt))
    }

    private fun modsTable(m: Mods): LuaTable = LuaTable().apply {
        rawset("shift", LuaValue.valueOf(m.shift))
        rawset("ctrl", LuaValue.valueOf(m.ctrl))
        rawset("alt", LuaValue.valueOf(m.alt))
    }

    private fun call(name: String, vararg args: LuaValue) {
        val fn = def.rawget(name)
        if (fn.isfunction()) extension.invoke(fn, LuaValue.varargsOf(args))
    }

    private fun callBool(name: String, vararg args: LuaValue): Boolean {
        val fn = def.rawget(name)
        if (!fn.isfunction()) return false
        return extension.invoke(fn, LuaValue.varargsOf(args))?.arg1()?.toboolean() ?: true
    }

    private fun method(name: String, body: (Varargs) -> Varargs) {
        val self = screen
        screen.rawset(name, object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = body(if (args.arg1() === self) args.subargs(2) else args)
        })
    }

    private fun fn(body: (Varargs) -> Varargs): VarArgFunction = object : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs = body(args)
    }

    private fun numericOf(v: LuaValue): NumericRange? {
        if (v.isnil() || v == LuaValue.FALSE) return null
        if (v == LuaValue.TRUE) return NumericRange(-ProtocolLimits.MAX_NUMERIC_VALUE, ProtocolLimits.MAX_NUMERIC_VALUE, true)
        val t = v.checktable()
        val min = t.rawget("min").optdouble(-ProtocolLimits.MAX_NUMERIC_VALUE)
        val max = t.rawget("max").optdouble(ProtocolLimits.MAX_NUMERIC_VALUE)
        if (!(min <= max)) throw LuaError("numeric: min must be <= max")
        return NumericRange(min, max, t.rawget("integer").let { it.isnil() || it.toboolean() })
    }

    companion object {
        const val DEFAULT_WIDTH = 176
        const val DEFAULT_HEIGHT = 166
        const val MAX_SIZE = 4_096
        const val MAX_INPUTS = 64
        const val DEFAULT_INPUT_HEIGHT = 20
        const val DEFAULT_MAX_LENGTH = 256

        /** Escape presses within [FORCED_ESCAPE_SECONDS] that always close a Lua screen (Lua may swallow fewer). */
        const val FORCED_ESCAPE_PRESSES = 3
        const val FORCED_ESCAPE_SECONDS = 1.5
        val CALLBACKS = listOf("init", "render", "resize", "close", "key", "char", "click", "release", "drag", "scroll")
        val CURSORS = setOf("default", "hand", "text", "crosshair", "resize_ns", "resize_ew", "move", "not_allowed")
        private val INPUT_ID = Regex("[A-Za-z0-9_.:-]{1,64}")

        /** `key.keyboard.left.shift` -> `left.shift`, `key.mouse.left` -> `mouse.left`. */
        fun shortKeyName(name: String): String = when {
            name.startsWith("key.keyboard.") -> name.removePrefix("key.keyboard.")
            name.startsWith("key.") -> name.removePrefix("key.")
            else -> name
        }
    }
}
