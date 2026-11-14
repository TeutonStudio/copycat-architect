# Copycat Architect Graphstudio 0.6.4

Browsereditor für Form- und Gebäudegraphen. Die Beispieldateien stammen aus
[`examples/`](../examples). Die beiden Editoren verwalten getrennte Entwürfe.

## Start

Benötigt werden Node.js 22.12 oder neuer, Java 21, Bash und curl.

```sh
cd web
npm ci
../gradlew -p .. test --tests "*GraphstudioExchangeTest.nativeCorpus"
npm test
../gradlew -p .. test --tests "*GraphstudioExchangeTest.browserExports" --rerun
npm run dev
```

`npm run build` erzeugt die statische Website in `web/dist`. Der Kotlin-Compiler
und dessen Laufzeit werden in der gepinnten Version aus Maven Central geholt.
Die generierten Dateien gehören zum Build.
Der Build legt die Projektlizenz, die npm-Lizenzen sowie Lizenz und Herkunft
der eingebundenen Kotlin-Standardbibliothek im Ausgabeverzeichnis ab.

Der Workflow prüft außerdem mit `./gradlew test` die Minecraft-Seite der
gemeinsamen Domänenlogik. Der Browser wird aus dem Fachkern desselben
Repository-Stands gebaut. Native Knoten und Einstellungen werden verlustfrei gelesen, bearbeitet und exportiert.
Die unten beschriebene Referenzerweiterung hat einen eigenen Browservertrag.

## Vorschau-HUD

Die 3D-Vorschau besitzt zwei seitliche Panels: links die sechs Seitenansichten
und **Einpassen**, rechts Status, Achsenlegende, Warnungen und Materialbedarf.
Die Zeichenfläche nutzt dazwischen die volle Höhe; lange Inhalte und Ansichten
werden mit Vor-/Zurück-Tasten in Seiten aufgeteilt. Das gilt auch bei schmaler Seitenleiste. Im Kopf schaltet
**Graph / Selektiert** direkt zwischen Gesamtergebnis und ausgewähltem Knoten
um. Der aktive Knopf ist hervorgehoben; Tab und Enter bzw. Leertaste bedienen
den Schalter per Tastatur.

Die Arbeitsfläche bleibt innerhalb des Browserfensters. Bei schmalen oder flachen
Fenstern schalten **Graph / Vorschau / Eigenschaften** zwischen den Ansichten um.
**Datei & Bibliothek** öffnet die vorhandenen Datei-, Beispiel-, Speicher- und
Undo-Funktionen. Inspektor, Werkzeuge, Bibliotheken und Vorschau-HUD verwenden
Seitensteuerungen; auch geöffnete Eigenschaftsabschnitte bleiben erreichbar.

## Referenzknoten und Architekturvorlagen

Im Gebäudegraph heißt die bisherige Copycat-Quelle **Referenz**. **Referenz auswählen**
öffnet einen gemeinsamen Dialog mit Grundformen, gespeicherten Formen, Gebäudegraphen
sowie gespeicherten Knotengruppen/Karten. Ordner entstehen aus Namen mit `/`.
Der Ordner **deutsche Renaisance** enthält zwölf vollständige Bauteile, darunter
Wandsegmente, Säule, Pilaster, Kreuzstock- und Rundbogenfenster, Portal, Gesims,
Sockelband, Ziergiebel und Balustrade. Einzelne Copycat-Bauteile liegen im Unterordner
**Bauteile**. Eine Vorlage setzt einen vollständigen Bauteilknoten ein.

Bei Graphen und Gebäudegruppen wählt **Ergebnisausgang** den typisierten Ausgang.
Die Instanz bleibt ein sichtbarer Knoten, hält ihre Definition samt Bibliothek und
Versionsnummer fest und veröffentlicht offene Eingänge als typisierte Anschlüsse.
Spätere Änderungen oder das Löschen des Bibliothekseintrags verändern bestehende
Instanzen nicht. Die 3D-Vorschau wertet die eingebettete Definition aus; inkompatible
Verbindungen, rekursive Definitionen und Überschreitungen der nativen Knotengrenze
werden abgewiesen. Graphspeicherungen erhalten fortlaufende Versionen.

