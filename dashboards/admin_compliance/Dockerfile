FROM gradle:9-jdk21 AS build
WORKDIR /home/gradle/project
COPY . .
RUN gradle installDist --no-daemon

FROM amazoncorretto:21
WORKDIR /app
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
COPY --from=build /home/gradle/project/build/install/admin_compliance /app/admin_compliance
ENTRYPOINT ["/app/admin_compliance/bin/admin_compliance"]