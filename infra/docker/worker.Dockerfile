FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -ntp -pl services/worker -am package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/worker/target/worker-0.1.0-SNAPSHOT.jar /app/worker.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/worker.jar"]
