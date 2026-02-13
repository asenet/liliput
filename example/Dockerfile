FROM gradle:8.4-jdk21 AS builder

WORKDIR /app
COPY settings.gradle build.gradle ./
COPY liliput4j/ liliput4j/
COPY example/build.gradle example/build.gradle
COPY example/src/ example/src/

RUN gradle :example:build -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=builder /app/example/build/libs/example-1.0-SNAPSHOT.jar app.jar
COPY --from=builder /app/example/build/distributions/example-1.0-SNAPSHOT.tar ./dist.tar

RUN tar xf dist.tar && rm dist.tar

CMD ["java", "-cp", "example-1.0-SNAPSHOT/lib/*", "pl.asenet.Main"]
