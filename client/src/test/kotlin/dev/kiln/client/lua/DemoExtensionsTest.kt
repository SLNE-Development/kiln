package dev.kiln.client.lua

import com.google.gson.JsonParser
import dev.kiln.client.ext.ExtensionPackage
import dev.kiln.client.lua.LuaScreenSession.KeyResult
import dev.kiln.client.lua.LuaScreenSession.Mods
import dev.kiln.client.lua.V2Host.Companion.item
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.luaj.vm2.LuaValue
import java.io.File

/**
 * The five ui_kit demo GUIs of the example server (`fabrik`, `questlog`, `warps`, `bestenliste`, `profil`) in the real
 * sandbox with the real `ui_kit`: loading, keybind, the main flows and the render budget.
 */
class DemoExtensionsTest {
    private val none = Mods(false, false, false)

    private fun extensionDir(id: String): File {
        val candidates = listOf(File("../example-server/src/main/resources/extensions/$id"), File("example-server/src/main/resources/extensions/$id"))
        return candidates.firstOrNull { it.isDirectory } ?: error("extension $id not found from ${File(".").absolutePath}")
    }

    /** Reads an extension folder like the server packs it (dependencies may be ids or {id, version} objects). */
    private fun realPackage(id: String): ExtensionPackage {
        val dir = extensionDir(id)
        val files = dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes() }
        val json = JsonParser.parseString(files.getValue("extension.json").toString(Charsets.UTF_8)).asJsonObject
        val permissions = json.getAsJsonArray("permissions").map { it.asString }.toSet()
        val deps = json.getAsJsonArray("dependencies")?.map { if (it.isJsonObject) it.asJsonObject["id"].asString else it.asString } ?: emptyList()
        return ExtensionPackage(id, json["version"].asString, json["name"].asString, "main.lua", permissions, files, deps)
    }

    private fun load(id: String): Pair<V2Host, LuaExtension> {
        val host = V2Host()
        host.load(realPackage("ui_kit"))
        val ext = host.load(realPackage(id))
        assertTrue(ext.alive, host.failures.toString())
        return host to ext
    }

    private fun lua(ext: LuaExtension, path: String): LuaValue = path.split('.').fold(ext.runtime.globals as LuaValue) { t, k -> t.get(k) }

    private fun frame(session: LuaScreenSession, mx: Int = 0, my: Int = 0, target: CountingTarget = CountingTarget()): CountingTarget {
        session.render(target, mx, my)
        return target
    }

    private fun click(session: LuaScreenSession, x: Int, y: Int, mods: Mods = none): Boolean {
        frame(session, x, y)
        val used = session.mouseClicked(x.toDouble(), y.toDouble(), 0, mods)
        session.mouseReleased(x.toDouble(), y.toDouble(), 0)
        frame(session, x, y)
        return used
    }

    /** Rectangle (x, y, w, h) of the last frame's region of widget [id] (with [data], if given). */
    private fun region(session: LuaScreenSession, id: String, data: Any? = null): IntArray {
        val ui = session.self.get("ui")
        val n = ui.get("n").toint()
        for (i in 1..n) {
            if (ui.get("rid").get(i).tojstring() != id) continue
            val d = ui.get("rdata").get(i)
            val matches = when (data) {
                null -> true
                is Int -> d.isnumber() && d.toint() == data
                else -> d.tojstring() == data.toString()
            }
            if (matches) return intArrayOf(ui.get("rx").get(i).toint(), ui.get("ry").get(i).toint(), ui.get("rw").get(i).toint(), ui.get("rh").get(i).toint())
        }
        error("no region $id/$data")
    }

    private fun clickRegion(session: LuaScreenSession, id: String, data: Any? = null, mods: Mods = none) {
        val r = region(session, id, data)
        click(session, r[0] + r[2] / 2, r[1] + r[3] / 2, mods)
    }

    /** Calls a Lua function of [ext] like the runtime does (budget, principal). */
    private fun callLua(ext: LuaExtension, path: String, vararg args: LuaValue): LuaValue =
        ext.invoke(lua(ext, path), LuaValue.varargsOf(args))!!.arg1()

    private fun lastSent(host: V2Host, channel: String) = host.sent.last { it.first == channel }.second.let { JsonParser.parseString(it).asJsonObject }

    /** Renders [frames] frames with a moving mouse and returns the max instructions of one frame. */
    private fun maxInstructions(ext: LuaExtension, session: LuaScreenSession, frames: Int = 300): Long {
        val target = CountingTarget()
        var maxUsed = 0L
        for (i in 0 until frames) {
            session.render(target, (i * 13) % 854, (i * 7) % 480)
            maxUsed = maxOf(maxUsed, ext.runtime.lastCallInstructions)
        }
        return maxUsed
    }

    private fun assertBudget(ext: LuaExtension, session: LuaScreenSession, host: V2Host) {
        session.layout(854, 480, 2.0)
        val used = maxInstructions(ext, session)
        val budget = ext.runtime.limits.instructionsPerFrame
        println("[benchmark] ${ext.id}: max $used instructions per frame (${100 * used / budget} % of $budget)")
        assertTrue(ext.alive, host.failures.toString())
        assertTrue(used < budget / 4, "${ext.id}: $used instructions per frame")
    }

    @Test
    fun `every demo loads, binds its own key and opens on the open message`() {
        val keys = mapOf("fabrik" to "key.keyboard.b", "questlog" to "key.keyboard.u", "warps" to "key.keyboard.v", "bestenliste" to "key.keyboard.n", "profil" to "key.keyboard.m")
        for ((id, key) in keys) {
            val (host, ext) = load(id)
            assertEquals(mapOf("$id:open" to key), host.keybinds, id)
            // the keybind asks the server to open the screen
            ext.onKey("open", true)
            assertEquals("$id:open" to "{}", host.sent.last(), id)
        }
        assertEquals(keys.size, keys.values.toSet().size, "different keys")
    }

    // ---- fabrik ---------------------------------------------------------------------------------------------

    private val factoryTypes = """[{"id":"SMELTER","label":"Hochofen","icon":"minecraft:blast_furnace","recipes":[
        {"id":"iron","label":"Eisenbarren","input":"minecraft:raw_iron","output":"minecraft:iron_ingot","seconds":4,"power":30},
        {"id":"glass","label":"Glas","input":"minecraft:sand","output":"minecraft:glass","seconds":2,"power":20}]}]"""

    private fun machine(id: Int, running: Boolean = true, priority: Int = 5, recipe: String = "iron", status: String = "RUNNING") =
        """{"id":$id,"name":"Ofen $id","type":"SMELTER","running":$running,"priority":$priority,"recipe":"$recipe","progress":0.5,"power":1.0,"demand":30,"produced":12,"status":"$status","statusLabel":"Läuft"}"""

    private fun factory(vararg machines: String) = """{"supply":120,"used":60,"maxPriority":10,"types":$factoryTypes,"machines":[${machines.joinToString(",")}]}"""

    @Test
    fun `fabrik - select a machine, toggle, priority on release, recipe, live updates`() {
        val (host, ext) = load("fabrik")
        ext.onChannelMessage("open", factory(machine(1), machine(2, running = false, status = "OFF")))
        val session = host.shown!!
        frame(session)
        assertEquals(1, lua(ext, "fabrik_debug.V.sel").toint())
        clickRegion(session, "machines", 2)
        assertEquals(2, lua(ext, "fabrik_debug.V.sel").toint())
        clickRegion(session, "power")
        val toggle = lastSent(host, "fabrik:set")
        assertEquals(2, toggle["machine"].asInt)
        assertTrue(toggle["running"].asBoolean)
        // slider: dragging changes only locally, letting go sends once
        val r = region(session, "priority")
        val sent = host.sent.size
        frame(session, r[0] + 4, r[1] + 5)
        session.mouseClicked((r[0] + 4).toDouble(), (r[1] + 5).toDouble(), 0, none)
        frame(session, r[0] + 4, r[1] + 5)
        frame(session, r[0] + r[2] - 2, r[1] + 5)
        frame(session, r[0] + r[2] - 2, r[1] + 5)
        assertEquals(sent, host.sent.size, "nothing sent while dragging")
        session.mouseReleased((r[0] + r[2] - 2).toDouble(), (r[1] + 5).toDouble(), 0)
        frame(session)
        val prio = lastSent(host, "fabrik:set")
        assertEquals(10, prio["priority"].asInt)
        assertEquals(sent + 1, host.sent.size)
        // recipe dropdown: open, pick the second recipe
        clickRegion(session, "recipe")
        clickRegion(session, "recipe", 2)
        assertEquals("glass", lastSent(host, "fabrik:set")["recipe"].asString)
        // a failed request shows an error toast; live state keeps the selection
        ext.onChannelMessage("result", """{"req":${prio["req"].asInt},"action":"set","ok":false,"error":"Nope"}""")
        ext.onChannelMessage("state", factory(machine(1), machine(2, priority = 3)))
        frame(session)
        assertEquals(2, lua(ext, "fabrik_debug.V.sel").toint())
        assertEquals(KeyResult.HANDLED, session.keyPressed("up", none))
        assertEquals(1, lua(ext, "fabrik_debug.V.sel").toint())
        assertEquals(KeyResult.CLOSE, host.escape())
        assertEquals("fabrik:closed" to "{}", host.sent.last())
        assertTrue(ext.alive, host.failures.toString())
    }

    @Test
    fun `fabrik - render budget`() {
        val (host, ext) = load("fabrik")
        ext.onChannelMessage("open", factory(*(1..12).map { machine(it) }.toTypedArray()))
        assertBudget(ext, host.shown!!, host)
    }

    // ---- questlog -------------------------------------------------------------------------------------------

    private fun quest(id: String, status: String, have: Int = 0, need: Int = 10) =
        """{"id":"$id","title":"Quest $id","category":"Sammeln","description":"Beschreibung von $id, lang genug für einen Umbruch im Detailbereich.",""" +
            """"status":"$status","objectives":[{"label":"Ziel","have":$have,"need":$need}],""" +
            """"rewards":[{"item":"${item("minecraft:bread")}","key":"minecraft:bread","count":8},{"key":"minecraft:iron_axe","count":1}],"xp":20}"""

    private fun quests(vararg q: String) = """{"maxActive":3,"quests":[${q.joinToString(",")}]}"""

    @Test
    fun `questlog - tabs, details with reward items, accept and abandon with confirmation`() {
        val (host, ext) = load("questlog")
        ext.onChannelMessage("open", quests(quest("a", "ACTIVE", 4), quest("b", "AVAILABLE"), quest("c", "COMPLETED", 10)))
        val session = host.shown!!
        val target = frame(session, 300, 100)
        assertTrue(target.items >= 2, "reward items drawn")
        assertEquals(1, lua(ext, "questlog_debug.V.tab").toint())
        assertEquals("a", lua(ext, "questlog_debug.V.sel.ACTIVE").tojstring())
        // reward slot shows the item tooltip
        val slotX = lua(ext, "questlog_debug.V.detailX").toint() + 8 + 5
        val slotY = lua(ext, "questlog_debug.V.rewardsAt").toint() + 5
        frame(session, slotX, slotY)
        val hover = frame(session, slotX, slotY)
        assertTrue(hover.tooltips.contains("item:minecraft:bread"), hover.tooltips.toString())
        // abandon -> confirmation -> request
        clickRegion(session, "abandon")
        assertEquals("abandon", lua(ext, "questlog_debug.V.confirm.action").tojstring())
        val sent = host.sent.size
        frame(session)
        clickRegion(session, "confirm", "yes")
        val req = lastSent(host, "questlog:abandon")
        assertEquals("a", req["quest"].asString)
        assertEquals(sent + 1, host.sent.size)
        ext.onChannelMessage("result", """{"req":${req["req"].asInt},"action":"abandon","ok":true,"message":"Quest abgebrochen"}""")
        assertTrue(lua(ext, "questlog_debug.V.confirm").isnil())
        assertEquals(3, lua(ext, "questlog_debug.V.tab").toint(), "follows the quest to its new tab")
        // available tab: accept, but cancel the confirmation with Escape
        ext.onChannelMessage("state", quests(quest("a", "AVAILABLE"), quest("b", "AVAILABLE"), quest("c", "COMPLETED", 10)))
        frame(session)
        clickRegion(session, "accept")
        assertFalse(lua(ext, "questlog_debug.V.confirm").isnil())
        assertEquals(KeyResult.HANDLED, host.escape())
        assertTrue(lua(ext, "questlog_debug.V.confirm").isnil())
        clickRegion(session, "accept")
        frame(session)
        clickRegion(session, "confirm", "yes")
        val accept = lastSent(host, "questlog:accept")
        assertEquals("a", accept["quest"].asString)
        frame(session)
        assertTrue(lua(ext, "questlog_debug.V.confirm.pending").toboolean(), "dialog waits for the answer")
        ext.onChannelMessage("result", """{"req":${accept["req"].asInt},"action":"accept","ok":true,"message":"Quest angenommen"}""")
        assertEquals(1, lua(ext, "questlog_debug.V.tab").toint())
        frame(session)
        // tab switch by click
        clickRegion(session, "tabs", 2)
        assertEquals(2, lua(ext, "questlog_debug.V.tab").toint())
        assertTrue(ext.alive, host.failures.toString())
    }

    @Test
    fun `questlog - accept is disabled with three active quests, render budget`() {
        val (host, ext) = load("questlog")
        val many = (1..3).map { quest("act$it", "ACTIVE", it) } + (1..30).map { quest("av$it", "AVAILABLE") }
        ext.onChannelMessage("open", quests(*many.toTypedArray()))
        val session = host.shown!!
        frame(session)
        clickRegion(session, "tabs", 3)
        frame(session)
        clickRegion(session, "accept")
        assertTrue(lua(ext, "questlog_debug.V.confirm").isnil(), "disabled button does nothing")
        assertBudget(ext, session, host)
    }

    // ---- warps ----------------------------------------------------------------------------------------------

    private fun warp(id: String, name: String, own: Boolean = false, favorite: Boolean = false) =
        """{"id":"$id","name":"$name","icon":"minecraft:compass","description":"Beschreibung $name","world":"world","x":10,"y":64,"z":-5,"own":$own,"favorite":$favorite}"""

    private val icons = """[{"id":"minecraft:red_bed","label":"Bett"},{"id":"minecraft:chest","label":"Truhe"}]"""

    private fun warps(vararg w: String, cooldown: Long = 0) =
        """{"cooldownMillis":10000,"cooldownLeft":$cooldown,"maxOwn":8,"nameMin":3,"nameMax":24,"icons":$icons,"warps":[${w.joinToString(",")}]}"""

    @Test
    fun `warps - search, favorites, teleport with cooldown, create with validation, delete with confirmation`() {
        val (host, ext) = load("warps")
        ext.onChannelMessage("open", warps(warp("spawn", "Spawn"), warp("markt", "Marktplatz", favorite = true), warp("own1", "Meine Base", own = true)))
        val session = host.shown!!
        frame(session)
        // favorites first
        assertEquals("markt", callLua(ext, "warps_debug.visible").get(1).get("id").tojstring())
        // search field with icon: typing filters locally, the clear button empties it
        val search = session.textInputs.single { it.id == "search" }.box as FakeBox
        search.type("base")
        frame(session)
        assertEquals(1, callLua(ext, "warps_debug.visible").length())
        frame(session)
        clickRegion(session, "search", "clear")
        frame(session)
        assertEquals("", lua(ext, "warps_debug.V.search").tojstring())
        assertEquals(3, callLua(ext, "warps_debug.visible").length())
        // star toggles the favorite
        clickRegion(session, "star", "spawn")
        val fav = lastSent(host, "warps:favorite")
        assertEquals("spawn", fav["warp"].asString)
        assertTrue(fav["on"].asBoolean)
        // teleport, then the cooldown blocks further teleports
        frame(session)
        clickRegion(session, "warp", "markt")
        val tp = lastSent(host, "warps:teleport")
        assertEquals("markt", tp["warp"].asString)
        ext.onChannelMessage("result", """{"req":${tp["req"].asInt},"action":"teleport","ok":true,"message":"Teleportiert: Marktplatz"}""")
        frame(session)
        val sent = host.sent.size
        clickRegion(session, "warp", "spawn")
        assertEquals(sent, host.sent.size, "cooldown: no request")
        host.clockSeconds += 11
        frame(session)
        clickRegion(session, "warp", "spawn")
        assertEquals("spawn", lastSent(host, "warps:teleport")["warp"].asString)
        // create: invalid name keeps the button disabled, a valid one sends the request
        clickRegion(session, "new")
        frame(session)
        assertTrue(session.textInputs.none { it.id == "search" }, "search hidden behind the modal")
        val name = session.textInputs.single { it.id == "create_name" }.box as FakeBox
        name.type("x!")
        frame(session)
        fun nameError(name: String) = callLua(ext, "warps_debug.nameError", LuaValue.valueOf(name))
        assertEquals("Mindestens 3 Zeichen.", nameError("x!").tojstring())
        clickRegion(session, "create_ok")
        assertTrue(host.sent.none { it.first == "warps:create" })
        assertEquals("Diesen Namen gibt es schon.", nameError("spawn").tojstring())
        assertEquals("Nur Buchstaben, Ziffern, Leerzeichen, _ und -.", nameError("Base!").tojstring())
        assertTrue(nameError("Höhle Süd").isnil(), "umlauts allowed")
        name.setValue("")
        name.type("Höhle Süd")
        frame(session)
        clickRegion(session, "create_icon", 2)
        clickRegion(session, "create_ok")
        val create = lastSent(host, "warps:create")
        assertEquals("Höhle Süd", create["name"].asString)
        assertEquals("minecraft:chest", create["icon"].asString)
        ext.onChannelMessage("result", """{"req":${create["req"].asInt},"action":"create","ok":false,"error":"Diesen Namen gibt es schon.","fields":{"name":"Diesen Namen gibt es schon."}}""")
        assertFalse(lua(ext, "warps_debug.V.create").isnil(), "dialog stays with the field error")
        assertEquals(KeyResult.HANDLED, host.escape())
        // delete own warp with confirmation
        frame(session)
        clickRegion(session, "del", "own1")
        frame(session)
        clickRegion(session, "delete", "yes")
        assertEquals("own1", lastSent(host, "warps:delete")["warp"].asString)
        assertTrue(ext.alive, host.failures.toString())
    }

    @Test
    fun `warps - render budget with many warps`() {
        val (host, ext) = load("warps")
        ext.onChannelMessage("open", warps(*(1..40).map { warp("w$it", "Warp Nummer $it", own = it % 5 == 0, favorite = it % 7 == 0) }.toTypedArray(), cooldown = 4000))
        assertBudget(ext, host.shown!!, host)
    }

    // ---- bestenliste ----------------------------------------------------------------------------------------

    private val boardMeta = """"categories":[{"id":"BLOCKS","label":"Abgebaute Blöcke","unit":"Blöcke"},{"id":"KILLS","label":"Mob-Kills","unit":"Kills"}],""" +
        """"periods":[{"id":"DAY","label":"Heute"},{"id":"WEEK","label":"Woche"},{"id":"ALL","label":"Gesamt"}]"""

    private fun board(n: Int, selfRank: Int, period: String = "ALL") =
        """{$boardMeta,"category":"BLOCKS","period":"$period","players":$n,"rows":[""" +
            (1..n).joinToString(",") { """{"rank":$it,"name":"Spieler$it","value":${(n - it + 1) * 100},"gain":${it % 3},"self":${it == selfRank}}""" } + "]}"

    @Test
    fun `bestenliste - header click sorts, own row highlighted, period and category query the server, chart`() {
        val (host, ext) = load("bestenliste")
        ext.onChannelMessage("open", board(30, 7))
        val session = host.shown!!
        frame(session)
        assertEquals("rank", lua(ext, "bestenliste_debug.V.sortKey").tojstring())
        // click the "Spieler" header: sorted by name ascending; again: descending
        clickRegion(session, "board", -2)
        assertEquals("name", lua(ext, "bestenliste_debug.V.sortKey").tojstring())
        assertFalse(lua(ext, "bestenliste_debug.V.sortDesc").toboolean())
        clickRegion(session, "board", -2)
        assertTrue(lua(ext, "bestenliste_debug.V.sortDesc").toboolean())
        val sorted = session.self.get("ui").get("state").get("board").get("sorted")
        assertEquals("Spieler9", sorted.get(1).get("name").tojstring())
        // "Wert" sorts descending first
        clickRegion(session, "board", -3)
        assertEquals("value", lua(ext, "bestenliste_debug.V.sortKey").tojstring())
        assertTrue(lua(ext, "bestenliste_debug.V.sortDesc").toboolean())
        assertEquals(7, lua(ext, "bestenliste_debug.M.me.rank").toint())
        // clicking a row selects it
        clickRegion(session, "board", 1)
        assertEquals("Spieler1", lua(ext, "bestenliste_debug.V.selName").tojstring())
        // period tab -> query
        clickRegion(session, "period", 1)
        val q = lastSent(host, "bestenliste:query")
        assertEquals("DAY", q["period"].asString)
        assertEquals("BLOCKS", q["category"].asString)
        assertTrue(lua(ext, "bestenliste_debug.V.loading").toboolean())
        ext.onChannelMessage("state", board(30, 7, period = "DAY"))
        assertFalse(lua(ext, "bestenliste_debug.V.loading").toboolean())
        // category dropdown -> query
        frame(session)
        clickRegion(session, "category")
        clickRegion(session, "category", 2)
        assertEquals("KILLS", lastSent(host, "bestenliste:query")["category"].asString)
        // chart view draws bars
        clickRegion(session, "view", 2)
        assertEquals(2, lua(ext, "bestenliste_debug.V.view").toint())
        val chart = frame(session)
        assertTrue(chart.fills > 20, "bars drawn")
        assertTrue(ext.alive, host.failures.toString())
    }

    @Test
    fun `bestenliste - render budget with 51 visible rows`() {
        val (host, ext) = load("bestenliste")
        ext.onChannelMessage("open", board(51, 51))
        val session = host.shown!!
        session.layout(854, 1000, 2.0)
        val used = maxInstructions(ext, session)
        println("[benchmark] bestenliste table (51 rows visible): max $used instructions per frame")
        assertTrue(used < ext.runtime.limits.instructionsPerFrame / 4, "$used")
        assertTrue(ext.alive, host.failures.toString())
    }

    // ---- profil ---------------------------------------------------------------------------------------------

    private val profile = """{"name":"Steve","settings":{"nickname":"","nickColor":"white","status":"","chatFilter":"MILD","volume":80,""" +
        """"notifications":true,"privateMessages":true,"compactChat":false},"defaults":{"nickname":"","nickColor":"white","status":"",""" +
        """"chatFilter":"MILD","volume":80,"notifications":true,"privateMessages":true,"compactChat":false},""" +
        """"colors":[{"id":"white","label":"Weiß","color":4294967295},{"id":"gold","label":"Gold","color":4294945280}],""" +
        """"filters":[{"id":"OFF","label":"Aus"},{"id":"MILD","label":"Mild"},{"id":"STRICT","label":"Streng"}],""" +
        """"nickMin":3,"nickMax":16,"statusMax":40,"volumeStep":5}"""

    @Test
    fun `profil - form with validation, dirty tracking, save and the unsaved-changes dialog`() {
        val (host, ext) = load("profil")
        ext.onChannelMessage("open", profile)
        val first = host.shown!!
        frame(first)
        assertFalse(callLua(ext, "profil_debug.dirty").toboolean())
        assertEquals(KeyResult.CLOSE, host.escape(), "nothing changed: Escape closes")
        ext.onChannelMessage("open", profile)
        val session = host.shown!!
        frame(session)
        val boxes = session.textInputs.associate { it.id to it.box as FakeBox }
        boxes.getValue("nickname").type("x")
        frame(session)
        assertTrue(callLua(ext, "profil_debug.dirty").toboolean())
        val errors = callLua(ext, "profil_debug.validate", lua(ext, "profil_debug.V.edit"))
        assertEquals("Mindestens 3 Zeichen (oder leer lassen).", errors.get("nickname").tojstring())
        clickRegion(session, "save")
        assertTrue(host.sent.none { it.first == "profil:save" }, "invalid: not sent")
        boxes.getValue("nickname").type("_Bau")
        frame(session)
        clickRegion(session, "notifications")
        clickRegion(session, "nickColor")
        clickRegion(session, "nickColor", 2)
        frame(session)
        // Escape with changes asks instead of closing
        assertEquals(KeyResult.HANDLED, host.escape())
        assertTrue(lua(ext, "profil_debug.V.ask").toboolean())
        frame(session)
        frame(session) // the modal blocks fields from the frame after it was first drawn
        assertTrue(session.textInputs.isEmpty(), "fields hidden behind the dialog")
        clickRegion(session, "ask_back")
        assertFalse(lua(ext, "profil_debug.V.ask").toboolean())
        frame(session)
        clickRegion(session, "save")
        val save = lastSent(host, "profil:save")
        val settings = save["settings"].asJsonObject
        assertEquals("x_Bau", settings["nickname"].asString)
        assertEquals("gold", settings["nickColor"].asString)
        assertFalse(settings["notifications"].asBoolean)
        assertEquals(80, settings["volume"].asInt)
        // server field error is shown, then success clears "dirty"
        ext.onChannelMessage("result", """{"req":${save["req"].asInt},"action":"save","ok":false,"error":"Bitte prüfe","fields":{"nickname":"Vergeben"}}""")
        assertEquals("Vergeben", lua(ext, "profil_debug.V.fields.nickname").tojstring())
        frame(session)
        clickRegion(session, "save")
        val again = lastSent(host, "profil:save")
        ext.onChannelMessage("result", """{"req":${again["req"].asInt},"action":"save","ok":true,"message":"Gespeichert"}""")
        assertFalse(callLua(ext, "profil_debug.dirty").toboolean())
        // defaults + discard via the dialog closes the screen
        frame(session)
        clickRegion(session, "defaults")
        assertTrue(callLua(ext, "profil_debug.dirty").toboolean())
        assertEquals(KeyResult.HANDLED, host.escape())
        frame(session)
        clickRegion(session, "ask_discard")
        assertEquals(null, host.shown)
        assertEquals("profil:closed" to "{}", host.sent.last())
        assertTrue(ext.alive, host.failures.toString())
    }

    @Test
    fun `profil - render budget`() {
        val (host, ext) = load("profil")
        ext.onChannelMessage("open", profile)
        assertBudget(ext, host.shown!!, host)
    }

    @Test
    fun `ui_kit modal - Escape goes to the app, Shift+Escape and repeated Escape always close`() {
        val (host, ext) = load("profil")
        ext.onChannelMessage("open", profile)
        val session = host.shown!!
        frame(session)
        (session.textInputs.first { it.id == "nickname" }.box as FakeBox).type("x")
        frame(session)
        // unsaved changes: Escape opens the modal instead of closing
        assertEquals(KeyResult.HANDLED, host.escape())
        assertTrue(lua(ext, "profil_debug.V.ask").toboolean())
        frame(session)
        assertEquals(KeyResult.CLOSE, host.escape(shift = true), "Shift+Escape leaves the screen even with the modal open")
        assertNull(host.shown)

        // the same with three quick presses
        host.clockSeconds += 20.0
        ext.onChannelMessage("open", profile)
        val again = host.shown!!
        frame(again)
        (again.textInputs.first { it.id == "nickname" }.box as FakeBox).type("y")
        frame(again)
        assertEquals(KeyResult.HANDLED, host.escape())
        frame(again)
        assertEquals(KeyResult.HANDLED, host.escape())
        frame(again)
        assertEquals(KeyResult.CLOSE, host.escape())
        assertNull(host.shown)
        assertTrue(ext.alive, host.failures.toString())
    }
}
