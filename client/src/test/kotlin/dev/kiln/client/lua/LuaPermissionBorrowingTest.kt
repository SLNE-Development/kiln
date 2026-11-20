package dev.kiln.client.lua

import dev.kiln.client.lua.V2Host.Companion.pkg
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue

/**
 * Confused deputy: no extension can use a permission it does not have, whichever way a permission-gated function or a
 * callback travels between extensions (security review finding 3). A gated function needs the permission from its
 * owner, from the principal of the call and from the extension whose code calls it directly.
 */
class LuaPermissionBorrowingTest {

    /** `lib`: an API extension WITHOUT `channels` that stores whatever it is given and calls it from its own entry points. */
    private val libSource = """
        local fn, arg
        kiln.keys.bind("go", { press = function() fn(arg, { via = "keybind" }) end })
        kiln.hud.layer("hud", { width = 4, height = 4 }, function(g, area) if fn then fn(arg, { via = "hud" }) end end)
        kiln.ui.widget("w", { render = function(self, g, w) fn(arg, { via = "widget" }) end })
        kiln.export({
          register = function(f, a) fn, arg = f, a end,
          poke = function(m) fn(arg, m) end,
          pokeTail = function(m) return fn(arg, m) end,
          pokeProtected = function(m) return pcall(fn, arg, m) end,
          each = function(callback, m) callback(m) end,
          eachTail = function(callback, m) return callback(m) end,
        })
    """.trimIndent()

    /** `app`: has `channels`, imports `lib` and hands it its raw `ch.send`. */
    private val appSource = """
        local lib = kiln.import("lib")
        local ch = kiln.net.channel("c")
        lib.register(ch.send, ch)
        results = {}
        ch:on(function(m)
          local function try(name, f, ...) local ok, err = pcall(f, ...) results[name] = ok and "ok" or err end
          try("poke", lib.poke, { via = "poke" })
          try("pokeTail", lib.pokeTail, { via = "pokeTail" })
          try("pokeProtected", function() local ok, err = lib.pokeProtected({ via = "pokeProtected" }) assert(ok, err) end)
          -- the app's own closure called back by lib: the app's code sends, that is fine
          try("each", lib.each, function(x) ch:send(x) end, { via = "each" })
          try("eachTail", lib.eachTail, function(x) return ch:send(x) end, { via = "eachTail" })
          try("own", function() return ch:send({ via = "own" }) end)
        end)
    """.trimIndent()

    private fun setup(): Triple<V2Host, LuaExtension, LuaExtension> {
        val host = V2Host()
        val lib = host.load(pkg("lib", setOf("keybinds", "hud", "widgets"), files = *arrayOf("main.lua" to libSource)))
        val app = host.load(pkg("app", setOf("channels"), listOf("lib"), "main.lua" to appSource))
        return Triple(host, lib, app)
    }

    private fun sentVia(host: V2Host): List<String> = host.sent.map { it.first + " " + it.second }

    @Test
    fun `a raw API function handed to another extension cannot be used by its code, even inside the owner's call`() {
        val (host, lib, app) = setup()
        app.onChannelMessage("c", "{}")
        val results = app.runtime.globals.get("results")
        for (name in listOf("poke", "pokeTail", "pokeProtected")) {
            val result = results.get(name).tojstring()
            assertTrue(result.contains("permission 'channels' missing: code of extension 'lib'"), "$name: $result")
        }
        // the app's own code (also when lib calls it back, also as a tail call) may use its channel
        assertEquals("ok", results.get("each").tojstring())
        assertEquals("ok", results.get("eachTail").tojstring())
        assertEquals("ok", results.get("own").tojstring())
        assertEquals(listOf("""app:c {"via":"each"}""", """app:c {"via":"eachTail"}""", """app:c {"via":"own"}"""), sentVia(host))
        assertTrue(app.alive && lib.alive, host.failures.toString())
    }

