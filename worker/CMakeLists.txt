cmake_minimum_required(VERSION 3.20)

project(forge_worker LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(APPLE)
    find_package(Protobuf CONFIG REQUIRED)
else()
    find_package(Protobuf REQUIRED)
endif()

find_package(gRPC CONFIG REQUIRED)

set(PROTO_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../proto")
set(PROTO_FILE "${PROTO_DIR}/forge.proto")
set(GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")

file(MAKE_DIRECTORY "${GENERATED_DIR}")

set(PROTO_SRCS
    "${GENERATED_DIR}/forge.pb.cc"
    "${GENERATED_DIR}/forge.grpc.pb.cc"
)

set(PROTO_HDRS
    "${GENERATED_DIR}/forge.pb.h"
    "${GENERATED_DIR}/forge.grpc.pb.h"
)

add_custom_command(
    OUTPUT
        ${PROTO_SRCS}
        ${PROTO_HDRS}

    COMMAND
        $<TARGET_FILE:protobuf::protoc>

    ARGS
        --proto_path=${PROTO_DIR}
        --cpp_out=${GENERATED_DIR}
        --grpc_out=${GENERATED_DIR}
        --plugin=protoc-gen-grpc=$<TARGET_FILE:gRPC::grpc_cpp_plugin>
        ${PROTO_FILE}

    DEPENDS
        ${PROTO_FILE}

    COMMENT
        "Generating Forge protobuf and gRPC sources"

    VERBATIM
)

add_executable(
    forge-worker
    src/main.cpp
    src/ProcessExecutor.cpp
    src/TaskExecutorPool.cpp
    ${PROTO_SRCS}
)

target_include_directories(
    forge-worker
    PRIVATE
        "${GENERATED_DIR}"
)

target_link_libraries(
    forge-worker
    PRIVATE
        gRPC::grpc++
        protobuf::libprotobuf
)
