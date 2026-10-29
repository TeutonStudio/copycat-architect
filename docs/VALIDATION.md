# Tatsächlich ausgeführte Abnahme

## Panelgebäude mit bebaubarem Innenraum, 28.10.2026

Der konkrete Entwurf `PanelBuildingExample` ist erfolgreich geprüft. Er besteht aus **188 Copycat-Positionen** mit vier Panelwänden von **3/16 Dicke**, Schichtfenstern von **2/16 Dicke**, Leistenrahmen und senkrechten Eckleisten mit **2 × 2/16 Querschnitt**. Waagerechte Leisten schließen die oberen und unteren Fassadenkanten ab. Die Fenster liegen an der Blockkante, nicht als Scheibe in der Blockmitte. Hinter dem Glas verbleibt kein opakes Panel. Boden und Decke lassen **4 × 4 × 4 = 64 Innenzellen** vollständig frei.

Der abschließende Befehl `./gradlew build runGameTestServer runClient -PgraphWorldSmoke -PpanelBuildingSmoke --offline --console=plain` war erfolgreich: **115 JUnit-Tests ohne Fehler**, **alle 32 erforderlichen Minecraft-GameTests bestanden** und der vollständige native Panelhaus-Clienttest erfolgreich. Java 21 und die gepinnten Offline-Abhängigkeiten wurden verwendet. `earlyWindowControl` wurde für den Client vorübergehend deaktiviert und anschließend wiederhergestellt. `python3 tools/verify_resources.py` prüft die Ressourcen und Sprachen weiterhin erfolgreich.

Der neue Welttest baut das gesamte Haus im Überlebensmodus mit exakt **33.024 Rohmaterialeinheiten = 129 Zinkbarren**, 136 Eichenholzbrettern, 48 Steinziegeln, 16 Glas und 36 Tiefenschieferfliesen. Er prüft beide tatsächlichen Minecraft-Kollisionsabfragen an allen **770.048 Rasterpunkten** der 188 Blöcke, die Materialzuordnung und Quittung jeder BlockEntity, ihren NBT-Rundlauf sowie eine kostenfreie idempotente Wiederholung. Alle vier Panel-/Fensterrichtungen und sämtliche Eck- und Fassadenleisten behalten ihre genaue Stärke. Die 64 Innenzellen bleiben Luft. Ein Survival-Spieler steht danach im Innenraum und platziert ohne Schleichen eine echte Werkbank, einen Ofen und eine Truhe auf dem Copycat-Boden. Ofen und Truhe besitzen ihre normalen funktionalen BlockEntities; die drei Einbauten verändern keinen Teil der Gebäudehülle.

Dabei wurden zwei Produktlücken behoben: Raum und direkt aus Wänden aufgebautes Stockwerk bieten nun den optionalen typisierten Eingang **Eckleisten-Copycat** mit passender Drehung an allen konvexen Ecken. Ohne explizite Quelle vereinigt die automatische Eckfüllung die beiden kompatiblen Wandflächen. Außerdem reichen fertig verkleidete Copycats Blockitems zur gewöhnlichen Platzierung weiter, sodass sie normale Innenausbauten erlauben. Rohe Gruppen nehmen geeignete Materialien weiterhin als Verkleidung an. Die bisherigen Tests für Materialauftragen, Schraubenschlüssel, Drucker, Rückgaben, Kartuschen und Turtle bestehen weiterhin.

Die neue Clientprüfung lädt den tatsächlichen Grapheditor mit denselben Formen und Modulen wie der Welttest. Sie bestätigt den sichtbaren typisierten Eckanschluss und die exakte Planübereinstimmung in normalen und kompakten Graph-/Inspektoransichten. Die beiden Außenansichten und eine gesondert bezeichnete Schnittdarstellung werden mit echten Texturen und Minecraft-Blockrenderern erzeugt. Die Schnittansicht blendet Dach, Decke und zwei Vorderwände nur zum Betrachten aus; der geprüfte Bauplan bleibt unverändert.

Beispiel und Nachbauanleitung: [Panelgebäude](../examples/panel-building/README.md), vollständiges Referenzpaket [library.json](../examples/panel-building/library.json). Das geschlossene Prüfgebäude enthält keine Tür; freie Eingangszellen lassen sich im Wandmodul vorsehen. Einzelimporte vergeben neue IDs und erfordern danach die Auswahl der gespeicherten Formen/Module; das Referenzpaket ist kein Gesamtbibliotheksimport.

Visuell geprüft: [Außenansicht 35°](images/panel-building-exterior-35.png), [Außenansicht 215°](images/panel-building-exterior-215.png), [bebaubarer Innenraum mit Einbauten](images/panel-building-interior-furnished-cutaway.png), [Baugraph](images/panel-building-graph-normal.png), [Eckleisten-Inspektor](images/panel-building-corner-inspector-normal.png), [kompakter Graph](images/panel-building-graph-compact.png) und [kompakter Inspektor](images/panel-building-corner-inspector-compact.png).

Die aktualisierte Mod liegt unter `build/libs/copycat_architect-0.5.1.jar`; daneben befindet sich ihre SHA-256-Prüfsumme.

