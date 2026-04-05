# Build stage
FROM gradle:9-jdk21 AS build

WORKDIR /home/gradle/src

# Copy Gradle settings and module build files first for better layer caching
COPY --chown=gradle:gradle gradle ./gradle
COPY --chown=gradle:gradle gradlew gradlew.bat settings.gradle.kts gradle.properties build.gradle ./
# Multi-project support: ensure all included subproject directories exist during configuration
RUN mkdir -p dashboards/admin_compliance dashboards/user_moderation
COPY --chown=gradle:gradle dashboards/admin_compliance/build.gradle.kts dashboards/admin_compliance/build.gradle.kts
COPY --chown=gradle:gradle dashboards/user_moderation/build.gradle.kts dashboards/user_moderation/build.gradle.kts

# Resolve dependencies for user moderation dashboard module
RUN gradle :dashboards:user_moderation:dependencies --no-daemon

# Copy project sources needed by dashboard module
COPY --chown=gradle:gradle dashboards dashboards

# Build dashboard distribution using standard application plugin tasks
RUN gradle :dashboards:user_moderation:installDist --no-daemon

# Runtime stage
FROM amazoncorretto:21

ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
WORKDIR /app
EXPOSE 8085

COPY --from=build /home/gradle/src/dashboards/user_moderation/build/install/user_moderation /app/user_moderation

ENTRYPOINT ["/app/user_moderation/bin/user_moderation"]
