package dev.kiln.client.lua

import dev.kiln.client.lua.LuaScreenSession.KeyResult
import dev.kiln.client.lua.LuaScreenSession.Mods
import dev.kiln.client.lua.V2Host.Companion.item
import dev.kiln.client.lua.V2Host.Companion.pkg
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Lua screens: lifecycle, input dispatch, native text inputs, render performance. */
class LuaScreenTest {
    private val none = Mods(false, false, false)

    private fun log(ext: LuaExtension): List<String> {
        val t = ext.runtime.globals.get("log")
        return (1..t.length()).map { t.get(it).tojstring() }
    }

    private val screens = """
        log = {}
        local function add(s) log[#log + 1] = s end
        modal = false
        kiln.ui.screen("main", {
          width = 200, height = 100,
          init = function(self, s, args) self.title = args.title add("init " .. args.title .. " " .. s.width .. "x" .. s.height .. "@" .. s.x .. "," .. s.y) end,
          resize = function(self, s) add("resize " .. s.x .. "," .. s.y) end,
          render = function(self, g, s)
            g:rect(0, 0, s.width, s.height, 0xFF202020)
            g:text(self.title, 4, 4)
            if s.mouseX >= 0 and s.mouseX < 50 then s:cursor("hand") end
          end,
          key = function(self, s, key, mods)
            add("key " .. key .. (mods.shift and "+shift" or ""))
            if key == "escape" and modal then modal = false return true end
            return false
          end,
          char = function(self, s, ch) add("char " .. ch) return true end,
          click = function(self, s, mx, my, button, mods) add("click " .. mx .. "," .. my .. " " .. button) return true end,
          release = function(self, s, mx, my, button) add("release " .. button) end,
          drag = function(self, s, mx, my, button, dx, dy) add("drag " .. dx) end,
          scroll = function(self, s, mx, my, dy) add("scroll " .. dy) return true end,
          close = function(self, s, reason) add("close " .. reason) end,
        })
        kiln.ui.screen("other", { fullscreen = true, init = function(self, s) add("other " .. s.width .. "x" .. s.height) end,
          close = function(self, s, reason) add("other close " .. reason) end })
        local ch = kiln.net.channel("open")
        ch:on(function(m)
          if m.what == "close" then add("ui.close " .. tostring(kiln.ui.close())) else add("open " .. tostring(kiln.ui.open(m.what, { title = m.title }))) end
        end)
    """.trimIndent()

    @Test
    fun `screen lifecycle, input dispatch and close reasons`() {
        val host = V2Host()
        val ext = host.load(pkg("shop", setOf("screens", "channels"), files = *arrayOf("main.lua" to screens)))
        ext.onChannelMessage("open", """{"what":"main","title":"Shop"}""")
        val session = host.shown!!
        assertSame(session, ext.screenSession)
        assertEquals(listOf("init Shop 200x100@100,100", "open true"), log(ext))

        val target = CountingTarget()
        session.render(target, 120, 110)
        assertEquals(1, target.texts)
        assertEquals(listOf("hand"), target.cursors)
        session.layout(600, 400, 3.0)
        assertTrue(session.mouseClicked(150.0, 130.0, 0, none))
        session.mouseDragged(151.0, 130.0, 0, 1.0, 0.0)
        session.mouseReleased(151.0, 130.0, 0)
        assertTrue(session.mouseScrolled(150.0, 130.0, 0.0, -1.0))
        assertTrue(session.charTyped("x"))
        assertEquals(KeyResult.PASS, session.keyPressed("a", Mods(true, false, false)))
        assertEquals(KeyResult.HANDLED, session.keyPressed("tab", none)) // no inputs: Tab is harmless

        // Escape: Lua may handle it (modal), otherwise the screen closes
        ext.runtime.globals.set("modal", org.luaj.vm2.LuaValue.TRUE)
        assertEquals(KeyResult.HANDLED, host.escape())
        assertSame(session, host.shown)
        assertEquals(KeyResult.CLOSE, host.escape())
        assertNull(host.shown)
        assertNull(ext.screenSession)
        assertEquals(
            listOf(
                "resize 200,150", "click -50,-20 0", "drag 1", "release 0", "scroll -1", "char x", "key a+shift", "key tab",
                "key escape", "key escape", "close escape",
            ),
            log(ext).drop(2),
        )
        // nothing reaches Lua after close
        session.render(target, 0, 0)
        assertFalse(session.mouseClicked(0.0, 0.0, 0, none))
        assertEquals(1, target.texts)

        // replaced by another screen, closed by Lua
        ext.onChannelMessage("open", """{"what":"main","title":"A"}""")
        ext.onChannelMessage("open", """{"what":"other"}""")
        ext.onChannelMessage("open", """{"what":"close"}""")
        assertEquals(listOf("init A 200x100@100,100", "open true", "close replaced", "other 400x300", "open true", "other close closed", "ui.close true"), log(ext).drop(13))
        assertTrue(ext.alive, host.failures.toString())
    }

