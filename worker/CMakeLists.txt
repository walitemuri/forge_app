cmake_minimum_required(VERSION 3.20)

project(forge_worker LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Protobuf REQUIRED)
find_package(gRPC CONFIG REQUIRED)

add_executable(
    forge-worker

    src/main.cpp
    src/ProcessExecutor.cpp
    src/TaskExecutorPool.cpp

    generated/forge.pb.cc
    generated/forge.grpc.pb.cc
)

target_include_directories(
    forge-worker
    PRIVATE
    generated
)

target_link_libraries(
    forge-worker
    PRIVATE
    gRPC::grpc++
    protobuf::libprotobuf
)