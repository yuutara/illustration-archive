FROM maven:3.9.16-eclipse-temurin-17 AS build

WORKDIR /build
COPY pom.xml .
COPY src/main ./src/main
RUN mvn -B -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
COPY --from=build /build/target/illustration-archive-0.0.1-SNAPSHOT.jar app.jar
RUN mkdir -p /data/storage && chown 10001:10001 /data/storage

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