    @Test
    fun `stored functions fail in the other extension's own keybind, HUD layer and widget callbacks`() {
        val (host, lib, _) = setup()
        lib.onKey("go", true)
        assertFalse(lib.alive)
        assertTrue(host.failures.last().contains("permission 'channels' missing"), host.failures.toString())

        val (host2, lib2, _) = setup()
        lib2.renderHud(lib2.hudLayers.single(), CountingTarget(), 0, 0)
        assertFalse(lib2.alive)
        assertTrue(host2.failures.last().contains("permission 'channels' missing"), host2.failures.toString())

        val (host3, lib3, _) = setup()
        val widget = lib3.createWidget("w")!!
        widget.render(CountingTarget(), 0, 0, 10, 10, emptyMap(), false, 0.0, 0.0)
        assertFalse(lib3.alive)
        assertTrue(host3.failures.last().contains("permission 'channels' missing"), host3.failures.toString())

        assertTrue(host.sent.isEmpty() && host2.sent.isEmpty() && host3.sent.isEmpty())
    }

    @Test
    fun `a raw API function used directly as a callback runs with the callback owner's principal`() {
        val host = V2Host()
        val lib = host.load(pkg("lib", setOf("hud", "keybinds"), files = *arrayOf("main.lua" to """
            local stored
            -- lib registers the layer in its own call: the HUD layer now IS the app's send function
            kiln.keys.bind("go", { press = function() kiln.hud.layer("h", { width = 1, height = 1 }, stored) end })
            kiln.export({ asHud = function(f) stored = f end })
        """.trimIndent())))
        host.load(pkg("app", setOf("channels"), listOf("lib"), "main.lua" to """
            local ch = kiln.net.channel("c")
            kiln.import("lib").asHud(ch.send)
        """.trimIndent()))
        lib.onKey("go", true)
        assertTrue(lib.alive, host.failures.toString())
        lib.renderHud(lib.hudLayers.single(), CountingTarget(), 0, 0)
        assertTrue(host.sent.isEmpty())
        assertFalse(lib.alive)
        assertTrue(host.failures.last().contains("permission 'channels' missing: extension 'lib' cannot use it through 'app'"), host.failures.toString())
    }

    @Test
    fun `exported raw API functions cannot be used by an importer without the permission`() {
        val host = V2Host()
        host.load(pkg("chan", setOf("channels", "toasts", "screens"), files = *arrayOf("main.lua" to """
            local ch = kiln.net.channel("c")
            kiln.export({ ch = ch, send = ch.send, channel = kiln.net.channel, toast = kiln.ui.toast, open = kiln.ui.open, net = kiln.net })
        """.trimIndent())))
        val poor = host.load(pkg("poor", setOf("widgets"), listOf("chan"), "main.lua" to """
            local api = kiln.import("chan")
            local function denied(f, ...)
              local ok, err = pcall(f, ...)
              assert(not ok and err:find("permission '", 1, true) and err:find("missing", 1, true), tostring(err))
            end
            denied(api.send, api.ch, { a = 1 })
            denied(function() return api.send(api.ch, { a = 1 }) end) -- tail call
            denied(function() return api.ch:send({ a = 1 }) end)
            denied(api.channel, "other")
            denied(api.net.channel, "other")
            denied(api.toast, "a", "b")
            denied(api.open, "s")
            result = "checked"
        """.trimIndent()))
        assertEquals("checked", poor.runtime.globals.get("result").tojstring())
        assertTrue(host.sent.isEmpty())
    }

