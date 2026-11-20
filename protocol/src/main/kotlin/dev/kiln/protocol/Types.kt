package dev.kiln.protocol

/**
 * A `namespace:path` identifier (Minecraft `Identifier` / Adventure `Key` without the dependency).
 * Same character rules as Minecraft.
 */
@ConsistentCopyVisibility
public data class ResourceId private constructor(val namespace: String, val path: String) {
    override fun toString(): String = "$namespace:$path"

    public companion object {
        private val NAMESPACE = Regex("[a-z0-9_.-]+")
        private val PATH = Regex("[a-z0-9_./-]+")

        /** Creates an id, throwing [IllegalArgumentException] for invalid characters or length. */
        public fun of(namespace: String, path: String): ResourceId {
            require(namespace.length + 1 + path.length <= ProtocolLimits.MAX_RESOURCE_ID_LENGTH) { "Resource id too long" }
            require(namespace.matches(NAMESPACE)) { "Invalid namespace '$namespace'" }
            require(path.matches(PATH)) { "Invalid path '$path'" }
            return ResourceId(namespace, path)
        }

        /** Parses `namespace:path`; a missing namespace means `minecraft`. */
        public fun parse(value: String): ResourceId {
            val colon = value.indexOf(':')
            return if (colon < 0) of("minecraft", value) else of(value.substring(0, colon), value.substring(colon + 1))
        }

        /** Like [parse] but returns `null` instead of throwing. */
        public fun tryParse(value: String): ResourceId? = runCatching { parse(value) }.getOrNull()
    }
}

/** A 32-bit ARGB color. */
@JvmInline
public value class Color(public val argb: Int) {
    public val alpha: Int get() = argb ushr 24
    public val red: Int get() = (argb shr 16) and 0xFF
    public val green: Int get() = (argb shr 8) and 0xFF
    public val blue: Int get() = argb and 0xFF

    override fun toString(): String = "#%08X".format(argb)

    public companion object {
        public val WHITE: Color = Color(0xFFFFFFFF.toInt())
        public val BLACK: Color = Color(0xFF000000.toInt())
        public val TRANSPARENT: Color = Color(0)

        /** Opaque color from a 24-bit RGB value. */
        public fun rgb(rgb: Int): Color = Color(rgb or (0xFF shl 24))

        public fun argb(alpha: Int, red: Int, green: Int, blue: Int): Color =
            Color(((alpha and 0xFF) shl 24) or ((red and 0xFF) shl 16) or ((green and 0xFF) shl 8) or (blue and 0xFF))
    }
}

/** Position relative to the parent element and size, in GUI pixels. */
public data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

public enum class TextAlign { LEFT, CENTER, RIGHT }

/** Shared validation rules for ids. */
public object Ids {
    private val ELEMENT_ID = Regex("[A-Za-z0-9_.:/-]{1,${ProtocolLimits.MAX_ID_LENGTH}}")
    private val EXTENSION_ID = Regex("[a-z0-9_.-]{1,${ProtocolLimits.MAX_ID_LENGTH}}")

    /** Element ids: `[A-Za-z0-9_.:/-]`, 1..64 chars. */
    public fun isValidElementId(id: String): Boolean = ELEMENT_ID.matches(id)

    /** Keybind ids follow the element id rules. */
    public fun isValidKeybindId(id: String): Boolean = ELEMENT_ID.matches(id)

    private val LUA_EXTENSION_ID = Regex("[a-z0-9_]{1,32}")
    private val EXTENSION_NAME = Regex("[a-z0-9_]{1,32}")

    /** Legacy ids announced in [Hello.extensions] (Fabric mod ids): `[a-z0-9_.-]`, 1..64 chars. */
    public fun isValidExtensionId(id: String): Boolean = EXTENSION_ID.matches(id)

    /** Ids of Lua extension packages (`extension.json` `id`; namespace of their widgets/channels/textures): `[a-z0-9_]{1,32}`. */
    public fun isValidLuaExtensionId(id: String): Boolean = LUA_EXTENSION_ID.matches(id)

    /** Channel and widget names inside an extension (the path of `<ext>:<name>`): `[a-z0-9_]{1,32}`. */
    public fun isValidExtensionName(name: String): Boolean = EXTENSION_NAME.matches(name)