## Blockdrucker 0.5.0, 28.10.2026

Mit Eclipse Temurin Java 21 und den vorhandenen Offline-Abhängigkeiten geprüft:

| Prüfung | Ergebnis |
| --- | --- |
| `./gradlew build runGameTestServer -PgraphWorldSmoke --offline --console=plain` | Erfolgreich; **106 JUnit-Tests und alle 31 erforderlichen Minecraft-GameTests bestanden**. |
| `./gradlew build runClient -PprinterSmoke --offline --console=plain` | Abschließender Lauf erfolgreich, einschließlich GPU-Displayspiegelung und kompakter Layoutkorrekturen. |
| `./gradlew runClient -PkilaGraphSmoke --offline --console=plain` | Vollständige bestehende native Graph-Bedienprüfung erfolgreich. |
| `lua src/test/lua/architect_printer_test.lua` | Alle zehn Protokoll-, Aufräum-, Historien- und bisherigen Auftragsszenarien bestanden. |
| `python3 tools/verify_resources.py` | JSON, identische DE-/EN-Sprachschlüssel, Lua-Syntax und Übereinstimmung des ROM-Programms erfolgreich. |

Die Welttests verwenden tatsächliche Item-/Spielmodusinteraktionen für Druckerplatzierung und Kartuschenstapel. Sie prüfen sieben benannte Kartuschenplätze samt Itemkomponenten, Survival und Kreativ, Abbruch, erfolgreichen Druck, Materialmangel, Oberflächenverbrauch, gezielte Schraubenschlüsselentfernung und bezahlte Rückgaben. Abgewiesene Platzierungen und ein absichtlich fehlerwerfender Schutzmod-Hook erhalten Weltzustand und Inventare. Abgewiesenes Aufstellen oder Stapeln erzeugt keine zusätzlichen Drops. Entzogene Baurechte blockieren Änderungen am aufgestellten Gerät; die eigene gehaltene Speicherverwaltung bleibt erreichbar. Der Speicherwechsel synchronisiert den aktuellen Entwurf auch unmittelbar nach einer Vorschau. Benannte Historieneinträge behalten Materialien und Drehung, einschließlich Bibliotheks- und Item-NBT-Rundlauf; alte Bibliotheken ohne Druckeinstellungen bleiben lesbar.

Die Create-Prüfungen befüllen Kartuschen über die echte `GenericItemFilling`-API, prüfen Teilmengen und Entleerung sowie platzierte Einzel- und Viererstapel. Die 47 geladenen Zink-Recyclingrezepte werden gegen die Herstellungskosten der vorhandenen Copycats geprüft. Die Turtle-Prüfungen decken physische Geräte, sieben Plätze, Laden/Entnehmen, Besitzer und Sitzungen, Druck, Abbruch, volle Rückgabeinventare, Schutzabbruch sowie Historie und benannte Speicherung ab. Der bestehende Gebäudebau mit **603 tatsächlichen Turtle-Platzierungen**, Materialnachschub und Neustart besteht weiterhin; der ältere 1.115-Platzierungen-Test wird mit dem Graph-Schalter übersprungen.

Der Drucker-Clienttest läuft ohne geladene Welt mit tatsächlichen Minecraft-Ressourcen, KilaGraph-Widgets und Block-/BlockEntity-Renderern. Er bedient Gruppenansicht und Undo/Redo über echte Screen-Ereignisse und bestätigt die vollständige Wiederherstellung von Form, Materialien und Drehung. Er prüft normale und kompakte Graph-, Inspektor- und Speicheransichten, importierte Materialien außerhalb der Standardpalette, sieben sichtbare Plätze, gespeicherte Form-Icons sowie die vier Kreativkatalogabschnitte und ihre leeren Überschriftszeilen. Der Render-Hover wird über Minecraft-Mauskoordinaten ausgelöst. Die GPU-Aufnahme bleibt während des Augen-Hovers unverändert, läuft danach weiter und ist auf maximal 512 Pixel je Kante begrenzt. Die Modellansicht zeigt die echte unverzerrte UI-Textur auf dem Display und das transparente Druckergebnis im Rahmen.

Alle drei Ponder-Anleitungen sind registriert und werden mit ihren echten Schematic-Ressourcen und Storyboards komponiert: Drucker mit 30, Kartuschen mit 31 und Architektentisch mit 110 Instructions. **Die Ausführung und Darstellung dieser Ponder-Animationen in einer geladenen Clientwelt wurde in diesem weltlosen Lauf nicht geprüft.** Die lokale NeoForge-Einstellung `earlyWindowControl` wurde für die Clientläufe vorübergehend deaktiviert und danach wiederhergestellt.

Visuell geprüft: [Drucker mit gespiegelter UI](images/printer-frame-and-ui-display.png), [Innenraum und Vorschau](images/printer-frame-interior-preview.png), [Graph](images/printer-graph-normal.png), [kompakter Graph](images/printer-graph-compact.png), [Augen-Hover](images/printer-eye-world-peek.png), [Gruppen](images/printer-groups-normal.png), [kompakter Inspektor](images/printer-inspector-compact.png), [Speicherverwaltung](images/printer-storage-normal.png) und [kompakter Speicher](images/printer-storage-compact.png).

