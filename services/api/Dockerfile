FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY services/api/pom.xml .
RUN mvn -q dependency:go-offline
COPY services/api/src src
RUN mvn -q -DskipTests package
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd --uid 10001 --create-home kampus
WORKDIR /app
COPY --from=build /build/target/kampus-api-0.1.0.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","app.jar"]
