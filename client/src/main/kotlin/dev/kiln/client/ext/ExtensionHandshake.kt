package dev.kiln.client.ext

import dev.kiln.protocol.ExtensionChunk
import dev.kiln.protocol.ExtensionInfo
import dev.kiln.protocol.ExtensionManifest
import dev.kiln.protocol.ExtensionRequest
import dev.kiln.protocol.ExtensionsReady
import dev.kiln.protocol.PROTOCOL_VERSION
import dev.kiln.protocol.ProtocolLimits
import dev.kiln.protocol.ServerboundMessage

/**
 * Client side of the configuration-phase handshake after `Hello`, as a pure state machine (no Minecraft):
 *
 * `Welcome` -> `ExtensionManifest` -> **consent** (skipped if already trusted) -> `ExtensionRequest` (only ids that
 * are not cached) -> `ExtensionChunk`s -> per tick: verify one package, then start (load) one extension ->
 * `ExtensionsReady(true)`. Nothing is requested before consent; declining disconnects. Any failure sends
 * `ExtensionsReady(false, reason)` (the server disconnects with that reason).
 *
 * [items] and the byte counters feed the loading screen.
 */
class ExtensionHandshake(private val io: Io) {
    interface Io {
        fun send(message: ServerboundMessage)
        fun isTrusted(grants: List<TrustStore.Grant>): Boolean
        fun trust(grants: List<TrustStore.Grant>)
        fun cached(info: ExtensionInfo): ByteArray?
        fun store(info: ExtensionInfo, zip: ByteArray)

        /** Show the consent screen for [infos]; the answer comes back through [consent]. */
        fun askConsent(infos: List<ExtensionInfo>)

        /** Show the loading screen (download/verify/start progress). */
        fun showProgress()

        /** Prepares loading (fresh state) before the first [load]. */
        fun beginLoad()

        /** Loads one verified package; throws [ExtensionLoadException]. */
        fun load(pkg: ExtensionPackage)

        /** Undo everything [load] did (failure). */
        fun unloadAll()

        fun disconnect(reason: String)

        /** `true` if a Fabric mod with this id is installed (its namespace is off limits for extensions). */
        fun isModLoaded(id: String): Boolean = false
    }

    enum class Phase { WAIT_WELCOME, WAIT_MANIFEST, CONSENT, DOWNLOADING, LOADING, DONE, FAILED, DECLINED }

    enum class Status { QUEUED, CACHED, DOWNLOADING, VERIFYING, STARTING, DONE, FAILED }

    class Item(val info: ExtensionInfo) {
        var status: Status = Status.QUEUED
        var received: Int = 0
        var error: String? = null
        internal var zip: ByteArray? = null
        internal var pkg: ExtensionPackage? = null
        internal var assembler: ChunkAssembler? = null
    }

    var phase: Phase = Phase.WAIT_WELCOME
        private set

    var items: List<Item> = emptyList()
        private set

    var error: String? = null
        private set

    val totalBytes: Long get() = items.sumOf { it.info.size.toLong() }
    val receivedBytes: Long get() = items.sumOf { if (it.status == Status.QUEUED || it.status == Status.DOWNLOADING) it.received.toLong() else it.info.size.toLong() }

    /** 0..1 over download (first half of the bar for downloads) and start-up. */
    val progress: Float
        get() {
            if (items.isEmpty()) return if (phase == Phase.DONE) 1f else 0f
            val bytes = if (totalBytes == 0L) 1f else receivedBytes.toFloat() / totalBytes
            val done = items.count { it.status == Status.DONE }.toFloat() / items.size
            return (bytes * 0.7f + done * 0.3f).coerceIn(0f, 1f)
        }

    private val grants: List<TrustStore.Grant> get() = items.map { TrustStore.Grant(it.info.id, it.info.permissions.toSet()) }

    fun onWelcome(version: Int) {
        if (phase != Phase.WAIT_WELCOME) return fail("unexpected Welcome")
        if (version != PROTOCOL_VERSION) return fail("protocol mismatch (server $version, client $PROTOCOL_VERSION)")
        phase = Phase.WAIT_MANIFEST
    }

    fun onManifest(manifest: ExtensionManifest) {
        if (phase != Phase.WAIT_MANIFEST) return fail("unexpected extension manifest")
        val infos = manifest.extensions
        if (infos.map { it.id }.toSet().size != infos.size) return fail("duplicate extension ids in the manifest")
        // before consent and download: an extension must not take over the namespace of the game, kiln or a mod
        for (info in infos) ExtensionNamespaces.problem(info.id, io::isModLoaded)?.let { return fail(it) }
        items = infos.map(::Item)
        if (io.isTrusted(grants)) {
            startDownload()
        } else {
            phase = Phase.CONSENT
            io.askConsent(infos)
        }
    }

    /** The player's answer on the consent screen. */
    fun consent(accepted: Boolean) {
        if (phase != Phase.CONSENT) return
        if (!accepted) {
            phase = Phase.DECLINED
            io.disconnect("Server-Erweiterungen abgelehnt")
            return
        }
        io.trust(grants)
        startDownload()
    }

