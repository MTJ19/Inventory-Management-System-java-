FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
COPY src src

RUN mvn --batch-mode -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system inventory && \
    useradd --system --gid inventory --home-dir /app inventory

RUN mkdir -p /app/data && \
    chown -R inventory:inventory /app

COPY --from=build --chown=inventory:inventory \
    /build/target/inventory-management-system-0.1.0-SNAPSHOT.jar \
    /app/inventory.jar

USER inventory

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/inventory.jar"]
CMD ["--serve"]
