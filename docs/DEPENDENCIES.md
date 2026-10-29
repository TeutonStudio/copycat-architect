# Gepinnte Versionsmatrix

Stand: 27.10.2026. Java-Ziel: 21; Eclipse Temurin 21.0.7 ist als Java-21-Toolchain vorhanden. Gradle-Wrapper: 9.8.0, ModDevGradle: 2.0.143. Kotlin-Compiler: 2.2.20. Kein Serialization-Plugin, kein geschattetes Kotlin, keine zweite eingebettete Standardbibliothek. Die tatsächlichen Prüfungen stehen in [VALIDATION.md](VALIDATION.md).

| Bestandteil | Exakte Version / Koordinate |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | `net.neoforged:neoforge:21.1.251` |
| Create | `maven.modrinth:create:6.0.10+mc1.21.1` |
| Copycats+ | `maven.modrinth:copycats:3.0.9+mc.1.21.1-neoforge` |
| CC:Tweaked | `cc.tweaked:cc-tweaked-1.21.1-forge:1.120.0` (NeoForge-Artefakt trägt weiterhin „forge“ im Namen) |
| KotlinForForge | `thedarkcolour:kotlinforforge-neoforge:5.10.0` |
| KilaGraph, erforderlich auf dem Client | `com.lowdragmc.kilagraph:kilagraph-neoforge-1.21.1:21.1.0.16` |
| LDLib2, erforderlich auf dem Client | `com.lowdragmc.ldlib2:ldlib2-neoforge-1.21.1:2.2.44:all` |

Create enthält seine veröffentlichten Begleitbibliotheken (Flywheel, Ponder, Registrate) als verschachtelte Abhängigkeiten. KFF 5.10.0 liefert Kotlin 2.2.20; deshalb ist der Compiler genau darauf eingestellt. `kotlin.stdlib.default.dependency=false` unterbindet die zusätzliche automatische Kotlin-Abhängigkeit. Copycat Architect bündelt keine dieser Mods und enthält keine eigenen Mixins.

Create, Copycats+, CC:Tweaked und KotlinForForge werden auf Client und Server installiert. **KilaGraph 21.1.0.16 und LDLib2 2.2.44 werden zusätzlich als separate Client-Mods benötigt.** Die Modmetadaten deklarieren beide als erforderliche Abhängigkeiten mit `side="CLIENT"`; ein dedizierter Server benötigt sie nicht. Der echte KilaGraph-Canvas und seine LDLib2-Oberfläche werden ausschließlich von der Clientregistrierung geöffnet. Die Gradle-Konfiguration löst beide Versionen aus `https://maven.firstdark.dev/snapshots` auf und lädt sie beim Entwicklungsclient automatisch. Die `all`-Variante ist die gepinnte veröffentlichte LDLib2-Abhängigkeit.

Primärquellen:

- [NeoForge 1.21.1 / Java 21](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)
- [KFF 5.x, Compiler und NeoForge-Koordinate](https://github.com/thedarkcolour/KotlinForForge/tree/5.x)
- [Create-Veröffentlichungen](https://github.com/Creators-of-Create/Create/releases)
- [Create 6.0.10 für 1.21.1](https://modrinth.com/mod/create/version/6.0.10+mc1.21.1)
- [Copycats+ exakter Quellcommit](https://github.com/copycats-plus/copycats/tree/60923e001d931ccbc2c6b21b0e911a248375186f)
- [Copycats+ Release](https://modrinth.com/mod/copycats/version/bPYeUWZx)
- [CC:Tweaked Maven](https://maven.squiddev.cc/cc/tweaked/cc-tweaked-1.21.1-forge/1.120.0/)
- [Offizielle Turtle-API](https://tweaked.cc/mc-1.21.x/javadoc/dan200/computercraft/api/turtle/ITurtleAccess.html)
- [KilaGraph für Minecraft 1.21.1](https://github.com/Low-Drag-MC/KilaGraph/tree/1.21)
- [LDLib2 für Minecraft 1.21.1](https://github.com/Low-Drag-MC/LDLib2/tree/1.21)
- [LDLib2 Node Graph Toolkit](https://low-drag-mc.github.io/LowDragMC-Doc/en/ldlib2/node-graph-toolkit/)

Die öffentliche CC-Javadoc zeigt inzwischen 1.120.2. Implementiert und kompiliert wurde gegen die tatsächlichen 1.120.0-JARs; insbesondere `UpgradeType`, `UpgradeData`, `executeCommand`, `TurtleCommandResult` und `WritableMount.openFile` wurden direkt geprüft. Die Bibliotheken wurden gemeinsam im echten NeoForge-GameTestServer geladen.

## Entwicklungsmods für runClient

Diese optionalen Mods werden nur im Gradle-Clientlauf geladen. Die Modrinth-Version-IDs pinnen die NeoForge-Dateien für Minecraft 1.21.1 eindeutig, auch wenn andere Loader dieselbe Versionsnummer verwenden.

| Mod | Version | Koordinate |
| --- | --- | --- |
| [JEI](https://modrinth.com/mod/jei/version/iv3KiVkE) | 19.64.0.455 | `maven.modrinth:jei:iv3KiVkE` |
| [Neat](https://modrinth.com/mod/neat/version/kALoScYM) | 1.21.1-47-neoforge | `maven.modrinth:neat:kALoScYM` |
| [Jade](https://modrinth.com/mod/jade/version/eYz2YBGT) | 15.10.6+neoforge | `maven.modrinth:jade:eYz2YBGT` |

JEI bringt MezzConfig bereits als eingebettete Abhängigkeit mit. Die Entwicklungsmods werden nicht in die Mod-JAR eingebettet und sind keine Installationsvoraussetzung für Copycat Architect.
