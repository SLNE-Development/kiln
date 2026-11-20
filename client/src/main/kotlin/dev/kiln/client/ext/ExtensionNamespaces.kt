package dev.kiln.client.ext

import dev.kiln.protocol.Ids

/**
 * Keeps server extensions out of namespaces that are not theirs (pure rules, no Minecraft; the runtime passes the
 * lookups). An extension's id is the namespace of its textures (`<id>:textures/...`) and the prefix of its
 * translation keys (`<id>.…`); without these checks a package called `minecraft`, `kiln` or like an installed mod
 * could replace their textures and texts (e.g. fake the consent screen or vanilla buttons).
 *
 * Checked on the manifest (before consent, [ExtensionHandshake]) and again when loading ([ExtensionManager]).
 */
object ExtensionNamespaces {
    /**
     * Why [id] cannot be used as an extension namespace on this client: reserved ([Ids.RESERVED_NAMESPACES]) or
     * the id of an installed mod ([isModLoaded]); `null` if it is fine.
     */
    fun problem(id: String, isModLoaded: (String) -> Boolean): String? {
        Ids.reservedNamespaceProblem(id)?.let { return it }
        if (isModLoaded(id)) return "extension id '$id' is the id of an installed mod (it would replace that mod's textures and texts)"
        return null
    }

    /**
     * A texture [id] of an extension may only be registered if nothing is known under it yet: neither a texture
     * registered at runtime ([registered]) nor a resource of the loaded resource packs ([inResources], vanilla and
     * mod assets that are loaded lazily). Throws [ExtensionPackageException] otherwise.
     */
    fun checkTexture(extension: String, id: String, registered: (String) -> Boolean, inResources: (String) -> Boolean) {
        if (registered(id) || inResources(id)) {
            throw ExtensionPackageException("$extension: texture $id already exists (extensions cannot replace textures of the game or of mods)")
        }
    }

    /**
     * A translation [key] of an extension may only be added if the language does not know it yet ([exists]), unless
     * this extension added it itself before ([own]: `en_us` first, then the selected language overrides it).
     */
    fun checkTranslation(extension: String, key: String, own: Set<String>, exists: (String) -> Boolean) {
        if (key !in own && exists(key)) {
            throw ExtensionPackageException("$extension: translation key '$key' already exists (extensions cannot replace texts of the game or of mods)")
        }
    }
}
