FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY services/sim/pom.xml .
RUN mvn -q dependency:go-offline
COPY services/sim/src src
RUN mvn -q -DskipTests package
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd --uid 10001 --create-home kampus && mkdir -p /data && chown kampus /data
WORKDIR /app
COPY --from=build /build/target/kampus-sim-0.1.0.jar app.jar
USER 10001
EXPOSE 8081
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","app.jar"]
