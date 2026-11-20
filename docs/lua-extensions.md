# Lua-Extensions – Spezifikation

Ersetzt die Kotlin-Client-Erweiterungen. Visueller Entwurf: `docs/lua-extensions.html`.

## Grundsätze
- Es gibt **keine Client-Erweiterungen in Kotlin/Java mehr**. Module `client-api` und `example-client` entfallen.
  Der Client ist nur noch die Runtime-Mod `kiln` (Fabric, einmal installiert).
- Alles Client-seitige eines Servers kommt als **Extension-Paket vom Server**: Manifest, Lua-Skripte, Texturen
  (PNG), optional Sprachdateien.
- **Keine Fallbacks.** Spieler ohne Runtime, mit falscher Protokollversion, abgelehnter Zustimmung oder
  fehlgeschlagenem Download/Laden werden mit verständlicher Meldung gekickt (Neuverbinden versucht es erneut).
- Lua läuft in einer **Sandbox** ohne Zugriff auf Java, Minecraft-Objekte, Dateien oder Netzwerk. Alles geht über die
  `kiln`-API.

## Paket-Format
Ordner oder Zip, Server-seitig unter `plugins/Kiln/extensions/<id>/` bzw. `<id>.zip`, oder von einem Plugin
per API registriert (z. B. aus den Ressourcen seines Jars).

```
<id>/extension.json
<id>/*.lua            (main = Einstieg, weitere per kiln.require("datei"))
<id>/textures/**.png
<id>/lang/<locale>.json   (optional)
```

`extension.json`: `id` (`[a-z0-9_]{1,32}`), `version`, `name`, `main`, `permissions` (Teilmenge von `widgets`,
`keybinds`, `hud`, `channels`, `toasts`, `screens`, `inventory`), optional `dependencies` (siehe „Lua-API v2“), optional `icon` (PNG im Paket, ≤ 16 KiB, ≤ 64×64; im Zustimmungs-Screen).
Der Server baut daraus ein deterministisches Zip (feste Reihenfolge, Zeit 0)
und dessen SHA-256.

## Auslieferung (Configuration-Phase, vor dem Betreten der Welt)
Bevorzugt in der **Configuration-Phase**, damit der Spieler die Welt erst betritt, wenn alles geladen ist
(Paper: `AsyncPlayerConnectionConfigureEvent` o. ä. prüfen; Fabric: `ClientConfigurationNetworking`). Wenn das in 26.3
nicht sauber geht: in der Play-Phase direkt nach dem Join, Spieler bis zum Abschluss eingefroren/unsichtbar halten.

1. Server → `Welcome` + `ExtensionManifest(list of {id, version, name, sha256, size, permissions})`.
2. Client prüft Cache (`<gameDir>/kiln/cache/<sha256>.zip`), fordert fehlende an: `ExtensionRequest(ids)`.
3. Server streamt `ExtensionChunk(id, index, total, bytes)` (Chunk ≤ 512 KiB, Paket gesamt ≤ 4 MiB).
4. Client prüft SHA-256, speichert im Cache.
5. **Zustimmung**: einmal pro Server-Adresse und Satz (id+permissions): Screen mit Namen und Rechten.
   Ablehnen → Client trennt die Verbindung. Zustimmung persistiert in `config/kiln-trust.json`.
6. Client lädt alle Extensions (Texturen registrieren, Lua starten). Ergebnis: `ExtensionsReady(ok, error?)`.
7. Server wartet bis `ExtensionsReady(ok)` (Timeout z. B. 60 s), sonst Kick mit Grund.

Beim Disconnect: alle Lua-Umgebungen beenden, Widgets, Keybinds, HUD-Layer, Kanäle, Texturen und Sprachschlüssel
entfernen.

## Entscheidungen

Verbindlich für Client und Server ab `PROTOCOL_VERSION = 4` (v4: Zustimmung **vor** dem Download, Icon im Manifest).
**Aktuell `PROTOCOL_VERSION = 5`** (v5: `dependencies` im Manifest, Rechte `screens`/`inventory`, Server→Client-
`ExtensionMessage` bis 262 144 Zeichen; Details im Abschnitt „Lua-API v2“). Die Versionsnummern in den Beispielen
unten (`Hello(4)`, `Welcome(4)`) gelten sinngemäß mit 5.

### (a) Auslieferung in der Configuration-Phase: ja
Belege (paper-api `26.3.build.256-stable`, Server-Jar `run/server/versions/26.3/paper-26.3.jar`):
- `io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent` ist asynchron; die
  Verbindung wird erst nach Rückkehr aller Handler fortgesetzt („before the player has entered the world“). Der
  Server blockiert dort (eigener Thread) bis zum Abschluss oder Kick.
- `PlayerConfigurationConnection` erbt über `PlayerCommonConnection` `PluginMessageRecipient` → `sendPluginMessage`
  in der Configuration-Phase. Paper (`PaperPlayerConfigurationConnection.sendPluginMessage`) sendet aber **nur**, wenn
  der Kanal in den per `minecraft:register` angemeldeten Kanälen des Clients steht (sonst still verworfen);
  `minecraft:register` wird in `ServerCommonPacketListenerImpl` auch in der Configuration-Phase ausgewertet.
- Empfang: `PluginMessageListener.onPluginMessageReceived(channel, PlayerConnection, bytes)` wird laut
  `StandardMessenger.dispatchIncomingMessage(PlayerConnection, …)` auch für Spieler in der Configuration-Phase
  aufgerufen.
- Kick: `PlayerConnection.disconnect(Component)`.

Spieler ohne erfolgreichen Abschluss betreten die Welt nie; eine Play-Phase mit eingefrorenem Spieler gibt es nicht.

### (b) Kanal und Ablauf
Ein Kanal für alles: `kiln:main` (Fabric: Payload-Typ für **Configuration und Play**, je C2S und S2C, als
`DiscardedPayload`-artiges Byte-Array; Codec `MessageCodec`). Der Client muss `kiln:main` in **beiden** Phasen
per `minecraft:register` anmelden. In der Configuration-Phase schickt Fabric sein `register` nur als Antwort auf ein
`register` des Servers, das Paper dort nicht sendet – der Client sendet es deshalb selbst vor `Hello`.

Configuration-Phase (alle Nachrichten auf `kiln:main`):
1. C→S `Hello(protocolVersion = 4, extensions = [])` – sobald der Client `kiln:main` angemeldet hat
   (Fabric: z. B. `ClientConfigurationConnectionEvents.START`/`READY` bzw. sobald `canSend` wahr ist; `register`
   muss **vor** `Hello` gesendet sein). Server wartet höchstens 10 s auf `Hello`.
2. Server prüft Version. Mismatch → S→C `Welcome(4)`, dann Kick. Sonst prüft er, dass `kiln:main`
   angemeldet ist (wartet bis 5 s), dann S→C `Welcome(4)` und `ExtensionManifest(list)` (Ladereihenfolge). Jeder
   Eintrag enthält alles für den Zustimmungs-Screen: id, name, version, permissions, size, sha256 und optional
   `icon` (PNG-Bytes aus `"icon"` im `extension.json`, ≤ 16 KiB, ≤ 64×64, vom Server beim Packen geprüft).
