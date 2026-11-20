package dev.kiln.server.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ExtensionDependenciesTest {

    private fun pkg(id: String, version: String = "1.0.0", deps: String = ""): ExtensionPackage {
        val manifest = """{"id":"$id","version":"$version","name":"$id"${if (deps.isEmpty()) "" else ""","dependencies":[$deps]"""}}"""
        return ExtensionPackager.build(id, mapOf("extension.json" to manifest.toByteArray(), "main.lua" to "-- x".toByteArray()))
    }

    @Test
    fun `manifest dependencies are parsed and carried as ids`() {
        val shop = pkg("shop", deps = """"ui_kit", {"id":"icons","version":">=1.2 <2"}""")
        assertEquals(listOf("ui_kit", "icons"), shop.dependencies.map { it.id })
        assertEquals(">=1.2 <2", shop.dependencies[1].range.toString())
        assertEquals(listOf("ui_kit", "icons"), shop.info.dependencies)

        assertThrows<InvalidExtensionException> { pkg("shop", deps = "\"shop\"") }
        assertThrows<InvalidExtensionException> { pkg("shop", deps = "\"a\", \"a\"") }
        assertThrows<InvalidExtensionException> { pkg("shop", deps = "\"Bad-Id\"") }
        assertThrows<InvalidExtensionException> { pkg("shop", deps = "1") }
        assertThrows<InvalidExtensionException> { pkg("shop", deps = """{"id":"a","version":"~>1"}""") }
        assertThrows<InvalidExtensionException> { pkg("shop", deps = (1..17).joinToString { "\"d$it\"" }) }
    }

    @Test
    fun `order puts dependencies first and keeps registration order otherwise`() {
        val shop = pkg("shop", deps = "\"ui_kit\"")
        val other = pkg("other")
        val kit = pkg("ui_kit", deps = "\"icons\"")
        val icons = pkg("icons")
        val resolution = ExtensionDependencies.resolve(listOf(shop, other, kit, icons))
        assertTrue(resolution.ok, resolution.problems.toString())
        assertEquals(listOf("other", "icons", "ui_kit", "shop"), resolution.order.map { it.id })
        // without dependencies the order is unchanged
        assertEquals(listOf("b", "a"), ExtensionDependencies.resolve(listOf(pkg("b"), pkg("a"))).order.map { it.id })
    }

    @Test
    fun `missing dependencies, version mismatches and cycles are problems`() {
        val missing = ExtensionDependencies.resolve(listOf(pkg("shop", deps = "\"ui_kit\"")))
        assertFalse(missing.ok)
        assertTrue(missing.problems.single().contains("'ui_kit', which is not registered"), missing.problems.toString())
        assertTrue(missing.order.isEmpty())

        val version = ExtensionDependencies.resolve(listOf(pkg("ui_kit", "2.1"), pkg("shop", deps = """{"id":"ui_kit","version":">=1 <2"}""")))
        assertTrue(version.problems.single().contains("version 2.1"), version.problems.toString())

        val cycle = ExtensionDependencies.resolve(
            listOf(pkg("a", deps = "\"b\""), pkg("b", deps = "\"d\""), pkg("d", deps = "\"a\""), pkg("free")),
        )
        assertTrue(cycle.problems.single().startsWith("Dependency cycle"), cycle.problems.toString())
        assertTrue(cycle.problems.single().contains("a -> b -> d -> a"), cycle.problems.toString())
    }

    @Test
    fun `version ranges`() {
        val range = VersionRange.parse(">=1.2 <2")
        assertTrue(range.matches("1.2"))
        assertTrue(range.matches("1.2.0"))
        assertTrue(range.matches("1.10.3"))
        assertFalse(range.matches("1.1.9"))
        assertFalse(range.matches("2.0.0"))
        assertTrue(VersionRange.parse("1.0.0").matches("1.0"))
        assertFalse(VersionRange.parse("=1.0.1").matches("1.0"))
        assertTrue(VersionRange.parse(">1.0.0-alpha").matches("1.0.0-beta"))
        assertThrows<IllegalArgumentException> { VersionRange.parse("") }
        assertThrows<IllegalArgumentException> { VersionRange.parse("~1") }
    }
}
