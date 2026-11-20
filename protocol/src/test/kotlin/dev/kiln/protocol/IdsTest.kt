package dev.kiln.protocol

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdsTest {
    @Test
    fun `reserved namespaces cover the game, the loader, kiln and common libraries`() {
        for (id in listOf("minecraft", "realms", "mojang", "kiln", "fabric", "c", "brigadier", "java", "forge", "neoforge", "quilt")) {
            assertTrue(Ids.isReservedNamespace(id), id)
            assertNotNull(Ids.reservedNamespaceProblem(id), id)
        }
        // everything with the fabric/quilt prefix (Fabric API modules, loader, language adapters)
        for (id in listOf("fabricloader", "fabric_api", "fabric_language_kotlin", "fabricx", "quilt_loader")) {
            assertTrue(Ids.isReservedNamespace(id), id)
        }
    }

    @Test
    fun `ordinary extension ids stay allowed`() {
        for (id in listOf("shop", "ui_kit", "kiln_example", "kilnx", "cc", "c2", "minecraft_ui", "javascript", "my_fabric")) {
            assertFalse(Ids.isReservedNamespace(id), id)
            assertNull(Ids.reservedNamespaceProblem(id), id)
        }
    }

    @Test
    fun `every reserved namespace is a syntactically valid extension id (so the check is not dead code)`() {
        for (id in Ids.RESERVED_NAMESPACES) assertTrue(Ids.isValidLuaExtensionId(id), id)
    }
}