3. **Zustimmung** auf dem Client (Screen, übersprungen wenn Server-Adresse + (id, permissions) schon vertraut sind).
   Bis hierhin wurde nichts heruntergeladen. Ablehnung → Client trennt selbst.
   Nach Zustimmung C→S genau einmal `ExtensionRequest(ids)` (nur nicht gecachte Pakete, evtl. leer; nur IDs aus dem
   Manifest). Der Server sendet **keinen** Chunk vor diesem Request.
4. S→C für jede angeforderte ID in Manifest-Reihenfolge alle `ExtensionChunk(id, index, total, bytes)`
   (je ≤ 512 KiB, alle außer dem letzten genau 512 KiB, `total = ceil(size / 512 KiB)`).
5. Client prüft Größe + SHA-256, speichert `<gameDir>/kiln/cache/<sha256hex>.zip`, prüft und startet die
   Extensions (Ladebildschirm mit Fortschritt, Abbrechen trennt). Dann C→S `ExtensionsReady(ok, error?)`.
6. `ok = true` → Configuration endet, Spieler betritt die Welt. `ok = false` → Kick mit `error`.

**Zeitlimits** (getrennt, `config.yml`): `ExtensionManifest` → `ExtensionRequest` (Zustimmung) 300 s
(`consent-timeout-seconds`); `ExtensionRequest` → `ExtensionsReady` (Download + Laden) 60 s
(`extensions-timeout-seconds`, bei Drossel verlängert, siehe (c)). Der Zustimmungs-Screen zeigt die verbleibende Zeit.

Andere Nachrichten in der Configuration-Phase sind Protokollfehler (Kick). Erst in der Play-Phase: `Viewport`,
`DeclareKeybinds`, Screens, Toasts, Tasten, `ExtensionMessage`. Der Client meldet in der Play-Phase kein erneutes
`Hello`; der Server legt die Session beim Join an und sendet `DeclareKeybinds`, sobald der Client
`kiln:main` in der Play-Phase angemeldet hat. Rekonfiguration (Paper `PlayerConnectionReconfigureEvent`)
startet keinen neuen Handshake.

**Kick-Gründe** (Text im Disconnect-Screen, deutsch): kein `Hello` innerhalb 10 s („Runtime fehlt“),
Protokoll-Mismatch (Server- und Client-Version), Kanal nicht angemeldet, ungültige/unerwartete Nachricht,
Zeitüberschreitung der Zustimmung (300 s: „Keine Antwort auf die Zustimmungsabfrage für die Server-Erweiterungen
(Zeit abgelaufen) …“) bzw. von Download/Laden (60 s: „… wurden nicht rechtzeitig geladen (Zeitüberschreitung) …“),
`ExtensionsReady(false, error)` (mit `error`), Server-Fehler beim Paketbau, Join ohne abgeschlossenen Handshake. Ein eigenes `Kick`-Paket gibt es nicht; der Grund steht im Vanilla-Disconnect.

**Paket-Zip (vom Server gebaut):** Dateien liegen im Zip **an der Wurzel** (`extension.json`, `main.lua`,
`textures/…`), ohne `<id>/`-Präfix. Einträge sortiert nach Pfad, Zeitstempel 1980-01-01 00:00 (DOS-Minimum),
DEFLATE Stufe 9, keine Verzeichniseinträge, keine Extra-Felder. Erlaubt: `.lua`, `.png`, `.json`; Pfade nur
`[a-z0-9_.-]` je Segment, `/` als Trenner, kein `..`, keine leeren Segmente; höchstens 256 Dateien, entpackt
zusammen ≤ 16 MiB, Zip ≤ 4 MiB. `extension.json`: `id` (`[a-z0-9_]{1,32}`), `version` (1–64 Zeichen), `name`
(1–128), `main` (Default `main.lua`, muss existieren, `.lua`), `permissions` (Teilmenge, ohne Duplikate; im
Manifest kanonisch sortiert: `widgets, keybinds, hud, channels, toasts, screens, inventory`). Unbekannte Felder (z. B. `assets`) werden
ignoriert. Der Client muss die Zip-Inhalte trotzdem selbst gegen diese Regeln prüfen.

**Namensräume:** Textur `textures/a/b.png` im Paket `x` → Identifier `x:textures/a/b.png` (genau der Pfad im
Paket). Widget `kiln.ui.widget("x:gauge", …)` ↔ Server `custom(…, Key("x", "gauge"))`. Kanal
`kiln.net.channel("hello")` in `x` ↔ `ExtensionMessage` mit Kanal `x:hello`. Namen von Kanälen/Widgets:
`[a-z0-9_]{1,32}`.

**Reservierte Namensräume:** Die Id ist Namensraum der Texturen und Präfix der Sprachschlüssel (`x.…`); eine
Extension mit fremdem Namensraum könnte Texturen und Texte des Spiels, des Loaders oder von kiln ersetzen (z. B. den
Zustimmungs-Screen fälschen). Abgelehnt werden deshalb (gemeinsame Liste `Ids.RESERVED_NAMESPACES` im
`protocol`-Modul): `minecraft`, `realms`, `mojang`, `brigadier`, `java`, `kiln`, `fabric` und alles mit Präfix
`fabric`, `c`, `forge`, `neoforge`, `quilt` und alles mit Präfix `quilt`.
- **Server:** beim Registrieren (`ExtensionPackager.build`): API → `IllegalArgumentException`, Ordner-Paket → SEVERE-Log
  und übersprungen.
- **Client:** beim Empfang des Manifests, vor Zustimmung und Download (`ExtensionHandshake.onManifest`), zusätzlich
  Ids installierter Fabric-Mods (`FabricLoader.isModLoaded`) → `ExtensionsReady(false, Grund)` → Kick; beim Lesen des
  Pakets und beim Laden noch einmal. Beim Laden wird außerdem jede Textur abgelehnt, deren Identifier schon im
  `TextureManager` registriert ist oder als Ressource existiert, und jeder Sprachschlüssel, den die Sprache schon
  kennt (außer von derselben Extension, `en_us` wird von der gewählten Sprache überschrieben) → Ladefehler. So kann
  z. B. eine Extension `gui` nicht `gui.done` umbenennen.

**`ExtensionMessage(channel, json)`** (Play-Phase, beide Richtungen): `json` ist genau ein JSON-Wert als Text
(Client→Server ≤ 16 384 Zeichen, Server→Client ≤ 262 144 Zeichen seit v5; Lua-Tabellen i. d. R. als Objekt). Verschachtelungstiefe ≤ 16. Server: höchstens 40
Nachrichten/s pro Spieler (Überschuss verworfen), ungültiges JSON wird verworfen und geloggt (Debug).
Nachrichten an Kanäle ohne Server-Handler werden verworfen.

