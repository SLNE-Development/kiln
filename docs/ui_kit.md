# ui_kit – UI-Komponenten für Lua-Screens

API-Extension (`example-server/src/main/resources/extensions/ui_kit/`, **keine Rechte**, Version 1.1.0), exportiert
per `kiln.export`. Benutzt wird sie vom Lua-Shop (`.../extensions/shop/`) und den fünf Demo-GUIs (Abschnitt
„Demo-GUIs“), Tests: `client/src/test/kotlin/dev/kiln/client/lua/ShopExtensionTest.kt`,
`DemoExtensionsTest.kt`.

```json
{ "id": "shop", "permissions": ["screens", "channels"], "dependencies": ["ui_kit"] }
```

## Modell: Immediate Mode

Die UI wird **jeden Frame neu deklariert** (`if ui:button(...) then … end`), Zustand liegt im Kontext unter der
Widget-Id (Scroll-Offsets, offenes Dropdown, Animationen) bzw. in der App.

Begründung: Lua-Screens werden ohnehin jeden Frame aus dem Zustand des Skripts gezeichnet, die Runtime hat keinen
Widget-Baum, und native Textfelder werden auch pro Frame deklariert. Ein Retained-Baum müsste ständig mit den
App-Daten synchronisiert werden (Live-Updates vom Server); im Immediate Mode ist die Darstellung immer eine Funktion
des aktuellen Zustands. Preis: Eingaben kommen zwischen zwei Frames. ui_kit merkt sich deshalb pro Frame die
Rechtecke aller interaktiven Widgets (in Zeichenreihenfolge = Stapelreihenfolge), testet Klick/Rad/Tasten gegen die
des letzten Frames (oberstes zuerst) und meldet das Ergebnis im nächsten Frame (ein Frame Latenz, ~16 ms).

## Einbindung

```lua
local kit = kiln.import("ui_kit")

kiln.ui.screen("main", {
  fullscreen = true,
  init    = function(self, s) self.ui = kit.new() end,           -- ein Kontext pro geöffnetem Screen
  render  = function(self, g, s)
    local ui = self.ui
    ui:frame(g, s)                                              -- immer zuerst
    if ui:button("ok", 10, 10, 80, 20, "OK", { style = "primary" }) then … end
    ui:finish()                                                 -- immer zuletzt: Dropdown-Listen, Toasts, Tooltip, Cursor
  end,
  click   = function(self, s, mx, my, b, mods) return self.ui:click(mx, my, b, mods) end,
  release = function(self, s, mx, my, b) self.ui:release(mx, my, b) end,
  scroll  = function(self, s, mx, my, dy) return self.ui:scroll(mx, my, dy) end,
  key     = function(self, s, key, mods)
    if self.ui:key(key, mods) then return true end              -- offenes Dropdown: ↑ ↓ Pos1 Ende Enter Escape
    if key == "escape" and self.dialog then self.dialog = nil return true end
    return false
  end,
})
```

`kit.new(theme)` liefert einen frischen Kontext (Methoden über eine geschützte Metatable: `getmetatable(ui) ==
"ui_kit.context"`). `theme` überschreibt Farben aus `kit.theme` (`text`, `muted`, `dim`, `accent`, `danger`,
`success`, `panel`, `card…`, `field…`, `overlay`, `backdrop`, `modal`, …).

Widget-Ids sind beliebige Strings; Widgets in Schleifen bekommen dieselbe Id und ein `data`-Feld (z. B. die
Angebots-Id) – das spart String-Verkettung pro Frame. Rückgabe „geklickt“ gilt nur für Linksklick.

## Komponenten

