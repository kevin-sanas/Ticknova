FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
WORKDIR /workspace
COPY backend backend
COPY db db
WORKDIR /workspace/backend
RUN --mount=type=cache,target=/root/.m2 MAVEN_CONFIG='' sh ./mvnw -B -ntp -DskipTests package

FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237
RUN groupadd --gid 10001 ticknova && useradd --uid 10001 --gid ticknova --no-create-home ticknova && mkdir -p /var/lib/ticknova/uploads && chown 10001:10001 /var/lib/ticknova/uploads
WORKDIR /app
COPY --from=build /workspace/backend/target/ticknova-api-0.1.0-SNAPSHOT.jar app.jar
COPY --from=build /workspace/backend/THIRD-PARTY-NOTICES.txt THIRD-PARTY-NOTICES.txt
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