Die auslieferbare Datei ist `build/libs/copycat_architect-0.5.0.jar`; daneben liegt ihre SHA-256-Prüfsumme.

## Gruppen, Rotation, Gebäudeaufbau und Pistolenplatzierung, 28.10.2026

Mit Java 21 und vorhandenem Gradle-Cache geprüft:

- Abschließender `./gradlew build runClient -PkilaGraphSmoke --offline --console=plain`: erfolgreich; **100 JUnit-Tests ohne Fehler** und vollständige native Clientprüfung. Die aktualisierte Mod-JAR liegt unter `build/libs/copycat_architect-0.4.0.jar`; ihre SHA-256-Datei wurde neu erzeugt.
- Die Aufgabe `runGameTestServer -PgraphWorldSmoke` bestand **alle zwölf erforderlichen Minecraft-GameTests**, einschließlich des echten Turtle-Baus mit 603 Platzierungen, Neustart und eingefrorenen Vorlagen. Der ältere 1.115-Platzierungen-Turtle-Test wurde mit diesem Schalter übersprungen.
- `python3 tools/verify_resources.py`: JSON, DE-/EN-Sprachschlüssel und ROM-Lua erfolgreich geprüft.

Die Fachtests prüfen symmetrische Differenz mit überlappenden Körpern, verschiedene Eingangsgruppen, gemeinsame Gruppen, geschachtelte Materialvererbung und Rezeptkosten. Masken und Herkunft bleiben bei Rotationen erhalten. Schnitt und Differenz übernehmen eindeutig das Material ihres ersten Operanden; gespeicherte frühere Gruppierungs-Overrides bleiben lesbar. Die Gebäudeprüfungen decken Copycat-Sequenzen, Stockwerke direkt aus Wänden/Boden/Decke, Dachdeckung und Giebelsegmente, Luftgiebel, Attika, JSON-Rundläufe und das Entfernen zusätzlicher Copycat-Eingänge ab.

Die native Prüfung bedient tatsächliche KilaGraph-Anschlüsse, Kabel, Menüs und Inspektorfelder. Sie bestätigt semantische Copycat-/Rotation-Typen und die Ablehnung falscher Kabeltypen, einen eigenen Rotationsknoten samt Achsen-/Winkelwahl, Eingangsgruppen und Gleichheitswarnung sowohl für Vereinigung als auch symmetrische Differenz, passende Materialübernahme beim Gruppieren sowie vollständiges Rückgängig/Wiederholen. Eine eigene Gebäudevorlage prüft die expliziten Copycat-/Wand-/Stockwerk-/Dachanschlüsse, Segmenterstellung, echte Kabelentfernung, dynamische Eingänge und Stockwerksmaße. Alle neuen Ansichten werden auch kompakt bei 427 × 240 GUI-Pixeln geprüft. Die Testschritte warten nach tatsächlichen Aktionen auf zwei Renderframes, damit native Layout- und Popupbegrenzungen abgeschlossen sind.

Visuell kontrollierte Prüfbilder: [Rotationsknoten](images/kilagraph-disc-axis-controls.png), [kompakte Rotation](images/kilagraph-compact-disc-axis-controls.png), [Gruppenwarnung](images/kilagraph-union-uniform-warning.png), [symmetrische Differenz mit getrennten Eingangsgruppen](images/kilagraph-symmetric-difference-separated-input-groups.png), [kompakte XOR-Warnung](images/kilagraph-compact-symmetric-difference-uniform-warning.png), [expliziter Gebäudeaufbau](images/kilagraph-assembly-building-native.png), [Stockwerk aus Wänden, Boden und Decke](images/kilagraph-assembly-storey-controls.png), [Dachanschlüsse](images/kilagraph-assembly-roof-controls.png), [kompakte Stockwerksanschlüsse](images/kilagraph-compact-assembly-storey-connections.png), [kompakte Dacheigenschaften](images/kilagraph-compact-assembly-roof-controls.png) und [begrenztes Untermenü am Fensterrand](images/kilagraph-compact-context-last-source.png).

Der neue Pistolen-GameTest verwendet echte Raycasts und `ServerPlayerGameMode.useItemOn` für alle sechs Seiten eines vollständigen und eines halben Komposit-Copycats. Er prüft den freien Nachbarplatz, den Erhalt des angeklickten Blocks, Survival-Verbrauch und idempotente Wiederholung. Die Kompositblöcke verwenden dynamische BlockState-Formen; der Scheiben-Welttest vergleicht beide Minecraft-Kollisionsabfragen an sämtlichen 4.096 Rasterpunkten. Die NeoForge-Entwicklungseinstellung `earlyWindowControl` wurde für den Clientlauf vorübergehend deaktiviert und anschließend wiederhergestellt.

## Erweiterter Grundformkatalog, 27.10.2026

Der Katalog umfasst jetzt **18 Grundformen**. Neu sind Balken, Byte, Create-Leiste, vertikale Leiste, halbes Panel, mittiges Flachpanel, Byte-Panel sowie Halb-Schicht, vertikale Halb-Schicht und gestapelte Halb-Schicht. Das Inventar wächst passend auf zwei Reihen. Die Extrusionsausbeuten wurden aus den Stonecutting-Rezepten der gepinnten Mod-JARs übernommen und im ausgelieferten Datapack ergänzt.

