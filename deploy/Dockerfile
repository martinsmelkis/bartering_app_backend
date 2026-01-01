# Use a lightweight Java 21 Runtime Environment as the base.
# This image is small because it doesn't contain Gradle or a full JDK.
FROM amazoncorretto:21

RUN mkdir /app

# Set a working directory inside the container
WORKDIR /app

# Copy the pre-built JAR file from the build context (the local folder)
# into the container's /app directory.
# Copy the pre-built JAR file from the build context (the deployment folder)
# into the container's /app directory.
COPY BarterAppBackend-all.jar ./BarterAppBackend-all.jar

# Copy Firebase credentials if it exists
# This will be used by the app if present, otherwise push notifications will be disabled
COPY --chown=gradle:gradle *.json ./ 

# Expose the port that the Ktor application will run on
EXPOSE 8081

# The command to run the application when the container starts
ENTRYPOINT ["java", "-jar", "BarterAppBackend-all.jar"]