| Aufruf | Rückgabe | Hinweise |
|---|---|---|
| `ui:button(id, x, y, w, h, label, {style="normal"\|"primary"\|"danger", disabled, tooltip, disabledTooltip, data, sound=true})` | `clicked` | Vanilla-Sprite, Hand-Cursor (`not_allowed` wenn deaktiviert), Klick-Sound |
| `ui:panel(x, y, w, h, {bg, border})` | – | Fläche mit Rahmen |
| `ui:card(id, x, y, w, h, {selected, clickable=true, disabled, tooltip, data})` | `clicked, hovered` | Hover/Selected-Farben |
| `ui:tabs(id, x, y, tabs, selectedIndex, {h=20, padding, minWidth, gap})` | `index, changed, width` | `tabs` = `{ {label, tooltip}, … }` oder Strings; Vanilla-Tab-Sprites |
| `ui:label(text, x, y, {color, align, shadow, scale, maxWidth})` | Breite | `maxWidth` kürzt mit „…“ |
| `ui:heading(text, x, y, {scale, color, maxWidth})` | Breite | Akzentfarbe |
| `ui:paragraph(text, x, y, w, {color, align})` | Höhe | Umbruch über `g:wrap` |
| `ui:dropdown(id, x, y, w, options, value, {h=20, placeholder, disabled, tooltip, rows=8})` | `value, changed` | `options` = `{ {value, label}, … }` oder Strings; Liste über allem (in `finish`), blockiert darunter, Klick daneben schließt, Rad scrollt, Tastatur |
| `ui:checkbox(id, x, y, label, checked, {size=17, disabled, tooltip})` | `checked, changed` | Vanilla-Sprites |
| `ui:toggle(id, x, y, on, {label, disabled, tooltip})` | `on, changed` | animierter Schalter 26×14 |
| `ui:slider(id, x, y, w, value, min, max, {step=1, h=20, text, disabled, tooltip})` | `value, changed, committed` | Klicken springt, Ziehen folgt der Maus (auch außerhalb), Raster `step`; `committed` (1.1) = Frame, in dem losgelassen wurde (Ende des Ziehens oder einfacher Klick) – dann an den Server senden statt bei jeder Änderung |
| `ui:scrollPanel(id, x, y, w, h, contentH, fn(cx, cy, cw), {step=24, bar=6})` | Offset | Mausrad, ziehbare Leiste, Klick auf die Spur blättert; Inhalt (Zeichnen, Klickflächen, Textfelder) geclippt; Offset pro Id (`ui:scrollOffset(id)`, `ui:setScroll(id, y)`) |
| `ui:modal(id, w, h, {title, dismiss, border})` | `cx, cy, cw, ch, dismissed` | dunkelt ab, blockiert alles darunter (Klick, Rad, Hover), versteckt native Felder darunter; `dismiss` = Klick auf den Hintergrund meldet `dismissed`. **Nach** dem verdeckten Inhalt zeichnen, Widgets danach liegen im Modal. Mehrere Modals stapeln sich. Escape behandelt die App (`key`); **Shift+Escape** oder **3× Escape in 1,5 s** schließen aber immer den ganzen Screen samt Modal (Runtime, nicht blockierbar). |
| `ui:itemSlot(id, x, y, handle, count, {selected, tooltip=extraLines\|false, clickable=true, data, size=18, dim, background})` | `clicked, hovered, mods` | Slot-Sprite, Item mit Anzahl (1.2k), Item-Tooltip; `mods.shift` für Shift-Klick |
| `ui:itemTile(id, data, x, y, w, h, {item, count, title, lines={{text, color}…}, tooltip, selected, disabled, dim})` | `clicked, hovered` | Karte mit Item-Slot links und Textzeilen (gekürzt) |
| `ui:textInput(id, opts)` | Feld oder Stub | wie `s:textInput`, aber unter Modals/Dropdown-Listen wird **kein** natives Feld deklariert, sondern eine Attrappe gezeichnet (die Runtime kennt keine Lua-Ebenen) |
| `ui:toast(text, "info"\|"success"\|"error", seconds=3)` | – | im Screen unten, auch aus Kanal-Handlern aufrufbar |
| `ui:tooltip(textOrLines)`, `ui:itemTooltip(handle, lines)`, `ui:cursor(name)` | – | gelten für den laufenden Frame |
| `ui:clip(x, y, w, h, fn)` | – | wie `g:clip`, zusätzlich für Klickflächen |
| `ui:hovered(x, y, w, h)`, `ui:mouseIn(…)`, `ui:blocked(…)`, `ui:isHot(id, data)` | bool | Hover ohne eigenes Widget; `blocked` = von Modal/Liste verdeckt |
| `ui:ellipsize(text, maxWidth)` | String | UTF-8-sicher, über `g:textWidth`, Ergebnisse gecacht |
| `ui:dropdownOpen()`, `ui:closeDropdown()`, `ui:stateOf(id)`, `ui:isActive(id)` (1.1: wird gerade gezogen) | | |

### Ergänzungen in 1.1 (abwärtskompatibel)

