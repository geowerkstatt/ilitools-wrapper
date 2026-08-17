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
- die Platzhalter des jeweiligen Tools, die erst das Tool selbst expandiert:
    - `%ITF_DIR` (`IlivalidatorService`): das Verzeichnis der Transferdatei, also das Session-Verzeichnis des Aufrufs. Dort liegt die Transferdatei und, falls mitgesendet, das entpackte Repository-Archiv (siehe [Repository im Request mitsenden](#repository-im-request-mitsenden)).
    - `%XTF_DIR` (`Ili2gpkgService`): dasselbe Verzeichnis. Dort liegen zusätzlich mitgesendete Dateien vom Typ `MODEL_FILE`, die das Tool darüber findet.
    - `%ILI_FROM_DB` (`Ili2gpkgService`): das im GeoPackage selbst abgelegte Modell. Nötig, sobald `modelDirs` gesetzt ist, weil dieser Eintrag sonst mit dem Tool-Default verloren geht.

Alles andere wird mit `INVALID_ARGUMENT` abgelehnt, bevor eine Datei entgegengenommen oder ein Tool-Prozess gestartet wird: lokale Pfade, andere Schemas wie `file:`, URLs mit Zugangsdaten, Einträge mit dem Trennzeichen `;` sowie der Platzhalter des jeweils anderen Tools. URLs, die in nicht öffentliche Adressbereiche auflösen (privat, Loopback, Link-Local, CGNAT, IPv6-ULA), werden ebenfalls abgelehnt; für Testumgebungen lässt sich das mit `MODELDIR_ALLOW_PRIVATE_NETWORKS=true` abschalten.

Ein URL-Eintrag ist damit per Definition ein öffentlich erreichbares Repository. Nicht publizierte Repositories werden stattdessen im Request mitgesendet.

`metaConfig` unterstützt bewusst nur die Form `ilidata:<DatasetId>`: Profile sind über die `ilidata.xml` des Repositorys indexiert, eine Datei-Form wird nicht angeboten.

### Repository im Request mitsenden

Ein kundenspezifisches Repository muss nicht per URL erreichbar sein. Beide Services nehmen den Dateityp `REPOSITORY_ARCHIVE` an, ein ZIP des Repository-Verzeichnisses. Der Wrapper entpackt es ins Session-Verzeichnis des Aufrufs, neben die Transferdatei, und behält die Verzeichnisstruktur des Archivs. Der Repository-Index (`ilidata.xml`, `ilisite.xml`, `ilimodels.xml`) gehört deshalb auf die oberste Ebene des Archivs.

Referenziert wird das entpackte Repository über den Tool-Platzhalter und nicht über ein zusätzliches Feld: `%ITF_DIR` beim `IlivalidatorService`, `%XTF_DIR` beim `Ili2gpkgService`. Ein Profil daraus löst `metaConfig = ilidata:<DatasetId>` über die mitgesendete `ilidata.xml` auf, analog zum bisherigen ilicop-Verhalten mit gemountetem Repository.

**Der Inhalt des Archivs wird unverändert benutzt.** Beim Entpacken prüft der Wrapper Pfade und Grössen, nie die Bedeutung der Dateien. Ein Archiv kann deshalb Modelle definieren, über eine eigene `ilidata.xml` die konfigurierte Profil-Id neu belegen und damit zum Beispiel Prüfungen abschalten, und über eine `ilisite.xml` auf weitere Repositories verketten, die das Tool dann ebenfalls abfragt. Wer das Archiv zusammenstellt, bestimmt also mit, was das Validierungsresultat bedeutet. Inhalte aus nicht geprüfter Quelle, etwa aus einem Upload, gehören nicht in dieses Archiv; für einzelne mitgelieferte Modell-Dateien wäre ein eigener Dateityp der geeignete Weg, den der Contract noch nicht hat.

Pro Aufruf ist höchstens ein Archiv erlaubt. Beim Entpacken gilt:

| Fall | Verhalten |
| --- | --- |
| Eintragspfad zeigt aus dem Session-Verzeichnis heraus (`..` oder absolut) | `INVALID_ARGUMENT` |
| Eintrag würde eine bereits empfangene Datei ersetzen | `INVALID_ARGUMENT` |
| mehr als 2000 Einträge | `INVALID_ARGUMENT` |
| mehr als 64 MB entpackt | `INVALID_ARGUMENT` |
| kein lesbares ZIP | `INVALID_ARGUMENT` |

Abgelehnt wird immer, bevor ein Tool-Prozess startet, und das Session-Verzeichnis wird auch im Fehlerfall gelöscht.

Die Inline-Route ist für kompakte Repositories gedacht; die Limits markieren die Eignungsgrenze. Grosse, insbesondere katalog-lastige Repositories gehören publiziert und per URL referenziert, dort lädt das Tool nur die benötigten Dateien und der `ILI_CACHE` greift.

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

Bei allen Operationen stehen zusätzlich die Felder `modelDirs` und `metaConfig` sowie der optionale Dateityp `REPOSITORY_ARCHIVE` zur Verfügung, siehe [Modell-Repositories und Profile](#modell-repositories-und-profile).

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
2. Für die Transferdatei:
    1. Ein `IlivalidatorFileStart` mit dem Typ `TRANSFER_FILE`.
    2. Direkt anschliessend der Dateiinhalt in einer oder mehreren `chunk`-Nachrichten.

Erwartet wird genau eine Transferdatei (`.xtf`), optional zusätzlich ein `REPOSITORY_ARCHIVE`.
Vom Lieferanten mitgelieferte einzelne Modelldateien können noch nicht als eigener Dateityp gesendet werden; sie lassen sich aber im Repository-Archiv mitgeben.
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

Dazu kommen `modelDirs` und `metaConfig`, siehe [Modell-Repositories und Profile](#modell-repositories-und-profile).

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
