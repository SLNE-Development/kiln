package dev.kiln.client.lua

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JseMathLib
import java.io.ByteArrayInputStream

/**
 * Builds the restricted Lua environments (LuaJ 3.0.1, Lua 5.2) of extensions.
 *
 * Every extension gets its **own** [Globals] with only:
 * `assert error getmetatable ipairs next pairs pcall print select setmetatable tonumber tostring type _G _VERSION`,
 * `string` (without `dump`), `table`, `math`. Everything else LuaJ offers is never loaded or removed again:
 * `io os debug luajava package require load loadstring dofile loadfile collectgarbage coroutine rawget rawset
 * rawequal rawlen xpcall` and `string.dump`.
 *
 * Escape routes that are closed:
 * - **luajava / Java access**: `LuajavaLib`/`JsePlatform` are never loaded, so no Java class is reachable.
 * - **debug**: not loaded; the instruction hook ([SandboxHook]) sits in `Globals.debuglib` without a Lua binding,
 *   so `debug.sethook`, `debug.getregistry`, `debug.setupvalue`, `debug.getmetatable` do not exist.
 * - **Bytecode**: no undumper is installed (`LoadState.install` is not called) and `load*` are removed; sources are
 *   compiled with the text-only compiler, and chunks starting with the bytecode signature (ESC) are rejected.
 * - **String metatable**: LuaJ keeps it in the JVM-wide static `LuaString.s_metatable`, shared by all environments.
 *   It is replaced once by a read-only table with `__metatable = "string"`, so `getmetatable("")` returns the
 *   string `"string"` (no `__index` table to modify) and `setmetatable` on it fails. Its `__index` is a read-only
 *   copy of the string library, so `("x"):upper()` cannot be redirected by one extension for another; each
 *   extension's global `string` is its own mutable copy.
 * - **Resource exhaustion**: instruction budget and call depth ([SandboxHook]), size checks of string producers
 *   (`..`, `rep`, `format`, `gsub`, `table.concat`), bounded `table.unpack`, memory estimate ([LuaMemory]).
 *
 * Not closed (documented limitation): LuaJ's pattern matcher runs in Java and is not counted by the instruction hook;
 * a pathological pattern on a long subject can still take long. Subjects are limited to [LuaLimits.maxStringBytes].
 */
object LuaSandbox {
    /** First byte of a precompiled Lua chunk (`\x1bLua`). */
    private const val BYTECODE_SIGNATURE: Byte = 0x1B

    private val REMOVED_GLOBALS = listOf(
        "collectgarbage", "dofile", "load", "loadfile", "loadstring", "rawequal", "rawget", "rawlen", "rawset",
        "xpcall", "require", "module", "package", "io", "os", "debug", "luajava", "coroutine", "print", "unpack",
    )

    /** The string library functions (without `dump`), created once. */
    private val stringFunctions: Map<String, LuaValue> by lazy { createStringFunctions() }

    /** Installs the shared, protected string metatable. Idempotent; also re-asserts it if something replaced it. */
    @Synchronized
    fun protectStringMetatable(limits: LuaLimits = LuaLimits()) {
        val current = LuaString.s_metatable
        if (current is ProtectedMetatable) return
        val index = ReadOnlyTable()
        for ((name, fn) in stringFunctions(limits)) index.init(name, fn)
        val mt = ProtectedMetatable()
        mt.init("__index", index)
        mt.init("__metatable", LuaValue.valueOf("string"))
        LuaString.s_metatable = mt
    }

    /**
     * Creates a sandboxed environment. [print] receives the arguments of Lua's `print` (already converted to one
     * string); the environment is not usable until [hook] is installed, which this function does.
     */
    fun createGlobals(hook: SandboxHook, limits: LuaLimits, print: (String) -> Unit): Globals {
        protectStringMetatable(limits)
        val globals = Globals()
        withPackageStub(globals)
        globals.load(BaseLib())
        globals.load(TableLib())
        globals.load(JseMathLib())
        LuaC.install(globals) // compiler + loader; no undumper -> no bytecode
        globals.undumper = null
        for (name in REMOVED_GLOBALS) globals.set(name, LuaValue.NIL)
        globals.finder = null

        val string = LuaTable()
        for ((name, fn) in stringFunctions(limits)) string.set(name, fn)
        globals.set("string", string)

        val table = globals.get("table")
        table.set("concat", BoundedConcat(limits))
        table.set("unpack", BoundedUnpack(limits))
        globals.set("print", PrintFunction(print))
        globals.debuglib = hook
        return globals
    }

    /**
     * Compiles [source] (UTF-8 Lua text) as a chunk named [chunkName] with environment [globals]. Precompiled
     * bytecode is rejected. Throws [LuaError] on syntax errors.
     */
    fun compile(globals: Globals, source: ByteArray, chunkName: String): LuaValue {
        if (source.isNotEmpty() && source[0] == BYTECODE_SIGNATURE) throw LuaError("$chunkName: precompiled chunks are not allowed")
        val prototype = globals.compilePrototype(ByteArrayInputStream(source), "=$chunkName")
        LuaExecution.registerChunk(prototype, globals)
        return globals.loader.load(prototype, chunkName, globals)
    }

