package dev.kiln.client.lua

import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction

/**
 * Read-only views of one extension's values for other extensions (`kiln.export` / `kiln.import`,
 * `kiln.readonly`).
 *
 * - Tables become [ReadOnlyProxy]s: reads go to the real table (through its metatable, so "classes" work) and wrap
 *   the result again (recursively, also keys and values seen by `pairs`/`ipairs`/`next`), every write - `t.k = v`,
 *   `table.insert/remove/sort`, `setmetatable` - is an error, `getmetatable` returns the string `"read-only"`.
 * - Functions become [ExportedFunction]s: they refuse to run once the exporting extension is unloaded, and hand
 *   proxies of the exporter's own tables back as the real tables (so the exporter can update state objects it gave
 *   out). Arguments and **return values are passed unchanged**: a function of an API extension returns what it
 *   wants to give out (fresh tables, or `kiln.readonly(t)` for shared ones).
 * - Strings, numbers, booleans, nil and item handles are immutable and passed as they are.
 *
 * Wrappers are cached per value so `ui.button == ui.button` holds; the caches are bounded ([MAX_CACHED]) and cleared
 * when full (identities of wrappers can then change, values never do).
 */
class LuaExports(internal val owner: LuaExtension) {
    private val tables = HashMap<LuaValue, ReadOnlyProxy>()
    private val functions = HashMap<LuaValue, ExportedFunction>()

    fun wrap(value: LuaValue): LuaValue = when {
        value is ReadOnlyProxy || value is ExportedFunction -> value
        value is LuaTable -> tables[value] ?: ReadOnlyProxy(value, this).also { cache(tables, value, it) }
        value.isfunction() -> functions[value] ?: ExportedFunction(value, this).also { cache(functions, value, it) }
        else -> value
    }

    /** The real table behind a proxy made by this exporter (anything else unchanged). */
    fun unwrap(value: LuaValue): LuaValue = if (value is ReadOnlyProxy && value.exports === this) value.target else value

    private fun <V> cache(map: HashMap<LuaValue, V>, key: LuaValue, value: V) {
        if (map.size >= MAX_CACHED) map.clear()
        map[key] = value
    }

    companion object {
        const val MAX_CACHED = 4_096
        const val LOCK = "read-only"
    }
}

/** See [LuaExports]. The proxy itself stores nothing; its metatable is locked and rejects writes. */
class ReadOnlyProxy internal constructor(internal val target: LuaTable, internal val exports: LuaExports) : LuaTable() {
    init {
        super.setmetatable(LOCKED_METATABLE)
    }

    private fun denied(): Nothing = throw LuaError("attempt to modify a read-only table (exported by '${exports.owner.id}')")

    // outside a runtime call (host code reading a definition table that is a proxy) no metamethod may run: it would
    // run Lua without budget and without principal
    override fun rawget(key: Int): LuaValue = exports.wrap(if (LuaExecution.active) target.get(key) else target.rawget(key))
    override fun rawget(key: LuaValue): LuaValue = exports.unwrap(key).let { k -> exports.wrap(if (LuaExecution.active) target.get(k) else target.rawget(k)) }
    override fun get(key: Int): LuaValue = rawget(key)
    override fun get(key: LuaValue): LuaValue = rawget(key)

    override fun rawset(key: Int, value: LuaValue) = denied()
    override fun rawset(key: LuaValue, value: LuaValue) = denied()
    override fun set(key: Int, value: LuaValue) = denied()
    override fun set(key: LuaValue, value: LuaValue) = denied()
    override fun setmetatable(metatable: LuaValue?): LuaValue = denied()
    override fun insert(pos: Int, value: LuaValue) = denied()
    override fun remove(pos: Int): LuaValue = denied()
    override fun sort(comparator: LuaValue) = denied()

    override fun next(key: LuaValue): Varargs {
        val n = target.next(exports.unwrap(key))
        val k = n.arg1()
        if (k.isnil()) return LuaValue.NIL
        return LuaValue.varargsOf(exports.wrap(k), exports.wrap(n.arg(2)))
    }

    override fun inext(key: LuaValue): Varargs {
        val n = target.inext(key)
        if (n.arg1().isnil()) return LuaValue.NONE
        return LuaValue.varargsOf(n.arg1(), exports.wrap(n.arg(2)))
    }

    override fun rawlen(): Int = target.rawlen()
    override fun length(): Int = target.length()
    override fun len(): LuaValue = target.len()

    override fun tojstring(): String = "table (read-only)"

    private companion object {
        /** `__metatable` hides it (and blocks `setmetatable`), `__newindex` rejects new keys. */
        val LOCKED_METATABLE: LuaTable = LuaTable().apply {
            set("__metatable", LuaExports.LOCK)
            set("__newindex", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs = throw LuaError("attempt to modify a read-only table")
            })
        }
    }
}

/** See [LuaExports]. */
class ExportedFunction internal constructor(private val target: LuaValue, private val exports: LuaExports) : VarArgFunction() {
    override fun invoke(args: Varargs): Varargs {
        if (!exports.owner.alive) throw LuaError("extension '${exports.owner.id}' is unloaded")
        val n = args.narg()
        var unwrapped: Array<LuaValue>? = null
        for (i in 1..n) {
            val a = args.arg(i)
            if (a is ReadOnlyProxy && a.exports === exports) {
                if (unwrapped == null) unwrapped = Array(n) { args.arg(it + 1) }
                unwrapped[i - 1] = a.target
            }
        }
        return target.invoke(if (unwrapped == null) args else LuaValue.varargsOf(unwrapped))
    }

    override fun tojstring(): String = "function (exported by ${exports.owner.id})"
}
