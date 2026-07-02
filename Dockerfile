FROM gradle:9-jdk25 AS build
WORKDIR /src
ARG VERSION=0.0.1
ARG ILI2GPKG_VERSION=5.5.2

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip \
    && mkdir -p /opt/ili2gpkg \
    && curl -fsSL -o /tmp/ili2gpkg.zip "https://downloads.interlis.ch/ili2gpkg/ili2gpkg-${ILI2GPKG_VERSION}.zip" \
    && unzip -q /tmp/ili2gpkg.zip -d /opt/ili2gpkg

# Copy project files
COPY *.gradle.kts gradle.* .
COPY gradle/ gradle/
COPY config/ config/
COPY src/ src/

# Build project
RUN gradle -Pversion=$VERSION build installDist


FROM eclipse-temurin:25-jre AS final
ENV HOME=/app
WORKDIR ${HOME}

ENV ILI2GPKG_VERSION=${ILI2GPKG_VERSION} \
    ILI2GPKG_HOME=/opt/ili2gpkg \
    ILI2GPKG_CACHE_DIR=/var/cache/ili2gpkg

# Cache dir is a named volume target by convention; persisting it across restarts avoids
# re-downloading INTERLIS models from models.interlis.ch on every worker recycle.
VOLUME ${ILI2GPKG_CACHE_DIR}

# Set default locale
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

# Create non-root user
ENV APP_UID=1234
RUN groupadd --gid=$APP_UID app && useradd --uid=$APP_UID --gid=$APP_UID --create-home app

USER $APP_UID

# Copy distribution from build stage
COPY --from=build /src/build/install/ilitools-wrapper ${HOME}
COPY --from=build /opt/ili2gpkg ${ILI2GPKG_HOME}

ENTRYPOINT ["./bin/ilitools-wrapper"]