Entstanden beim Bau der fünf Demo-GUIs (unten); vorher hätte jede GUI sie selbst nachbauen müssen. Bestehende
Aufrufe verhalten sich unverändert (`ui:slider` liefert nur einen dritten Rückgabewert mehr). Abhängige können die
Version verlangen: `"dependencies": [{ "id": "ui_kit", "version": ">=1.1 <2" }]`.

| Aufruf | Rückgabe | Hinweise |
|---|---|---|
| `kit.screen(def)` | Screen-Definition | für `kiln.ui.screen`: legt `self.ui = kit.new(def.theme)` vor `def.init` an, ruft `def.render(self, g, s, ui)` zwischen `ui:frame`/`ui:finish`, leitet `click`/`release`/`scroll`/`key` zuerst an den Kontext und nur Unbenutztes an `def.*` weiter; übrige Felder (`fullscreen`, `close`, …) werden kopiert. Spart die fünf Weiterleitungs-Callbacks pro Screen. |
| `ui:list(id, x, y, w, h, items, selected, {rowH=18, gap=2, key="id", render, empty, step})` | `selected, changed` | scrollbare Auswahlliste aus Karten; `selected` = Schlüssel (`item[key]`, String selbst oder Index); `render(item, x, y, w, h, selected, hovered, index)` zeichnet den Zeileninhalt (sonst `item.label` + `item.sub` rechts); zeichnet nur sichtbare Zeilen |
| `ui:table(id, x, y, w, h, columns, rows, {sortKey, sortDesc, rowH=14, headerH=16, highlight, selected, empty, rev, clickable=true})` | `sortKey, sortDesc, clickedRow` | Kopfzeile mit Sortierpfeil, Klick sortiert (nochmal: umgekehrt); `columns = { {key, label, w \| flex, align, sortable=true, desc, tooltip, format(v,row), color(row), sortValue(row)}, … }`; `highlight` = Feldname oder Funktion (z. B. eigene Zeile, Akzentbalken); Sortierung stabil, gecacht bis sich `rows`, Sortierung oder `rev` ändern; nur sichtbare Zeilen werden gezeichnet; Region-Daten: Kopf = `-Spalte`, Zeile = Position |
| `ui:searchField(id, opts)` | `field, cleared` | `ui:textInput` mit Lupen-Icon links und „×“ rechts, solange Text da ist (ruft `onChange(field, "")`); Optionen wie `textInput` plus `clear=true`, `clearTooltip` |
| `ui:confirm(id, {title, text, yes="Ja", no="Abbrechen", danger, pending, w=280})` | `"yes"`, `"no"` oder `nil` | Ja/Nein-Modal mit umbrochenem Text; Klick auf den Hintergrund = `"no"` (nicht während `pending`); Escape behandelt die App (`key`) |
| `ui:progressBar(x, y, w, h, value, {color, bg, border, text, textColor, tooltip})` | – | Balken 0..1 mit optionaler Beschriftung (Energie, Fortschritt, Abklingzeit) |
| `ui:badge(text, x, y, {color, textColor, align, h=11})` | Breite | kleines farbiges Etikett (Status); `align="right"`: `x` ist die rechte Kante |
| `ui:section(title, x, y, w, {color, line})` | Höhe (14) | Abschnittstitel mit Linie (Formulare, Detailbereiche) |

Kosten (Benchmarks `DemoExtensionsTest`, 854×480): Fabrik ≈ 5 400, Quest-Log ≈ 6 400, Profil ≈ 3 400, Warps mit 40
Kacheln ≈ 42 000 Instruktionen pro Frame; Tabelle mit 51 sichtbaren Zeilen (854×1000) ≈ 40 000 – alle unter 17 % des
Render-Budgets.

**Layout** (reine Funktionen, keine Zeichnung):

```lua
local grid = kit.grid(x, y, w, { minWidth = 150, h = 44, gap = 4, padding = 0, maxColumns })
grid.cols, grid.cellW ; local cx, cy, cw, ch = grid:cell(i) ; grid:height(n)
local row = kit.row(x, y, { gap = 4, h = 20, padding }) ; local x, y, w, h = row:next(width)
local col = kit.column(x, y, { gap = 4, w = 100, padding }) ; local x, y, w, h = col:next(height)
```