### (c) Bandbreiten-Drossel für die Auslieferung
`extension-send-rate-kib-per-second` in `config.yml` (Default `0` = unbegrenzt) begrenzt pro Spieler die Rate, mit
der die `ExtensionChunk`s gesendet werden (`SendThrottle`, reine Funktion, getestet).
- **Wo gewartet wird:** im Thread des `AsyncPlayerConnectionConfigureEvent` dieses Spielers, der ohnehin bis
  `ExtensionsReady` blockiert (eigener Thread pro Verbindung) – nie im Server-Thread, kein Scheduler-Task nötig. Vor
  jedem Chunk wartet der Handshake, bis seit Download-Beginn `bereits gesendete Bytes / Rate` vergangen ist (erster
  Chunk sofort, danach z. B. bei 1024 KiB/s alle 500 ms ein 512-KiB-Chunk), in Schritten ≤ `pollMillis` (250 ms), damit
  eine getrennte Verbindung sofort bemerkt wird. Chunkgröße und -format bleiben unverändert (Protokoll unberührt).
- **Thread beim Senden:** `PlayerConfigurationConnection.sendPluginMessage` (Paper 26.3,
  `PaperPlayerConfigurationConnection`) prüft nur Kanal-Anmeldung und ruft `ServerConfigurationPacketListenerImpl.send`
  → `Connection.send` auf; das ist threadsicher (außerhalb des Netty-Event-Loops wird an ihn übergeben), es gibt keinen
  Async-Catcher. Senden aus dem Handshake-Thread ist daher zulässig (so wurden schon Manifest und Chunks gesendet).
- **Zeitlimit:** Das Download-Zeitlimit (`ExtensionRequest` → `ExtensionsReady`) wird pro Spieler um die Zeit
  verlängert, die die Drossel selbst für die angeforderten Pakete braucht: `extensions-timeout-seconds +
  angeforderte Bytes / Rate`. Die konfigurierten 60 s bleiben so für Netzwerk, Prüfung und Laden; eine niedrige Rate
  kann nicht allein zum Timeout-Kick führen. Gecachte Pakete zählen nicht mit. Beim Start loggt das Plugin die
  Dauer eines vollständigen Downloads.
- Test-Server (`run/server`): `0` (unbegrenzt), keine Ordner-Extensions (nur die des Beispiel-Plugins). Zum
  Ausprobieren der Drossel: die 30 Test-Extensions aus `tools/gen_test_extensions.py` (≈ 14,5 MiB) brauchen bei
  1024 KiB/s ohne Cache ≈ 15 s.

## Lua-Sandbox (Client)
- LuaJ (reines Java), pro Extension eigene Globals. Erlaubt: `string` (ohne `dump`), `math`, `table`, `pairs`,
  `ipairs`, `next`, `select`, `type`, `tostring`, `tonumber`, `pcall`, `error`, `assert`, `setmetatable`,
  `getmetatable` (ohne `__metatable`-Umgehung von Strings). Entfernt: `io`, `os`, `debug`, `luajava`, `load`,
  `loadstring`, `dofile`, `loadfile`, `require` (ersetzt durch `kiln.require` nur innerhalb des Pakets),
  `collectgarbage`, `coroutine` optional.
- **Limits**: Instruktionsbudget pro Aufruf (Debug-Hook bzw. LuaJ-Interrupt), Speicher grob über Allokationszähler
  oder Tabellen-/String-Größenlimits, Fehler im Skript → Extension wird entladen, Spieler bekommt Toast; bei Fehler
  beim Laden → Kick.
- Rechte aus dem Manifest werden durchgesetzt (API-Teile ohne Recht sind `nil`).

## Lua-API (v1)
```
kiln.ui.widget(type, { init, render(self, g, w), click(self, w, button) -> bool, press, release, scroll })
   -- type "<ext>:<name>"; vom Server per custom("…", Key("<ext>", "<name>")) benutzt
kiln.ui.tooltip(text) ; kiln.ui.toast(title, text) ; kiln.ui.lerpColor(a, b, t)
kiln.hud.layer(id, { anchor, width, height }, function(g, area) … end)
kiln.keys.bind(id, { name, default = "J", press, release })
kiln.net.channel(name) -> ch ; ch:send(table) ; ch:on(function(msg) … end)
kiln.time() ; kiln.player() -> { name, x, y, z } (Kopie) ; kiln.require(file)
Zeichenkontext g: rect, outline, line, arc, text(str, x, y, {align,color,shadow}), texture(id, x, y, w, h, u, v, rw, rh, tw, th), item(itemId, x, y)
w: width, height, props (Tabelle), hovered, mouseX, mouseY (relativ)
```
Kanal-Nachrichten: Lua-Tabelle ↔ JSON (begrenzt in Größe, Tiefe, Rate). Kanal-ID `<ext>:<name>`.

## Server-API (Kotlin)
```kotlin
// Kiln.registerExtension(plugin, id, source: ExtensionSource): Extension
//   ExtensionSource.Directory(path) | Zip(path) | PluginResource(plugin, "extensions/<id>") | ExtensionSource.of(path)
val ext = registerExtension("kiln_example")            // Kurzform in JavaPlugin: Ressourcen extensions/<id>/
val hello = ext.channel<HelloMessage>("hello") { player, msg -> // Receiver ExtensionChannel<T>, Main-Thread
    send(player, HelloMessage("Hallo zurück"))                  // JSON via Gson
}
hello.send(player, HelloMessage("…")); hello.broadcast(msg); ext.channel<State>("machine") // nur senden
custom("gauge", ext.widget("gauge"), width = 40, height = 40) { property("value", …) }
image(Key.key(ext.id, "textures/factory_icon.png"), …)          // Textur aus dem Paket
```
`Kiln.extension(id)`, `extensions()`; `Extension.id/version/name/permissions/plugin/sha256/size/unregister()`.
Ordner-Pakete unter `plugins/Kiln/extensions/` werden beim Start geladen (ungültige: Log + übersprungen),
API-Registrierungen werfen bei ungültigen Paketen `IllegalArgumentException`. Registrierung gilt für danach
verbindende Spieler. Entfernt wurden `isConnected`, `connectedPlayers`, `extensions(player)`,
`sendExtensionMessage`/`registerExtensionListener` (ByteArray) und die Connect-/VersionMismatch-Events:
jeder Spieler in der Welt hat Runtime und alle Extensions. `openScreen` liefert immer einen Handle (wirft nur für
Offline-Spieler), `showToast` gibt nichts zurück. Server-Keybinds (`registerKeybind`) bleiben.

Das Beispiel `example-server/src/main/resources/extensions/kiln_example/main.lua` nutzt
`g:arc(cx, cy, radius, thickness, from, to, color)` wie im Entwurf: oberer Halbkreis um (cx, cy), `from`/`to` als
Anteil 0..1 von links nach rechts; Farben als ARGB-Zahlen (z. B. `0xFF3C3C3C`); `w.props.*` sind Strings.

## Client-Umsetzung (Runtime `kiln`)