    @Test
    fun `screens close without Lua on unload and cannot be reopened from close`() {
        val host = V2Host()
        val ext = host.load(pkg("shop", setOf("screens", "channels"), files = *arrayOf("main.lua" to screens)))
        ext.onChannelMessage("open", """{"what":"main","title":"Shop"}""")
        val session = host.shown!!
        ext.stop() // disconnect / unload
        assertEquals(LuaScreenSession.State.CLOSED, session.state)
        session.removed()
        assertFalse(log(ext).any { it.startsWith("close") })

        // trapping the player: reopening from the close callback is an error
        val trap = host.load(pkg("trap", setOf("screens", "channels"), files = *arrayOf("main.lua" to """
            kiln.ui.screen("t", { close = function() kiln.ui.open("t") end })
            kiln.net.channel("open"):on(function() kiln.ui.open("t") end)
            assert(not pcall(kiln.ui.open, "t")) -- not while loading
        """.trimIndent())))
        trap.onChannelMessage("open", "{}")
        assertEquals(KeyResult.CLOSE, host.escape())
        assertNull(host.shown)
        assertFalse(trap.alive)
        assertTrue(host.failures.last().contains("close callback"), host.failures.toString())

        // without the permission there is no screen API at all
        val plain = host.load(pkg("plain", setOf("widgets"), files = *arrayOf("main.lua" to "assert(kiln.ui.screen == nil and kiln.ui.open == nil)")))
        assertTrue(plain.alive)
    }

    @Test
    fun `a script error in a screen callback unloads the extension and closes the screen`() {
        val host = V2Host()
        val ext = host.load(pkg("x", setOf("screens", "channels"), files = *arrayOf("main.lua" to """
            kiln.ui.screen("s", { render = function(self, g, s) error("boom") end })
            kiln.net.channel("open"):on(function() kiln.ui.open("s") end)
        """.trimIndent())))
        ext.onChannelMessage("open", "{}")
        host.shown!!.render(CountingTarget(), 0, 0)
        assertFalse(ext.alive)
        assertNull(host.shown)
        assertTrue(host.failures.single().contains("boom"))
    }

    private val inputs = """
        state = { q = "", amount = "1" }
        changes = 0
        clipped = false
        kiln.ui.screen("form", {
          fullscreen = true,
          render = function(self, g, s)
            s:textInput("q", { x = 10, y = 10, w = 100, h = 20, value = state.q, placeholder = "Suche…", maxLength = 8,
              onChange = function(f, v) state.q = v changes = changes + 1 end })
            if not hideAmount then
              local f = s:textInput("amount", { x = 10, y = 40, w = 50, value = state.amount, numeric = { min = 1, max = 64 },
                onChange = function(f, v) state.amount = v end })
              amountValid = f.valid
            end
            g:clip(0, 100, 50, 20, function()
              s:textInput("clipped", { x = 0, y = 100, w = 200, h = 20 })
            end)
          end,
          click = function() clicks = (clicks or 0) + 1 return true end,
        })
        kiln.net.channel("open"):on(function(m) if m.q then state.q = m.q else kiln.ui.open("form") end end)
    """.trimIndent()