    @Test
    fun `gated functions refuse to run outside a runtime call and without the owner's permission`() {
        val host = V2Host()
        val ext = host.load(pkg("x", setOf("toasts", "channels"), files = *arrayOf("main.lua" to "ch = kiln.net.channel('c')")))
        val toast = ext.runtime.globals.get("kiln").get("ui").get("toast")
        val outside = assertThrows<LuaError> { toast.call(LuaValue.valueOf("a"), LuaValue.valueOf("b")) }
        assertTrue(outside.message!!.contains("outside a call"), outside.message)
        val ch = ext.runtime.globals.get("ch")
        assertThrows<LuaError> { ch.get("send").call(ch, org.luaj.vm2.LuaTable()) }
        assertTrue(host.sent.isEmpty())
        // inside a call of the owner it works
        ext.runtime.call(ch.get("send"), LuaValue.varargsOf(ch, org.luaj.vm2.LuaTable()))
        assertEquals(1, host.sent.size)

        // the owner's own permission is checked as well (defense in depth; such functions are never created)
        val plain = host.load(pkg("plain", setOf("widgets"), files = *arrayOf("main.lua" to "")))
        val error = assertThrows<LuaScriptException> {
            plain.runtime.call(object : org.luaj.vm2.lib.ZeroArgFunction() {
                override fun call(): LuaValue { plain.checkCaller("channels"); return LuaValue.NIL }
            })
        }
        assertTrue(error.message!!.contains("extension 'plain' does not have it"), error.message)
    }

    @Test
    fun `host code reading a proxied definition table runs no Lua outside a call`() {
        val host = V2Host()
        val lib = host.load(pkg("lib", files = *arrayOf("main.lua" to """
            hits = 0
            local def = setmetatable({}, { __index = function(t, k) hits = hits + 1 return nil end })
            kiln.export({ def = def })
        """.trimIndent())))
        val app = host.load(pkg("app", setOf("widgets"), listOf("lib"), "main.lua" to """
            kiln.ui.widget("w", kiln.import("lib").def)
        """.trimIndent()))
        val afterLoad = lib.runtime.globals.get("hits").toint()
        assertTrue(afterLoad > 0, "inside the (budgeted) definition call metamethods run as usual")
        val widget = app.createWidget("w")
        widget!!.render(CountingTarget(), 0, 0, 10, 10, emptyMap(), false, 0.0, 0.0)
        assertTrue(widget.mouseClicked(10, 10, emptyMap(), 1.0, 1.0, 0))
        assertEquals(afterLoad, lib.runtime.globals.get("hits").toint(), "no metamethod ran outside a call")
        assertTrue(app.alive && lib.alive, host.failures.toString())
    }

    @Test
    fun `tail calls are plain calls now and still return all results`() {
        val host = V2Host()
        val ext = host.load(pkg("t", files = *arrayOf("main.lua" to """
            local function three() return 1, 2, 3 end
            local function pass(...) return select("#", ...), ... end
            local function viaTail() return three() end
            local a, b, c = viaTail()
            assert(a == 1 and b == 2 and c == 3)
            local n, x, y = pass(viaTail())
            assert(n == 3 and x == 1 and y == 2)
            local function count(i) if i == 0 then return "done" end return count(i - 1) end
            assert(count(100) == "done")
            -- deep "tail recursion" now hits the call depth limit (catchable)
            assert(not pcall(count, 10000))
            result = "ok"
        """.trimIndent())))
        assertEquals("ok", ext.runtime.globals.get("result").tojstring(), host.failures.toString())
    }

    @Test
    fun `a callback run by the API extension cannot use the API extension's exported channel`() {
        val host = V2Host()
        val kit = host.load(pkg("kit", setOf("channels", "keybinds"), files = *arrayOf("main.lua" to """
            local ch = kiln.net.channel("x")
            local callbacks = {}
            kiln.keys.bind("go", { press = function() for _, f in ipairs(callbacks) do f() end end })
            ch:on(function() for _, f in ipairs(callbacks) do f() end end)
            kiln.export({ channel = ch, onEvent = function(f) callbacks[#callbacks + 1] = f end })
        """.trimIndent())))
        val poor = host.load(pkg("poor", setOf("widgets"), listOf("kit"), "main.lua" to """
            local kit = kiln.import("kit")
            kit.onEvent(function()
              local ok, err = pcall(function() kit.channel:send({ stolen = true }) end)
              result = ok and "sent" or err
            end)
        """.trimIndent()))
        // kit (with channels) is the principal in both of its own entry points, but poor's code makes the call
        kit.onKey("go", true)
        assertTrue(poor.runtime.globals.get("result").tojstring().contains("permission 'channels' missing: extension 'poor'"))
        kit.onChannelMessage("x", "{}")
        assertTrue(poor.runtime.globals.get("result").tojstring().contains("permission 'channels' missing"))
        assertTrue(host.sent.isEmpty())
        assertTrue(kit.alive && poor.alive, host.failures.toString())
    }

