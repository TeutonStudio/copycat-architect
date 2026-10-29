# Lua-API 0.4

Drucker wie ein normales Turtle-Upgrade ausrüsten: Drucker in ausgewähltem Slot, dann `turtle.equipLeft()` oder `turtle.equipRight()`. Die bestehende Datapack-Upgrade-ID `copycat_architect:extrusion_gun` und der Peripheralname bleiben erhalten. Für die physische Druckersteuerung benötigt die Turtle zusätzlich einen Drucker in ihrem Inventar; das ausgerüstete Upgrade wird dabei weder verbraucht noch dupliziert.

```lua
local architect = peripheral.find("copycat_architect")
assert(architect, "Drucker-Upgrade ausrüsten")
local ok, forms = architect.forms()
if not ok then error(forms) end
for _, form in ipairs(forms) do print(form.key, form.name, form.units) end
```

**Jede Methode liefert `true, Ergebnis` oder `false, Fehlermeldung`.** Alle Welt- und Inventarzugriffe werden über die Turtle-Befehlswarteschlange auf dem Serverthread ausgeführt. Kein Aufruf setzt die Turtle-Position.

| Methode | Ergebnis / Bedeutung |
| --- | --- |
| `pose()` | `{x,y,z,facing,fuel}`. Richtung: Süd 0, West 1, Nord 2, Ost 3. Tatsächliche Position ohne GPS. |
| `forms()` | Liste `{key,name,version,units,groups}` aus der Bibliothek des Turtle-Besitzers. |
| `inspect(direction)` | `{air,block,parts,material,groups,surfaces}` beim Nachbarblock. `direction`: `front`, `up`, `down`. |
| `cost(key)` | `{units,surfaceBlocks=1,groups}` für eine einfarbige Platzierung. Für mehrere Materialien `requirements` verwenden. |
| `place(direction,key,material,rotation)` | `{placed=boolean}`. Ausrichtung absolut: 0–3 Vierteldrehungen um Y. Tatsächliche Nachbarplatzierung aus Turtle-Inventar; passende existierende Form kostet nichts. |
| `requirements(key,paletteJson)` | Konkreter Bedarf `{units,surfaces}` für eine JSON-Zuordnung von Gruppen-IDs zu Material-IDs. |
| `placeGrouped(direction,key,paletteJson,rotation)` | Mehrmaterialform aus dem realen Turtle-Inventar platzieren. Beispiel unten. |
| `resupply(jobId)` | `{items={itemId=count}}`: gezielt aus Kiste/Fass unmittelbar unter der Turtle am zugewiesenen Depot nachladen. |
| `jobs()` | Zugängliche Aufträge des Besitzers in dieser Dimension: `{id,name,state,computer,done,total}`. |
| `claim(jobId)` | `{id,dock,min,max,state,index}`. Erstzuweisung nur am angegebenen Depot, danach nur derselbe Computer. Im Editor kann die Computer-ID vorgegeben werden. |
| `next(jobId)` | `{state,message,index,total,target,units}`. `target.pos` ist Weltposition, `target.parts` eingefrorene Geometrie; `index` ist nullbasiert. |
| `build(jobId,index)` | `{done=true,index}`. Turtle muss direkt **über** dem Ziel stehen. Konsumiert Material und schreibt Fortschritt. Wiederholung einer bereits verbuchten Indexnummer berechnet nichts erneut. |
| `report(jobId,message)` | Rückmeldung an den Gebäudeeditor; maximal 200 Zeichen. |

Die vorhandenen Methoden für gespeicherte Formen und Bauaufträge bleiben kompatibel. Die folgenden Methoden steuern zusätzlich einen echten Drucker unmittelbar neben der Turtle. Alle Richtungen sind relativ zur aktuellen Turtle-Ausrichtung: `front`, `up` oder `down`.

