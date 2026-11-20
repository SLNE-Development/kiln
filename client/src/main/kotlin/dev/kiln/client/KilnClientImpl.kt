package dev.kiln.client

import dev.kiln.client.ext.ExtensionCache
import dev.kiln.client.ext.ConsentScreen
import dev.kiln.client.ext.ExtensionHandshake
import dev.kiln.client.ext.ExtensionIcons
import dev.kiln.client.ext.LoadingScreen
import dev.kiln.client.ext.ExtensionManager
import dev.kiln.client.ext.ExtensionPackage
import dev.kiln.client.ext.TrustStore
import dev.kiln.client.keybind.ServerKeybinds
import dev.kiln.client.mixin.ClientCommonPacketListenerAccessor
import dev.kiln.client.net.KilnPayload
import dev.kiln.client.screen.Components
import dev.kiln.client.screen.ItemStacks
import dev.kiln.client.screen.KilnScreen
import dev.kiln.client.screen.ScreenHost
import dev.kiln.client.toast.KilnToast
import dev.kiln.protocol.ClientboundMessage
import dev.kiln.protocol.CloseScreen
import dev.kiln.protocol.DeclareKeybinds
import dev.kiln.protocol.ExtensionChunk
import dev.kiln.protocol.ExtensionInfo
import dev.kiln.protocol.ExtensionManifest
import dev.kiln.protocol.ExtensionMessage
import dev.kiln.protocol.Hello
import dev.kiln.protocol.OpenScreen
import dev.kiln.protocol.PROTOCOL_VERSION
import dev.kiln.protocol.ProtocolException
import dev.kiln.protocol.ProtocolLimits
import dev.kiln.protocol.ResourceId
import dev.kiln.protocol.ServerboundMessage
import dev.kiln.protocol.ShowToast
import dev.kiln.protocol.UpdateScreen
import dev.kiln.protocol.Viewport
import dev.kiln.protocol.Welcome
import dev.kiln.protocol.codec.MessageCodec
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.impl.networking.RegistrationPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import org.slf4j.LoggerFactory
import java.util.Locale

/**
 * The client runtime. Everything runs on the client (render) thread; network handlers hop over with `execute`.
 *
 * Connection flow (protocol v3, see `docs/lua-extensions.md` "Entscheidungen"):
 * 1. Configuration phase starts: announce `kiln:main` (`minecraft:register`), send `Hello(3, [])`.
 * 2. `Welcome` + `ExtensionManifest` (with icons): consent first ([ConsentScreen], skipped if this address already
 *    trusts the same ids+permissions); declined -> disconnect, nothing is downloaded.
 * 3. After consent: `ExtensionRequest` for the packages not in the cache, `ExtensionChunk`s reassembled and verified
 *    (size + SHA-256), stored in the cache; [LoadingScreen] shows the progress.
 * 4. Verify and start the extensions one per tick ([ExtensionHandshake], [ExtensionManager]);
 *    `ExtensionsReady(ok, error)`; the server kicks on `ok = false`.
 * 6. Play phase: screens, keybinds, toasts, channel messages; everything is removed on disconnect.
 */
