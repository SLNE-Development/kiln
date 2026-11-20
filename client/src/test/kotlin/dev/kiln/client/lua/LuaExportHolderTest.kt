package dev.kiln.client.lua

import dev.kiln.client.lua.V2Host.Companion.pkg
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.luaj.vm2.LuaValue

/**
 * Confused deputy through views without holder (security review: `LuaExports.holder == null`): an importer without a
 * permission obtains a wrapper of an exporter's gated function by some path, hands it back to the exporter as a
 * callback, and the exporter calls it in its own call (principal and direct caller = exporter, both have the
 * permission). Only the holder can stop that, so a wrapper must never run a gated function without a known holder.
 */
class LuaExportHolderTest {

    /**
     * `kit` (with `channels`) stores a callback + context of an importer and calls `fn(arg, ...)` on its own keybind
     * (`send(arg, m)` sends `arg` when `arg` is not the channel: the importer even chooses the message).
     */
    private val kitSource = """
        local ch = kiln.net.channel("x")
        local fn, arg
        kiln.keys.bind("go", { press = function() if fn then lastOk, lastErr = pcall(fn, arg, { via = "kit" }) end end })
        local ro = kiln.readonly({ send = ch.send, ch = ch })
        kiln.export({
          register = function(f, a) fn, arg = f, a end,
          registerIn = function(t) fn, arg = t.f, t.a end,
          ask = function(cb) fn, arg = cb() end,
          ro = ro,
          nested = { deep = { ro = ro } },
          getRo = function() return kiln.readonly({ send = ch.send }) end,
          getNested = function() return { inner = kiln.readonly({ send = ch.send }) } end,
          meta = setmetatable({}, { __index = function(t, k) return ro end }),
          each = function(cb) cb(ro) end,
          ch = ch,
          getRaw = function() return ch.send end,
          getRawTable = function() return { send = ch.send } end,
        })
    """.trimIndent()

    /** Ways an importer gets hold of (a wrapper of) `kit`'s `ch.send` (Lua expression yielding the function). */
    private val paths = mapOf(
        "readonly field of the export" to "kit.ro.send",
        "nested field" to "kit.nested.deep.ro.send",
        "return value (readonly)" to "kit.getRo().send",
        "readonly nested in a fresh return value" to "kit.getNested().inner.send",
        "__index metamethod of an exported table" to "kit.meta.anything.send",
        "pairs over a readonly view" to "(function() for k, v in pairs(kit.ro) do if k == 'send' then return v end end end)()",
        "argument of a callback the exporter calls" to "(function() local f kit.each(function(t) f = t.send end) return f end)()",
        "readonly channel table, method" to "kit.ro.ch.send",
        "importer view (holder known)" to "kit.ch.send",
        "raw return value" to "kit.getRaw()",
        "raw function in a fresh return value" to "kit.getRawTable().send",
    )

    /** Ways to hand it back to `kit` (Lua statement using `f` and the context `a`). */
    private val handBacks = mapOf(
        "as argument" to "kit.register(f, a)",
        "in a table field" to "kit.registerIn({ f = f, a = a })",
        "as return value of a callback" to "kit.ask(function() return f, a end)",
    )

    /**
     * Known limit (documented): a raw gated function the exporter itself puts into a plain table it hands out comes
     * back raw when it travels back inside a plain value (table field, callback result); the runtime cannot see it.
     */
    private fun knownLimit(path: String, handBack: String) =
        path == "raw function in a fresh return value" && handBack != "as argument"

    private fun attack(path: String, handBack: String, permissions: Set<String> = setOf("widgets")): V2Host {
        val host = V2Host()
        val kit = host.load(pkg("kit", setOf("channels", "keybinds"), files = *arrayOf("main.lua" to kitSource)))
        host.load(pkg("poor", permissions, listOf("kit"), "main.lua" to """
            local kit = kiln.import("kit")
            local f, a = ${paths.getValue(path)}, { stolen = true }
            assert(type(f) == "function", "path yields no function")
            -- poor's own code may not call it (without the permission) ...
            if not kiln.net then assert(not pcall(f, { direct = true })) end
            -- ... so it hands it back to kit as a callback, with a message of its choice as context
            ${handBacks.getValue(handBack)}
        """.trimIndent()))
        kit.onKey("go", true)
        assertTrue(kit.alive, host.failures.toString())
        return host
    }