    /**
     * Namespaces a Lua extension id must never use. The id is the namespace of the extension's textures
     * (`<id>:textures/...`) and the prefix of its translation keys (`<id>.…`), so an extension called like the game, the
     * mod loader or a platform library could replace their textures and texts (fake buttons, consent texts, ...):
     *
     * - `minecraft`: every vanilla resource (textures, GUI sprites, language keys under `minecraft:`);
     * - `realms`, `mojang`: vanilla Realms UI and Mojang assets/branding shipped with the client;
     * - `brigadier`, `java`: namespaces of the command library and the JVM that appear in ids/keys of the client;
     * - `kiln`: this runtime itself (consent screen, loading screen, its own texts);
     * - `fabric` and every id starting with `fabric` (`fabricloader`, `fabric_api`, `fabric_language_kotlin`, ...):
     *   Fabric Loader, Fabric API modules;
     * - `c`: the shared "common" tag/convention namespace used by Fabric and other loaders;
     * - `forge`, `neoforge`, `quilt` (+ prefix `quilt`): other loaders' namespaces, also present on some clients
     *   (Sinytra/Quilt compatibility layers).
     *
     * The client additionally rejects ids of installed mods and any texture or translation key that already exists.
     */
    public val RESERVED_NAMESPACES: Set<String> = setOf(
        "minecraft", "realms", "mojang", "brigadier", "java", "kiln", "fabric", "c", "forge", "neoforge", "quilt",
    )

    /** Reserved prefixes (see [RESERVED_NAMESPACES]): every id starting with one of them is reserved. */
    public val RESERVED_NAMESPACE_PREFIXES: List<String> = listOf("fabric", "quilt")

    /** `true` if [id] is a reserved namespace ([RESERVED_NAMESPACES], [RESERVED_NAMESPACE_PREFIXES]). */
    public fun isReservedNamespace(id: String): Boolean =
        id in RESERVED_NAMESPACES || RESERVED_NAMESPACE_PREFIXES.any { id.startsWith(it) }

    /** A readable reason why [id] cannot be a Lua extension id because it is reserved, or `null`. */
    public fun reservedNamespaceProblem(id: String): String? =
        if (isReservedNamespace(id)) "extension id '$id' is a reserved namespace (it would replace textures/texts of the game, the mod loader or kiln)" else null
}

/** Permissions an extension can request in `extension.json`; the client grants only these API parts. */
public object ExtensionPermissions {
    public const val WIDGETS: String = "widgets"
    public const val KEYBINDS: String = "keybinds"
    public const val HUD: String = "hud"
    public const val CHANNELS: String = "channels"
    public const val TOASTS: String = "toasts"

    /** Own Lua screens (`kiln.ui.screen` / `open` / `close`, `ui.sound`); since protocol v5. */
    public const val SCREENS: String = "screens"

    /** Read-only copy of the player's own inventory (`kiln.player().inventory()`); since protocol v5. */
    public const val INVENTORY: String = "inventory"

    /** Every known permission, in canonical order. */
    public val ALL: List<String> = listOf(WIDGETS, KEYBINDS, HUD, CHANNELS, TOASTS, SCREENS, INVENTORY)

    public fun isValid(permission: String): Boolean = permission in ALL
}

/**
 * An item as shown in [ItemDisplay], [ItemSlot] and [ShowToast].
 *
 * - [itemId]: item type; used by the client when [nbt] is absent or cannot be decoded (fallback rendering).
 * - [count]: display count, 1..[ProtocolLimits.MAX_ITEM_COUNT]. Independent of the stack inside [nbt] (which the
 *   server serializes with amount 1, because the vanilla codec only allows 1..99); counts > 1 are drawn as the
 *   slot count text, counts > 99 abbreviated by the client.
 * - [nbt]: the full stack (all data components: name, lore, enchantment glint, item model, custom model data, ...)
 *   exactly as produced by Paper's `ItemStack.serializeAsBytes()`: gzip-compressed NBT of `ItemStack.CODEC` with a
 *   root `DataVersion` int. The client decodes it with `NbtIo.readCompressed` (quota
 *   [ProtocolLimits.MAX_ITEM_NBT_UNCOMPRESSED]), runs the DataFixer from `DataVersion` if it differs and parses with
 *   `ItemStack.CODEC` using the connection's registry access. Must start with the gzip magic `1f 8b`.
 */
public class ItemStackData(
    public val itemId: ResourceId,
    public val count: Int = 1,
    public val nbt: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is ItemStackData && itemId == other.itemId && count == other.count &&
            (nbt?.contentEquals(other.nbt) ?: (other.nbt == null)))

    override fun hashCode(): Int = (31 * itemId.hashCode() + count) * 31 + (nbt?.contentHashCode() ?: 0)

    override fun toString(): String = "ItemStackData($itemId x$count, nbt=${nbt?.size ?: 0} bytes)"

    public fun withCount(count: Int): ItemStackData = ItemStackData(itemId, count, nbt)
}