    @Test
    fun `text inputs keep player edits, apply script changes and report valid edits`() {
        val host = V2Host()
        val ext = host.load(pkg("form", setOf("screens", "channels"), files = *arrayOf("main.lua" to inputs)))
        val g = ext.runtime.globals
        ext.onChannelMessage("open", "{}")
        val session = host.shown!!
        val target = CountingTarget()
        session.render(target, 0, 0)
        val (q, amount, clipped) = host.view.boxes
        assertEquals("", q.value)
        assertEquals("1", amount.value)
        assertEquals(8, q.maxLength)
        assertEquals("Suche…", q.placeholder)
        assertEquals(64.0, amount.numeric!!.max)
        assertEquals(listOf(10, 10, 100, 20), q.rect.toList())
        assertEquals(1, q.draws)

        // player types: field value + onChange, the script stores it and passes it back unchanged -> no reset
        q.type("ab")
        assertEquals("ab", g.get("state").get("q").tojstring())
        assertEquals(1, g.get("changes").toint())
        session.render(target, 0, 0)
        assertEquals("ab", q.value)
        q.type("cdefghij") // maxLength 8
        assertEquals("abcdefgh", q.value)

        // the script changes the value it passes: applied without onChange
        ext.onChannelMessage("open", """{"q":"zz"}""")
        session.render(target, 0, 0)
        assertEquals("zz", q.value)
        assertEquals(2, g.get("changes").toint())

        // numeric: invalid content is shown but not reported
        amount.type("00") // "100" > max
        session.render(target, 0, 0)
        assertFalse(g.get("amountValid").toboolean())
        assertEquals("1", g.get("state").get("amount").tojstring())

        // focus: click into a field focuses it (no Lua click), outside blurs and goes to Lua; Tab cycles
        assertTrue(session.mouseClicked(20.0, 15.0, 0, none))
        assertSame(q, session.focused?.box)
        assertTrue(q.focused)
        assertTrue(g.get("clicks").isnil())
        assertEquals(KeyResult.HANDLED, session.keyPressed("tab", none))
        assertSame(amount, session.focused?.box)
        assertFalse(q.focused)
        assertEquals(KeyResult.HANDLED, session.keyPressed("tab", Mods(true, false, false)))
        assertSame(q, session.focused?.box)
        session.mouseClicked(300.0, 200.0, 0, none)
        assertNull(session.focused)
        assertEquals(1, g.get("clicks").toint())

        // clipped part of a field is not clickable
        session.mouseClicked(100.0, 105.0, 0, none)
        assertNull(session.focused)
        session.mouseClicked(20.0, 105.0, 0, none)
        assertSame(clipped, session.focused?.box)

        // a field not declared in a frame disappears and loses focus
        session.mouseClicked(20.0, 45.0, 0, none)
        assertSame(amount, session.focused?.box)
        g.set("hideAmount", org.luaj.vm2.LuaValue.TRUE)
        session.render(target, 0, 0)
        assertNull(session.focused)
        assertEquals(listOf("q", "clipped"), session.textInputs.map { it.id })
        assertTrue(ext.alive, host.failures.toString())
    }

    @Test
    fun `text inputs only while rendering, ids unique per frame`() {
        val host = V2Host()
        val ext = host.load(pkg("x", setOf("screens", "channels"), files = *arrayOf("main.lua" to """
            kiln.ui.screen("s", {
              render = function(self, g, s)
                s:textInput("a", { x = 0, y = 0, w = 10 })
                if twice then s:textInput("a", { x = 0, y = 0, w = 10 }) end
              end,
              click = function(self, s) return pcall(s.textInput, s, "late", { x = 0, y = 0, w = 10 }) == false end,
            })
            kiln.net.channel("open"):on(function() kiln.ui.open("s") end)
        """.trimIndent())))
        ext.onChannelMessage("open", "{}")
        val session = host.shown!!
        session.render(CountingTarget(), 0, 0)
        assertTrue(session.mouseClicked(150.0, 150.0, 0, none))
        ext.runtime.globals.set("twice", org.luaj.vm2.LuaValue.TRUE)
        session.render(CountingTarget(), 0, 0)
        assertFalse(ext.alive)
        assertTrue(host.failures.single().contains("declared twice"), host.failures.toString())
    }

