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
 *   out). Plain tables and Lua functions in arguments and return values are passed unchanged (a function of an API
 *   extension returns what it wants to give out: fresh tables, or `kiln.readonly(t)` for shared ones); **views and
 *   permission-gated API functions** in arguments and return values get the holder of the call ([adopt]).
 * - Strings, numbers, booleans, nil and item handles are immutable and passed as they are.
 *
 * **Holder** (see `docs/lua-extensions.md`, "Kein Ausleihen von Rechten"): every view knows its holder, the importer
 * it was made for, or `null` = unknown (`kiln.readonly`, which can end up anywhere). While an [ExportedFunction] runs,
 * its holder must have every permission a gated function inside needs; an unknown holder has none ("deny by
 * default"), so it does not matter by which path a holderless view travels. A view of another view is made for the
 * new holder ([wrap]): a holderless one of the same exporter is re-made for this holder, any other is nested.
 *
 * **Invariant: a holder that is on a view is never lost.** Every way a view can travel - proxy reads, `pairs`,
 * arguments and return values of exported functions ([adopt]), plain tables, callbacks - either passes the view
 * object itself on, or wraps it into another view, which keeps the inner one. Only the unknown holder (`null`) is
 * replaced. The one exception is a table proxy given back to its own exporter ([unwrap]): it becomes the real table
 * again (the exporter may change its state objects); a table holds no rights, but raw gated functions the exporter
 * itself stored in it are then raw again (documented limit, see `docs/lua-extensions.md`).
 *
 * Wrappers are cached per value so `ui.button == ui.button` holds; the caches are bounded ([MAX_CACHED]) and cleared
 * when full (identities of wrappers can then change, values never do).
 */
class LuaExports(
    internal val owner: LuaExtension,
    /**
     * The importer these views were made for (`kiln.import`), `null` for `kiln.readonly` views. Every exported
     * function remembers it: whoever ends up calling it - the holder itself, or the exporter's own code after the
     * holder handed the function back (as callback, metamethod, ...) - needs the holder's permissions as well.
     */
    internal val holder: LuaExtension? = null,
) {
    private val tables = HashMap<LuaValue, ReadOnlyProxy>()
    private val functions = HashMap<LuaValue, ExportedFunction>()

    fun wrap(value: LuaValue): LuaValue = when {
        value is ReadOnlyProxy && value.exports === this -> value
        value is ExportedFunction && value.exports === this -> value
        // a view of this exporter without holder (kiln.readonly): re-made for this holder, nothing is lost
        value is ReadOnlyProxy && value.exports.owner === owner && value.exports.holder == null -> wrap(value.target)
        value is ExportedFunction && value.exports.owner === owner && value.exports.holder == null -> wrap(value.target)
        // tables and functions, also views with another holder (of this or another exporter): nested, so every
        // holder stays (never re-made: that would drop the earlier holder, "holder laundering")
        value is LuaTable -> tables[value] ?: ReadOnlyProxy(value, this).also { cache(tables, value, it) }
        value.isfunction() -> functions[value] ?: ExportedFunction(value, this).also { cache(functions, value, it) }
        else -> value
    }

    /**
     * A value crossing the boundary of a call of an [ExportedFunction] (argument or return value): views and gated API
     * functions get this view's holder, so a raw gated function or a holderless view is not passed on without one.
     * Plain tables and Lua functions stay as they are (their code is checked as the direct caller anyway).
     */
    internal fun adopt(value: LuaValue): LuaValue =
        if (value is ReadOnlyProxy || value is ExportedFunction || value is GatedFunction) wrap(value) else value

    /** The real table behind a proxy (also nested, any holders) made by this exporter (anything else unchanged). */
    fun unwrap(value: LuaValue): LuaValue {
        var v = value
        var result = value
        while (v is ReadOnlyProxy) {
            val layer = v
            v = v.target
            if (layer.exports.owner === owner) result = v
        }
        return result
    }

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
class ExportedFunction internal constructor(internal val target: LuaValue, internal val exports: LuaExports) : VarArgFunction() {
    override fun invoke(args: Varargs): Varargs {
        if (!exports.owner.alive) throw LuaError("extension '${exports.owner.id}' is unloaded")
        // deny by default: a view without holder (kiln.readonly) runs gated functions for nobody
        LuaExecution.pushHolder(exports.holder ?: LuaExecution.UNKNOWN_HOLDER)
        try {
            return adoptAll(target.invoke(adoptAll(args, arguments = true)), arguments = false)
        } finally {
            LuaExecution.popHolder()
        }
    }

    /** Proxies of the exporter's own tables in [values] become the real tables again ([arguments]); see [LuaExports.adopt]. */
    private fun adoptAll(values: Varargs, arguments: Boolean): Varargs {
        val n = values.narg()
        var changed: Array<LuaValue>? = null
        for (i in 1..n) {
            val v = values.arg(i)
            val u = exports.adopt(if (arguments) exports.unwrap(v) else v)
            if (u !== v) {
                if (changed == null) changed = Array(n) { values.arg(it + 1) }
                changed[i - 1] = u
            }
        }
        return if (changed == null) values else LuaValue.varargsOf(changed)
    }

    /** True if this wrapper (or a wrapper it wraps) wraps [function]. */
    internal fun wraps(function: LuaValue): Boolean {
        var v: LuaValue = this
        while (v is ExportedFunction) {
            v = v.target
            if (v === function) return true
        }
        return false
    }

    override fun tojstring(): String = "function (exported by ${exports.owner.id})"
}

/** A permission-gated API function of [owner] (`kiln.net.channel`, `ch.send`, `ui.toast`, ...), see [LuaExtension.checkCaller]. */
abstract class GatedFunction internal constructor(internal val owner: LuaExtension) : VarArgFunction()
