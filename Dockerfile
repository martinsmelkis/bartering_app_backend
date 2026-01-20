# Build stage
FROM gradle:9-jdk21 AS build

WORKDIR /home/gradle/src

# Copy Gradle configuration files first for better caching
COPY --chown=gradle:gradle gradle ./gradle
COPY --chown=gradle:gradle gradlew gradlew.bat build.gradle settings.gradle.kts gradle.properties ./

# Download dependencies (this layer will be cached unless build files change)
RUN gradle dependencies --no-daemon

# Copy source code
COPY --chown=gradle:gradle src ./src
COPY --chown=gradle:gradle resources ./resources

# Copy Firebase credentials if it exists
# This will be used by the app if present, otherwise push notifications will be disabled
COPY --chown=gradle:gradle *.json ./ 

# Build the application
RUN gradle shadowJar --no-daemon

# Runtime stage
FROM amazoncorretto:21

# Set UTF-8 encoding for Java
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"

EXPOSE 8081
WORKDIR /app

# Copy the built JAR from build stage
COPY --from=build /home/gradle/src/build/libs/BarterAppBackend-all.jar /app/barter-app-backend-api.jar

# Copy Firebase credentials from build stage if it exists
COPY --from=build /home/gradle/src/*.json /app/ 

ENTRYPOINT ["java","-Dfile.encoding=UTF-8","-jar","/app/barter-app-backend-api.jar"]
