ARG ILI2GPKG_VERSION=5.5.2
ARG ILIVALIDATOR_VERSION=1.15.0

FROM gradle:9-jdk25 AS build
WORKDIR /src
ARG APP_VERSION=0.0.1
ARG ILI2GPKG_VERSION
ARG ILIVALIDATOR_VERSION
ARG GRPCURL_VERSION=1.9.3

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip

RUN mkdir -p /opt/ili2gpkg \
    && curl -fsSL -o /tmp/ili2gpkg.zip "https://downloads.interlis.ch/ili2gpkg/ili2gpkg-${ILI2GPKG_VERSION}.zip" \
    && unzip -q /tmp/ili2gpkg.zip -d /opt/ili2gpkg

RUN mkdir -p /opt/ilivalidator \
    && curl -fsSL -o /tmp/ilivalidator.zip "https://downloads.interlis.ch/ilivalidator/ilivalidator-${ILIVALIDATOR_VERSION}.zip" \
    && unzip -q /tmp/ilivalidator.zip -d /opt/ilivalidator

RUN mkdir -p /opt/grpcurl \
    && curl -fsSL -o /tmp/grpcurl.tar.gz "https://github.com/fullstorydev/grpcurl/releases/download/v${GRPCURL_VERSION}/grpcurl_${GRPCURL_VERSION}_linux_x86_64.tar.gz" \
    && tar -xzf /tmp/grpcurl.tar.gz -C /opt/grpcurl

# Copy project files
COPY *.gradle.kts gradle.* ./
COPY gradle/ gradle/
COPY config/ config/
COPY proto/ proto/
COPY src/ src/

# Build project
RUN gradle -Pversion=$APP_VERSION build installDist


FROM eclipse-temurin:25-jre AS final
ENV HOME=/app
WORKDIR ${HOME}

ARG ILI2GPKG_VERSION
ARG ILIVALIDATOR_VERSION
ENV ILI2GPKG_VERSION=${ILI2GPKG_VERSION} \
    ILI2GPKG_HOME=/opt/ili2gpkg \
    ILIVALIDATOR_VERSION=${ILIVALIDATOR_VERSION} \
    ILIVALIDATOR_HOME=/opt/ilivalidator \
    ILIVALIDATOR_PLUGINS_DIR=/plugins \
    ILI_CACHE=/var/cache/ilicache \
    PROCESSING_DIR=/app/processing

# Cache dir is a named volume target by convention; persisting it across restarts avoids
# re-downloading INTERLIS models from models.interlis.ch on every worker recycle.
VOLUME ${ILI_CACHE}

# Set default locale
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

# Create non-root user
ENV APP_UID=1234
RUN groupadd --gid=$APP_UID app && useradd --uid=$APP_UID --gid=$APP_UID --create-home app
# The plugin directory is created empty and is meant to be mounted into. Nothing is baked into it, so a new
# plugin needs no new image, and an empty directory means no plugin is on offer. It deliberately does not live
# under ILIVALIDATOR_HOME: <jarDir>/plugins is the tool default and would load every jar on every run,
# regardless of what a request selected.
RUN mkdir -p ${ILI_CACHE} ${PROCESSING_DIR} ${ILIVALIDATOR_PLUGINS_DIR} \
    && chown -R $APP_UID:$APP_UID ${ILI_CACHE} ${PROCESSING_DIR} ${ILIVALIDATOR_PLUGINS_DIR}

USER $APP_UID

# Copy distribution from build stage
COPY --from=build /src/build/install/ilitools-wrapper ${HOME}
COPY --from=build /opt/ili2gpkg ${ILI2GPKG_HOME}
COPY --from=build /opt/ilivalidator ${ILIVALIDATOR_HOME}
COPY --from=build /opt/grpcurl /opt/grpcurl

LABEL org.opencontainers.image.title="ilitools-wrapper" \
      org.opencontainers.image.description="A service that provides INTERLIS ilitools functionality over gRPC connections." \
      org.opencontainers.image.source="https://github.com/geowerkstatt/ilitools-wrapper" \
      org.opencontainers.image.licenses="AGPL-3.0-or-later"

HEALTHCHECK CMD /opt/grpcurl/grpcurl -plaintext "localhost:${GRPC_PORT:-5555}" grpc.health.v1.Health/Check | grep '"status":\s*"SERVING"' || exit 1

ENTRYPOINT ["./bin/ilitools-wrapper"]