`./gradlew build runGameTestServer -PgraphWorldSmoke --offline --console=plain` war mit Java 21 erfolgreich: **73 JUnit-Tests ohne Fehler**, **alle zehn erforderlichen Minecraft-GameTests bestanden**, einschließlich des Gebäudegraph-Turtle-Baus mit 603 Platzierungen. Der Quellenvergleich prüft jetzt jede zulässige Größe, Boden-/Deckenmontage, alle vier horizontalen Drehungen und bei neigbaren Quellen alle vier Neigungen. Damit wurden **872 Quellzustände an jeweils 4.096 Rasterpunkten** mit der tatsächlichen Minecraft-Form verglichen. Die neuen Fachtests prüfen außerdem die mittige Balken-/Flachpanel-Geometrie, acht Bytes als Vollblock, vier Byte-Panels als Panel, korrekte Rezeptkosten, Halb-Schichtgrößen und vollständige JSON-Rundläufe.

Der abschließende `./gradlew build runClient -PkilaGraphSmoke --offline --console=plain` war ebenfalls erfolgreich. Die native Bedienprüfung wählt alle 18 Einträge über echte Inventarslots, prüft die quellabhängige Rücksetzung, unveränderte Fremdfelder und Rückgängig/Wiederholen für jeden Eintrag. Tastaturnavigation in die zweite Reihe, Auswahl des letzten Eintrags und dessen Fokus nach erneutem Öffnen und Resize sind geprüft. Das Rechtsklickmenü ist in vier Gruppen mit maximal zehn Einträgen gegliedert. Im kompakten Fenster wurden die letzte Quelle, ihre Position auf dem Bildschirm, die korrekte Einfügeposition und Rückgängig geprüft. Die Position des Hauptmenüs wird an den GUI-Rand begrenzt; die Menüs werden über der Fußzeile gezeichnet.

Visuell geprüft: [18 Grundformen im Inventar](images/copycat-inventory.png), [kompakte Inventaransicht](images/copycat-inventory-compact.png) und [kompaktes Rechtsklickmenü bis zur letzten Quelle](images/copycat-context-compact.png). Die lokale NeoForge-Einstellung `earlyWindowControl` wurde für den Clientlauf vorübergehend deaktiviert und anschließend wiederhergestellt. `python3 tools/verify_resources.py` ist ebenfalls erfolgreich.

## Grundcopycat-Inventarauswahl, 27.10.2026

Mit Eclipse Temurin Java 21.0.7 und dem vorhandenen Gradle-Cache geprüft:

- `./gradlew build --offline --console=plain`: erfolgreich, **67 JUnit-Tests ohne Fehler**; aktualisierte Mod-JAR erzeugt.
- `./gradlew runClient -PkilaGraphSmoke --offline --console=plain`: vollständige native KilaGraph-Abnahme erfolgreich, einschließlich der neuen Inventarauswahl.
- `python3 tools/verify_resources.py`: JSON, DE-/EN-Sprachschlüssel und ROM-Lua erfolgreich geprüft.

Die neue Clientprüfung klickt den echten Button am KilaGraph-Eingangsknoten und den Grundform-Button im Bedienfeld. Alle acht unterstützten Formen besitzen registrierte Item-Symbole und erreichbare Inventarslots. Esc und die erneute Auswahl derselben Form erhalten Dokument und Undo-/Redo-Verfügbarkeit exakt. Der Wechsel setzt nur die bisher schon quellspezifischen Werte Größe, Versatz und Neigung zurück; Drehung, Montage, Materialgruppe, Verbindungen und andere Dokumentfelder bleiben erhalten. Ein einzelnes Rückgängig/Wiederholen stellt den vollständigen Zustand wieder her. Home, Pfeiltasten, Eingabe und Resize wurden ebenfalls über echte Screen-Ereignisse geprüft.

Visuell kontrolliert: [Auswahlbuttons am Eingangsknoten](images/copycat-source-nodes.png), [Inventar mit Minecraft-Rahmen](images/copycat-inventory.png) und [kompakte Inventaransicht bei 427 × 240 GUI-Pixeln](images/copycat-inventory-compact.png). Die Ansichten zeigen die tatsächlichen Copycat-Itemmodelle und die markierte aktuelle Grundform.

Ein zusätzlicher Clientstart blieb vor dem Spielstart bei NeoForges Ladefenster-Übergabe hängen. Der abschließende erfolgreiche Lauf verwendete vorübergehend `earlyWindowControl=false` in der lokalen Entwicklungskonfiguration; die Einstellung wurde anschließend wiederhergestellt. Welt-/Turtle-Tests wurden für diese reine Clientänderung nicht erneut ausgeführt.

## KilaGraph-Überarbeitung, 27.10.2026

Abschließend mit Eclipse Temurin Java 21.0.7, Gradle 9.8.0, KilaGraph 21.1.0.16 und LDLib2 2.2.44 geprüft:

