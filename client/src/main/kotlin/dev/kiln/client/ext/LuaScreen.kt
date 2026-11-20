package dev.kiln.client.ext

import com.mojang.blaze3d.platform.InputConstants
import dev.kiln.client.lua.DrawTarget
import dev.kiln.client.lua.LuaScreenSession
import dev.kiln.client.lua.ScreenView
import dev.kiln.client.lua.TextBox
import dev.kiln.client.screen.MouseButtons
import dev.kiln.client.screen.NumericInput
import dev.kiln.client.screen.WidgetColors
import dev.kiln.protocol.NumericRange
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.network.chat.Component

/**
 * Minecraft side of a Lua screen ([LuaScreenSession]): forwards layout, frames and input, and provides the native
 * text inputs ([EditBoxTextBox]). Nothing here is a vanilla child widget: the session decides focus and hit tests,
 * text inputs are drawn inside the Lua render call (so they are layered and clipped like Lua drawing).
 *
 * Keys: the focused text input first (editing keys), then Lua `key`; Tab cycles inputs, Escape closes unless Lua
 * handled it; Shift+Escape and a third Escape within 1.5 s always close (see [LuaScreenSession.keyPressed]). The screen closes on disconnect (the runtime replaces it) without running Lua.
 */
class LuaScreen(val session: LuaScreenSession, private val textures: ExtensionTextures) : Screen(Component.empty()), ScreenView {
    /** Set when the screen goes away without Lua being told (disconnect, extension unloaded). */
    var silent: Boolean = false

    /** Mouse event in progress (text inputs need the vanilla event for cursor placement). */
    private var currentEvent: MouseButtonEvent? = null
    private var dragging: EditBoxTextBox? = null

    init {
        session.view = this
    }

    override fun init() = layout()

    override fun repositionElements() = layout()

    private fun layout() {
        session.layout(width, height, minecraft.window.guiScale.toDouble())
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        if (minecraft.level != null) extractTransparentBackground(graphics) else super.extractBackground(graphics, mouseX, mouseY, a)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        session.render(GuiDrawTarget(graphics, font, textures, mouseX, mouseY), mouseX, mouseY)
    }

    // ---- input --------------------------------------------------------------------------------------------

    private fun mods(event: KeyEvent) = LuaScreenSession.Mods(event.hasShiftDown(), event.hasControlDown(), event.hasAltDown())

    private fun mods(event: MouseButtonEvent) = LuaScreenSession.Mods(event.hasShiftDown(), event.hasControlDown(), event.hasAltDown())

    override fun keyPressed(event: KeyEvent): Boolean {
        val name = LuaScreenSession.shortKeyName(InputConstants.getKey(event).name)
        // Escape never goes to a text field: the session decides (including the forced way out, Shift+Escape / 3x)
        val box = session.focused?.box as? EditBoxTextBox
        if (name != "escape" && box != null && box.edit.keyPressed(event)) return true
        return when (session.keyPressed(name, mods(event))) {
            LuaScreenSession.KeyResult.HANDLED -> true
            LuaScreenSession.KeyResult.CLOSE -> {
                onClose()
                true
            }
            LuaScreenSession.KeyResult.PASS -> false
        }
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        val box = session.focused?.box as? EditBoxTextBox
        if (box != null) return box.edit.charTyped(event)
        return session.charTyped(event.codepointAsString())
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        currentEvent = event
        try {
            val handled = session.mouseClicked(event.x(), event.y(), MouseButtons.toProtocol(event.button()), mods(event), doubleClick)
            dragging = session.focused?.box as? EditBoxTextBox
            return handled
        } finally {
            currentEvent = null
        }
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragging?.edit?.mouseReleased(event)
        dragging = null
        session.mouseReleased(event.x(), event.y(), MouseButtons.toProtocol(event.button()))
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val box = dragging
        if (box != null) return box.edit.mouseDragged(event, dx, dy)
        session.mouseDragged(event.x(), event.y(), MouseButtons.toProtocol(event.button()), dx, dy)
        return true
    }

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean =
        session.mouseScrolled(x, y, scrollX, scrollY)

    override fun removed() {
        if (silent) session.dispose() else session.extension.screenRemoved(session)
    }

    override fun isPauseScreen(): Boolean = false

    // ---- ScreenView ---------------------------------------------------------------------------------------

    override fun createTextBox(onEdit: (String) -> Unit): TextBox = EditBoxTextBox(onEdit)

    /** Vanilla [EditBox] as a Lua text input: numeric filtering like server text fields, red text when invalid. */
    inner class EditBoxTextBox(private val onEdit: (String) -> Unit) : TextBox {
        private var numeric: NumericRange? = null
        private var applying = false

        val edit: EditBox = object : EditBox(font, 0, 0, 0, 0, Component.empty()) {
            override fun insertText(input: String) {
                val range = numeric ?: return super.insertText(input)
                val oldValue = value
                val oldCursor = cursorPosition
                applying = true
                try {
                    super.insertText(input)
                    if (!NumericInput.isPartial(value, range)) {
                        value = oldValue
                        setCursorPosition(oldCursor)
                        setHighlightPos(oldCursor)
                    }
                } finally {
                    applying = false
                }
                if (value != oldValue) changed(value)
            }
        }

        init {
            edit.setResponder { if (!applying) changed(it) }
        }

        private fun changed(value: String) {
            updateColor()
            onEdit(value)
        }

        private fun updateColor() {
            val range = numeric
            edit.setTextColor(if (range != null && !NumericInput.reportable(edit.value, range)) WidgetColors.INVALID else EditBox.DEFAULT_TEXT_COLOR)
        }

        override val value: String get() = edit.value

        override fun setValue(value: String) {
            applying = true
            try {
                edit.value = value
            } finally {
                applying = false
            }
            updateColor()
        }

        override var focused: Boolean
            get() = edit.isFocused
            set(v) {
                edit.isFocused = v
            }

        override fun configure(maxLength: Int, placeholder: String, numeric: NumericRange?, enabled: Boolean) {
            this.numeric = numeric
            edit.setMaxLength(maxLength)
            edit.setHint(Component.literal(placeholder))
            edit.setEditable(enabled)
            edit.active = enabled
            updateColor()
        }

        override fun place(x: Int, y: Int, width: Int, height: Int) {
            edit.setRectangle(width, height, x, y)
        }

        override fun draw(target: DrawTarget, mouseX: Int, mouseY: Int) {
            val graphics = (target as? GuiDrawTarget)?.graphics ?: return
            edit.extractRenderState(graphics, mouseX, mouseY, 0f)
        }

        override fun mouseClicked(x: Double, y: Double, button: Int, doubleClick: Boolean) {
            val event = currentEvent ?: MouseButtonEvent(x, y, MouseButtonInfo(1, 0))
            edit.mouseClicked(event, doubleClick)
        }
    }
}
