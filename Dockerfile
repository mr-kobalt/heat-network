# Многоступенчатая сборка. Java 11 зафиксирована требованиями конкурса.
FROM docker.io/library/maven:3.8.6-openjdk-11 AS build
WORKDIR /workspace

# Сначала зависимости — лучше кэшируется.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

FROM docker.io/library/eclipse-temurin:11-jre
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "/app/app.jar"]
