[![CI](https://github.com/geowerkstatt/ilitools-wrapper/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/geowerkstatt/ilitools-wrapper/actions/workflows/ci.yml)

# ilitools-wrapper

Der ilitools-wrapper stellt verschiedene INTERLIS Tools als gRPC-Server zur Verfügung.

## Anforderungen

Java 25 (LTS) oder neuer wird benötigt, um den `ilitools-wrapper` auszuführen.

## Konfiguration

Der Port des gRPC-Servers kann über die Umgebungsvariable `GRPC_PORT` konfiguriert werden, der Standardwert ist `5555`.

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