**Sandbox** (`client/.../lua/LuaSandbox.kt`, LuaJ 3.0.1 = Lua 5.2): pro Extension eigene `Globals` mit nur `assert
error getmetatable ipairs next pairs pcall print select setmetatable tonumber tostring type _G _VERSION`, `string`
(ohne `dump`), `table`, `math`. Nicht geladen bzw. entfernt: `io os debug luajava package require load loadstring
dofile loadfile collectgarbage coroutine rawget rawset rawequal rawlen xpcall unpack`. Kein Undumper → kein Bytecode;
Quellen mit Bytecode-Signatur werden abgelehnt. LuaJs String-Metatable ist JVM-weit statisch: sie wird einmal durch
eine schreibgeschützte Tabelle mit `__metatable = "string"` ersetzt (`getmetatable("")` liefert `"string"`), deren
`__index` eine schreibgeschützte Kopie der String-Bibliothek ist; `string` ist pro Extension eine eigene Kopie.
`print` landet im Client-Log.

**Limits** (`LuaLimits`): Instruktionen gezählt über `Globals.debuglib` (eigener Hook ohne Lua-Bindung, LuaJ ruft
ihn vor jeder VM-Instruktion): 100 000 pro Aufruf, 2 000 000 beim Laden, 6 000 000 pro Sekunde und Extension.
Überschreitung wirft einen `Error` (von `pcall` nicht fangbar). Aufruftiefe 200 (auch Tail-Calls, siehe „Kein Ausleihen von Rechten“). Strings ≤ 1 MiB (geprüft vor
`..`, `string.rep`, `gsub`, `table.concat`; `format` danach), `table.unpack` ≤ 4096 Werte, Lua-Speicher ≈ 16 MiB
(Schätzung über erreichbare Tabellen/Closures/Strings, alle 1 M Instruktionen). Bekannte Lücke: LuaJs
Pattern-Matching läuft in Java und wird nicht gezählt.

**Lua-API-Details:** `ui.widget("<ext>:<name>" oder "<name>", def)` nur im eigenen Namensraum; Callbacks
`init(self)`, `render(self, g, w)`, `press(self, w, mx, my, button)`, `click(self, w, button) -> bool` (fehlt →
Klick wird gemeldet), `release(self, w, mx, my, button)` (an das Widget, auf dem gedrückt wurde),
`scroll(self, w, mx, my, amount) -> bool`; Buttons 0/1/2 = links/rechts/Mitte; `self` bleibt pro Element-ID über
`UpdateScreen` erhalten. `w.props` sind Strings. `g:text(...)` gibt die Breite zurück; `g:texture(id, x, y, w, h)`
ohne u/v/rw/rh/tw/th zeichnet die ganze Textur (Größe aus dem PNG), `id` ohne Namensraum = eigene Extension;
`g:item(id, x, y [, size])`; `g:line(x1, y1, x2, y2, color [, thickness])`. Zeichnen ist auf Widget/HUD-Bereich
geclippt; `g` außerhalb eines Render-Aufrufs ist ein Fehler. `hud.layer` Anker: `top_left top top_right left
center right bottom_left bottom bottom_right` (4 px Rand, Größe ≤ 512), gezeichnet unter allen Vanilla-HUD-Elementen.
`keys.bind` nur während des Ladens; `default` `"J"` → `key.keyboard.j`, volle Namen (`key.mouse.middle`) erlaubt.
`ch:send(t)` → `false` bei > 40/s oder außerhalb der Play-Phase; ungültige Tabellen (gemischte Schlüssel, Lücken,
Funktionen, NaN, Zyklen, Tiefe > 16, > 16 384 Zeichen) sind Lua-Fehler; leere verschachtelte Tabelle → `[]`.
`ui.toast` höchstens 5 pro 10 s. Grenzen: 64 Widget-Typen, 16 HUD-Layer, 32 Keybinds, 32 Kanäle, 8 Handler/Kanal.
`kiln.require("lib/util")` lädt `lib/util.lua` einmal (Ergebnis gecacht, Zyklen sind Fehler).

**Fehler:** beim Laden → `ExtensionsReady(false, Grund)`; zur Laufzeit → Extension entladen (Widgets werden
Platzhalter, HUD/Keybinds weg), Toast „Erweiterung … entladen“.

**Auslieferung:** Zustandsmaschine `ExtensionHandshake` (rein, getestet): Manifest → Zustimmung → Request → Chunks
→ pro Tick ein Paket prüfen bzw. eine Extension starten → `ExtensionsReady`. Kanal-Anmeldung in der
Configuration-Phase macht der Client selbst (`minecraft:register` vor `Hello`). Pakete werden nur im Speicher
entpackt (Cache speichert das Zip unter `<sha256>.zip`), Pfade/Endungen/Größen werden geprüft, PNG-Größe ≤ 2048² vor
dem Dekodieren; vor der Zustimmung wird nur das Manifest-Icon dekodiert (≤ 16 KiB, ≤ 64×64). `extension.json` muss
zu id, name, version und permissions des Manifests passen. Server-Adresse = `ServerData` der Verbindung
(`Minecraft.getCurrentServer()` ist in der Configuration-Phase noch leer).

**Zustimmungs-Screen:** scrollbare Accordion-Liste (Icon oder neutrales Standard-Icon, Name, Chevron, das beim
Auf-/Zuklappen animiert um 90° dreht; aufgeklappt Version, interner Name und alle Rechte mit grünem Haken
(angefordert) bzw. rotem X); „Zulassen“/„Ablehnen und trennen“ fest unten, Escape = Ablehnen; Restzeit des Servers.
Gespeichert in `config/kiln-trust.json` (pro Server-Adresse Liste (id, permissions); gefragt wird, sobald ein
Paar fehlt). **Ladebildschirm:** Servername, Gesamtfortschritt (Prozent, Bytes), pro Extension Icon, Name und Status
(aus dem Cache / lädt X % / prüft / startet / fertig / Fehler), Abbrechen. Beide Screens ersetzen den
`ConnectScreen`, der in der Configuration-Phase die Verbindung tickt – sie ticken sie deshalb selbst
(`Connection.tick`/`handleDisconnection`), sonst bliebe der Screen nach einem Server-Kick stehen.

**Zustimmung zurücknehmen:** Button „Server-Erweiterungen…“ in den Optionen (eigene Zeile unter dem Button-Raster)
und im Pausenmenü (über „Trennen“, nur in der Welt eines kiln-Servers). Der Screen listet alle Server aus
`config/kiln-trust.json` als Accordion (Adresse, Anzahl, aufgeklappt id + Rechte mit Haken/X); pro Server
„Zurücknehmen“, unten „Alle zurücknehmen“ und „Cache leeren (Größe)“, jeweils mit zweitem Klick zur Bestätigung
(4 s). Der Eintrag wird sofort gespeichert; betrifft es den verbundenen Server, werden die Extensions entladen und
die Verbindung mit „Zustimmung zurückgenommen“ getrennt (keine Fallbacks), beim nächsten Join wird wieder gefragt.
`TrustStore` lädt die Datei vor jeder Prüfung/Änderung neu, wenn sich Änderungszeit oder Größe geändert haben
(externes Löschen/Bearbeiten wirkt ohne Neustart).

## Lua-API v2 (Protokoll v5)

