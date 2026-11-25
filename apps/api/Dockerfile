FROM eclipse-temurin:21-jdk-alpine AS build

RUN apk add --no-cache curl unzip

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod 0755 mvnw

COPY config config
COPY src src
RUN ./mvnw -B -ntp -Ppostgres -DskipTests package

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S -g 10001 novel \
    && adduser -S -D -H -u 10001 -G novel novel

WORKDIR /app
RUN mkdir -p /var/lib/novel/import-staging \
    && chown 10001:10001 /var/lib/novel/import-staging

COPY --from=build --chown=10001:10001 /workspace/target/api-0.0.1-SNAPSHOT.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=120s --retries=12 \
  CMD wget -q --spider http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
