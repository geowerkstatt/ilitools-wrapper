[![CI](https://github.com/geowerkstatt/ilitools-wrapper/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/geowerkstatt/ilitools-wrapper/actions/workflows/ci.yml)
[![Release](https://github.com/geowerkstatt/ilitools-wrapper/actions/workflows/release.yml/badge.svg)](https://github.com/geowerkstatt/ilitools-wrapper/actions/workflows/release.yml)
[![Latest Release](https://img.shields.io/github/v/release/geowerkstatt/ilitools-wrapper)](https://github.com/geowerkstatt/ilitools-wrapper/releases/latest)
[![License](https://img.shields.io/github/license/geowerkstatt/ilitools-wrapper)](https://github.com/geowerkstatt/ilitools-wrapper/blob/main/LICENSE)

# ilitools-wrapper

Der ilitools-wrapper stellt verschiedene INTERLIS Tools als gRPC-Server zur Verfügung.

## Anforderungen

Java 25 (LTS) oder neuer wird benötigt, um den `ilitools-wrapper` auszuführen.

Beim Starten der Anwendung mittels Gradle `run` Task und beim Erstellen des Docker Images wird automatisch eine Version von ili2gpkg heruntergeladen und konfiguriert.

## Konfiguration

| Umgebungsvariable | Default-Wert | Beschreibung |
| --- | --- | --- |
| `GRPC_PORT` | `5555` | Port des gRPC-Servers |
| `PROCESSING_DIR` | `processing` | Basis-Verzeichnis für temporäre Dateien während der Prozessierung |
| `ILI2GPKG_HOME` | Aus Dockerfile oder Gradle `run` Task | Verzeichnis der ili2gpkg Installation |
| `ILI2GPKG_VERSION` | Aus Dockerfile oder gradle.properties | Version des installierten ili2gpkg Tools |
| `ILIVALIDATOR_HOME` | Aus Dockerfile oder Gradle `run` Task | Verzeichnis der ilivalidator Installation |
| `ILIVALIDATOR_VERSION` | Aus Dockerfile oder gradle.properties | Version des installierten ilivalidator Tools |
| `ILITOOLS_PLUGINS_DIR` | nicht gesetzt | Verzeichnis mit einem Unterordner pro angebotenem Plugin. Ohne Angabe bietet das Deployment keine Plugins an (siehe [Plugins zuschalten](#plugins-zuschalten)) |
| `MODELDIR_ALLOW_PRIVATE_NETWORKS` | `false` | Erlaubt `modelDirs`-URLs, die in nicht öffentliche Adressbereiche auflösen (siehe [Modell-Repositories und Profile](#modell-repositories-und-profile)) |

## Modell-Repositories und Profile

Beide Services nehmen in der `info`-Nachricht zwei optionale Felder, mit denen die Auflösung der INTERLIS-Modelle und der Validierungs-Profile gesteuert wird. Die Werte werden unverändert an das Tool weitergegeben:

| Feld | Tool-Argument | Beschreibung |
| --- | --- | --- |
| `modelDirs` | `--modeldir` | Geordnete Liste von Modell-Repositories, in Listenreihenfolge mit `;` zusammengefügt |
| `metaConfig` | `--metaConfig` | Meta-Konfiguration in der Form `ilidata:<DatasetId>`, vom Tool über die `modelDirs` aufgelöst |

Ohne Angabe gilt das Default-Verhalten der Tools: Die Modelle werden über die eingebauten Repositories bzw. den `ILI_CACHE` aufgelöst.

Ein gesetztes `modelDirs` **ersetzt den Default des Tools vollständig**. Wer die Standard-Repositories weiterhin braucht, gibt sie explizit als Eintrag an (z.B. `https://models.interlis.ch/`).

**Die Reihenfolge ist die Auflösungsreihenfolge.** Das Tool durchsucht die Einträge von links nach rechts und nimmt den ersten Treffer. Ein früherer Eintrag verdrängt damit gleichnamige Modelle eines späteren, auch bei identischer Modell-Version und ohne Warnung; im Log steht die Herkunft (`lookup model <X> in repository <...>`). Einträge, deren Inhalt nicht aus einer geprüften Quelle stammt, gehören deshalb an das Ende der Liste, sonst können sie die Modelle der geprüften Repositories ersetzen.

Erlaubte Einträge:

- `http(s)`-URLs auf INTERLIS-Modell-Repositories
- die Platzhalter des jeweiligen Tools, die erst das Tool selbst expandiert, jeweils exakt oder mit einem relativen Unterpfad (etwa `%ITF_DIR/models`):
    - `%ITF_DIR` (`IlivalidatorService`): das Verzeichnis der Transferdatei, also das Session-Verzeichnis des Aufrufs. Mitgesendete Dateien liegen in eigenen Unterordnern: Modell-Dateien in `%ITF_DIR/models`, das entpackte Repository-Archiv in `%ITF_DIR/repository` (siehe die beiden folgenden Abschnitte).
    - `%XTF_DIR` (`Ili2gpkgService`): dasselbe Verzeichnis, mit denselben Unterordnern (`%XTF_DIR/models`, `%XTF_DIR/repository`).
    - `%ILI_FROM_DB` (`Ili2gpkgService`): das im GeoPackage selbst abgelegte Modell. Nötig, sobald `modelDirs` gesetzt ist, weil dieser Eintrag sonst mit dem Tool-Default verloren geht.

Ein Verzeichnis-Eintrag wird vom Tool nicht rekursiv gescannt (gemessen an ilivalidator 1.15.0): `%ITF_DIR` sieht die Unterordner nicht, jede Quelle ist nur über ihren eigenen Eintrag sichtbar, und genau das macht ihre Reihenfolge konfigurierbar.

Alles andere wird mit `INVALID_ARGUMENT` abgelehnt, bevor eine Datei entgegengenommen oder ein Tool-Prozess gestartet wird: lokale Pfade, andere Schemas wie `file:`, URLs mit Zugangsdaten, Einträge mit dem Trennzeichen `;`, der Platzhalter des jeweils anderen Tools sowie Unterpfade, die das Verzeichnis verlassen könnten (leere Segmente, `.` oder `..`, Backslashes). URLs, die in nicht öffentliche Adressbereiche auflösen (privat, Loopback, Link-Local, CGNAT, IPv6-ULA), werden ebenfalls abgelehnt; für Testumgebungen lässt sich das mit `MODELDIR_ALLOW_PRIVATE_NETWORKS=true` abschalten.

Ein URL-Eintrag ist damit per Definition ein öffentlich erreichbares Repository. Nicht publizierte Repositories werden stattdessen im Request mitgesendet.

`metaConfig` unterstützt bewusst nur die Form `ilidata:<DatasetId>`: Profile sind über die `ilidata.xml` des Repositorys indexiert, eine Datei-Form wird nicht angeboten.

### Repository im Request mitsenden

Ein kundenspezifisches Repository muss nicht per URL erreichbar sein. Beide Services nehmen den Dateityp `REPOSITORY_ARCHIVE` an, ein ZIP des Repository-Verzeichnisses. Der Wrapper entpackt es in den Unterordner `repository/` des Session-Verzeichnisses und behält die Verzeichnisstruktur des Archivs. Der Repository-Index (`ilidata.xml`, `ilisite.xml`, `ilimodels.xml`) gehört deshalb auf die oberste Ebene des Archivs.

Referenziert wird das entpackte Repository über den Platzhalter mit Unterpfad und nicht über ein zusätzliches Feld: `%ITF_DIR/repository` beim `IlivalidatorService`, `%XTF_DIR/repository` beim `Ili2gpkgService`. Ein Profil daraus löst `metaConfig = ilidata:<DatasetId>` über die mitgesendete `ilidata.xml` auf, analog zum bisherigen ilicop-Verhalten mit gemountetem Repository.

**Der Inhalt des Archivs wird unverändert benutzt.** Beim Entpacken prüft der Wrapper Pfade und Grössen, nie die Bedeutung der Dateien. Ein Archiv kann deshalb Modelle definieren, über eine eigene `ilidata.xml` die konfigurierte Profil-Id neu belegen und damit zum Beispiel Prüfungen abschalten, und über eine `ilisite.xml` auf weitere Repositories verketten, die das Tool dann ebenfalls abfragt. Wer das Archiv zusammenstellt, bestimmt also mit, was das Validierungsresultat bedeutet. Inhalte aus nicht geprüfter Quelle, etwa aus einem Upload, gehören nicht in dieses Archiv, sondern als `MODEL_FILE` in den Request: Dessen Umbenennung schliesst genau diese Fähigkeiten aus (siehe [Einzelne Modell-Dateien mitsenden](#einzelne-modell-dateien-mitsenden)).

Pro Aufruf ist höchstens ein Archiv erlaubt. Beim Entpacken gilt:

| Fall | Verhalten |
| --- | --- |
| Eintragspfad zeigt aus dem Zielordner heraus (`..` oder absolut) | `INVALID_ARGUMENT` |
| Eintrag würde eine bereits entpackte Datei ersetzen | `INVALID_ARGUMENT` |
| mehr als 2000 Einträge | `INVALID_ARGUMENT` |
| mehr als 64 MB entpackt | `INVALID_ARGUMENT` |
| kein lesbares ZIP | `INVALID_ARGUMENT` |

Abgelehnt wird immer, bevor ein Tool-Prozess startet, und das Session-Verzeichnis wird auch im Fehlerfall gelöscht.

Die Inline-Route ist für kompakte Repositories gedacht; die Limits markieren die Eignungsgrenze. Grosse, insbesondere katalog-lastige Repositories gehören publiziert und per URL referenziert, dort lädt das Tool nur die benötigten Dateien und der `ILI_CACHE` greift.

### Einzelne Modell-Dateien mitsenden

Beide Services nehmen den Dateityp `MODEL_FILE` an: einzelne `.ili`-Dateien, etwa die mitgelieferten Modelle einer Datenlieferung. Der Wrapper legt sie unter eigenem Namen (`fileN.ili`) in den Unterordner `models/` des Session-Verzeichnisses; sichtbar werden sie ausschliesslich über einen entsprechenden Eintrag in `modelDirs` (`%ITF_DIR/models` bzw. `%XTF_DIR/models`). Wer Modell-Dateien sendet, setzt also auch `modelDirs`; der Tool-Default kennt den Unterordner nicht. Der Dateiname eines Modells ist für die Auflösung irrelevant, das Tool scannt das Verzeichnis und parst die Dateien; der Contract braucht deshalb kein Namensfeld. Weil `models/` als ein Repository gescannt wird, lösen sich auch `IMPORTS` zwischen mitgesendeten Modellen dort auf; ein Import auf ein nicht mitgesendetes Modell wird über die übrigen `modelDirs`-Einträge aufgelöst.

Weil der Wrapper die Dateien selbst benennt, kann über diesen Weg kein Repository-Index (`ilidata.xml`, `ilisite.xml`, `ilimodels.xml`) eingeschleust werden: Der Inhalt wird als Modell geparst, nie als Index gelesen. Der Kanal eignet sich damit, anders als das Repository-Archiv, auch für Modelle aus nicht geprüfter Quelle, etwa aus einem Upload. Ein geliefertes Modell kann aber weiterhin ein gleichnamiges amtliches verdrängen oder eigene Prüfungen abschwächen; die Position von `%ITF_DIR/models` bzw. `%XTF_DIR/models` in `modelDirs` entscheidet die Präzedenz, ungeprüfter Inhalt gehört ans Ende der Liste.

Da jede Quelle ihren eigenen Unterordner hat, ist auch die Kombination mit einem Repository-Archiv vollständig priorisierbar, zum Beispiel `https://models.interlis.ch/;%ITF_DIR/repository;%ITF_DIR/models`: amtliche Repositories vor dem mitgesendeten Repository vor den Lieferanten-Modellen.

## Plugins zuschalten

Ein Plugin stellt benutzerdefinierte Funktionen bereit, die ein Modell in seinen Constraints aufrufen kann. Ohne das passende Plugin lässt sich ein solcher Constraint nicht auswerten.

**Beide Services** haben dafür das optionale Feld `pluginIds` in der `info`-Nachricht. Der Wrapper nimmt keine Jars im Request entgegen, sondern bietet an, was sein Plugin-Verzeichnis enthält (`ILITOOLS_PLUGINS_DIR`, siehe [Konfiguration](#konfiguration)). Das Verzeichnis enthält **einen Unterordner pro Plugin**, dessen Name die Id ist, und darin die Jar-Dateien des Plugins. Ein Unterordner ohne Jar gilt nicht als Plugin. Ob das Verzeichnis ins Image gebacken oder hineingemountet wird, ist eine Deployment-Entscheidung; der Contract kennt nur Ids.

Das Feld `pluginIds` der `info`-Nachricht wählt aus dieser Menge aus. Es heisst nach dem, was es trägt, und nicht nach der Tool-Option: `--plugins` nimmt einen einzelnen Ordner, den der Wrapper aus dieser Auswahl erst zusammenstellt.

| Fall | Verhalten |
| --- | --- |
| `pluginIds` leer | `--plugins` wird nicht gesetzt, es läuft kein Plugin |
| Id ist im Plugin-Verzeichnis vorhanden | Die Jars des Plugins werden ins Session-Verzeichnis kopiert und über `--plugins` geladen |
| Id ist nicht vorhanden, leer oder doppelt | `INVALID_ARGUMENT`, bevor eine Datei entgegengenommen wird |

Für die Jars im Plugin-Verzeichnis gilt die Regel des Werkzeugs: eine Zusatzfunktion muss das Java-Interface `ch.interlis.iox_j.validator.InterlisFunction` implementieren, **und der Name der Java-Klasse muss mit `IoxPlugin` enden** (dokumentiert in `docs/ilivalidator.html` der Distribution). Eine Klasse mit anderem Namen wird stillschweigend ignoriert und äussert sich als übersprungener Constraint, nicht als Fehler. Der Name der Jar-Datei ist dagegen irrelevant, gesucht wird nach Klassen.

Die Menge wird bei **jedem** Request aus dem Verzeichnis gelesen. Ein neu abgelegtes Plugin ist damit ohne Neustart des Dienstes wählbar. Werden mehrere Plugins gewählt, führt der Wrapper deren Jars in einem Verzeichnis zusammen, weil `--plugins` genau eines annimmt; tragen zwei gewählte Plugins eine Jar-Datei mit demselben Namen, wird der Request abgelehnt.

**Ein fehlendes Plugin fällt nicht auf.** Gemessen an ilivalidator 1.15.0: ruft ein `MANDATORY CONSTRAINT` eine Funktion auf, deren Plugin nicht geladen ist, überspringt das Tool den Constraint mit `Warning: ... is not yet implemented.` und **beendet den Lauf erfolgreich**. Eine Option, die das zum Fehler macht, gibt es nicht. Wer aus dem Validierungsresultat eine Freigabe ableitet, muss die Logs deshalb auf übersprungene Constraints prüfen; der Erfolg allein sagt nicht, dass alle Constraints ausgewertet wurden. Aus demselben Grund ist die Zeile `pluginFolder <...>` im Log kein Nachweis: sie erscheint bei jedem Lauf, auch ohne `--plugins`.


## Ili2gpkg service

Der `Ili2gpkgService` kapselt das Kommandozeilen-Tool `ili2gpkg`, das INTERLIS-Transferdateien und GeoPackage-Datenbanken ineinander umwandelt.
Der Service stellt eine einzige RPC-Methode bereit:

```proto
rpc Convert(stream ConvertRequest) returns (stream ConvertResponse)
```

### Ablauf einer Anfrage

Die `ConvertRequest`-Nachrichten müssen in folgender Reihenfolge gesendet werden:

1. Genau eine `ConvertRequestInfo` zuerst. Sie definiert die auszuführende Operation und ihre Optionen.
2. Pro Eingabedatei:
    1. Ein `Ili2gpkgFileStart`, welcher den Dateityp definiert.
    2. Direkt anschliessend der jeweilige Dateiinhalt in einer oder mehreren `chunk`-Nachrichten.

Die maximale Grösse einer eingehenden Nachricht beträgt 100 MB.
Falls eine Datei grösser ist, muss sie auf mehrere Chunks aufgeteilt werden.

### Operationen und benötigte Dateien

Die Operation in der `info`-Nachricht bestimmt, welche Eingabedateien erwartet und welche Ausgabedatei erzeugt wird:

| Operation | Beschreibung | Benötigte Eingabedateien | Ausgabedatei |
| --- | --- | --- | --- |
| `OPERATION_SCHEMA_IMPORT` | Erzeugt das GeoPackage-Schema aus einem INTERLIS-Modell | `MODEL_FILE` (`.ili`) | `DB_FILE` (`.gpkg`) |
| `OPERATION_IMPORT` | Importiert eine Transferdatei in ein bestehendes GeoPackage | `TRANSFER_FILE` (`.xtf`), `DB_FILE` (`.gpkg`) | `DB_FILE` (`.gpkg`) |
| `OPERATION_EXPORT` | Exportiert ein GeoPackage in eine Transferdatei | `DB_FILE` (`.gpkg`) | `TRANSFER_FILE` (`.xtf`) |
| `OPERATION_UPDATE` | Aktualisiert die Daten in einem bestehenden GeoPackage aus der Transferdatei | `TRANSFER_FILE` (`.xtf`), `DB_FILE` (`.gpkg`) | `DB_FILE` (`.gpkg`) |
| `OPERATION_VALIDATE` | Validiert die Daten in einem GeoPackage | `DB_FILE` (`.gpkg`) | `XTF_LOG_FILE` (`.xtf`) |

Bei allen Operationen stehen zusätzlich die Felder `modelDirs` und `metaConfig` sowie der optionale Dateityp `REPOSITORY_ARCHIVE` zur Verfügung, siehe [Modell-Repositories und Profile](#modell-repositories-und-profile). Zusatzfunktionen aus Plugins lassen sich über `pluginIds` zuschalten, siehe [Plugins zuschalten](#plugins-zuschalten); ili2gpkg kann mit `OPERATION_VALIDATE` ebenfalls validieren und nimmt dieselbe Tool-Option.

| Feld | Beschreibung |
| --- | --- |
| `toolVersion` | Version des Werkzeugs für diesen Request. Leer bedeutet die Voreinstellung des Deployments (siehe [Werkzeug-Version wählen](#werkzeug-version-wählen)). Eine Version, die das Deployment nicht anbietet, wird mit `INVALID_ARGUMENT` abgelehnt, bevor eine Datei entgegengenommen wird |

### Ablauf der Antwort

Nachdem der Anfrage-Stream abgeschlossen ist, werden die Daten verarbeitet.
Nach der Verarbeitung antwortet der Server mit `ConvertResponse`-Nachrichten in folgender Reihenfolge:

1. Ein `StatusUpdate`, das angibt, ob die Verarbeitung erfolgreich war.
2. Die Log-Datei des ili2gpkg-Prozesses, aufgeteilt in `fileStart` und einen oder mehrere `chunk`s.
3. Bei Erfolg wird zusätzlich die Ausgabedatei der Operation gesendet, ebenfalls aufgeteilt in `fileStart` und `chunk`s. Bei `OPERATION_VALIDATE` wird das `XTF_LOG_FILE` auch im Fehlerfall gesendet, da es die gemeldeten Validierungsfehler enthält.

## Ilivalidator service

Der `IlivalidatorService` kapselt das Kommandozeilen-Tool `ilivalidator`, das INTERLIS-Transferdateien gegen ihre Modelle validiert.
Der Service stellt eine einzige RPC-Methode bereit:

```proto
rpc Validate(stream ValidateRequest) returns (stream ValidateResponse)
```

### Ablauf einer Anfrage

Die `ValidateRequest`-Nachrichten müssen in folgender Reihenfolge gesendet werden:

1. Genau eine `ValidateRequestInfo` zuerst. Sie definiert die Validierungsoptionen.
2. Pro Eingabedatei:
    1. Ein `IlivalidatorFileStart`, welcher den Dateityp definiert.
    2. Direkt anschliessend der jeweilige Dateiinhalt in einer oder mehreren `chunk`-Nachrichten.

Erwartet wird genau eine Transferdatei, gesendet als `TRANSFER_FILE_XTF` oder `TRANSFER_FILE_ITF`, optional zusätzlich beliebig viele Modell-Dateien (`MODEL_FILE`, siehe [Einzelne Modell-Dateien mitsenden](#einzelne-modell-dateien-mitsenden)) und ein `REPOSITORY_ARCHIVE`.

Der Transferdatei-Typ trägt das Format: Der Wrapper legt die Datei entsprechend als `fileN.xtf` bzw. `fileN.itf` ab, und die Unterscheidung existiert, weil das Tool nur bei der Endung `.itf` auf die INTERLIS-1-Semantik umschaltet (gemessen an ilivalidator 1.15.0: pro Tabelle eindeutige TIDs sind in ITF legal, scheitern aber unter einem `.xtf`-Namen). Eine INTERLIS-1-Lieferung sendet ihre Transferdatei deshalb als `TRANSFER_FILE_ITF`.

Die Modell-Repositories und das Validierungs-Profil werden über `modelDirs` und `metaConfig` gesteuert, siehe [Modell-Repositories und Profile](#modell-repositories-und-profile).

Die maximale Grösse einer eingehenden Nachricht beträgt 100 MB.
Falls eine Datei grösser ist, muss sie auf mehrere Chunks aufgeteilt werden.

### Validierungsoptionen

Die folgenden Optionen können in der `info`-Nachricht gesetzt und werden als Kommandozeilen-Argumente an `ilivalidator` weitergegeben:

| Option | ilivalidator-Argument |
| --- | --- |
| `forceTypeValidation` | `--forceTypeValidation` |
| `disableAreaValidation` | `--disableAreaValidation` |
| `disableConstraintValidation` | `--disableConstraintValidation` |
| `allObjectsAccessible` | `--allObjectsAccessible` |
| `multiplicityOff` | `--multiplicityOff` |
| `skipPolygonBuilding` | `--skipPolygonBuilding` |

Dazu kommen `modelDirs` und `metaConfig`, siehe [Modell-Repositories und Profile](#modell-repositories-und-profile), sowie `pluginIds`, siehe [Plugins zuschalten](#plugins-zuschalten).

| Feld | Beschreibung |
| --- | --- |
| `toolVersion` | Version des Werkzeugs für diesen Request. Leer bedeutet die Voreinstellung des Deployments (siehe [Werkzeug-Version wählen](#werkzeug-version-wählen)). Eine Version, die das Deployment nicht anbietet, wird mit `INVALID_ARGUMENT` abgelehnt, bevor eine Datei entgegengenommen wird |

### Ablauf der Antwort

Nachdem der Anfrage-Stream abgeschlossen ist, werden die Daten validiert.
Nach der Verarbeitung antwortet der Server mit `ValidateResponse`-Nachrichten in folgender Reihenfolge:

1. Ein `StatusUpdate`, das angibt, ob die Validierung erfolgreich war (keine Validierungsfehler).
2. Die Text-Logdatei (`--log`) des ilivalidator-Prozesses, aufgeteilt in `fileStart` und einen oder mehrere `chunk`s.
3. Die XTF-Logdatei (`--xtflog`) mit den strukturierten Validierungsergebnissen, ebenfalls aufgeteilt in `fileStart` und `chunk`s.

Beide Logdateien werden immer zurückgegeben, auch im Fehlerfall, da sie die eigentlichen Validierungsergebnisse enthalten.

## Testen mit grpcurl

Der ilitools-wrapper stellt die verfügbaren Services und ihre Definitionen als [Reflection-Endpunkt](https://grpc.io/docs/guides/reflection/) zur Verfügung.

Die Services können z.B. mit grpcurl aufgelistet oder beschrieben werden:
```bash
grpcurl -plaintext localhost:5555 list
grpcurl -plaintext localhost:5555 describe
```

Grpcurl ist auch als docker-Anwendung verfügbar.
Dabei kann über `host.docker.internal` auf den lokalen Service zugegriffen werden:
```bash
docker run --rm fullstorydev/grpcurl -plaintext host.docker.internal:5555 list
```

## Entwicklung mit VS Code

### Tests über Gradle ausführen

Damit die Tests im Test Explorer über Gradle statt über den integrierten Java-Test-Runner laufen, muss das Testprofil `Delegate Test to Gradle` als Standard gesetzt werden.
Voraussetzung sind die Erweiterungen [Test Runner for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-test) und [Gradle for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-gradle).

1. Die Ansicht **Testing** öffnen.
2. Im Split-Button **Run tests** im Header oben in der Testing-Ansicht **Select Default Profile** wählen.
3. **Delegate Test to Gradle** als Standardprofil auswählen.

Das Standard-Profil muss einmalig ausgewählt werden und lässt sich derzeit nicht über `settings.json` konfigurieren.