Arbeitsgrundlage für Extensions mit eigenen Screens (z. B. den Shop) und für API-Extensions wie `ui_kit`
(umgesetzt im Beispiel-Plugin: `ui_kit` und `shop`, Doku `docs/ui_kit.md`).
`kiln.apiVersion == 2`. Alles aus v1 gilt unverändert weiter. Koordinaten in GUI-Pixeln, Farben ARGB-Zahlen,
Maustasten 0/1/2 = links/rechts/Mitte. „Fehler“ = Lua-Fehler (mit `pcall` fangbar, sonst wird die Extension entladen).
Tests: `client/src/test/kotlin/dev/kiln/client/lua/LuaImportTest.kt`, `LuaScreenTest.kt` (zeigen jede
Funktion in Benutzung), Server `ExtensionDependenciesTest.kt`.

### Neue Rechte
| Recht | Schaltet frei |
|---|---|
| `screens` | `ui.screen`, `ui.open`, `ui.close`, `ui.sound` |
| `inventory` | `kiln.player().inventory` |

Ohne Recht ist der API-Teil `nil` (wie v1). Immer vorhanden: `ui.lerpColor`, `ui.viewport`, `items.decode`, `time`,
`clock`, `player`, `require`, `export`, `import`, `readonly`, `id`, `apiVersion`. Zustimmungs-Screen-Texte:
„Eigene Menüs öffnen“, „Dein Inventar lesen“.

### Abhängigkeiten, Exporte, Importe
`extension.json`:
```json
{ "id": "shop", "version": "1.0.0", "name": "Shop", "permissions": ["screens", "channels"],
  "dependencies": ["ui_kit", { "id": "icons", "version": ">=1.2 <2" }] }
```
- Eintrag = Id (`[a-z0-9_]{1,32}`) oder Objekt `{ "id", "version" }`. Versionsbereich: Vergleiche mit `>= > <= < =`,
  durch Leerzeichen getrennt (alle müssen gelten), nackte Version = `=`; verglichen wird segmentweise (`.`, `-`, `+`),
  Zahlen numerisch, sonst als Text, fehlende Segmente = 0 (`1.2` = `1.2.0`). Höchstens 16, keine Duplikate, nicht die
  eigene Id.
- **Server:** prüft nach jeder (De-)Registrierung über alle Pakete: Abhängigkeit existiert, Version passt, keine
  Zyklen (`ExtensionDependencies.resolve`). Manifest-Reihenfolge = stabile topologische Sortierung (Abhängigkeiten
  vor Abhängigen, sonst Registrierungsreihenfolge). Bei Problemen werden **alle** verbindenden Spieler nach der
  Versionsprüfung gekickt („Die Server-Erweiterungen sind fehlerhaft konfiguriert (Abhängigkeiten): …“); die
  Probleme werden einen Tick nach dem Plugin-Start als SEVERE geloggt. Server-API: `Extension.dependencies`.
- **Protokoll:** `ExtensionInfo.dependencies` (nur Ids); der Codec lehnt Manifeste ab, in denen eine Abhängigkeit
  nicht vor der Extension steht (damit auch fehlende Abhängigkeiten und Zyklen).
- **Client:** `extension.json` muss dieselben Abhängigkeits-Ids wie das Manifest haben. Geladen wird in
  Manifest-Reihenfolge; scheitert eine Extension beim Laden, scheitern alle von ihr Abhängigen mit
  („… (needed by shop)“) → `ExtensionsReady(false)` → Kick. Stirbt eine Extension zur Laufzeit, werden alle (auch
  transitiv) von ihr abhängigen Extensions ebenfalls entladen; Funktionen einer entladenen API-Extension werfen
  `extension '<id>' is unloaded`. Der Zustimmungs-Screen zeigt aufgeklappt „Benötigt: Name (id)“.

```lua
kiln.export(value)          -- nur während des Ladens (main + require), genau einmal; meist eine Tabelle
local ui = kiln.import(id)  -- id muss in "dependencies" stehen; Fehler, wenn nichts exportiert wurde
local ro = kiln.readonly(t) -- schreibgeschützte Sicht ohne Halter (für Rückgabewerte; rechtepflichtige Funktionen laufen darin nicht)
```

### Sicherheitsmodell beim Import
- **Schreibschutz:** `import` liefert eine Proxy-Sicht. Lesen geht an die echte Tabelle (inkl. deren Metatable,
  „Klassen“ funktionieren) und verpackt das Ergebnis wieder (rekursiv, auch Schlüssel/Werte von `pairs`, `ipairs`,
  `next`; `#` liefert die echte Länge). Jedes Schreiben (`t.k = v`, neue Schlüssel, `table.insert/remove/sort`,
  `setmetatable`) ist ein Fehler; `getmetatable(proxy) == "read-only"`. Funktionen sind Wrapper, die nach dem
  Entladen des Exporteurs nicht mehr laufen. Proxies werden pro Wert gecacht (`ui.f == ui.f`; Cache max. 4096, danach
  geleert). Strings, Zahlen, Booleans und Item-Handles werden direkt übergeben.
- **Argumente/Rückgaben:** Argumente gehen unverändert an die Funktion – außer Proxies *desselben* Exporteurs, die
  wieder zur echten Tabelle werden (ein Importeur kann Zustandsobjekte, die er von `ui_kit` bekam, an `ui_kit`
  zurückgeben, und `ui_kit` darf sie ändern). **Rückgabewerte werden nicht verpackt** (Ausnahme: Sichten und
  rechtepflichtige API-Funktionen bekommen auf oberster Ebene einen Halter, siehe „Kein Ausleihen von Rechten“, gilt
  ebenso für Argumente): Eine API-Extension gibt
  frische Tabellen zurück oder schützt geteilten Zustand selbst mit `kiln.readonly(t)`. Interne, geteilte
  Tabellen nie ungeschützt zurückgeben (sonst kann ein Importeur sie für andere Importeure ändern). Begründung:
  rekursives Verpacken von Rückgaben würde auch Tabellen des Aufrufers schreibschützen, die er nur durchreicht.
