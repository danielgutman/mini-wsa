# syntax=docker/dockerfile:1

# --- build stage: compile + package the executable jar (tests run in CI, not here) ---
FROM amazoncorretto:21 AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests clean package

# --- run stage ---
FROM amazoncorretto:21
WORKDIR /app
COPY --from=build /app/target/mini-wsa-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