| Befehl | Ergebnis |
| --- | --- |
| `./gradlew build runClient -PkilaGraphSmoke --offline --console=plain` | Erfolgreich; aktuelle Mod-JAR, **67 JUnit-Tests ohne Fehler** und vollständige native KilaGraph-Abnahme. |
| `./gradlew runGameTestServer -PgraphWorldSmoke --console=plain` | Erfolgreicher dedizierter Java-21-Serverstart; **alle 10 erforderlichen GameTests bestanden**. |
| `python3 tools/verify_resources.py` | JSON, deutsche/englische Sprachschlüssel und ROM-Lua erfolgreich geprüft. |

Der Client verwendet den tatsächlichen KilaGraph-Canvas. Die Abnahme bedient native Knoten, Anschlüsse, Kabel und Menüs über Maus-/Tastaturereignisse und kontrolliert:

- Exakte JSON-Gleichheit beim wiederholten Wechsel Graph/Bedienfeld, einschließlich unverbundener Entwurfszweige, Versionen, Namen, Materialien, Koordinaten sowie Undo-/Redo-Verfügbarkeit.
- Unveränderte Geometrie und Kosten beim Verschieben von Formknoten; identische Gebäudepläne beim Verschieben von Gebäudeknoten. Pan, Zoom und Einpassen ändern ausschließlich den Ansichtsstand.
- Echte Kabelverbindung, geschützte Ergebnisknoten, Zyklus- und Typablehnung sowie Auswahl/Entfernen eines Kabels mit gemeinsamem Rückgängig/Wiederholen.
- Benannte Neuanlage einer Gruppe direkt aus der Gruppenauswahl eines Knotens, richtige Zuordnung und vollständiges Rückgängig mit einem Schritt.
- Direkte Zahlen-/Texteingabe, ungültige Werte, Überlauf, Resize während einer Eingabe, Abbruch ohne Änderungen, Übernahme mit Eingabe und Korrektur nach abgewiesener Bearbeitung.
- Ältere graphlose Formen sowie klassische Gebäude bleiben ohne automatische Konvertierung erhalten. Beim klassischen Gebäude bleiben auch Aussparungen, Wandmuster, Leisten und der komplette berechnete Bauplan exakt gleich.
- Kompakte Ansicht bei 427 × 240 GUI-Pixeln: beide Darstellungen, scrollbare Eigenschaften, unveränderte Dokumente und lesbare, sich nicht überlagernde Übersichtsbeschriftungen.
- Rechtsklick am unteren/rechten Graphenrand: echtes Untermenü außerhalb der Zeichenfläche, Auswahl über dem Eigenschaftenbereich, Einfügen an den korrekten transformierten Graphkoordinaten und Rückgängig. Ein Außenklick schließt das Menü, ohne Eigenschaften darunter zu bedienen.
- Die vorhandene Mesh-Prüfung kontrolliert eindeutige Außenflächen, Materialprioritäten und die opake Grenzfläche hinter Glas erneut.

Die **13 nativen Prüfbilder wurden visuell kontrolliert**: [Formgraph](images/kilagraph-shape.png), [Form-Bedienfeld](images/kilagraph-shape-controls.png), [Gruppentextfelder](images/kilagraph-groups.png), [Gebäudegraph](images/kilagraph-building.png), [Gebäude-Bedienfeld](images/kilagraph-building-controls.png), [klassische Gebäudeübersicht](images/kilagraph-classic-building.png), [klassische Bedienung](images/kilagraph-classic-controls.png), [ältere Form](images/kilagraph-legacy-shape.png), [kompakter Formgraph](images/kilagraph-compact-shape.png), [kompaktes Form-Bedienfeld](images/kilagraph-compact-controls.png), [kompakter Gebäudegraph](images/kilagraph-compact-building.png), [kompaktes Gebäude-Bedienfeld](images/kilagraph-compact-building-controls.png) und [Rand-Untermenü](images/kilagraph-context-menu.png).

Der Serverlauf führte den echten Gebäudegraph-Turtle-Bau mit **603 Platzierungen** und **54 Teppichbelägen** aus, einschließlich Unter-/Erd-/Obergeschoss, je zwei Räumen, eingefrorenen Vorlagen, Computerneustart, Nachversorgung und abschließendem Welt-/Depotvergleich. `-PgraphWorldSmoke` überspringt ausdrücklich den älteren 1.115-Platzierungen-Bau; dieser wurde für die KilaGraph-Überarbeitung **nicht erneut ausgeführt**. Der dedizierte Entwicklungsserver startete in diesem Lauf mit den beiden zusätzlichen Bibliotheken; die Modmetadaten verlangen sie ausschließlich auf dem Client.

Die älteren Client-Schalter `-PclientSmoke`, `-PgraphSmoke` und `-PbuildingGraphSmoke` führen jetzt dieselbe aktuelle KilaGraph-Abnahme aus. Die folgenden Abschnitte dokumentieren vorherige Abnahmen der früheren Oberfläche.

Datum: 26.10.2026. Plattform: Linux, Eclipse Temurin Java 21.0.7, Gradle 8.13. Exakte Modkombination: [DEPENDENCIES.md](DEPENDENCIES.md).