    @Test
    fun `no path gives an importer without the permission a function the exporter would run for it`() {
        val leaked = ArrayList<String>()
        for (path in paths.keys) for (handBack in handBacks.keys) {
            if (knownLimit(path, handBack)) continue
            val host = attack(path, handBack)
            if (host.sent.isNotEmpty()) leaked += "$path / $handBack"
        }
        assertEquals(emptyList<String>(), leaked, "kit sent on behalf of poor")
    }

    @Test
    fun `an importer with the permission can still hand the exporter its functions`() {
        // known holder with the permission: as before, the exporter may run the function in its own call
        val denied = ArrayList<String>()
        for (path in paths.keys) for (handBack in handBacks.keys) {
            val host = attack(path, handBack, setOf("channels"))
            if (host.sent.isEmpty()) denied += "$path / $handBack" else assertEquals(listOf("""kit:x {"stolen":true}"""), host.sent.map { it.first + " " + it.second })
        }
        // only a holderless view (kiln.readonly) that reaches the importer inside a plain value and goes back inside a
        // plain value: the runtime never learns who holds it, so it runs gated functions for nobody (deny by default)
        val unknownHolder = listOf("readonly nested in a fresh return value", "argument of a callback the exporter calls")
            .flatMap { path -> listOf("$path / in a table field", "$path / as return value of a callback") }
        assertEquals(unknownHolder, denied)
    }

    @Test
    fun `re-exported wrappers keep every holder on the way`() {
        val host = V2Host()
        val base = host.load(pkg("base", setOf("channels", "keybinds"), files = *arrayOf("main.lua" to kitSource)))
        // mid has the permission and re-exports base's channel (and a holderless view of it)
        host.load(pkg("mid", setOf("channels"), listOf("base"), "main.lua" to """
            local base = kiln.import("base")
            kiln.export({ ch = base.ch, ro = base.ro, send = base.ch.send })
        """.trimIndent()))
        val poor = host.load(pkg("poor", setOf("widgets"), listOf("base", "mid"), "main.lua" to """
            local base, mid = kiln.import("base"), kiln.import("mid")
            function handBack(i, how)
              local f = ({ mid.ch.send, mid.ro.send, mid.send })[i]
              if how == 1 then base.register(f, { stolen = i })
              elseif how == 2 then base.registerIn({ f = f, a = { stolen = i } })
              else base.ask(function() return f, { stolen = i } end) end
            end
        """.trimIndent()))
        for (i in 1..3) for (how in 1..3) {
            poor.runtime.call(poor.runtime.globals.get("handBack"), LuaValue.varargsOf(LuaValue.valueOf(i), LuaValue.valueOf(how)))
            base.onKey("go", true)
        }
        assertEquals(emptyList<Pair<String, String>>(), host.sent)
        assertTrue(base.alive && poor.alive, host.failures.toString())
    }

    @Test
    fun `holderless views run gated functions for nobody, not even for the owner`() {
        val host = V2Host()
        val ext = host.load(pkg("x", setOf("channels"), files = *arrayOf("main.lua" to """
            local ch = kiln.net.channel("c")
            local ro = kiln.readonly({ send = ch.send, helper = function(m) return ch:send(m) end, plain = function() return 1 end })
            local ok, err = pcall(ro.send, ch, { via = "readonly" })
            assert(not ok and err:find("unknown holder", 1, true), tostring(err))
            assert(not pcall(ro.helper, { via = "helper" }))
            assert(ro.plain() == 1) -- functions without permission work as before
            ch:send({ via = "own" })
            result = "ok"
        """.trimIndent())))
        assertEquals("ok", ext.runtime.globals.get("result").tojstring(), host.failures.toString())
        assertEquals(listOf("""x:c {"via":"own"}"""), host.sent.map { it.first + " " + it.second })
    }