- **Kein Ausleihen von Rechten:** Jeder Aufruf aus der Runtime in Lua (Render, Event, Kanal-Nachricht, Keybind,
  Laden) hat einen **Principal** = die aufgerufene Extension (`LuaExecution.principal`). Rechtepflichtige Funktionen
  prüfen **zusätzlich** zum eigenen Manifest, dass der Principal das Recht hat. Ruft `shop` (ohne `channels`) eine
  `ui_kit`-Funktion auf, die über `ui_kit`s Kanal sendet → Fehler „permission 'channels' missing: extension 'shop'
  cannot use it through 'ui_kit'“. Umgekehrt: Ruft `ui_kit` in *seinem* Aufruf (z. B. eigener Keybind) einen
  gespeicherten Callback von `shop` auf, prüfen `shop`s API-Funktionen gegen `ui_kit` als Principal.
  **Zusätzlich direkter Aufrufer** (seit dem Security-Review): Eine rechtepflichtige Funktion verlangt das Recht von
  (1) ihrem Besitzer (`this`; Funktionen ohne Recht existieren ohnehin nicht – geprüft als Defense-in-depth),
  (2) dem Principal – ohne Principal (Host-Code außerhalb eines Runtime-Aufrufs) läuft sie gar nicht – und (3) der
  Extension, deren Lua-Code sie direkt aufruft (`LuaExecution.caller()`: Besitzer der innersten Lua-Funktion auf dem
  Stapel, `pcall`-Rahmen übersprungen; Besitz über die kompilierten Prototypen). Wirksam ist die Schnittmenge; ein
  Callback läuft nie mit mehr Rechten als sein Besitzer. Der Aufruf muss **direkt** sein: ein `CALL` von Lua-Code
  oder `pcall(f, …)` (der Hook merkt sich pro Rahmen die laufende Instruktion und prüft, dass deren
  Funktionsregister die rechtepflichtige Funktion bzw. ihren Export-Wrapper enthält). Als **Metamethode** (`__index`,
  `__tostring`, `__concat`, `__eq`, `__lt`, `__call`, …) oder über eine **Bibliotheksfunktion** (`tostring`,
  `table.sort`, `string.gsub`, …) läuft sie nie – dort ist der Code, der sie auslöst, nicht ihr Aufrufer. Außerdem
  merkt sich jede **importierte Funktion ihren Halter** (den Importeur, `LuaExports.holder`): Solange sie läuft, braucht
  auch der Halter das Recht. Gibt ein Importeur eine importierte Funktion an den Exporteur zurück (als Callback),
  verleiht sie ihm so keine Rechte des Exporteurs. Damit scheitert auch: `ui_kit` (ohne `channels`) bekommt
  von `shop` dessen rohes `ch.send` und ruft es in `shop`s Aufruf (Principal `shop`) auf; ein Importeur ruft in einem
  Callback, den die API-Extension in ihrem eigenen Aufruf ausführt, deren exportierten Kanal auf.
  **Halter auf jedem Weg, unbekannter Halter = abgelehnt** (seit dem zweiten Security-Review, `LuaExportHolderTest`):
  `kiln.readonly` erzeugt Sichten **ohne** Halter (sie können überall landen: Feld eines Exports, Rückgabewert,
  Callback-Argument, Metamethode, `pairs`). Eine solche Sicht konnte ein Importeur ohne Recht dem Exporteur als
  Callback zurückgeben, der sie dann in seinem eigenen Aufruf ausführte (ausnutzbar, Beleg im Test). Jetzt gilt
  *deny by default*: Ein Wrapper ohne Halter läuft zwar, zählt aber als Halter **ohne** Rechte – keine
  rechtepflichtige Funktion läuft darin, auch nicht für den Exporteur selbst (`kiln.readonly({ send = ch.send }).send`
  scheitert mit „unknown holder“; Funktionen ohne Recht laufen normal). Damit hängt die Sicherheit nicht davon ab, auf
  welchem Weg eine halterlose Sicht wandert. Damit legitime Nutzung weiter geht, bekommen Sichten den Halter, wo die
  Runtime ihn kennt: Liest ein Importeur über *seine* Sicht eine **halterlose** Sicht desselben Exporteurs (z. B. ein
  `readonly`-Feld des Exports), wird sie für ihn neu erzeugt; jede andere Sicht (anderer Halter, auch desselben
  Exporteurs, oder Re-Export `base` → `mid` → `poor`) wird verschachtelt, sodass jeder Halter auf dem Weg das Recht
  braucht. **Invariante: Ein Halter, der einmal auf einer Sicht steht, geht nie verloren** – auf jedem Weg (Lesen,
  `pairs`, Argumente/Rückgaben, normale Tabellen, Callbacks) wird die Sicht entweder selbst weitergereicht oder
  eingepackt; ersetzt wird nur der unbekannte Halter. (Vorher erzeugte das Neu-Einpacken auch Sichten mit Halter neu:
  `b` ohne Recht gab eine Sicht an `c` mit Recht, beim Rückgeben an den Exporteur fiel `b` heraus – getestet über
  alle Wege A → B → C → A.) Einzige Ausnahme: Ein Tabellen-Proxy, der an seinen Exporteur zurückgeht, wird wieder
  die echte Tabelle (auch verschachtelt); eine Tabelle trägt keine Rechte, und solange der Aufruf des Importeurs
  läuft, steht sein Halter auf dem Stapel. Beim Aufruf einer
  importierten Funktion bekommen Sichten und rohe rechtepflichtige API-Funktionen in Argumenten und Rückgabewerten
  (oberste Ebene) den Halter des Aufrufs. **Bekannte Grenze:** Legt der Exporteur eine *rohe* rechtepflichtige
  Funktion selbst in eine normale Tabelle, die er herausgibt (`return { send = ch.send }`), und kommt sie in einer
  normalen Tabelle oder als Rückgabewert eines Callbacks zurück, sieht die Runtime sie nicht (rohe Tabellen werden
  bewusst nicht verpackt). API-Extensions geben rechtepflichtige Funktionen deshalb nie roh in Tabellen heraus
  (Empfehlung bleibt: API-Extensions brauchen keine Rechte). Damit der direkte
  Aufrufer stimmt, werden **Tail-Calls beim Kompilieren in normale Aufrufe umgeschrieben** (`TAILCALL A B 0; RETURN A 0`
  → `CALL A B 0; RETURN A 0`, gleiche Rückgabewerte; LuaJ führt Tail-Calls sonst erst nach dem Entfernen des
  aufrufenden Rahmens aus). Folge: Tail-Rekursion zählt auf die Aufruftiefe 200. Bewusst **nicht** jede Extension auf
  dem ganzen Pfad: `kit.screen` ruft `render`/`click` der App aus `ui_kit`-Code auf; die App muss darin ihren eigenen
  Kanal benutzen dürfen, obwohl `ui_kit` kein `channels` hat (Rechte kann ein Zwischenglied so nicht gewinnen: es
  ruft nur Code, den der Besitzer geschrieben hat, mit dessen Rechten ∩ Principal). Host-Code liest Tabellen eines
  Importeurs, die Proxies sind (z. B. eine importierte Tabelle als Widget-Definition), außerhalb eines Aufrufs ohne
  Metamethoden (kein Lua ohne Budget und Principal). Tests: `LuaPermissionBorrowingTest`. Empfehlung: API-Extensions wie `ui_kit` brauchen **keine** Rechte; alles für
  UI kommt über die Fähigkeits-Objekte `g`, `s`, `field` (wer sie hat, darf sie benutzen – die Runtime übergibt sie nur
  dem Besitzer des Screens bzw. Widgets, und `g`/`s:textInput`/`s:cursor` wirken nur während dessen Render-Aufruf).
- **Budget:** Alles, was in einem Aufruf läuft – auch Code importierter Extensions – zählt auf das
  Instruktionsbudget des Principals (die Hooks aller Extensions leiten an den Hook des Principals weiter). Ein
  Lua-Fehler oder eine Budget-Überschreitung in importiertem Code entlädt den **Aufrufer**, die API-Extension bleibt.
  Ausnahme: Ein Callback einer anderen Extension, den die Runtime innerhalb eines fremden Aufrufs auslöst (z. B. das
  `close` eines durch `ui.open` ersetzten Screens), entlädt bei einem Lua-Fehler nur diese Extension; das Budget
  bleibt das des Principals.