| Methode | Ergebnis / Bedeutung |
| --- | --- |
| `printerBegin(direction)` | Setzt einen zusätzlichen Drucker aus dem Turtle-Inventar in das leere Nachbarfeld. Liefert `{session,position,formJson,paletteJson,rotation,slots}`. Öffnet kein Spielerfenster. Gespeicherter Entwurf und sieben eingebaute Kartuschen bleiben erhalten. |
| `printerPreview(direction,session,formJson,paletteJson,rotation)` | Übernimmt einen vollständigen Form-JSON-Entwurf samt Gruppenpalette und absoluter Y-Drehung 0–3. Liefert `{session,units,surfaces,groups,rotation}`. Verändert nur die Druckervorschau und verbraucht nichts. Form-JSON maximal 28.000 Zeichen, Palette maximal 2.000 Zeichen. |
| `printerPrint(direction,session)` | Druckt den aktuellen Entwurf. Verbraucht die benötigten Kartuschen-/Verkleidungsressourcen aus Drucker und Turtle-Inventar, ersetzt den Drucker durch den Copycat und gibt das aktualisierte Druckergerät zurück. Ergebnis `{printed,position,printerSlot,historyId}`. `printerSlot` bezeichnet den Rückgabeplatz im Turtle-Inventar. |
| `printerCancel(direction,session)` | Entfernt den benachbarten Drucker und gibt ihn mitsamt Entwurf und Kartuschen ohne Druckverbrauch zurück: `{cancelled,printerSlot}`. |
| `printerSlots(direction,session)` | Sieben Einträge `{slot,item,units,creative}`. Druckerplätze sind **1–7**; leere Plätze haben `item=""` und `units=0`. Kreative Kartuschen haben `creative=true`. |
| `printerLoadCartridge(direction,session,printerSlot,turtleSlot)` | Legt eine reale Kartusche aus Turtle-Platz **1–16** in Druckerplatz **1–7**. Ein vorheriger Inhalt gelangt ins Turtle-Inventar zurück. `turtleSlot=0` entlädt den Druckerplatz. Liefert die aktualisierte Liste der sieben Plätze. Ohne freien Rückgabeplatz bleibt alles unverändert. |
| `printerHistory()` | Abgeschlossene Drucke des Besitzers, neueste zuerst: `{id,name,time,units,formJson,paletteJson,rotation}`. `time` ist Unixzeit in Millisekunden. Der Verlauf ist auf 24 Einträge und 80.000 JSON-Zeichen begrenzt; große Bibliotheken können ältere Einträge früher verdrängen. |
| `printerSaveHistory(id,name)` | Speichert eine benannte Form aus dem Druckverlauf mitsamt Palette und Ausrichtung: `{key,name}`. Name: 1–64 Zeichen. Der gespeicherte Druck erscheint anschließend in der synchronisierten Formenbibliothek und im Abschnitt „Gespeicherte“. |

Jeder Druckerzugriff prüft Besitzer, Sitzung und das direkt benachbarte, geladene Ziel. Wird die Turtle bewegt oder gedreht, muss `direction` weiterhin auf denselben Drucker zeigen. Eine fremde oder abgelaufene Sitzung ist ungültig. Platzierung und Druck beachten den Schutz der Welt. Vor `printerPrint` und `printerCancel` muss mindestens ein Turtle-Inventarplatz frei sein; dieser bleibt beim Druck für das zurückgegebene Gerät reserviert. Bei fehlenden Ressourcen bleibt der Drucker bestehen. Es werden weder Upgrade noch Kartuschen oder Druckergehäuse erzeugt.

Eine leere Gruppenpalette `{}` druckt unverkleidetes Copycat-Rohmaterial. Gruppen mit gewähltem Material benötigen den zugehörigen Verkleidungsblock. Der Druckverlauf speichert auch die Palette und die Ausrichtung; zum Wiederholen werden `formJson`, `paletteJson` und `rotation` an `printerPreview` übergeben. `printerSaveHistory` erhält diese Einstellungen für den benannten Druck, einschließlich Laden im Drucker und des Eintrags im Kreativkatalog.

```lua
local architect = assert(peripheral.find("copycat_architect"))
-- Ein zusätzliches Druckergerät und passende Ressourcen liegen im Turtle-Inventar.
local ok, session = architect.printerBegin("front")
if not ok then error(session) end

local formFile = assert(fs.open("my-form.json", "r"))
local formJson = formFile.readAll()
formFile.close()
local palette = textutils.serializeJSON({body="minecraft:oak_planks"})
local ready, preview = architect.printerPreview("front", session.session, formJson, palette, 0)
if not ready then
    architect.printerCancel("front", session.session)
    error(preview)
end
print(preview.units, "Zinkeinheiten")
local printed, result = architect.printerPrint("front", session.session)
if not printed then
    -- Ressourcen nachlegen und mit derselben Sitzung erneut drucken oder abbrechen.
    error(result)
end
print("Drucker zurück in Slot", result.printerSlot)
local saved, form = architect.printerSaveHistory(result.historyId, "Meine gedruckte Form")
if not saved then error(form) end
print(form.key)
```

```lua
local ok, cost = architect.cost("inner@1")
if ok then print(cost.units, "Zinkeinheiten") end
local ok, result = architect.place("front", "inner@1", "minecraft:oak_planks", 0)
if not ok then print(result) end
```

## Geliefertes Bauprogramm

`architect` ist als ROM-Programm in jeder Turtle verfügbar. Die Quelldatei liegt zusätzlich unter `lua/architect.lua`. Installation einer automatischen Wiederaufnahme:

```text
architect list
edit startup.lua
```

In `startup.lua`:

```lua
shell.run("architect", "HIER-DIE-AUFTRAGS-ID")
```