    /** LuaJ's libraries register themselves in `package.loaded`; give them a throwaway table (removed later). */
    private fun withPackageStub(globals: Globals) {
        val pkg = LuaTable()
        pkg.set("loaded", LuaTable())
        globals.set("package", pkg)
    }

    private fun stringFunctions(limits: LuaLimits): Map<String, LuaValue> {
        val base = stringFunctions
        return base + mapOf(
            "rep" to BoundedRep(limits, base.getValue("rep")),
            "format" to BoundedResult(limits, base.getValue("format")),
            "gsub" to BoundedGsub(limits, base.getValue("gsub")),
        )
    }

    private fun createStringFunctions(): Map<String, LuaValue> {
        // StringLib only fills LuaString.s_metatable if it is still null; protectStringMetatable overwrites it anyway
        val scratch = Globals()
        withPackageStub(scratch)
        scratch.load(StringLib())
        val lib = scratch.get("string").checktable()
        val result = LinkedHashMap<String, LuaValue>()
        var key = LuaValue.NIL
        while (true) {
            val next = lib.next(key)
            key = next.arg1()
            if (key.isnil()) break
            val name = key.tojstring()
            if (name != "dump") result[name] = next.arg(2)
        }
        return result
    }

    /** A table that cannot be written by Lua or LuaJ library code; filled only through [init]. */
    private open class ReadOnlyTable : LuaTable() {
        fun init(key: String, value: LuaValue) {
            super.rawset(LuaValue.valueOf(key), value)
        }

        override fun rawset(key: LuaValue, value: LuaValue) = throw LuaError("attempt to modify a read-only table")
        override fun rawset(key: Int, value: LuaValue) = throw LuaError("attempt to modify a read-only table")
        override fun setmetatable(metatable: LuaValue?): LuaValue = throw LuaError("attempt to modify a read-only table")
    }

    private class ProtectedMetatable : ReadOnlyTable()

    private fun checkLength(limits: LuaLimits, length: Long) {
        if (length > limits.maxStringBytes) throw LuaError("string too long (limit ${limits.maxStringBytes} bytes)")
    }

    private class BoundedRep(private val limits: LuaLimits, private val delegate: LuaValue) : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val s = args.checkstring(1)
            val n = args.checkint(2)
            if (n <= 0 || s.m_length == 0) return LuaValue.EMPTYSTRING
            checkLength(limits, s.m_length.toLong() * n)
            return delegate.invoke(args)
        }
    }

    private class BoundedGsub(private val limits: LuaLimits, private val delegate: LuaValue) : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val s = args.checkstring(1)
            checkLength(limits, s.m_length.toLong())
            val repl = args.arg(3)
            if (repl is LuaString || repl.isnumber()) {
                // worst case: every position (and the end) matches the empty pattern
                val max = if (args.isnil(4)) s.m_length + 1L else minOf(args.checkint(4).toLong().coerceAtLeast(0), s.m_length + 1L)
                checkLength(limits, s.m_length + max * repl.tostring().checkstring().m_length)
            }
            val result = delegate.invoke(args)
            checkLength(limits, result.arg1().checkstring().m_length.toLong())
            return result
        }
    }

    /** Checks the size of a string result after the fact (for functions whose output is bounded by their input). */
    private class BoundedResult(private val limits: LuaLimits, private val delegate: LuaValue) : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            for (i in 1..args.narg()) {
                val a = args.arg(i)
                if (a is LuaString) checkLength(limits, a.m_length.toLong())
            }
            val result = delegate.invoke(args)
            val first = result.arg1()
            if (first is LuaString) checkLength(limits, first.m_length.toLong())
            return result
        }
    }

    private class BoundedConcat(private val limits: LuaLimits) : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val list = args.checktable(1)
            val sep = if (args.isnoneornil(2)) LuaValue.EMPTYSTRING else args.checkstring(2)
            val i = args.optint(3, 1)
            val j = if (args.isnoneornil(4)) list.length() else args.checkint(4)
            if (i > j) return LuaValue.EMPTYSTRING
            var total = 0L
            for (k in i..j) {
                val v = list.get(k)
                total += if (v is LuaString) v.m_length.toLong() else if (v.isnumber()) 32L else throw LuaError("invalid value (at index $k) in table for 'concat'")
                total += sep.m_length
                checkLength(limits, total)
            }
            return list.concat(sep, i, j)
        }
    }

    private class BoundedUnpack(private val limits: LuaLimits) : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val t = args.checktable(1)
            val i = args.optint(2, 1)
            val j = if (args.isnoneornil(3)) t.length() else args.checkint(3)
            if (i > j) return LuaValue.NONE
            if (j.toLong() - i + 1 > limits.maxUnpack) throw LuaError("too many results to unpack")
            return t.unpack(i, j)
        }
    }

    private class PrintFunction(private val sink: (String) -> Unit) : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val text = StringBuilder()
            for (i in 1..args.narg()) {
                if (i > 1) text.append('\t')
                val value = args.arg(i)
                // plain tostring: no __tostring metamethod, so print can never run unbudgeted Lua code
                text.append(if (value.isstring()) value.tojstring() else value.typename())
                if (text.length > 1_000) break
            }
            sink(text.take(1_000).toString())
            return LuaValue.NONE
        }
    }
}