    /** The player cancelled on the loading screen. */
    fun cancel() {
        if (phase == Phase.DONE || phase == Phase.FAILED || phase == Phase.DECLINED) return
        phase = Phase.DECLINED
        io.unloadAll()
        io.disconnect("Laden der Server-Erweiterungen abgebrochen")
    }

    private fun startDownload() {
        val missing = ArrayList<String>()
        for (item in items) {
            val cached = io.cached(item.info)
            if (cached != null) {
                item.zip = cached
                item.status = Status.CACHED
            } else {
                item.assembler = ChunkAssembler(item.info)
                missing += item.id()
            }
        }
        phase = Phase.DOWNLOADING
        io.showProgress()
        io.send(ExtensionRequest(missing))
        items.firstOrNull { it.status == Status.QUEUED }?.status = Status.DOWNLOADING
        if (missing.isEmpty()) phase = Phase.LOADING
    }

    fun onChunk(chunk: ExtensionChunk) {
        if (phase != Phase.DOWNLOADING) return fail("unexpected chunk for '${chunk.id}'")
        val item = items.firstOrNull { it.status == Status.DOWNLOADING } ?: return fail("unexpected chunk for '${chunk.id}'")
        val zip = try {
            item.assembler!!.add(chunk)
        } catch (e: ExtensionPackageException) {
            item.status = Status.FAILED
            item.error = e.message
            return fail(e.message ?: "download failed")
        }
        item.received = minOf(item.info.size, item.received + chunk.data.size)
        if (zip == null) return
        io.store(item.info, zip)
        item.zip = zip
        item.assembler = null
        item.status = Status.VERIFYING
        val next = items.firstOrNull { it.status == Status.QUEUED }
        if (next != null) next.status = Status.DOWNLOADING else phase = Phase.LOADING
    }

    /**
     * Advances loading by one step (called every client tick, so the loading screen shows each state): verify the
     * next package, or start the next verified extension, or report readiness.
     */
    fun tick() {
        if (phase != Phase.LOADING) return
        val toVerify = items.firstOrNull { it.pkg == null && it.zip != null }
        if (toVerify != null) {
            toVerify.status = Status.VERIFYING
            try {
                toVerify.pkg = verify(toVerify)
                toVerify.zip = null
                toVerify.status = Status.STARTING
            } catch (e: ExtensionPackageException) {
                toVerify.status = Status.FAILED
                toVerify.error = e.message
                fail(e.message ?: "invalid package")
            }
            return
        }
        if (items.none { it.status == Status.DONE }) io.beginLoad()
        val toStart = items.firstOrNull { it.status == Status.STARTING }
        if (toStart != null) {
            try {
                io.load(toStart.pkg!!)
                toStart.status = Status.DONE
            } catch (e: ExtensionLoadException) {
                toStart.status = Status.FAILED
                toStart.error = e.message
                io.unloadAll()
                // extensions that import the failed one cannot load either (no fallbacks)
                val dependents = dependentsOf(toStart.info.id)
                for (item in items) if (item.info.id in dependents) {
                    item.status = Status.FAILED
                    item.error = "needs ${toStart.info.id}"
                }
                val suffix = if (dependents.isEmpty()) "" else " (needed by ${dependents.joinToString()})"
                fail((e.message ?: "extension failed to load") + suffix)
            }
            return
        }
        if (items.all { it.status == Status.DONE }) {
            phase = Phase.DONE
            io.send(ExtensionsReady(true))
        }
    }

    /** Ids of the manifest entries that (transitively) depend on [id]. */
    fun dependentsOf(id: String): List<String> {
        val result = LinkedHashSet<String>()
        var changed = true
        while (changed) {
            changed = false
            for (item in items) {
                val itemId = item.info.id
                if (itemId !in result && item.info.dependencies.any { it == id || it in result }) {
                    result += itemId
                    changed = true
                }
            }
        }
        return result.toList()
    }

    private fun verify(item: Item): ExtensionPackage {
        val info = item.info
        val pkg = ExtensionPackage.read(item.zip!!, info.id)
        if (pkg.permissions != info.permissions.toSet()) {
            throw ExtensionPackageException("${info.id}: permissions in extension.json differ from the manifest")
        }
        if (pkg.version != info.version || pkg.name != info.name) {
            throw ExtensionPackageException("${info.id}: name/version in extension.json differ from the manifest")
        }
        if (pkg.dependencies != info.dependencies) {
            throw ExtensionPackageException("${info.id}: dependencies in extension.json differ from the manifest")
        }
        return pkg
    }

    private fun fail(reason: String) {
        if (phase == Phase.FAILED || phase == Phase.DECLINED || phase == Phase.DONE) return
        phase = Phase.FAILED
        error = reason
        io.send(ExtensionsReady(false, reason.take(ProtocolLimits.MAX_EXTENSION_ERROR_LENGTH)))
    }

    private fun Item.id() = info.id
}