Danach `reboot`. Alternativ direkt `architect AUFTRAGS-ID` ausführen. Die tatsächliche Position, Orientierung und der nächste Bauindex werden nach einem Neustart vom Peripheral gelesen. Ein lokales GPS oder eine unzuverlässig nach jedem Schritt geschriebene Positionsdatei werden nicht benötigt.

Für einen einzelnen physischen Druck aus einer exportierten Formdatei:

```text
architect print front my-form.json palette.json 0
architect print up my-form.json - 1
architect history
architect save-print DRUCKVERLAUF-ID Meine Form
```

`palette.json` enthält beispielsweise `{"body":"minecraft:oak_planks"}`. `-` wählt eine unverkleidete Form. Das Programm setzt einen realen Drucker, überträgt den Entwurf und druckt. Bei einem Fehler versucht es, das Gerät wieder abzuholen. Falls der Schutz der Welt oder ein volles Inventar das Abholen verhindert, zeigt es die weiterhin gültige Sitzung an; Drucker und Inhalt bleiben an Ort und Stelle. Die vorhandenen Auftragsbefehle werden unverändert ausgeführt.

Der Materialbehälter steht direkt unter der Depotposition. Die Turtle verwendet `resupply`, um pro benötigter Materialart einen Stapel aus der unmittelbar benachbarten Kiste oder dem Fass zu übernehmen, und hält an, wenn danach Rohstoff, Oberfläche, Inventarplatz oder Treibstoff fehlen. Lege die benötigten Materialien, Zink und Kohle im Behälter bereit. Weitere Stapel derselben Materialsorte verdrängen keine anderen Paletteinträge aus dem Turtle-Inventar. Es gibt keine automatische Materialerzeugung. Als Treibstoff werden ausschließlich Kohle, Holzkohle und Kohleblöcke verbrannt, damit Oberflächen-Holz unangetastet bleibt.

Pause/Weiter/Abbruch erfolgen am Bauplantisch. Bei unerwarteten Hindernissen endet das Programm mit einer sichtbaren Meldung. Hindernis manuell beheben und dasselbe Programm erneut starten. Die Turtle gräbt niemals. Abbruch behält bereits gebaute Blöcke; deren bezahlter Verbrauch bleibt als Abbauquittung gespeichert.

## Mehrere Materialgruppen

Gruppen-IDs und Prioritäten stehen in `forms()` und `inspect()`. Die Schlüssel sind lokal zur gespeicherten Formversion. Für eine Vorlage mit den Gruppen `body` und `cap`:

```lua
local palette = textutils.serializeJSON({
    body = "minecraft:stone",
    cap = "minecraft:oak_planks",
})
local ok, need = architect.requirements("multi_material@1", palette)
if not ok then error(need) end
for item, count in pairs(need.surfaces) do print(item, count) end
local ok, result = architect.placeGrouped("front", "multi_material@1", palette, 0)
if not ok then print(result) end
```

Neue Bibliotheken enthalten `multi_material@1`. Bei einer älteren Bibliothek die Beispieldatei `examples/multi-material-form.json` im Formeditor importieren und speichern; beim Import wird eine neue ID vergeben. In diesem Fall den tatsächlichen Schlüssel aus `forms()` übernehmen. In Bauaufträgen sind Gruppen und Materialzuordnungen bereits eingefroren; das unveränderte `architect`-Bauprogramm verarbeitet sie mit `next`/`build`.


Graphformen benötigen keine zusätzliche Turtle-Methode: Der Server berechnet und speichert das Ergebnis beim Speichern der Vorlage. `place`, `placeGrouped`, `requirements` sowie das Auftragsprogramm verwenden denselben eingefrorenen Geometrieschnappschuss, einschließlich Differenz-/Schnittmasken und Materialgruppen. Ein Graph kann unter `examples/nodegraph-form.json` importiert werden. Unverbundene oder leere Ergebnisse können nicht als Formversion gespeichert werden.

Die Knotenschalter „Gruppiert / Separiert“ werden vor dem Speichern ausgewertet; die gewählten Gruppen erreichen die Turtle unverändert. Der Ergebnis-Gruppenfilter begrenzt ausschließlich die Editoransicht. Er ändert weder `requirements` noch den gebauten Block.


Gebäudegraphen verwenden dieselben Aufträge und dasselbe `architect`-Programm. Wandmuster, Raumgeometrien, Raumversätze, Schichten und Geschosslagen werden vor Auftragserzeugung zu vollständigen Platzierungen/Freiraumanforderungen berechnet. Der laufende Auftrag benötigt anschließend keine bearbeitbaren Raum- oder Formvorlagen mehr. Bei Untergeschossen ist der gewählte Bauursprung die tiefste konstruktive Lage; Depot und Startorientierung werden wie bisher relativ dazu eingestellt. Teppichbeläge erscheinen als normale benötigte Items, beispielsweise `minecraft:white_carpet`, und werden mit realem Verbrauch auf ihre bereits gebaute Tragschicht gesetzt.
