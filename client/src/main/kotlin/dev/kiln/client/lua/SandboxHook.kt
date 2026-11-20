package dev.kiln.client.lua

import org.luaj.vm2.Lua
import org.luaj.vm2.LuaClosure
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.DebugLib

/**
 * Thrown when a script exceeds a hard limit (instructions, memory). Deliberately an [Error], not an [Exception]:
 * LuaJ's `pcall` catches `LuaError` and every `Exception`, but not `Error`, so a script cannot swallow it and keep
 * running. LuaJ's interpreter loop (`LuaClosure.execute`) only wraps `Exception`s, so it travels unchanged up to the
 * runtime's call boundary ([LuaRuntime.call]), which unloads the extension.
 */
class LuaLimitExceeded(message: String) : Error(message, null, false, false)

/**
 * Execution hook of one extension. LuaJ calls `Globals.debuglib` on every function entry/exit and **before every VM
 * instruction** of interpreted code (`LuaClosure.execute`) as soon as the field is non-null; this is the only
 * per-instruction hook point LuaJ offers without patching the interpreter. The hook is installed directly into the
 * field and never exposed as the `debug` library, and it does not delegate to [DebugLib] (no call stack bookkeeping,
 * no hooks a script could set).
 *
 * It enforces:
 * - the instruction budget ([remaining]); exceeding it throws [LuaLimitExceeded];
 * - the call depth ([LuaLimits.maxCallDepth]) as a catchable `LuaError("stack overflow")`, before LuaJ's Java
 *   recursion could overflow the render thread's stack;
 * - the size of `..` results: before an `OP_CONCAT` executes, the lengths of its operands are summed from the
 *   frame's register array (captured in [onCall]); oversized results never get allocated.
 *
 * Coroutines are not available in the sandbox, so there is exactly one Lua thread and one frame stack.
 *
 * **Calls across extensions** (Lua API v2 imports): LuaJ reports instructions to the hook of the *closure's*
 * environment, so code of an imported API extension would be counted on that extension's hook. While a call is in
 * progress, every hook therefore forwards to the hook of the call's principal ([LuaExecution.activeHook]): the
 * caller pays for everything that runs in its call, including code of other extensions.
 */
class SandboxHook(private val limits: LuaLimits) : DebugLib() {
    /** The runtime this hook belongs to (owner of the closures compiled for its globals, [LuaExecution.caller]). */
    var runtime: LuaRuntime? = null
        internal set

    /** Instructions left in the current call. */
    var remaining: Long = 0

    /** Instructions executed since the hook was created (used to schedule memory checks). */
    var executed: Long = 0
        private set

    private var closures = arrayOfNulls<LuaClosure>(64)
    private var stacks = arrayOfNulls<Array<LuaValue>>(64)
    private var depth = 0

    /** Prepares a new outermost call with [budget] instructions. */
    fun begin(budget: Long) {
        remaining = budget
        if (depth != 0) {
            closures.fill(null)
            stacks.fill(null)
            depth = 0
        }
    }

    /** The innermost Lua function of the running call (frames of `pcall` skipped), or `null`. */
    fun innermostClosure(): LuaClosure? {
        for (i in depth - 1 downTo 0) closures[i]?.let { return it }
        return null
    }

    /** Instructions consumed since [begin] with [budget]. */
    fun used(budget: Long): Long = budget - remaining

    override fun onCall(f: LuaFunction) {
        val active = LuaExecution.activeHook
        if (active != null && active !== this) return active.onCall(f)
        push(null, null)
    }

    override fun onCall(c: LuaClosure, varargs: Varargs, stack: Array<LuaValue>) {
        val active = LuaExecution.activeHook
        if (active != null && active !== this) return active.onCall(c, varargs, stack)
        push(c, stack)
    }

    private fun push(closure: LuaClosure?, stack: Array<LuaValue>?) {
        // thrown before LuaJ enters its try/finally, so no onReturn follows: the stack stays balanced
        if (depth >= limits.maxCallDepth) throw LuaError("stack overflow (more than ${limits.maxCallDepth} nested calls)")
        if (depth == closures.size) {
            closures = closures.copyOf(depth * 2)
            stacks = stacks.copyOf(depth * 2)
        }
        closures[depth] = closure
        stacks[depth] = stack
        depth++
    }

    override fun onReturn() {
        val active = LuaExecution.activeHook
        if (active != null && active !== this) return active.onReturn()
        if (depth == 0) return
        depth--
        closures[depth] = null
        stacks[depth] = null
    }

    override fun onInstruction(pc: Int, v: Varargs, top: Int) {
        val active = LuaExecution.activeHook
        if (active != null && active !== this) return active.onInstruction(pc, v, top)
        executed++
        if (--remaining < 0) throw LuaLimitExceeded("instruction budget exceeded")
        if (depth == 0) return
        val closure = closures[depth - 1] ?: return
        val code = closure.p.code
        if (pc < 0 || pc >= code.size) return
        val i = code[pc]
        if (Lua.GET_OPCODE(i) == Lua.OP_CONCAT) checkConcat(stacks[depth - 1] ?: return, Lua.GETARG_B(i), Lua.GETARG_C(i))
    }

    private fun checkConcat(stack: Array<LuaValue>, from: Int, to: Int) {
        var total = 0L
        for (r in from..minOf(to, stack.size - 1)) {
            val value = stack[r]
            total += when {
                value is LuaString -> value.m_length.toLong()
                value.isnumber() -> 32L
                else -> 0L // metamethod (__concat) or error: counted when it runs
            }
        }
        if (total > limits.maxStringBytes) throw LuaError("string too long (limit ${limits.maxStringBytes} bytes)")
    }

    /** LuaJ appends this to error messages; the sandbox keeps messages short and free of host details. */
    override fun traceback(level: Int): String = ""
}