    /** A UI kit as an API extension and a 50-tile shop screen using it (scroll offset, clip, hover, items, text). */
    private val uiKit = """
        local K = {}
        function K.hovered(s, x, y, w, h) return s.mouseX >= x and s.mouseY >= y and s.mouseX < x + w and s.mouseY < y + h end
        function K.panel(g, x, y, w, h, hover)
          g:rect(x, y, w, h, hover and 0xFF3A3F45 or 0xFF2A2F35)
          g:outline(x, y, w, h, 0xFF50565E)
        end
        function K.button(g, s, x, y, w, h, label)
          local hover = K.hovered(s, x, y, w, h)
          g:sprite(hover and "widget/button_highlighted" or "widget/button", x, y, w, h)
          g:text(label, x + w / 2, y + (h - 8) / 2, { align = "center" })
          if hover then s:cursor("hand") end
          return hover
        end
        function K.scrollbar(g, x, y, h, offset, content)
          local thumb = math.max(16, h * h / content)
          g:sprite("widget/scroller_background", x, y, 6, h)
          g:sprite("widget/scroller", x, y + (h - thumb) * offset / math.max(1, content - h), 6, thumb)
        end
        function K.price(n)
          if n >= 1000 then return string.format("%.1fk", n / 1000) end
          return tostring(n)
        end
        kiln.export(K)
    """.trimIndent()

    private val shop = """
        local ui = kiln.import("ui_kit")
        offers = {}
        for i = 1, 50 do
          offers[i] = { name = "Angebot " .. i, price = i * 137, stock = i % 7, item = kiln.items.decode(ITEM) }
        end
        kiln.ui.screen("shop", {
          fullscreen = true,
          init = function(self, s) self.scroll = 0 end,
          render = function(self, g, s)
            g:gradient(0, 0, s.width, s.height, 0xC0101010, 0xE0101010)
            g:text("Marktplatz", s.width / 2, 8, { align = "center", scale = 1.5 })
            local cols, tile, gap = 5, 70, 6
            local x0, y0 = 20, 30
            local viewH = s.height - 60
            g:clip(x0, y0, cols * (tile + gap), viewH, function()
              for i, o in ipairs(offers) do
                local c, r = (i - 1) % cols, math.floor((i - 1) / cols)
                local x, y = x0 + c * (tile + gap), y0 + r * (tile + gap) - self.scroll
                if y + tile >= y0 and y < y0 + viewH then
                  local hover = ui.hovered(s, x, y, tile, tile)
                  ui.panel(g, x, y, tile, tile, hover)
                  g:item(o.item, x + 27, y + 6, { count = o.stock })
                  g:text(o.name, x + tile / 2, y + 28, { align = "center", color = 0xFFE0E0E0 })
                  g:text(ui.price(o.price) .. " ⛁", x + tile / 2, y + 40, { align = "center", color = 0xFFFFD700 })
                  ui.button(g, s, x + 5, y + 52, tile - 10, 14, "Kaufen")
                  if hover then g:itemTooltip(o.item, s.mouseX, s.mouseY, { "Preis: " .. o.price }) end
                end
              end
            end)
            ui.scrollbar(g, x0 + cols * (tile + gap), y0, viewH, self.scroll, 10 * (tile + gap))
            s:textInput("search", { x = 20, y = s.height - 26, w = 150, placeholder = "Suchen" })
          end,
          scroll = function(self, s, mx, my, dy) self.scroll = math.max(0, math.min(500, self.scroll - dy * 20)) return true end,
        })
        kiln.net.channel("open"):on(function() kiln.ui.open("shop") end)
    """.trimIndent()

    @Test
    fun `benchmark - a 50 tile shop renders well within the frame budget`() {
        val host = V2Host()
        host.load(pkg("ui_kit", files = *arrayOf("main.lua" to uiKit)))
        val ext = host.load(pkg("shop", setOf("screens", "channels"), listOf("ui_kit"), "main.lua" to shop.replace("ITEM", "\"${item("minecraft:emerald")}\"")))
        ext.onChannelMessage("open", "{}")
        val session = host.shown!!
        session.layout(854, 820, 2.0) // tall window: all 50 tiles visible at once (worst case)
        val target = CountingTarget()
        repeat(200) { session.render(target, 100 + it % 300, 60 + it % 200) } // warm-up (JIT)
        val frames = 1_000
        var maxInstructions = 0L
        var sum = 0L
        val start = System.nanoTime()
        for (i in 0 until frames) {
            if (i % 50 == 0) session.mouseScrolled(100.0, 100.0, 0.0, if (i % 100 == 0) -1.0 else 1.0)
            session.render(target, 100 + i % 300, 60 + i % 200)
            val used = ext.runtime.lastCallInstructions
            maxInstructions = maxOf(maxInstructions, used)
            sum += used
        }
        val millis = (System.nanoTime() - start) / 1e6 / frames
        val budget = ext.runtime.limits.instructionsPerFrame
        println("[benchmark] shop 50 tiles: avg ${sum / frames} / max $maxInstructions instructions per frame " +
            "(budget $budget, ${100 * maxInstructions / budget} %), ${"%.3f".format(millis)} ms per frame on this machine (Lua side only)")
        assertTrue(ext.alive, host.failures.toString())
        assertTrue(maxInstructions < budget / 2, "max $maxInstructions instructions per frame")
        assertTrue(millis < 4.0, "$millis ms per frame")
        assertTrue(target.items >= 50 * 1000)
    }