## Befehle und Ergebnisse

| Befehl | Ergebnis |
| --- | --- |
| `./gradlew build` | Erfolgreich; Mod-JAR und 54 bestandene JUnit-Fachtests. |
| `./gradlew runGameTestServer` (auch zusammen mit `build` ausgeführt) | Echter dedizierter NeoForge-Testserver mit sämtlichen Abhängigkeiten; alle 10 erforderlichen GameTests bestanden. |
| `./gradlew runClient -PclientSmoke` | Erfolgreicher Minecraft-Clientstart; beide Editoren und ihre Teilansichten tatsächlich gerendert, Screenshots erzeugt und visuell geprüft. |
| `./gradlew build runClient -PbuildingGraphSmoke` | Gebäudegraph nativ gerendert; Rechteckgriffe, L-Raum, Raumpositionierung, belegte Raumgrenzen und dynamische Ober-/Untergeschosse geprüft. |
| `./gradlew build runClient -PgraphSmoke` | Abschließender Build und gezielte native Graph-Bedienprüfung. |
| `python3 tools/verify_resources.py` | JSON-Ressourcen, identische Sprachschlüssel und identisches ROM-/Quell-Lua geprüft. |
| `lua tools/test_turtle.lua lua/architect.lua` | Das ausgelieferte Bauprogramm hält bei fehlendem Treibstoff und unerwartetem Hindernis mit Rückmeldung an. |
| `luac -p lua/architect.lua` | Lua-Syntax gültig. |

Die Befehle wurden mit Java 21 über `JAVA_HOME` ausgeführt. Das standardmäßig vorhandene System-Java 25 wurde nicht als Minecraft-Ziel verwendet.

## Fachtests

Die 54 Tests prüfen unter anderem:

- L-Innenecke aus zwei Leisten, Drehungen, Boden-/Deckenmontage und Entfernen eines Schenkels.
- Identische und enthaltene Teile; keine kostenlos erhaltenen Restteile.
- Segmentmuster, Zwischenstücke, W=11/W=12, n=0/n=1, Folgen, feste Abschlüsse und variable Zwischenräume.
- Eindeutige Ecken, Öffnungen, Konflikte und alle vier Wandrichtungen.
- Geteilte Geschossplatten, Flachdach und Satteldach in beiden Richtungen, gerade/ungerade Breiten und L-Grundriss.
- Deterministische Beispieldaten mit 1.115 Platzierungen.
- Materialgruppen als disjunkte Voxelpartition, Prioritätswechsel, deckungsgleiche Gruppen und normalisierte verdeckte Teile.
- Zwei Slab-Materialien, konkrete Materialbedarfe und gleiche Weltanforderungen trotz umbenannter lokaler Gruppen-IDs.
- Gruppenrollen durch den gemeinsamen Gebäudegenerator bis zur Materialliste.
- Zehn grundlegende Graphfälle: exakte Boolesche Geometrie, dynamische Eingänge, geschachtelte Differenzen, geteilte Quellen, Materialvererbung, Maskendrehung, JSON-Rundlauf, unvertraute Ergebnisdaten, Zyklen/ungültige Ports und Migration alter Formschemata.
- Fünf weitere Materialfälle: alle drei Operationen mit Gruppiert/Separiert, verschachtelte Neuzuweisung, ein gemeinsames Quellrezept über Materialzweige, unveränderte Kosten und Geometrie bei Vorschaufiltern sowie Persistenz und alte Graphen.
- 16 Gebäudegraphfälle: verschachtelte feste Muster, Rechteckvereinigungen und Wandläufe, adaptive Luft, belegte Raumgrenzen, tragfähige Teppichschichten, Raumversätze, gemeinsame Platten, Ober-/Untergeschosse, temporäre Anschlüsse, Typ-/Zyklusablehnung, allgemeines orthogonales Satteldach, persistierte Graphen und ausgelieferte Beispiele.

## Zehn Tests in einer echten Minecraft-Welt

1. Survivalplatzierung einer L-Form: exakt 32 Rohmaterialeinheiten, ein Oberflächenblock, wiederholter Auftrag ohne erneute Abbuchung, BlockEntity-NBT-Rundlauf.
2. Abhängigkeiten und Persistenz: alle angebotenen Quell-IDs vorhanden, Upgrade registriert, Bibliothek und begonnener Auftrag durch tatsächlich komprimiertes und wieder gelesenes NBT gespeichert. Größere Aufträge verwenden Bytefelder und überschreiten nicht mehr das NBT-Stringlimit.
3. Vollständiger Turtle-Bau: **1.115 Positionen**, zwei Geschosse, L-Grundriss, Fensteröffnungen, Leisten, getrennte Boden-/Deckenplatten und Satteldach. Die Platten werden durch einen Graphen mit **Differenz, Schnitt und Vereinigung** erzeugt, einschließlich explizit gruppierter Verarbeitungsknoten, und verwenden zwei Materialgruppen mit **Stein und Eichenbrettern**. Eine reale CC:Tweaked-Turtle führt das ausgelieferte Lua-Programm aus, fährt benachbarte Arbeitspositionen an, lädt aus der Depotkiste nach und kehrt am Ende zum Depot zurück. Der Computer wird während des Baus neu gestartet; zusätzlich wird pausiert und nach erneuter Baustellenprüfung fortgesetzt. Abschließend werden **alle Weltpositionen und alle Freiraumanforderungen** mit dem eingefrorenen Plan verglichen.
4. Fehlerfälle: fehlendes Material, blockierte Position und fehlender Platz für Rohstoffreste ändern weder Inventar noch Welt.
5. Geometrieadapter: die tatsächlichen Blockstates der gepinnten Create-/Copycats+-Version werden gegen das 16³-Fachmodell verglichen, einschließlich Drehung, Montagehälfte und Größenparametern.
6. Mehrmaterialverbrauch: fehlende zweite Materialart verhindert jede Abbuchung; erfolgreiche Platzierung verbraucht genau 288 Einheiten sowie je einen Stein- und Holzblock. Wiederholung ist kostenlos und idempotent. Gruppen, Priorität, Palette und Quittung überstehen NBT. Abbau gibt genau die bezahlten Ressourcen zurück; ein anderer Vordergrund wird nicht als identischer Zielzustand akzeptiert.

