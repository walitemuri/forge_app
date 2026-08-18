#include <grpcpp/grpcpp.h>

#include <iostream>
#include <memory>
#include <string>
#include <thread>

#include <unistd.h>
#include <sys/sysinfo.h>

#include "forge.grpc.pb.h"
#include <chrono>

std::string getHostname() {
    char hostname[256];

    if (gethostname(hostname, sizeof(hostname)) == 0) {
        return hostname;
    }

    return "unknown";
}

uint64_t getMemoryBytes() {
    struct sysinfo info{};

    if (sysinfo(&info) == 0) {
        return static_cast<uint64_t>(info.totalram) * info.mem_unit;
    }

    return 0;
}

int main() {
    std::cout << "Forge Worker starting...\n";

    std::string hostname = getHostname();
    unsigned int cpuCores = std::thread::hardware_concurrency();
    uint64_t memoryBytes = getMemoryBytes();

    std::cout << "Hostname: " << hostname << "\n";
    std::cout << "CPU cores: " << cpuCores << "\n";
    std::cout << "Memory: " << memoryBytes << " bytes\n";

    auto channel = grpc::CreateChannel(
        "localhost:50051",
        grpc::InsecureChannelCredentials()
    );

    std::unique_ptr<forge::v1::ForgeController::Stub> stub =
        forge::v1::ForgeController::NewStub(channel);

    forge::v1::RegisterWorkerRequest request;

    request.set_worker_id(hostname + "-worker");
    request.set_hostname(hostname);
    request.set_cpu_cores(cpuCores);
    request.set_memory_bytes(memoryBytes);
    request.set_operating_system("Linux");

    forge::v1::RegisterWorkerResponse response;

    grpc::ClientContext context;

    grpc::Status status =
        stub->RegisterWorker(
            &context,
            request,
            &response
        );

    if (!status.ok()) {
        std::cerr << "Failed to register worker\n";
        std::cerr << "gRPC error: "
                  << status.error_message()
                  << "\n";

        return 1;
    }

    std::cout << "\nController response:\n";
    std::cout << response.message() << "\n";

    while (true) {

        forge::v1::HeartbeatRequest heartbeat;
        heartbeat.set_worker_id(hostname + "-worker");

        heartbeat.set_cpu_usage_percent(0.0);
        heartbeat.set_memory_used_bytes(0);
        heartbeat.set_running_tasks(0);

        forge::v1::HeartbeatResponse heartbeatResponse;

        grpc::ClientContext heartbeatContext;

        grpc::Status heartbeatStatus =
            stub->Heartbeat(
                &heartbeatContext,
                heartbeat,
                &heartbeatResponse
            );

        if (heartbeatStatus.ok() &&
            heartbeatResponse.accepted()) {

            std::cout
                << "[heartbeat] sent successfully\n";
        }
        else {

            std::cerr
                << "[heartbeat] failed: "
                << heartbeatStatus.error_message()
                << "\n";
        }

        std::this_thread::sleep_for(
            std::chrono::seconds(5)
        );
    }
    return 0;
}