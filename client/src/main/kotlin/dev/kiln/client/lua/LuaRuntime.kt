package dev.kiln.client.lua

import org.luaj.vm2.Globals
import org.luaj.vm2.Lua
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Prototype
import org.luaj.vm2.Varargs
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/** A script failed or exceeded a limit; the extension must be unloaded. [message] is safe to show to the player. */
class LuaScriptException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Who runs Lua right now (client thread only). The **principal** is the runtime whose [LuaRuntime.call] started the
 * current call chain: the extension the host called (render, event, channel message, keybind). Everything that runs
 * until that call returns - its own code, code of imported API extensions, host functions calling back into Lua - is
 * charged to the principal's budget, and permission-checked API functions check the principal's permissions
 * (see `docs/lua-extensions.md`, "Sicherheitsmodell beim Import").
 */
object LuaExecution {
    /** Runtime of the outermost call in progress, or `null` when no Lua runs. */
    var principal: LuaRuntime? = null
        private set

    /** Hook of [principal]; every extension's hook forwards to it (see [SandboxHook]). */
    @JvmField
    internal var activeHook: SandboxHook? = null

    val active: Boolean get() = principal != null

    /**
     * Holders of the exported functions running now ([ExportedFunction.holder], innermost last): an importer that
     * calls (or hands on) a function it imported adds its own permissions to the check of everything that runs inside.
     */
    private val holders = ArrayList<Any>()
    val activeHolders: List<Any> get() = holders

    internal fun pushHolder(holder: Any) { holders += holder }

    /** Holder of a view nobody imported (`kiln.readonly`): has no permissions (see [LuaExports]). */
    internal val UNKNOWN_HOLDER = Any()

    internal fun popHolder() { if (holders.isNotEmpty()) holders.removeAt(holders.size - 1) }

    internal fun enter(runtime: LuaRuntime, hook: SandboxHook) {
        principal = runtime
        activeHook = hook
        holders.clear()
    }

    internal fun exit() {
        principal = null
        activeHook = null
        holders.clear()
    }

    /** Owner (environment) of every compiled function prototype; closures can only come from these (no `load`). */
    private val owners: MutableMap<Prototype, WeakReference<Globals>> = Collections.synchronizedMap(WeakHashMap())

    /**
     * Records [globals] as owner of [prototype] and all functions nested in it, and turns tail calls into plain
     * calls: LuaJ runs a tail call *after* the calling function's frame is gone, so the callee would appear to be
     * called by the caller's caller (see [caller]). `return f(x)` compiles to `TAILCALL A B 0; RETURN A 0`, and
     * `CALL A B 0` (all results to the stack top) followed by that `RETURN` returns exactly the same values. The only
     * difference: tail calls now count towards [LuaLimits.maxCallDepth].
     */
    internal fun registerChunk(prototype: Prototype, globals: Globals) {
        val pending = ArrayDeque<Prototype>()
        pending += prototype
        while (pending.isNotEmpty()) {
            val p = pending.removeLast()
            owners[p] = WeakReference(globals)
            val code = p.code
            for (i in code.indices) {
                val instruction = code[i]
                if (Lua.GET_OPCODE(instruction) == Lua.OP_TAILCALL && Lua.GETARG_C(instruction) == 0) {
                    code[i] = (instruction and Lua.MASK_NOT_OP) or (Lua.OP_CALL shl Lua.POS_OP)
                }
            }
            p.p?.let { pending.addAll(it) }
        }
    }

    /** Who called the host function that is running now. */
    sealed interface Caller {
        /** No Lua function on the stack: the host called the function directly (e.g. as a callback). */
        data object Host : Caller

        /** The innermost Lua function on the stack belongs to [runtime]. */
        data class Script(val runtime: LuaRuntime) : Caller

        /** A Lua function whose owner is unknown (must not happen; treated as "deny"). */
        data object Unknown : Caller

        /** Not called directly by Lua code: a metamethod or a library function in between (denied for gated functions). */
        data object Indirect : Caller
    }

    /**
     * The direct Lua caller of the running host function [isTarget] (the function itself, or a wrapper of it): the
     * owner of the Lua frame whose `CALL` (or `pcall(f, ...)`) invokes it ([SandboxHook.directCall]). Frames are
     * tracked by the principal's hook, which every extension's hook forwards to, so this works across extensions.
     */
    fun caller(isTarget: (LuaValue) -> Boolean): Caller {
        val hook = activeHook ?: return Caller.Host
        val closure = when (val call = hook.directCall(isTarget)) {
            SandboxHook.Call.FromHost -> return Caller.Host
            SandboxHook.Call.Indirect -> return Caller.Indirect
            is SandboxHook.Call.Direct -> call.closure
        }
        val globals = owners[closure.p]?.get() ?: return Caller.Unknown
        val runtime = (globals.debuglib as? SandboxHook)?.runtime ?: return Caller.Unknown
        return Caller.Script(runtime)
    }
}