7. Graphplatzierung: Ein Vollblock erhält einen echten Ausschnitt durch Differenz mit einer vertikalen Leiste. Nach 90°-Drehung werden nur die verbleibende Quelle und eine Oberfläche berechnet; die wieder geladene BlockEntity besitzt an allen 4.096 Rasterpunkten die erwartete Kollisionsform. Wiederholung ist idempotent und die Quittung enthält keine Cutter-Rückgabe.

8. Knotengruppierung: Eine Vollblockquelle verzweigt in obere Differenz und unteren Schnitt. Der Schnitt erhält über `GROUPED` die Holzgruppe; die Vereinigung bleibt `SEPARATED`. Es werden exakt 256 Quelleneinheiten und zwei Oberflächen bezahlt. Vorschauauswahl „Holz“ beeinflusst die vollständige Platzierung nicht. Materialpartition, gemeinsame Rezeptreferenz und Quittung überstehen NBT; die Wiederholung belastet kein Material und beim Abbau werden genau die bezahlten Ressourcen erstattet.

9. Native Teppichschichten: Ein Teppich ohne Tragschicht wird vor Weltänderung oder Abbuchung abgewiesen. Nach Platzierung der tragenden Copycat-Schicht entsteht ein echter Vanilla-Teppich; ein Teppichitem und ausschließlich die Rohstoffe der Tragform werden verbraucht. Wiederholung ist idempotent.
10. Vollständiger **Gebäudegraph-Turtle-Bau mit 603 Positionen**: verschachteltes festes Wandmuster A + BC, Untergeschoss/Erdgeschoss/Obergeschoss mit je zwei Rauminstanzen, gemeinsam benutzte Platten, **54 echte Teppichbeläge**, eigene Decken und separates Dach. Der gesamte Graph und seine Geschossschlüssel überstehen den SavedData-Rundlauf. Während des Baus werden die Formvorlagen im Test entfernt und der Computer neu gestartet; der eingefrorene Auftrag baut dennoch fertig. Alle Ziel- und Freiraumpositionen werden mit der Welt verglichen; die Turtle kehrt zum Depot zurück.

Der abschließende reguläre Serverlauf mit allen Tests meldete: `All 10 required tests passed`. Auch alle 54 JUnit-Tests sind erfolgreich.

## Client- und Renderprüfung

Der automatische Testclient öffnet Formeditor, Modulraster, Modul-3D-Vorschau, Wandmuster, Gebäudedarstellung und Auftragsansicht. Opaque Oberfläche, gerichtete Holztextur und Glas werden dargestellt. Eine zusätzliche Mesh-Prüfung kontrolliert beim überlagerten Vollblock:

- genau 1.536 äußere 1/16-Rasterflächen, jede genau einmal;
- Material der oberen Fläche entspricht der vorderen Gruppe;
- beim Prioritätswechsel gehört die gesamte Oberfläche der anderen Gruppe;
- hinter einer Glasschicht existiert genau die benötigte opake Grenzfläche, ohne deckungsgleiche Glas-Rückfläche.

Der native Nodegraph wurde ebenfalls tatsächlich im Minecraft-Client gerendert. Die Bedienprüfung erweitert eine Vereinigung auf drei Eingänge, verbindet den neuen Anschluss über echte Screen-Mausereignisse, verschiebt einen Knoten und prüft Rückgängig/Wiederholen, Hinzufügen/Löschen, den Wechsel zur Teilebearbeitung, Zyklusablehnung, Paketbegrenzung, Zoom sowie den JSON-Rundlauf. Ein fehlender Anschluss erzeugt eine konkrete Meldung im Ergebnisknoten. Die Ergebnisvorschau zeigt die vereinigte ausgeschnittene Stein-/Holzgeometrie. Seit 0.3.0 betätigt die native Prüfung außerdem den Gruppiert-/Separiert-Schalter, wählt die gemeinsame Zielgruppe aus dem Dropdown und schaltet den Ergebnisfilter über beide Materialgruppen wieder auf alles. Dabei werden sichtbare Voxelpartition und unveränderte Gesamtkosten kontrolliert. Der finale gezielte Clientlauf wurde mit `./gradlew runClient -PgraphSmoke` ausgeführt und erfolgreich beendet.