- **Speicher:** Nach Aufrufen wird der Speicher des Principals und seiner direkten Abhängigkeiten geschätzt (je
  16 MiB, Überschreitung entlädt den Aufrufer); Proxies zählen nicht, Item-Handles je 1 KiB.

### Screens
```lua
kiln.ui.screen(id, def)               -- id "<ext>:<name>" oder "<name>" (eigener Namensraum), max. 32
kiln.ui.open(id [, args]) -> bool      -- false: nicht in der Welt / Handshake nicht fertig
kiln.ui.close() -> bool                -- schließt den eigenen offenen Screen (close-Grund "closed")
kiln.ui.sound(name [, pitch = 1])      -- click | pling | success | error | page | toast; pitch 0.5..2; ≤ 10/s
kiln.ui.viewport() -> { width, height, scale } | nil   -- GUI-skalierte Fenstergröße, GUI-Scale
kiln.clock() -> Sekunden               -- monoton, Echtzeit (Animationen); kiln.time() bleibt Spielzeit
```
`def`: `fullscreen` (bool, Default false), `width`/`height` (1..4096, Default 176×166, nur ohne fullscreen; der
Screen wird zentriert), Callbacks (alle optional, `self` = Zustandstabelle pro geöffnetem Screen):
```
init(self, s, args)                       -- beim Öffnen (args = Tabelle aus ui.open, sonst {})
render(self, g, s)                        -- jeden Frame
resize(self, s)                           -- Fenstergröße/GUI-Scale geändert (s.* schon aktualisiert)
close(self, s, reason)                    -- "escape" | "closed" (ui.close / s:close) | "replaced" (anderer Screen)
key(self, s, key, mods) -> bool           -- "escape", "enter", "tab", "backspace", "up", "a", "left.shift", "keypad.enter", …
char(self, s, ch) -> bool                 -- getipptes Zeichen (nur ohne fokussiertes Textfeld)
click(self, s, mx, my, button, mods) -> bool
release(self, s, mx, my, button)
drag(self, s, mx, my, button, dx, dy)
scroll(self, s, mx, my, dy, dx) -> bool
```
`mods = { shift, ctrl, alt }`. Maus relativ zum Screen-Ursprung (`s.x`, `s.y`). Rückgabe `true` = behandelt (fehlt
sie, gilt `false`). Tastennamen = Vanilla-`InputConstants`-Namen ohne `key.keyboard.`.

**Lebenszyklus / Regeln:** `ui.open` ersetzt jeden offenen Screen (auch Vanilla/Server-Screens; ein ersetzter
Lua-Screen bekommt `close(…, "replaced")`). Nicht während des Ladens, nicht aus `close` heraus (Fehler, verhindert
Fallen), höchstens 10 Öffnungen pro 10 s. **Escape:** zuerst `key(…, "escape")` – `true` hält den Screen offen (z. B.
Modal schließen), sonst schließt er (`close(…, "escape")`). **Garantierter Ausweg** (kann die Extension nicht
blockieren): **Shift+Escape** schließt immer, ebenso das **dritte Escape innerhalb von 1,5 s** – ohne `key` zu fragen
(`close(…, "escape")` läuft trotzdem). Danach liefert `ui.open` dieser Extension 10 s lang `false`, damit sie den
Spieler nicht sofort wieder einfängt (HUD-Render, Kanal-Handler). Gilt auch für in Lua gezeichnete Modals (z. B. von
`ui_kit`): sie sind Teil desselben Screens. Ein fokussiertes Textfeld bekommt Escape nie. **Tab/Shift+Tab** (wenn `key` nicht `true` liefert):
Fokus auf das nächste/vorige Textfeld (Deklarationsreihenfolge). Disconnect, Entladen der Extension (auch wegen einer
entladenen Abhängigkeit) oder ein Skriptfehler schließen den Screen **ohne** `close`. Der Server öffnet Screens nur
indirekt: Kanal-Nachricht → Handler ruft `ui.open` (keine eigene Plattformfunktion). Keybinds werden in Lua-Screens
nicht ausgelöst. Der Screen pausiert das Spiel nicht.

**Budget:** `render` hat ein eigenes Budget von **250 000 Instruktionen pro Frame** und zählt nicht ins
Sekundenfenster (6 M/s gilt für alle anderen Aufrufe, je 100 000). Benchmark (`LuaScreenTest`): Shop mit 50
gleichzeitig sichtbaren Kacheln (Panel, Item mit Anzahl, 2 Texte, Button-Sprite, Hover, Item-Tooltip, Clip,
Scrollbar, Suchfeld) über ein importiertes `ui_kit` ≈ 8 100 Instruktionen ≈ 3 % des Budgets, ≈ 0,35 ms pro Frame
(nur Lua-Seite, ohne Vanilla-Rendering).

**Screen-Objekt `s`** (Felder vor jedem Aufruf aktualisiert): `id`, `fullscreen`, `x`, `y` (absoluter Ursprung),
`width`, `height`, `viewportWidth`, `viewportHeight`, `guiScale`, `mouseX`, `mouseY` (relativ), `time` (Sekunden seit
Öffnen), `delta` (Sekunden seit letztem Frame), `shift`, `ctrl`, `alt` (letztes Ereignis). Methoden:
```
s:textInput(id, opts) -> field   -- nur in render; siehe unten
s:cursor(name)                   -- nur in render; default | hand | text | crosshair | resize_ns | resize_ew | move | not_allowed
s:close() -> bool ; s:sound(name [, pitch]) ; s:isMouseDown(button) -> bool ; s:focused() -> id | nil ; s:blur()
```
`s:sound` braucht kein Recht (wer `s` hat, darf Klick-Sounds abspielen; gleiche Liste und Rate wie `ui.sound`).

### Native Texteingabe
```lua
local field = s:textInput(id, { x, y, w, h = 20, value, placeholder = "", maxLength = 256,
                                numeric = { min, max, integer = true } | true, enabled = true, focus = false,
                                onChange = function(field, value) end })
-- field.id, field.value, field.valid, field.focused ; field:focus() ; field:blur() ; field:setValue(text)
```
- Vanilla `EditBox` (Cursor, Markierung, Clipboard, IME, Rahmen-Sprites). **Pro Frame deklarieren**: in `render`
  aufrufen; das Feld wird genau dort gezeichnet (Zeichenreihenfolge und `g:clip` gelten). Ein Feld, das in einem
  Frame nicht deklariert wird, verschwindet (Zustand und Fokus weg). `id` `[A-Za-z0-9_.:-]{1,64}`, eindeutig pro
  Frame (sonst Fehler), max. 64 Felder, `maxLength` 1..1024, `focus = true` fokussiert beim Anlegen.
- **Sync:** `value` wird beim Anlegen übernommen und danach nur, wenn sich der übergebene `value` gegenüber dem
  letzten Frame ändert (programmatische Änderung, ohne `onChange`). Muster: `value = state.q, onChange =
  function(f, v) state.q = v end` – Eingaben des Spielers bleiben, Änderungen von `state.q` (z. B. per Server)
  erscheinen. `field:setValue` setzt sofort.
