# Build stage
FROM gradle:9-jdk21 AS build

WORKDIR /home/gradle/src

# Copy Gradle settings and module build files first for better layer caching
COPY --chown=gradle:gradle gradle ./gradle
COPY --chown=gradle:gradle gradlew gradlew.bat settings.gradle.kts gradle.properties build.gradle ./
COPY --chown=gradle:gradle dashboards/admin_compliance/build.gradle.kts dashboards/admin_compliance/build.gradle.kts

# Resolve dependencies for admin compliance dashboard module
RUN gradle :dashboards:admin_compliance:dependencies --no-daemon

# Copy project sources needed by dashboard module
COPY --chown=gradle:gradle dashboards dashboards

# Build dashboard distribution using standard application plugin tasks
RUN gradle :dashboards:admin_compliance:installDist --no-daemon

# Runtime stage
FROM amazoncorretto:21

ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
WORKDIR /app
EXPOSE 8084

COPY --from=build /home/gradle/src/dashboards/admin_compliance/build/install/admin_compliance /app/admin_compliance

ENTRYPOINT ["/app/admin_compliance/bin/admin_compliance"]