Prüfbilder: [Nodegraph](images/nodegraph.png), [Dropdown](images/nodegraph-dropdown.png), [einzelne Gruppe](images/nodegraph-group.png), [unverbundener Eingang](images/nodegraph-error.png), [Gebäude](images/building.png), [Materialgruppen](images/material-groups.png), [geänderter Vordergrund](images/material-groups-background.png), [Glasgruppe](images/material-groups-glass.png).

Die native Gebäudegraph-Abnahme arbeitet ebenfalls über echte Screen-Mausereignisse: Rechteck an der Ecke vergrößern, Wandverbindungen erhalten, Rückgängig/Wiederholen, Stockwerk an Ober-/Untergeschoss anschließen und unbenutzte Zusatzanschlüsse ausblenden, zweite Rauminstanz automatisch platzieren und per Ziehen verschieben. Eine zusätzliche Runde fügt ein zweites Raumrechteck hinzu, verschiebt es zu einem L und verbindet passende adaptive Luftwände. JSON-Rundlauf und derselbe Generator werden geprüft. Screenshots wurden im Minecraft-Client aufgenommen und visuell kontrolliert: [Graph](images/building-graph.png), [mehrere Geschosse](images/building-graph-levels.png), [temporäre Anschlüsse](images/building-graph-level-sockets.png), [L-Raum und Raumversätze](images/building-graph-room-union.png).

Für die Entwicklung existiert `-PgraphWorldSmoke`: Hierbei wird der bisherige 1.115-Positionen-Test ausgelassen und der neue Graphbau gezielt geprüft. Der oben genannte abschließende `runGameTestServer`-Lauf verwendete diese Option **nicht**; beide vollständigen Bauten liefen.

## Grenzen der Abnahme

Kein manuelles Mehrspieler-Langzeitspiel und keine Prüfung mit zusätzlichen Schutzmods. Ein ordentlicher Welt-Speicher-/Laderundlauf ist getestet; ein Prozessabbruch mitten in unabhängigen Minecraft-Chunk-/Inventarschreibvorgängen ist kein getestetes Transaktionsverfahren. Create-/Copycats-Connected-Textures zwischen Kompositblöcken sind nicht implementiert. Die angebotene Materialpalette enthält zehn Vanilla-Vollblöcke; Wände sind eine Zelle dick, klassische L-Dächer auf die dokumentierte Zwei-Flügel-Anordnung begrenzt. Der neue Gebäudegraph verwendet die beschriebenen endlichen orthogonalen Vereinigungen. Untergeschosse werden relativ zur tiefsten Konstruktion gestapelt; automatischer Erdaushub ist nicht implementiert. Diese Beschränkungen werden in [IMPLEMENTATION.md](IMPLEMENTATION.md) beschrieben und nicht als bestandene weitergehende Tests ausgegeben.


## UI-Überarbeitung, 27.10.2026

Mit Java 21 und dem vorhandenen Gradle-Cache wurden `./gradlew build --offline`, `./gradlew runClient -PgraphSmoke --offline`, `./gradlew runClient -PbuildingGraphSmoke --offline` und `python3 tools/verify_resources.py` erfolgreich ausgeführt. Alle 54 Fachtests sind erfolgreich. Die nativen Prüfungen arbeiten mit Screen-Maus- und Tastaturereignissen und kontrollieren zusätzlich:

- Rechtsklickmenü, Einfügen an den korrekten Graphkoordinaten nach Zoom/Verschieben, Rechtsziehen ohne Einfügen, Esc und Außenklick.
- Direkte Dropdown-Auswahl für Eigenschaften und Verbindungen; passende Quellen, Materialmodus und Ergebnisfilter.
- Exakte Gleichheit des Graphen vor/nach dem Ansichtswechsel sowie unveränderte Layoutkoordinaten beim Bearbeiten über das Bedienfeld; ansichtsübergreifendes Rückgängig.
- Oberflächen- und Gruppenwahl in der gemeinsamen Fußleiste ohne Änderung von Graph oder Geometrie; Tastaturbedienung und modale Menüs.
- Kompakte Ansicht bei 427 × 240 GUI-Pixeln: Kopf und Fußleiste bleiben sichtbar, der Inhalt scrollt ohne Überlappung oder Entwurfsänderung. Große Vorschauen sind bei dieser Höhe abschnittsweise sichtbar.

Beide gezielten Clientläufe endeten erfolgreich. Aktuelle native Prüfbilder wurden visuell kontrolliert: [Formgraph](images/nodegraph-ui.png), [Form-Bedienfeld mit Ergebnisvorschau](images/nodegraph-controls.png), [Einfügen per Rechtsklick](images/nodegraph-context.png), [Gebäude-Bedienfeld mit Ergebnisvorschau](images/building-graph-controls.png), [Gebäude-Knotenkatalog](images/building-graph-context.png). Die früher dokumentierten Welt- und Turtle-Prüfungen wurden für diese UI-Änderung nicht erneut ausgeführt.