    /**
     * Invariant "a holder that is on a view is never lost", over every way a view travels A -> B -> C -> A: B (holder
     * of a view of A's `ch.send`) passes it to C (with the permission), C hands it back to A, A runs it in its own
     * call. B must stay a holder on every way, so A never sends for B. Control: with the permission on B, it sends.
     */
    @Test
    fun `a holder on a view is never lost on its way A to B to C to A`() {
        val obtain = listOf("a.ch.send", "a.ro.send", "a.getRaw()")
        val bToC = listOf("b.get()", "b.field", "(function() local g b.each(function(x) g = x end) return g end)()",
            "(function() local t = {} b.fill(t) return t.f end)()", "b.nested.f")
        val cToA = listOf("a.register(g, m)", "a.registerIn({ f = g, a = m })", "a.ask(function() return g, m end)")
        for (bHasPermission in listOf(false, true)) {
            val sent = ArrayList<String>()
            for (o in obtain.indices) for (bc in bToC.indices) for (ca in cToA.indices) {
                val host = V2Host()
                val a = host.load(pkg("a", setOf("channels", "keybinds"), files = *arrayOf("main.lua" to kitSource)))
                host.load(pkg("b", if (bHasPermission) setOf("channels") else setOf("widgets"), listOf("a"), "main.lua" to """
                    local a = kiln.import("a")
                    local f = ${obtain[o]}
                    kiln.export({ field = f, nested = { f = f }, get = function() return f end,
                                  each = function(cb) cb(f) end, fill = function(t) t.f = f end })
                """.trimIndent()))
                host.load(pkg("c", setOf("channels"), listOf("a", "b"), "main.lua" to """
                    local a, b = kiln.import("a"), kiln.import("b")
                    local g, m = ${bToC[bc]}, { stolen = "$o/$bc/$ca" }
                    assert(type(g) == "function")
                    ${cToA[ca]}
                """.trimIndent()))
                a.onKey("go", true)
                assertTrue(host.failures.isEmpty(), host.failures.toString())
                if (host.sent.isNotEmpty()) sent += "${obtain[o]} | ${bToC[bc]} | ${cToA[ca]}"
                else if (!bHasPermission) {
                    val error = a.runtime.globals.get("lastErr").tojstring()
                    assertTrue(error.contains("extension 'b'"), "${obtain[o]} | ${bToC[bc]} | ${cToA[ca]}: $error")
                }
            }
            val all = obtain.size * bToC.size * cToA.size
            if (bHasPermission) assertEquals(all, sent.size, "control: b with the permission") else assertEquals(emptyList<String>(), sent, "a sent for b")
        }
    }

    @Test
    fun `table proxies given back to their exporter become the real table, its gated functions stay checked in the call`() {
        val host = V2Host()
        val a = host.load(pkg("a", setOf("channels"), files = *arrayOf("main.lua" to """
            local ch = kiln.net.channel("x")
            local box = {}
            kiln.export({ ch = ch, box = box,
                          put = function(t) box.v = t.v end,         -- stores a view as it is (inside a plain table)
                          isCh = function(o) return o == ch end,
                          use = function(o) return o.send(o, { via = "use" }) end })
        """.trimIndent())))
        host.load(pkg("b", setOf("widgets"), listOf("a"), "main.lua" to """
            local a = kiln.import("a")
            a.put({ v = a.ch })                                         -- a now holds b's proxy of its own channel
            assert(a.isCh(a.ch))
            assert(not pcall(a.use, a.ch))                              -- b's holder is on the stack: denied
        """.trimIndent()))
        val c = host.load(pkg("c", setOf("channels"), listOf("a"), "main.lua" to """
            local a = kiln.import("a")
            local nested = a.box.v                                      -- c's view of b's view of a's channel
            assert(a.isCh(nested), "nested proxies of the exporter unwrap to the real table")
            result = "ok"
        """.trimIndent()))
        assertEquals("ok", c.runtime.globals.get("result").tojstring(), host.failures.toString())
        assertTrue(host.sent.isEmpty())
    }
}