/**
 * One sandboxed Lua environment with its limits. All calls into Lua go through [call] / [load], which
 * - give the call its instruction budget (bounded by what is left of the one-second window),
 * - convert every failure into a [LuaScriptException] (Lua errors, limit violations, Java stack overflow),
 * - run a memory estimate every [LuaLimits.memoryCheckInterval] instructions.
 *
 * Calls made while another call is in progress (Lua -> host -> Lua, also into another extension's runtime) are
 * nested: they run inside the outer call's budget and error handling ([LuaExecution]).
 *
 * Not thread-safe: an environment is only used from the client (render) thread.
 */
class LuaRuntime(
    val name: String,
    val limits: LuaLimits = LuaLimits(),
    print: (String) -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
) {
    private val hook = SandboxHook(limits)
    val globals: Globals = LuaSandbox.createGlobals(hook, limits, print)

    init {
        hook.runtime = this
    }

    /** The object (extension) this runtime belongs to; read through [LuaExecution.principal]. */
    var owner: Any? = null

    /** Extra GC roots for the memory estimate (values held only by the host, e.g. widget state tables). */
    var extraRoots: () -> Iterable<LuaValue> = { emptyList() }

    /**
     * Runtimes whose memory is checked together with this one after its calls: the API extensions it imports, so
     * a caller cannot grow an imported extension's heap unchecked (that extension may never run calls of its own).
     */
    var memoryDependencies: () -> List<LuaRuntime> = { emptyList() }

    /** Instructions of the last outermost call (benchmarks, diagnostics). */
    var lastCallInstructions: Long = 0
        private set

    private var windowStart = clock()
    private var windowUsed = 0L
    private var nextMemoryCheck = limits.memoryCheckInterval

    /** Compiles and runs [source] as chunk [chunkName] with the load budget; returns the chunk's first result. */
    fun load(source: ByteArray, chunkName: String): LuaValue {
        val chunk = try {
            LuaSandbox.compile(globals, source, chunkName)
        } catch (e: LuaError) {
            throw LuaScriptException(cleanMessage(e))
        }
        return call(chunk, LuaValue.NONE, limits.instructionsForLoad).arg1()
    }

    /**
     * Calls [function] with [args]. Re-entrant calls (Lua -> host -> Lua, any runtime) share the outer call's budget.
     * [countInWindow] = false leaves the one-second window untouched (screen rendering: at most once per frame and
     * bounded by [LuaLimits.instructionsPerFrame]).
     */
    fun call(function: LuaValue, args: Varargs = LuaValue.NONE, budget: Long = limits.instructionsPerCall, countInWindow: Boolean = true): Varargs {
        if (LuaExecution.active) return function.invoke(args) // nested: inside the outer call's budget and error handling
        val now = clock()
        if (now - windowStart >= 1_000_000_000L) {
            windowStart = now
            windowUsed = 0
        }
        val available = if (countInWindow) minOf(budget, limits.instructionsPerSecond - windowUsed) else budget
        if (available <= 0) throw LuaScriptException("$name: instruction budget per second exceeded")
        hook.begin(available)
        LuaExecution.enter(this, hook)
        try {
            return function.invoke(args)
        } catch (e: LuaLimitExceeded) {
            throw LuaScriptException("$name: ${e.message}")
        } catch (e: LuaError) {
            throw LuaScriptException(cleanMessage(e))
        } catch (e: StackOverflowError) {
            throw LuaScriptException("$name: stack overflow")
        } catch (e: LuaScriptException) {
            throw e
        } catch (e: RuntimeException) {
            throw LuaScriptException("$name: ${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            LuaExecution.exit()
            val used = hook.used(available)
            lastCallInstructions = used
            if (countInWindow) windowUsed += used
            hook.begin(0)
            if (hook.executed >= nextMemoryCheck) {
                nextMemoryCheck = hook.executed + limits.memoryCheckInterval
                checkMemory()
            }
        }
    }

    /**
     * Estimates the reachable Lua heap of this runtime and of [memoryDependencies]; throws [LuaScriptException] above
     * [LuaLimits.maxMemoryBytes] (each runtime has its own limit).
     */
    fun checkMemory() {
        val estimate = LuaMemory.estimate(listOf<LuaValue>(globals) + extraRoots(), limits.maxMemoryBytes, limits.maxObjects)
        if (estimate.exceeded) {
            throw LuaScriptException("$name: memory limit exceeded (~${estimate.bytes / 1024} KiB, ${estimate.objects} objects)")
        }
        for (dependency in memoryDependencies()) {
            val e = LuaMemory.estimate(listOf<LuaValue>(dependency.globals) + dependency.extraRoots(), dependency.limits.maxMemoryBytes, dependency.limits.maxObjects)
            if (e.exceeded) throw LuaScriptException("$name: memory limit of imported extension ${dependency.name} exceeded (~${e.bytes / 1024} KiB)")
        }
    }

    private fun cleanMessage(e: LuaError): String {
        val raw = (e.message ?: e.toString()).trim()
        return raw.lineSequence().first().take(300)
    }
}
