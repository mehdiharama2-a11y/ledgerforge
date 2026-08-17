FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S ledgerforge && adduser -S ledgerforge -G ledgerforge
WORKDIR /app
COPY --from=build /workspace/target/ledgerforge-0.1.0.jar app.jar
USER ledgerforge
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
