FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y \
    bash \
    ca-certificates \
    git \
    python3 \
    unzip \
    curl \
    build-essential \
    cmake \
    pkg-config \
    openjdk-21-jdk-headless \
    ffmpeg \
    protobuf-compiler \
    protobuf-compiler-grpc \
    libprotobuf-dev \
    libgrpc++-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src

COPY worker ./worker
COPY proto ./proto

RUN cmake \
      -S /src/worker \
      -B /build \
      -DCMAKE_BUILD_TYPE=Release \
    && cmake --build /build --parallel

# Ship only the source needed by the verification template, with a warmed
# Gradle cache so visitor-triggered builds do not download dependencies.
ENV GRADLE_USER_HOME=/opt/gradle-cache
COPY controller /workspace/forge/controller
COPY worker /workspace/forge/worker
COPY proto /workspace/forge/proto
COPY scripts /workspace/forge/scripts
ARG FORGE_REVISION=working-tree
RUN echo "$FORGE_REVISION" > /workspace/forge/REVISION \
    && cd /workspace/forge/controller \
    && chmod +x gradlew \
    && ./gradlew test --tests '*Test' --no-daemon --max-workers=2 \
    && rm -rf build .gradle

# Bundle a compact 30-second excerpt of real Tears of Steel footage. The full
# source is 915 MB; this excerpt keeps deployment practical while preserving
# the original film's picture and audio.
COPY demo/video/deploy/tears-of-steel-excerpt.mp4 /workspace/forge/demo/video/deploy/tears-of-steel-excerpt.mp4
WORKDIR /app

RUN cp /build/forge-worker /app/forge-worker \
    && rm -rf /src /build

ENTRYPOINT ["/app/forge-worker"]