    /** A screen that swallows every key (an attempt to keep the player inside) and reopens whenever it can. */
    private val trapScreen = """
        log = {}
        kiln.ui.screen("trap", {
          key = function(self, s, key) log[#log + 1] = "key " .. key return true end,
          close = function(self, s, reason) log[#log + 1] = "close " .. reason end,
        })
        kiln.net.channel("open"):on(function() log[#log + 1] = "open " .. tostring(kiln.ui.open("trap")) end)
    """.trimIndent()

    @Test
    fun `Shift+Escape always closes, Lua is not asked`() {
        val host = V2Host()
        val ext = host.load(pkg("trap", setOf("screens", "channels"), files = *arrayOf("main.lua" to trapScreen)))
        ext.onChannelMessage("open", "{}")
        assertEquals(KeyResult.HANDLED, host.escape())
        assertEquals(KeyResult.CLOSE, host.escape(shift = true))
        assertNull(host.shown)
        assertNull(ext.screenSession)
        assertEquals(listOf("open true", "key escape", "close escape"), log(ext))
        assertTrue(ext.alive, host.failures.toString())
    }

    @Test
    fun `the third Escape within 1,5 s closes even if Lua swallows it`() {
        val host = V2Host()
        val ext = host.load(pkg("trap", setOf("screens", "channels"), files = *arrayOf("main.lua" to trapScreen)))
        ext.onChannelMessage("open", "{}")
        // slow presses: Lua keeps handling Escape (e.g. closing one modal after another)
        repeat(4) {
            assertEquals(KeyResult.HANDLED, host.escape())
            host.clockSeconds += 0.8
        }
        assertSame(ext.screenSession, host.shown)
        host.clockSeconds += 1.0
        // three quick presses (the oldest 1.4 s before the last) force the way out
        assertEquals(KeyResult.HANDLED, host.escape())
        host.clockSeconds += 0.7
        assertEquals(KeyResult.HANDLED, host.escape())
        host.clockSeconds += 0.7
        assertEquals(KeyResult.CLOSE, host.escape())
        assertNull(host.shown)
        assertEquals(listOf("open true") + List(6) { "key escape" } + "close escape", log(ext))
    }

    @Test
    fun `after a forced close the extension cannot reopen its screen for a while`() {
        val host = V2Host()
        val ext = host.load(pkg("trap", setOf("screens", "channels"), files = *arrayOf("main.lua" to trapScreen)))
        ext.onChannelMessage("open", "{}")
        // a normal close (Lua does not swallow) does not block
        ext.runtime.call(ext.runtime.globals.get("kiln").get("ui").get("close"))
        ext.onChannelMessage("open", "{}")
        assertTrue(host.shown != null)
        assertEquals(KeyResult.CLOSE, host.escape(shift = true))
        ext.onChannelMessage("open", "{}")
        assertNull(host.shown, "reopening right after the forced close is refused")
        host.clockSeconds += LuaExtension.FORCED_OUT_SECONDS - 0.1
        ext.onChannelMessage("open", "{}")
        assertNull(host.shown)
        host.clockSeconds += 0.2
        ext.onChannelMessage("open", "{}")
        assertTrue(host.shown != null)
        assertEquals(listOf("open true", "close closed", "open true", "close escape", "open false", "open false", "open true"), log(ext))
        assertTrue(ext.alive, host.failures.toString())
    }
}