**Helfer:** `kit.formatCount(n)` (999, 1.2k, 12k, 1.5M), `kit.formatNumber(n)` (1.234.567), `kit.lower(s)`
(mit ÄÖÜ), `kit.utf8sub(s, n)`, `kit.snap(v, min, max, step)`, `kit.sliderValue(mx, x, w, min, max, step)`,
`kit.clampScroll(y, contentH, viewH)`, `kit.clamp(v, lo, hi)`.

## Regeln und Grenzen

- Zeichenreihenfolge = Stapelreihenfolge: Was später gezeichnet wird, liegt oben und bekommt Klicks zuerst.
  Dropdown-Listen werden in `ui:finish()` gezeichnet (immer oben); öffnet sich ein Modal, schließt ein Dropdown
  darunter.
- Native Textfelder **immer** über `ui:textInput` deklarieren, sonst bleiben sie unter Modals klickbar. Ein
  verstecktes Feld verliert Fokus und Cursor; der Wert kommt beim nächsten Deklarieren aus `value`.
- „Verdeckt“ wird über die Blocker des letzten Frames bestimmt: ändert sich die Zahl der Widgets vor einem Modal
  zwischen zwei Frames stark, kann ein Frame lang Hover unter dem Modal erscheinen (Klicks sind nie betroffen).
- ui_kit braucht keine Rechte: Alles läuft über `g`/`s` des Aufrufers; Sounds über `s:sound`. Budget und Fehler
  zählen für die aufrufende Extension (siehe Sicherheitsmodell in `docs/lua-extensions.md`).
- Kosten (Benchmark `ShopExtensionTest`): Markt mit 50 sichtbaren Kacheln ≈ 22 000 Instruktionen pro Frame (≈ 9 %
  des Render-Budgets von 250 000), ≈ 0,5 ms Lua-Zeit.

## Shop-Kanäle (`shop:<name>`, Beispiel-Plugin)

Der Server (`example-server/.../shop/lua/`) ist autoritativ: Geld, Bestand, Inventar und Preise werden bei jeder
Anfrage gegen den echten Zustand geprüft; der Client zeigt nur an und filtert/sortiert lokal.

| Kanal | Richtung | Inhalt |
|---|---|---|
| `open` | S→C | Zustand (unten) mit `open = true`, `tab` (`market`/`sell`/`mine`) → `ui.open` |
| `open` | C→S | `{}` – Spieler möchte den Shop (Taste K) → Server antwortet mit `open` |
| `state` | S→C | Zustand bei jeder Änderung (Kauf/Verkauf/… auch anderer Spieler, auch aus `/shopdsl`), bei Inventaränderung und alle 30 s, nur an Spieler mit offenem Shop |
| `closed` | C→S | `{}` – Screen geschlossen, keine Live-Updates mehr |
| `buy` | C→S | `{req, offer, amount, price}` – `price` = gesehener Stückpreis (Preisänderung → Fehler mit `newPrice`) |
| `sell` | C→S | `{req, slot (0–35), item ("minecraft:oak_log"), amount, price, duration (H1/H12/H24/D7), category (BLOCKS/TOOLS/ORES/FOOD/OTHER), notify}` |
| `withdraw` | C→S | `{req, offer}` (nur eigene) |
| `reprice` | C→S | `{req, offer, price}` (nur eigene, aktive) |
| `result` | S→C | `{req, action, ok, message?, error?, fields?: {amount, price, item, category}, newPrice?}` |

Zustand: `{balance, startBalance, maxPrice, categories: [{id, label}], durations: [{id, label}], offers: [{id,
item (ExtensionItems.encode), name, key, cat, price, amount, seller, own, created, expiresIn?}], mine: [{…,
expired}], suggest: {"minecraft:oak_log": "BLOCKS"}, truncated}`. Höchstens 400 Angebote und 200 000 Zeichen
Item-Daten pro Nachricht (`truncated = true`, neueste zuerst). Anfragen: Ganzzahlen müssen JSON-Zahlen ohne
Nachkommastellen im Bereich sein, Strings ohne Steuerzeichen, sonst „Ungültige Anfrage.“; Rate-Limit 6 Anfragen
sofort, danach 4/s pro Spieler.

## Demo-GUIs (Beispiel-Plugin)

