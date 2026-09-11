FROM eclipse-temurin:21-jdk AS build

WORKDIR /src

COPY controller ./controller
COPY proto ./proto

WORKDIR /src/controller

RUN chmod +x gradlew
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build \
  /src/controller/build/libs/controller-0.0.1-SNAPSHOT.jar \
  /app/forge-controller.jar

EXPOSE 8080
EXPOSE 50051

ENTRYPOINT ["java", "-jar", "/app/forge-controller.jar"]