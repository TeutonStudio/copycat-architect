# Panelgebäude mit Schichtfenstern

Dieses konkrete Prüfgebäude verwendet **Create-Copycat-Panels mit 3/16 Dicke als Wände**, **Copycat-Schichten mit 2/16 Dicke als Fenster** und **Leisten mit 2 × 2/16 Querschnitt**. Die Schichten liegen an der Blockkante und bündig zur inneren Paneloberfläche; sie verwenden keine mittige Scheibe. Hinter dem Glas bleibt kein opakes Panel stehen. Die Fenster erhalten vier Leisten als Holzrahmen. Zusätzlich laufen Leisten an den vier senkrechten Gebäudeecken sowie an den oberen und unteren Fassadenkanten.

Die freie Innenfläche beträgt **4 × 4 Blöcke bei vier Blöcken Höhe**, also **64 vollständig bebaubare Blockzellen**. Boden und Decke liegen außerhalb dieser Zellen. Die Konstruktion umfasst 188 Copycat-Positionen und belegt mit ihrer äußeren Blockhülle 6 × 6 × 7 Zellen. Es ist ein geschlossenes Prüfgebäude; für einen Eingang können im Wandmodul freie Zellen anstelle der betreffenden Panels vorgesehen werden.

## Entwurf im Editor

1. Die sechs Formen aus `forms/` nachbauen. Panels und Schichten werden über eigene Rotationsknoten um die X-Achse gedreht. Bei den Panel- und Fensterformen haben die Leisten eine eigene Materialgruppe mit höherer Priorität.
2. Zwei Wandmodule mit Breite 1 und Höhe 4 anlegen: eine Panelspalte und eine Fensterspalte. Beide erhalten unten `panel_lower` und oben `panel_upper`. Dazwischen verwendet die erste Spalte normale Panels und die zweite die Schichtfenster.
3. Zwei Wandsegmente mit diesen Modulen erstellen und als Wandmuster **Panel – Fenster – Fenster – Panel** verbinden.
4. Ein Stockwerk mit Aufbau **Wände, Boden und Decke**, Rechteck **4 × 4** und Höhe **4** anlegen. Das Wandmuster an alle vier Wandanschlüsse anschließen. Boden und Decke auf **Bebaubar** lassen.
5. Eine Copycat-Quelle mit der senkrechten Leistenform und Materialrolle `trim` an **Eckleisten-Copycat (optional)** anschließen. Das Stockwerk dreht diese Form an allen vier konvexen Ecken passend.
6. Boden, Decke und Flachdach mit Vollblockformen verbinden, Stockwerk und Dach am Gebäude anschließen. Materialpalette gemäß `building.json` setzen.

`library.json` enthält alle festen Referenzen als reproduzierbares Gesamtpaket für Tests. `building.json`, die sechs Formen und die zwei Module liegen zusätzlich einzeln vor. Der bestehende Editor importiert einzelne Entwürfe und vergibt neue IDs; beim manuellen Import müssen daher in den Modulen und im Gebäude die jeweils neu gespeicherten Formen/Module über ihre Namen ausgewählt werden. Das Gesamtpaket ist kein Ein-Klick-Bibliotheksimport.

## Materialbedarf

| Material | Anzahl |
| --- | ---: |
| Druckermaterial | 33.024 Einheiten = 129 Zinkbarren |
| Eichenholzbretter | 136 |
| Steinziegel | 48 |
| Glas | 16 |
| Tiefenschieferfliesen | 36 |

Der Welttest baut den Plan im Überlebensmodus mit genau diesen Vorräten, prüft die Kollisionsgeometrie und gespeicherten Materialien und stellt anschließend normale Werkbank, Ofen und Truhe in drei freien Innenzellen auf. Die Schnittansicht der Clientprüfung blendet nur zur Darstellung Dach, Decke und zwei Vorderwände aus; sie verändert den Bauplan nicht.
