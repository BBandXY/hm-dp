FROM maven:3-eclipse-temurin-8 AS builder
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package

FROM eclipse-temurin:8-jre-alpine
WORKDIR /app
COPY --from=builder /workspace/target/hm-dianping-0.0.1-SNAPSHOT.jar app.jar
RUN mkdir -p /app/data/images
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
