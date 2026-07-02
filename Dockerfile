FROM gradle:9-jdk25 AS build
WORKDIR /src
ARG VERSION=0.0.1

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

# Set default locale
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

# Create non-root user
ENV APP_UID=1234
RUN groupadd --gid=$APP_UID app && useradd --uid=$APP_UID --gid=$APP_UID --create-home app

USER $APP_UID

# Copy distribution from build stage
COPY --from=build /src/build/install/ilitools-wrapper ${HOME}
ENTRYPOINT ["./bin/ilitools-wrapper"]
