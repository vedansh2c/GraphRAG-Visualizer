FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY core core
COPY adapter-neo4j adapter-neo4j
COPY adapter-model adapter-model
COPY adapter-document adapter-document
COPY contract-tests contract-tests
COPY web web
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/web/target/web-1.0.0.jar app.jar
RUN mkdir -p /app/.data && chown -R 10001:10001 /app
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