- `onChange` läuft bei jeder Eingabe des Spielers – bei `numeric` nur für gültige Werte (leer oder Zahl im Bereich);
  `field.valid` zeigt die Gültigkeit, ungültiger Text wird rot. `numeric` lässt nur Zahlzeichen zu (`-` nur bei
  `min < 0`, `.` nur ohne `integer`).
- **Fokus/Eingabe:** Klick in ein Feld (Rechteck ∩ Clip des letzten Frames, `enabled`) fokussiert es und geht
  nicht an `click`; das zuletzt deklarierte Feld liegt oben. Klick daneben nimmt den Fokus und geht an `click`. Ein
  fokussiertes Feld bekommt Tasten/Zeichen zuerst; was es nicht verbraucht (Enter, Escape, Tab, …) geht an `key`.
  **Felder hinter einem in Lua gezeichneten Modal nicht deklarieren (oder `enabled = false`)** – die Runtime kennt
  keine Lua-Ebenen.

### Zeichenkontext `g` (v2-Ergänzungen)
```
g:text(str, x, y [, { align, color, shadow = true, scale = 1 }]) -> Breite (skaliert); scale (0, 8]
g:textWidth(str) -> px ; g:wrap(str, width) -> { zeilen… } (max. 64)
g:gradient(x, y, w, h, top, bottom)
g:clip(x, y, w, h, fn)     -- fn() mit Scissor (mit äußerem Clip geschnitten), Tiefe ≤ 16; Texteingaben respektieren ihn
g:item(itemIdOrHandle, x, y [, size | { size = 16, count = 1, decorations = true }])
                           -- Handle: volles Item (Komponenten, Glint, Modell, Haltbarkeitsbalken); count > 1 als
                           -- Slot-Zahl (1.2k, 12k, 1.5M …); String-Id wie v1 (Zahl nur mit count > 1)
g:itemTooltip(handle, mx, my [, extraLines])  -- Vanilla-Item-Tooltip + Zusatzzeilen (Screens/Widgets, nicht HUD)
g:tooltip(textOrLines)     -- Tooltip an der Maus (Screens/Widgets, nicht HUD)
g:sprite(id, x, y, w, h)   -- Vanilla-GUI-Sprite (Nine-Slice wie Vanilla), nur aus der Liste unten
```
Sprites (mit oder ohne `minecraft:`): `widget/button`, `widget/button_highlighted`, `widget/button_disabled`,
`widget/checkbox`, `widget/checkbox_highlighted`, `widget/checkbox_selected`, `widget/checkbox_selected_highlighted`,
`widget/slider`, `widget/slider_highlighted`, `widget/slider_handle`, `widget/slider_handle_highlighted`,
`widget/scroller`, `widget/scroller_background`, `widget/text_field`, `widget/text_field_highlighted`, `widget/tab`,
`widget/tab_highlighted`, `widget/tab_selected`, `widget/tab_selected_highlighted`, `widget/page_forward`,
`widget/page_forward_highlighted`, `widget/page_backward`, `widget/page_backward_highlighted`,
`widget/locked_button`, `widget/locked_button_highlighted`, `widget/locked_button_disabled`,
`widget/unlocked_button`, `widget/unlocked_button_highlighted`, `widget/unlocked_button_disabled`, `container/slot`,
`container/slot_highlight_back`, `container/slot_highlight_front`, `popup/background`, `icon/search`,
`icon/checkmark`.

### Items
```lua
local h, err = kiln.items.decode(text)  -- Handle oder nil, Grund ("not Base64", "not gzip NBT …", "cannot decode item")
h.id ; h.name ; h.maxStack                     -- nur lesbar; getmetatable(h) == "item"; tostring(h) == "item(<id>)"
local inv = kiln.player().inventory()   -- Recht inventory: { {slot, handle, count, id, name}, … } (Kopien) | nil
```
- `text` = Base64 von Paper `ItemStack.serializeAsBytes()` mit Menge 1 (Server: `ExtensionItems.encode(stack)`);
  die Anzahl separat schicken. Dekodiert wie Server-Screen-Items (`ItemStacks`: NBT-Quota 2 MiB, DataFixer,
  Registries der Verbindung).
- Limits: Text ≤ 87 384 Zeichen (64 KiB NBT); LRU-Cache 512 Einträge pro Extension (gleicher Text → gleiches
  Handle); höchstens 256 Cache-Fehlschläge pro Sekunde (darüber Fehler – Handles beim Empfang dekodieren und
  speichern, nicht jeden Frame). Handles sind unveränderlich und dürfen zwischen Extensions geteilt werden.
- Inventar-Slots: 0–35 Hauptinventar (0–8 Hotbar), 36–39 Rüstung, 40 Nebenhand; nur nicht-leere Slots.

### Server-API
```kotlin
ExtensionItems.encode(stack: ItemStack): String   // Base64, Menge 1; wirft für Luft/leer oder > 87 384 Zeichen
Extension.dependencies: List<String>
ExtensionChannel.send(player, msg)                // JSON bis 262 144 Zeichen (vom Client weiterhin ≤ 16 384)
```
Ein Shop mit ~50 Angeboten inkl. Items (je ~0,3–3 KiB Base64) passt in **eine** Nachricht; kein Chunking. Für
größere Listen seitenweise senden. Clientseitig gilt für eingehende Nachrichten: Tiefe ≤ 16, ≤ 32 768 Einträge.
Muster „Server öffnet Screen“:
```kotlin
data class Offer(val id: String, val item: String, val count: Int, val price: Long)
data class OpenShop(val title: String, val offers: List<Offer>)
val open = ext.channel<OpenShop>("open")
open.send(player, OpenShop("Markt", offers.map { Offer(it.id, ExtensionItems.encode(it.stack), it.amount, it.price) }))
```
```lua
kiln.net.channel("open"):on(function(m)
  for _, o in ipairs(m.offers) do o.handle = kiln.items.decode(o.item) end
  kiln.ui.open("shop", m)
end)
```

### Bekannte Grenzen
- Lua-gezeichnete Ebenen (Modals) kennt die Runtime nicht: Eingabefelder darunter selbst ausblenden.
- Keine Keybinds in Lua-Screens; keine echten Inventar-Slots oder Drag & Drop von Items (nur Anzeige).
- Pattern-Matching von LuaJ läuft weiterhin ungezählt in Java (siehe Sandbox).
- Höchstens 128 Extensions pro Server (`ProtocolLimits.MAX_MANIFEST_EXTENSIONS`). Geprüft wird bei der
  **Registrierung** (`ExtensionRegistry.register`): API-Registrierungen darüber werfen `IllegalArgumentException`
  („Too many extensions …“), Ordner-Pakete werden beim Start mit SEVERE-Log übersprungen; einen Tick nach dem Start
  loggt das Plugin „N of at most 128 extensions registered“. Joins scheitern daran nicht mehr.
- Host-Code liest Lua-Definitionen (Widgets, Screens) mit `rawget`, damit keine Metamethoden außerhalb eines
  budgetierten Aufrufs laufen.