Fünf eigenständige Lua-Extensions auf ui_kit (Lua: `example-server/src/main/resources/extensions/<id>/`, Server:
`example-server/.../demos/<name>/`, registriert in `ExamplePlugin`). Jede hat Rechte `keybinds`, `channels`,
`screens`, hängt von `ui_kit >=1.1 <2` ab, öffnet sich per eigenem Keybind (`kiln.keys.bind`, Taste in den
Steuerungs-Optionen änderbar) und per Befehl. Die Default-Tasten sind in Vanilla 26.3 unbelegt (Vanilla belegt u. a.
W A S D E F Q T L O P C X G, Shift, Strg, Tab, `/`, F1–F5, F11) und kollidieren nicht mit G, H, J, K des Beispiels.

| Extension | Taste | Befehl | UI-Muster |
|---|---|---|---|
| `fabrik` – Fabrik-Steuerzentrale | B | `/fabrik` | Liste links (`ui:list`, eigene Zeilen), Details rechts: Status-Badge, Strom-/Fortschrittsbalken, An/Aus-Toggle, Prioritäts-Slider (sendet bei `committed`), Rezept-Dropdown; Live-Update 1×/s |
| `questlog` – Quest-Log | U | `/quests [test]` | Tabs Aktiv/Abgeschlossen/Verfügbar mit Anzahl, Liste mit Fortschritt, Detail mit Zielen und Belohnungs-Item-Slots (Item-Tooltip), Annehmen/Abbrechen über `ui:confirm` |
| `warps` – Warps | V | `/warps` | Kachel-Raster (`kit.grid` im `scrollPanel`), `ui:searchField`, Favoriten-Filter (Checkbox) und Stern pro Kachel, Teleport per Klick mit Abklingzeit-Balken, „Neuer Warp“-Modal mit Live-Validierung und Symbolwahl, Löschen mit `ui:confirm` |
| `bestenliste` – Bestenliste | N | `/bestenliste` | Kategorie-Dropdown, Zeitraum-Tabs, sortierbare `ui:table` (eigene Zeile hervorgehoben, Zeile auswählbar), Diagramm-Ansicht aus `g:rect` (animiert, Hover-Tooltip) |
| `profil` – Einstellungen & Profil | M | `/profil` | Formular in Abschnitten (`ui:section`) mit Textfeldern, Dropdowns, Toggles, Slider; Live-Vorschau; Speichern/Zurücksetzen/Standard; Escape bei ungespeicherten Änderungen öffnet ein Modal (Speichern / Verwerfen / Weiter) |

**Kanäle** (alle wie beim Shop, `<id>:<name>`, Server-Glue `demos/LuaDemo.kt`): `open` (C→S `{}` per Taste, S→C
Zustand → `ui.open`), `state` (S→C, nur bei offenem Screen), `closed` (C→S), `result` (S→C `{req, action, ok,
message?, error?, fields?}`) und je Demo:

| Demo | Anfragen (C→S, immer mit `req`) | Server prüft |
|---|---|---|
| `fabrik` | `set {machine, running?, priority?, recipe?}` | Maschine des Spielers, mindestens ein Feld, `priority` 1–10 (Ganzzahl), Rezept passt zum Maschinentyp |
| `questlog` | `accept {quest}`, `abandon {quest}` | Quest existiert, Statusübergang erlaubt, höchstens 3 aktive; Fortschritt nur aus echten Ereignissen (Abbau, Kills), Belohnung vom Server |
| `warps` | `teleport {warp}`, `favorite {warp, on}`, `create {name, icon}`, `delete {warp}` | Abklingzeit 10 s, Name 3–24 Zeichen `[A-Za-z0-9ÄÖÜäöüß _-]`, eindeutig, Symbol aus der Liste, höchstens 8 eigene, nur eigene löschen, Position = Standort des Spielers |
| `bestenliste` | `query {category, period}` | Werte aus den Enums; Antwort ist der neue Zustand |
| `profil` | `save {settings: {nickname, nickColor, status, chatFilter, volume, notifications, privateMessages, compactChat}}` | jedes Feld (Typ, Länge, Zeichen, Liste, `volume` 0–100 in 5er-Schritten), Fehler pro Feld; wendet Name/Farbe auf Anzeige- und Tablisten-Namen an |

Ungültige Typen (Strings statt Zahlen, Nachkommastellen, Steuerzeichen) → „Ungültige Anfrage.“; Rate-Limit wie
beim Shop (6 sofort, dann 4/s pro Spieler). Alles in-memory, beim Verlassen vergessen. Tests:
`example-server/src/test/.../demos/DemoControllersTest.kt`, `client/src/test/.../lua/DemoExtensionsTest.kt`.