    /** `kit` (with channels) exports its channel and runs callbacks/reads items importers registered, in its own calls. */
    private val deputySource = """
        local ch = kiln.net.channel("x")
        local callbacks, items = {}, {}
        local function run()
          for _, f in ipairs(callbacks) do pcall(f, { event = "tick" }) end
          for _, item in ipairs(items) do
            pcall(function() return item.label end)            -- __index
            pcall(function() return tostring(item) end)        -- __tostring through a library function
            pcall(function() return "x" .. item end)           -- __concat
            pcall(function() return item == items end)         -- __eq (different tables)
            pcall(function() return item < item end)           -- __lt
            pcall(function() return item() end)                -- __call
            pcall(table.sort, { item, item, item }, function(a, b) return false end)
          end
        end
        kiln.keys.bind("go", { press = run })
        kiln.export({ channel = ch, onEvent = function(f) callbacks[#callbacks + 1] = f end,
                      addItem = function(t) items[#items + 1] = t end })
    """.trimIndent()

    @Test
    fun `exported API functions handed back as callbacks or metamethods cannot make the exporter send`() {
        val host = V2Host()
        val kit = host.load(pkg("kit", setOf("channels", "keybinds"), files = *arrayOf("main.lua" to deputySource)))
        val poor = host.load(pkg("poor", setOf("widgets"), listOf("kit"), "main.lua" to """
            local kit = kiln.import("kit")
            local send = kit.channel.send
            kit.onEvent(send)                                       -- the function value itself as callback
            local mt = { __index = send, __tostring = send, __concat = send, __eq = send, __lt = send, __call = send }
            kit.addItem(setmetatable({ stolen = true }, mt))        -- metamethods dispatched by kit's code
        """.trimIndent()))
        kit.onKey("go", true)
        assertEquals(emptyList<String>(), sentVia(host))
        assertTrue(kit.alive && poor.alive, host.failures.toString())
    }

    @Test
    fun `a raw API function cannot run as a metamethod or through a library function`() {
        val host = V2Host()
        val lib = host.load(pkg("lib", files = *arrayOf("main.lua" to """
            local fn
            kiln.export({
              register = function(f) fn = f end,
              -- tables whose metamethods are the app's own send; the APP's code triggers them
              make = function() return setmetatable({}, { __index = fn, __tostring = fn, __call = fn }) end,
              wrapped = function() return fn end,
            })
        """.trimIndent())))
        val app = host.load(pkg("app", setOf("channels"), listOf("lib"), "main.lua" to """
            local lib = kiln.import("lib")
            local ch = kiln.net.channel("c")
            lib.register(ch.send)
            ch:on(function()
              local t = lib.make()
              pcall(function() return t.label end)
              pcall(function() return tostring(t) end)
              pcall(function() return t() end)
              pcall(table.sort, { 1, 2, 3 }, ch.send)
              pcall(string.gsub, "abc", "b", ch.send)
              -- direct calls (also through pcall) by the owner keep working
              assert(pcall(ch.send, ch, { via = "pcall" }))
              ch:send({ via = "direct" })
              lib.wrapped()(ch, { via = "handed back" }) -- the app's own raw function, returned unwrapped
            end)
        """.trimIndent()))
        app.onChannelMessage("c", "{}")
        assertEquals(listOf("""app:c {"via":"pcall"}""", """app:c {"via":"direct"}""", """app:c {"via":"handed back"}"""), sentVia(host))
        assertTrue(app.alive && lib.alive, host.failures.toString())
    }
}
