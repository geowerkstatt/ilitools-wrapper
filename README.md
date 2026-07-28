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

### Ablauf der Antwort

Nachdem der Anfrage-Stream abgeschlossen ist, werden die Daten verarbeitet.
Nach der Verarbeitung antwortet der Server mit `ConvertResponse`-Nachrichten in folgender Reihenfolge:

1. Ein `StatusUpdate`, das angibt, ob die Verarbeitung erfolgreich war.
2. Die Log-Datei des ili2gpkg-Prozesses, aufgeteilt in `fileStart` und einen oder mehrere `chunk`s.
3. Bei Erfolg wird zusätzlich die Ausgabedatei der Operation gesendet, ebenfalls aufgeteilt in `fileStart` und `chunk`s. Bei `OPERATION_VALIDATE` wird das `XTF_LOG_FILE` auch im Fehlerfall gesendet, da es die gemeldeten Validierungsfehler enthält.

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