class KilnClientImpl(
    private val cache: ExtensionCache,
    private val trust: TrustStore,
) : ScreenHost {
    private val logger = LoggerFactory.getLogger("kiln")

    /** Handshake done and all extensions loaded (`ExtensionsReady(true)` sent) for the current connection. */
    var isConnected: Boolean = false
        private set

    /** `true` between join (play phase) and disconnect. */
    private var inPlay = false

    /** In the world of a server with a completed kiln handshake (pause-menu entry point). */
    val isInKilnWorld: Boolean get() = isConnected && inPlay

    /** Trust key (address) of the server of the current kiln connection, or `null`. */
    val currentServer: String? get() = session?.server

    val extensions = ExtensionManager({ channel, json -> sendChannel(channel, json) }, { isConnected && inPlay })

    /** Last viewport sent to the server (GUI-scaled window size). */
    private var sentViewport: Viewport? = null

    private var session: Session? = null

    /** Icons of the announced extensions (consent and loading screens). */
    private val icons = ExtensionIcons()

    /** One connection's configuration-phase state. */
    private inner class Session(val listener: ClientConfigurationPacketListenerImpl, val server: String) : ExtensionHandshake.Io {
        val handshake = ExtensionHandshake(this)
        var manifestAt = 0L
        var reported = false

        override fun send(message: ServerboundMessage) {
            sendConfiguration(message)
        }

        override fun isTrusted(grants: List<TrustStore.Grant>) = trust.isTrusted(server, grants)

        override fun trust(grants: List<TrustStore.Grant>) {
            trust.trust(server, grants)
            trust.save()
        }

        override fun cached(info: ExtensionInfo): ByteArray? = cache.get(info)

        override fun store(info: ExtensionInfo, zip: ByteArray) = cache.put(info, zip)

        override fun askConsent(infos: List<ExtensionInfo>) {
            logger.info("Asking for consent to {} extension(s) of {}", infos.size, server)
            icons.prepare(infos)
            Minecraft.getInstance().gui.setScreen(ConsentScreen(server, infos, icons, manifestAt + CONSENT_TIMEOUT_MILLIS,
                onAccept = {
                    logger.info("Extensions of {} accepted", server)
                    if (session === this) handshake.consent(true)
                },
                onDeny = {
                    logger.info("Extensions of {} declined, disconnecting", server)
                    if (session === this) handshake.consent(false)
                },
                tickConnection = ::tickConnection,
            ))
        }

        override fun showProgress() {
            icons.prepare(handshake.items.map { it.info })
            Minecraft.getInstance().gui.setScreen(LoadingScreen(server, handshake, icons, ::tickConnection))
        }

        override fun beginLoad() = extensions.beginLoad(server)

        override fun load(pkg: ExtensionPackage) = extensions.load(pkg)

        override fun unloadAll() = extensions.unloadAll()

        override fun isModLoaded(id: String) = FabricLoader.getInstance().isModLoaded(id)

        override fun disconnect(reason: String) {
            (listener as ClientCommonPacketListenerAccessor).`kiln$getConnection`().disconnect(Component.literal(reason))
        }

        /**
         * Our screens replace the `ConnectScreen`, which is what ticks the pending connection in the configuration
         * phase; without this a server-side disconnect (timeout, kick) would go unnoticed and the screen would stay.
         */
        fun tickConnection() {
            val connection = (listener as ClientCommonPacketListenerAccessor).`kiln$getConnection`()
            if (Minecraft.getInstance().isLocalServer) return // Minecraft ticks its pending local connection itself
            if (connection.isConnected) connection.tick() else connection.handleDisconnection()
        }
    }

    // ---- sending ------------------------------------------------------------------------------------------

    /** Encodes and sends [message] in the play phase; `false` if not connected or the message is invalid. */
    override fun send(message: ServerboundMessage): Boolean {
        if (!inPlay || !isConnected) return false
        val bytes = encode(message) ?: return false
        return try {
            ClientPlayNetworking.send(KilnPayload(bytes))
            true
        } catch (e: IllegalStateException) {
            false
        }
    }

    private fun sendConfiguration(message: ServerboundMessage): Boolean {
        val bytes = encode(message) ?: return false
        return try {
            ClientConfigurationNetworking.send(KilnPayload(bytes))
            true
        } catch (e: IllegalStateException) {
            logger.warn("Cannot send {} outside the configuration phase", message)
            false
        }
    }

    private fun encode(message: ServerboundMessage): ByteArray? = try {
        MessageCodec.encodeServerbound(message)
    } catch (e: ProtocolException) {
        logger.warn("Not sending invalid message {}: {}", message, e.message)
        null
    }

    private fun sendChannel(channel: String, json: String): Boolean {
        val id = ResourceId.tryParse(channel) ?: return false
        if (json.length > ProtocolLimits.MAX_EXTENSION_JSON_LENGTH) return false
        return send(ExtensionMessage(id, json))
    }

    // ---- configuration phase ------------------------------------------------------------------------------

    /** Configuration phase started (also after a server-initiated reconfiguration). */
    fun onConfigurationStart(listener: ClientConfigurationPacketListenerImpl) {
        if (isConnected) {
            logger.debug("Reconfiguration: keeping the kiln session")
            return
        }
        resetConnection()
        val serverData = (listener as ClientCommonPacketListenerAccessor).`kiln$getServerData`()
        val minecraft = Minecraft.getInstance()
        session = Session(listener, serverKey(serverData?.ip, minecraft.isLocalServer))
        // Paper only reads plugin messages on channels the client announced; Fabric announces its configuration
        // channels only in reply to a server-side minecraft:register, which Paper does not send in this phase.
        try {
            ClientConfigurationNetworking.send(RegistrationPayload(RegistrationPayload.REGISTER, listOf(KilnPayload.TYPE.id())))
        } catch (e: RuntimeException) {
            logger.warn("Could not announce {}", KilnPayload.TYPE.id(), e)
        }
        sendConfiguration(Hello(PROTOCOL_VERSION, emptyList()))
    }

    fun onConfigurationPayload(data: ByteArray) {
        val s = session ?: return
        val message = try {
            MessageCodec.decodeClientbound(data)
        } catch (e: ProtocolException) {
            logger.warn("Dropping invalid kiln message ({} bytes): {}", data.size, e.message)
            return
        }
        val handshake = s.handshake
        when (message) {
            is Welcome -> handshake.onWelcome(message.protocolVersion)
            is ExtensionManifest -> {
                s.manifestAt = Util.getMillis()
                logger.info("Server announced {} extension(s)", message.extensions.size)
                handshake.onManifest(message)
            }
            is ExtensionChunk -> handshake.onChunk(message)
            else -> logger.warn("Unexpected {} in the configuration phase", message)
        }
        afterHandshakeStep(s)
    }

    /** Called every client tick: advances loading one step (so the loading screen shows each state). */
    private fun tickHandshake() {
        val s = session ?: return
        s.handshake.tick()
        afterHandshakeStep(s)
    }

    private fun afterHandshakeStep(s: Session) {
        if (s.reported) return
        when (s.handshake.phase) {
            ExtensionHandshake.Phase.DONE -> {
                s.reported = true
                isConnected = true
                logger.info("kiln ready: {} extension(s) loaded", s.handshake.items.size)
            }
            ExtensionHandshake.Phase.FAILED -> {
                s.reported = true
                logger.warn("Extensions failed: {}", s.handshake.error)
                extensions.unloadAll()
            }
            else -> {}
        }
    }

    // ---- play phase ---------------------------------------------------------------------------------------

    fun onJoin() {
        inPlay = true
        sentViewport = null
        if (isConnected) sendViewportIfChanged()
    }

    fun onDisconnect() {
        resetConnection()
    }

    /**
     * The player revoked the consent of the server this client is connected to: unload every extension and leave the
     * server with a clear message (there are no fallbacks; the next join asks for consent again).
     */
    fun disconnectAfterRevoke(server: String) {
        logger.info("Consent for {} revoked while connected: unloading extensions and disconnecting", server)
        extensions.unloadAll()
        ServerKeybinds.clear()
        val reason = Component.literal("Zustimmung zurückgenommen\n\nDu hast die Zustimmung zu den Erweiterungen von $server zurückgenommen. " +
            "Beim nächsten Verbinden wirst du erneut gefragt.")
        val minecraft = Minecraft.getInstance()
        val connection = minecraft.connection?.getConnection()
            ?: session?.let { (it.listener as ClientCommonPacketListenerAccessor).`kiln$getConnection`() }
        if (connection == null) {
            resetConnection()
            return
        }
        connection.disconnect(reason)
        // handled right away (not only by the next game tick, which does not run while a local world is paused)
        if (!connection.isConnected) connection.handleDisconnection()
    }

    private fun resetConnection() {
        (Minecraft.getInstance().gui.screen() as? KilnScreen)?.closeSilently = true
        ServerKeybinds.clear()
        extensions.unloadAll()
        icons.clear()
        session = null
        inPlay = false
        isConnected = false
        sentViewport = null
    }

    fun onPayload(data: ByteArray) {
        if (!inPlay) return
        val message = try {
            MessageCodec.decodeClientbound(data)
        } catch (e: ProtocolException) {
            logger.warn("Dropping invalid kiln message ({} bytes): {}", data.size, e.message)
            return
        }
        try {
            handle(message)
        } catch (e: Exception) {
            logger.error("Failed to handle kiln message {}", message, e)
        }
    }

    private fun handle(message: ClientboundMessage) {
        if (!isConnected) {
            logger.debug("Ignoring {} without a completed handshake", message)
            return
        }
        val minecraft = Minecraft.getInstance()
        when (message) {
            is DeclareKeybinds -> ServerKeybinds.declare(session?.server ?: serverKey(minecraft), message.keybinds)
            is OpenScreen -> {
                (minecraft.gui.screen() as? KilnScreen)?.closeSilently = true
                minecraft.gui.setScreen(KilnScreen(message.screenId, message.root, this, message.fullscreen))
            }
            is UpdateScreen -> {
                val screen = minecraft.gui.screen() as? KilnScreen
                if (screen != null && screen.screenId == message.screenId) screen.update(message.root, message.fullscreen)
                else logger.debug("Ignoring update for screen {} (not open)", message.screenId)
            }
            is CloseScreen -> {
                val screen = minecraft.gui.screen() as? KilnScreen
                // removed() acknowledges with ScreenClosed
                if (screen != null && screen.screenId == message.screenId) minecraft.gui.setScreen(null)
            }
            is ExtensionMessage -> extensions.onChannelMessage(message.channel.toString(), message.json)
            is ShowToast -> showToast(minecraft, message)
            is Welcome, is ExtensionManifest, is ExtensionChunk -> logger.debug("Ignoring {} in the play phase", message)
        }
    }

    private fun showToast(minecraft: Minecraft, message: ShowToast) {
        val title = Components.parse(message.title) ?: Component.empty()
        val text = Components.parse(message.text)
        val icon = message.icon?.let { ItemStacks.decode(it) }
        val millis = message.displayMillis.coerceIn(500, ProtocolLimits.MAX_TOAST_MILLIS).toLong()
        minecraft.gui.toastManager().addToast(KilnToast(title, text, icon, millis))
    }

    private fun serverKey(minecraft: Minecraft): String = serverKey(minecraft.currentServer?.ip, minecraft.isLocalServer)

    companion object {
        /** Server-side limit for manifest -> `ExtensionRequest`, i.e. the consent screen (`docs/lua-extensions.md`). */
        const val CONSENT_TIMEOUT_MILLIS = 300_000L

        /**
         * Key for consent and keybind persistence: the address as entered in the server list (lowercase). In the
         * configuration phase it comes from the listener's `ServerData`, because `Minecraft.getCurrentServer()` is
         * only set once the play phase starts.
         */
        fun serverKey(address: String?, localServer: Boolean): String =
            address?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: if (localServer) "singleplayer" else "unknown"
    }

    // ---- ScreenHost ---------------------------------------------------------------------------------------

    override fun createWidget(type: Identifier) = extensions.createWidget(type)

    override val textures get() = extensions

    // ---- ticking ------------------------------------------------------------------------------------------

    /** Reports the GUI-scaled window size whenever it changes (resize, GUI scale, fullscreen toggle). */
    private fun sendViewportIfChanged() {
        if (!isConnected || !inPlay) return
        val window = Minecraft.getInstance().window
        val viewport = Viewport(window.guiScaledWidth.coerceIn(1, ProtocolLimits.MAX_SIZE), window.guiScaledHeight.coerceIn(1, ProtocolLimits.MAX_SIZE))
        if (viewport == sentViewport) return
        if (send(viewport)) sentViewport = viewport
    }

    fun tick() {
        tickHandshake()
        sendViewportIfChanged()
        ServerKeybinds.tick(isConnected && inPlay) { send(it) }
    }
}