Der Browservertrag verwendet `referenceSchema: 1` und `kind: "REFERENCE"` mit
`reference: {schema, id, version, output, document, library, bindings}` sowie
`referenceInputs` und optionalen `referenceOverrides` für Instanzeinstellungen. JSON-Export, Zwischenablage und Browsersicherung erhalten diese
Definitionen. **Dieses erweiterte Format benötigt den in
[Issue #26](https://github.com/TeutonStudio/Copycat-Architecture/issues/26)
beschriebenen Modumbau, bevor es im Spiel importiert werden kann.** Dokumente ohne
diese Erweiterung verwenden weiterhin den bisherigen Minecraft-Vertrag.

Browserprüfung gegen einen gestarteten Entwicklungs- oder Produktionsserver:

```sh
python3 tools/verify-reference-browser.py --url http://127.0.0.1:4193/ --chromium /pfad/zu/chromium
```

Sie prüft Vorlagenauswahl, 3D-Vorschau, Übernahme, erneutes Laden und alle Seiten
von Eigenschaften, Werkzeugen, HUD und Bibliothek bei zehn Fenstergrößen
(einschließlich 320 × 568, 844 × 390 und den effektiven CSS-Fenstergrößen bei
125/150/200 % Zoom auf einem 1280 × 720 Bildschirm).

## Austausch mit Minecraft

Form- und Gebäudedokumente verwenden die JSON-Felder des Mods. React Flow
übersetzt Verbindungen über die gemeinsame Kotlin-Domänenlogik in diese Felder.
`GraphModels.kt` definiert die Knotentypen, Graphgrenzen und Anschlüsse beider
Editoren. Die Gebäudeanschlüsse ergeben sich aus dem Grundriss, den Eingaben
und den Geschossen. `DiagramEdits` wendet Verbindungsänderungen für
beide Oberflächen an.

Der Web Worker führt `GraphCompiler` und `BuildingGraphCompiler` aus. Die
3D-Ansicht zeigt die berechneten Rasterformen mit Materialfarben oder
Texturvorschauen. Minecraft-Spezialrenderer werden nicht nachgebildet.
Die Bibliothek lässt sich als JSON mit `forms` und `modules` öffnen.
Für ältere Gebäudedokumente wird ein in Minecraft erzeugter Gebäudegraph benötigt.

Formexporte enthalten auch das frisch berechnete `parts`-Ergebnis. Bei Gebäuden
können unvollständige Entwürfe exportiert werden. Zum Bauen müssen die Referenzen
auflösbar und der Gebäudeplan gültig sein. Die Minecraft-Zwischenablage
akzeptiert Graphdokumente bis 28.000 Zeichen.

## GitHub Pages

In den Repository-Einstellungen unter **Pages → Source** die Option
**GitHub Actions** wählen. Der Workflow **Graphstudio** baut den Editor und
veröffentlicht erfolgreiche Builds von `master`. Die Wiki-Seite
**Graphstudio** verlinkt die Website und erläutert den Dateiaustausch.

## Vorschau und Orientierung

**Graph** wertet das endgültige Ergebnis aus, **Selektiert** den angeklickten Knoten. Die gemeinsame Domänenlogik stellt dafür `GraphCompiler.intermediate` und `BuildingGraphCompiler.Evaluation.preview` bereit. Boden/Decke verwenden einen verbundenen Grundriss, ohne andere Raumzweige auszuwerten; das Dach benötigt den Gebäudekontext. Rotationswerte werden als typisierte Werte angezeigt. Knotenwechsel erhalten Kameraposition, Blickziel und Zoom; „3D einpassen“ setzt die Ansicht ausdrücklich neu.

Im Formmodus bleibt der Referenzrahmen eine volle Blockzelle, mit 16 hB je Achse. X/Y/Z entsprechen den Minecraft-Weltachsen. Rahmenkanten und Legende verwenden dieselben Achsenfarben: X rot, Y grün und Z blau. Verdeckte Kanten behalten ihre Achsenfarbe mit geringerer Deckkraft. Der Bodenkompass zeigt Nord, Ost, Süd und West an festen Weltpositionen; seine Beschriftungen drehen sich innerhalb der Bodenebene zur Kamera. Oben-/Unten-Markierungen entfallen. Die sechs Seitenansichten und „3D einpassen“ richten die Kamera aus. Die Seitenleiste teilt eine feste Gesamthöhe mit dem Graphen, solange mindestens einer ihrer Bereiche offen ist. Beide Splitter unterstützen Pointer- und Tastaturbedienung.

Ausrichtungs-Enums übersetzen weiterhin in das vorhandene Quellformat. Nicht unterstützte native Neigungen bleiben deaktiviert; importierte Werte werden beim bloßen Anzeigen nicht umgeschrieben. Raumversätze werden als hB im bisherigen Ganzblockraster angezeigt und beim Export zurückgerechnet. Die neuen Bedienelemente erweitern nicht still den unterstützten Knoten-/Schemabereich.

Die vollständige deutsche [Knotenreferenz](../docs/wiki/Knotenreferenz.md) dokumentiert den gemeinsamen Knotenvertrag einschließlich zusätzlicher nativer Funktionen; jede Knotenbeschreibung kennzeichnet ihre Browserverfügbarkeit.

## Gemeinsamer Modellstand 0.6.1

Alle zehn Form- und 38 Gebäudeknoten werden aus dem Modkatalog angeboten.
`generate-codec.mjs` erzeugt den vollständigen JSON-Codec und die Eigenschaftstypen
aus den 52 erreichbaren nativen Datenklassen. Formdokumente 1–4, Formgraphen 1–3
und Gebäudegraphen 1–4 werden mit denselben Kotlin-Validatoren bearbeitet.
Gruppen, benannte Schnittstellen, Projektion, exakte Geometrie, Dachvorlagen,
vertikale Erschließung und Erker/Gauben bleiben beim Austausch erhalten.
`Knoteneinstellungen` erlaubt das Bearbeiten der zusätzlichen Eigenschaften;
zusammengehörige Änderungen werden gemeinsam übernommen.

Native Knotengruppen können aus der Auswahl angelegt, umbenannt, instanziert und
aufgelöst werden. Benannte Gruppeneingänge aktualisieren beim Neuverbinden alle
gebundenen Anschlüsse. Die gemeinsamen Definitionen synchronisieren Einstellungen
zwischen Instanzen. Die Browserbibliothek kann weiterhin Knotenauswahlen speichern.

`compatibility.json` im Website-Build nennt Release, Schemata, Knotentypen und
SHA-256-Fingerabdrücke des Katalogs und Fachkerns. CI prüft sieben echte native
Dokumente in der Reihenfolge JVM → Browser → JVM und beide nativen Knotenfabriken.

Die 3D-Ansicht stellt exakte Formen weiterhin konservativ im 16er-Raster dar;
die exportierten rationalen Ebenen bleiben vollständig erhalten. Die visuelle
Rasterdarstellung ist daher keine Prüfung der exakten Oberflächenqualität.

## Material and personal libraries

Graphstudio includes a declarative Minecraft 1.21.1 blockstate/model catalog and loads **Faithful 64x Java** texture previews directly from the pinned 1.21.1 repository commit. Credit: [Faithful Resource Pack](https://faithfulpack.net); [license](https://faithfulpack.net/license). No PNG textures are bundled. See `src/data/README.md` for provenance and the catalog rebuild tool.

Public GitHub mod repositories can be linked with a branch/tag/commit and optional resource root. Resource paths, Fabric/Forge/NeoForge metadata, model inheritance, texture aliases, state variants and multipart conditions are read as data; no mod/build code runs. Missing model/texture dependencies are reported and can be provided by linking another repository. GitHub limits and repositories without checked-in generated assets are explicit error cases. Texture previews approximate surfaces on Copycat geometry; custom renderers, CTM, biome tint, exact model UV rotation and animation are not a Minecraft rendering implementation.

IndexedDB holds current drafts, named graphs (including loaded copycat libraries and preview assignments), reusable selected-node templates and pinned mod links. Resources are cached separately. Saves are ordered, failures are visible, and optimistic revisions prevent older tabs from overwriting newer changes. Backup JSON exports/imports workspaces without changing the native Minecraft JSON schema. Group material/state assignments are browser-preview metadata; native form exports retain their existing role-based material contract, and building palettes export block IDs.

[German usage guide](../docs/wiki/Materialbibliothek.md).

## Gespeicherte Copycats und Gebäudeoperationen

**Copycat auswählen** öffnet in der Werkzeugleiste und an den Formquellen
`INPUT` und `SAVED` sowie der Gebäudequelle `COPYCAT` den Auswahldialog.
**Grundformen** enthält 19 Quellen einschließlich Dachvorlage;
**Gespeicherte Formen** enthält persönliche Formgraphen und geladene
Bibliotheksformen mit Herkunft oder Versionsschlüssel. Suchbare Karten zeigen
die berechnete Geometrie; die größere Vorschau lässt sich drehen und zoomen.
**Auswahl übernehmen** ersetzt die ausgewählte Quelle oder erzeugt einen neuen
Quellknoten, wenn keine Quelle ausgewählt ist. **Abbrechen** und Escape ändern
nichts. Der Wechsel zwischen `INPUT` und `SAVED` erhält Knoten-ID und Verbindungen.
Gespeicherte Geometrie und Materialgruppen werden übernommen. Zusätzliche
Drehwerte am Quellknoten werden zurückgesetzt; nachgeschaltete Transformationen
bleiben erhalten.

Im Formgraphen mit **Graph speichern** einen persönlichen Eintrag anlegen.
Im Gebäudegraphen verwendet die Auswahl einer Bibliotheksform ihren genauen
Versionsschlüssel; persönliche Formgraphen und Grundformen werden als feste
Version in die Referenzbibliothek des Gebäudeentwurfs aufgenommen.
Materialzuordnungen übernehmen wie die Gebäudepalette die reine Block-ID.
**Formdatei laden** im Inspektor oder **Copycat laden** in der Werkzeugleiste
importiert weiterhin eine einzelne Form-JSON.

Benannte Browsergraphen und Graphstudio-Sicherungen enthalten die geladene
Referenzbibliothek. Der normale Minecraft-Gebäudeexport enthält nur die
Formreferenzen; deren Versionen müssen im Spiel vorhanden sein.

Alle sechs Operationen sind im Gebäudegraphen für beide Wertfamilien vorhanden:

| Operation | Copycat | Segment |
| --- | --- | --- |
| Rotieren | `COPYCAT_ROTATE` | `SEGMENT_ROTATE` |
| Spiegeln | `COPYCAT_MIRROR` | `SEGMENT_MIRROR` |
| Vereinigung | `COPYCAT_UNION` | `SEGMENT_UNION` |
| Schnitt | `COPYCAT_INTERSECTION` | `SEGMENT_INTERSECTION` |
| Differenz A − B | `COPYCAT_DIFFERENCE` | `SEGMENT_DIFFERENCE` |
| Symmetrische Differenz | `COPYCAT_SYMMETRIC_DIFFERENCE` | `SEGMENT_SYMMETRIC_DIFFERENCE` |

Die separaten Werte-Knoten `ROTATION` und `MIRRORING` enthalten geordnete Listen
mit X/Y/Z und Vierteldrehungen bzw. Spiegelebenen. Im Inspektor lassen sich
Schritte hinzufügen, entfernen und umsortieren. Die passenden Werteingänge an
Copycat- und Segmentoperatoren akzeptieren ausschließlich den jeweiligen Typ.
Leere Listen entsprechen der Identität. Bestehende Graphen ohne Werteverbindung
verwenden weiter ihre direkt gespeicherten Einstellungen.

Die Grundformauswahl bietet außerdem „Leere Copycat“ als explizite leere Quelle.

Segmentspiegelung kann
wahlweise nur Positionen oder Positionen samt Zellgeometrie spiegeln.
Die booleschen Knoten akzeptieren 2–16 geordnete Eingänge, Differenz genau zwei.
Copycat-Ergebnisse bleiben mit Copycat-Eingängen verbindbar, Segmentergebnisse
mit Segmenteingängen. Leere Zwischenergebnisse bleiben verwendbar.

Die gemeinsame Auswertung schneidet Copycat-Teilformen je Blockposition.
Teilumwandlungen normaler Vollblöcke und semantische Segmentmetadaten werden
ausdrücklich abgewiesen. Für die acht neuen booleschen Operator-IDs ist beim
Spielimport ein aktualisierter Mod-Build erforderlich; Modrelease 0.6.0
enthält sie noch nicht.

## 2D-Anordnungsraster

Das 2D-Raster im Inspektor ordnet Segmente am Wandmuster und bei der
Segment-Vereinigung (X/Y, X/Z, Z/Y) sowie
Rauminstanzen am Geschoss (X/Z) an. Ziehen und Pfeiltasten verwenden ganze
Blöcke. Bei der Vereinigung wirken die Versätze auf die tatsächliche Geometrie.
Negative Versätze verschieben den gemeinsamen lokalen Ursprung, damit die
resultierenden Segmentkoordinaten nichtnegativ bleiben; die relative Lage bleibt
erhalten. Wandmuster-Instanzen besitzen zusätzlich Drehachse und Vierteldrehung.
Übernehmen prüft das Ergebnis und erzeugt einen gemeinsamen Undo-Schritt;
Verwerfen lässt das Dokument unverändert. Native `transforms`, `patternAxis`
und `offsets` bleiben beim JSON-Austausch erhalten.


Der gesamte hier beschriebene Stand ist auf diesem Entwicklungszweig implementiert, aber noch nicht in master integriert oder live bereitgestellt.

## Browserprüfung des Produktionsbuilds

`tools/verify-graphstudio-browser.py` prüft wiederholte JSON-Importe, sichtbare
Knoten nach erneuten Renderdurchläufen, Copycat-Vorschauen, gespeicherte Formen,
Undo/Redo, Neuladen, fehlerhafte Quellen und Desktop-/Tablet-/Handybedienung.
Es benötigt Python, Playwright und Chromium. Nach `npm run build` den Build
beispielsweise mit `npx vite preview --host 127.0.0.1 --port 4173` ausliefern:

```sh
python3 tools/verify-graphstudio-browser.py --url http://127.0.0.1:4173
```

`--chromium` wählt die ausführbare Chromium-Datei. `--artifacts` bestimmt das
Verzeichnis für die Screenshots (Standard: `/tmp/graphstudio-browser-check`).
Gemessene Knotengrößen bleiben ausschließlich Ansichtsstatus; der Test prüft,
dass diese Daten nicht im Minecraft-JSON landen.


Ein- und Ausgangstypen werden direkt aus den nativen Anschlüssen übernommen.
Die Paritätsprüfung vergleicht alle 72 Gebäudeknotenvarianten und sämtliche
Inspektorfelder einschließlich Typen, Auswahlwerten und Grenzen. Referenzfelder
bieten passende native Quellknoten an. Abgeleitete Maße öffnen ihre gemeinsame
Quelle. Benannte Gruppenschnittstellen verwenden denselben Adapter wie der
Architektentisch; Eingänge, Ausgänge, Typen und Bindungen lassen sich bearbeiten.

## Optionale Dachspitze

Bei einfachen Satteldächern (`assembly: false`) nimmt **Dachspitze (optional)**
eine eigene Copycat-Quelle an. Sie ersetzt ausschließlich die mittlere Firstreihe,
wenn die lokale Dachbreite quer zur Firstachse ungerade ist. Die Vorlage ist
standardmäßig entlang Z ausgerichtet und dreht für einen First entlang X um 90°.
Überhang und jede Deckungsschicht werden berücksichtigt. Ohne Anschluss sowie
bei gerader Breite oder Flachdächern bleibt die bestehende Deckung erhalten.
Die neueren Dächer aus Dachebenen verwenden weiterhin ihre sechs Segmentvorlagen
einschließlich **First**.

Unter **Beispiel öffnen → Dachspitze bei ungerader Breite** ist ein vollständiger
Entwurf zum Vergleichen verfügbar. Am Dach im Inspektor die Dachspitze auf
**Offen** setzen und mit **Rückgängig** vergleichen. Die Referenz `bar@1` stammt
aus `examples/library.json`; beim Spielimport muss diese Bibliotheksversion
vorhanden sein. Ältere Mod-Builds kennen das neue Feld `roofPeak` noch nicht.

`python3 tools/verify-roof-peak-browser.py --url http://127.0.0.1:4186` prüft
Beispiel, Anschluss, Vorschau, Trennen, Undo/Redo und JSON-Export im
Produktionsbuild. `--chromium` wählt den Browserpfad.

Beim Wechsel zwischen Flach- und Satteldach aktualisieren sich nach **Übernehmen**
Knotenanschlüsse, Kanten und Inspektor. Die Dachspitze erscheint nur am Satteldach;
dessen Giebelanschlüsse folgen der Firstachse (Z: Nord/Süd, X: Ost/West).
Beim Flachdach stehen die vier Dachränder für Rand-/Attikavorlagen bereit.
Ausgeblendete Verbindungen bleiben für einen späteren Rückwechsel erhalten und
werden beim Löschen ihrer Quellen bereinigt. Typ- und Gruppenprüfung bleiben aktiv.

## Dachendkante parallel zum First

Bei einfachen Satteldächern nimmt **Dachendkante / Traufe (optional)** eine
Copycat-Quelle auf. Sie ersetzt ausschließlich die beiden unteren Dachrandreihen
parallel zum First, einschließlich der Verlängerung über die Giebelseiten.
Die gegenüberliegenden Seiten werden passend gedreht; First und Giebelmauern
bleiben unabhängig. Ohne Verbindung bleibt die normale Deckung erhalten.
Beim Flachdach bleibt die ausgeblendete Verbindung gespeichert.
Bei Dachebenen dient dafür weiterhin der zweite Vorlageneingang `in:1`.
Das Beispiel **Dachendkante parallel zum First** zeigt den neuen Anschluss.
Für den Spielimport wird Mod 0.6.4 mit demselben Knotenvertrag benötigt.

## Rotationssegment für Säulen

**Rotationssegment** (`ROTATION_SEGMENT`) besitzt den Copycat-Anschluss `in:0`
und den Anzahl-Anschluss `length`. **Anzahl** (`INT`) liefert `countValue` als
Ganzzahl. Die Verbindung wird als `dimensions.length` im Segment gespeichert.
Ohne Verbindung gilt dessen lokaler `length`-Wert; erlaubt sind 1–128 Blöcke.

Die gemeinsame Kotlin-Auswertung vereinigt die Copycat bei 0°, 90°, 180° und
270° um die Y-Achse innerhalb einer Blockzelle und wiederholt das Profil entlang
Y. Das Ergebnis hat die Segmentabmessungen **1 × Länge × 1**. Überlappungen
zählen für Geometrie, Material und Zinkkosten einmal. Vorherige Copycat-Drehungen,
Spiegelungen und Materialzuordnungen bleiben erhalten. Die Ausgabe lässt sich
mit Segmentoperationen oder einer Segmentrotation weiterverarbeiten.

Das Beispiel **Rotationssegment · Säule** verbindet eine Anzahlquelle und ein
stehendes Panel bis zum Gebäudeausgang. **Quelle bearbeiten** am abgeleiteten
Längenwert öffnet den Anzahl-Knoten.

`python3 tools/verify-rotation-segment-browser.py` prüft Anschlüsse, Quellenwechsel,
Trennen, Längenänderung, Rückgängig und JSON-Export im Chromium.
