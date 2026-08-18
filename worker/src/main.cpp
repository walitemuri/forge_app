#include <grpcpp/grpcpp.h>

#include <chrono>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <sstream>
#include <string>
#include <thread>

#ifdef __APPLE__
#include <mach/mach.h>
#include <mach/mach_host.h>
#include <sys/sysctl.h>
#else
#include <sys/sysinfo.h>
#endif

#include <unistd.h>
#include <unistd.h>

#include "forge.grpc.pb.h"


struct CpuTimes {
    uint64_t idle;
    uint64_t total;
};


std::string getHostname() {
    char hostname[256];

    if (gethostname(hostname, sizeof(hostname)) == 0) {
        return hostname;
    }

    return "unknown";
}


uint64_t getTotalMemoryBytes() {

#ifdef __APPLE__

    uint64_t memory = 0;
    size_t length = sizeof(memory);

    if (sysctlbyname(
            "hw.memsize",
            &memory,
            &length,
            nullptr,
            0) == 0) {

        return memory;
    }

    return 0;

#else

    struct sysinfo info{};

    if (sysinfo(&info) == 0) {
        return static_cast<uint64_t>(info.totalram)
            * info.mem_unit;
    }

    return 0;

#endif
}


uint64_t getMemoryUsedBytes() {

#ifdef __APPLE__

    mach_msg_type_number_t count =
        HOST_VM_INFO64_COUNT;

    vm_statistics64_data_t vmStats{};

    mach_port_t host =
        mach_host_self();

    kern_return_t result =
        host_statistics64(
            host,
            HOST_VM_INFO64,
            reinterpret_cast<host_info64_t>(&vmStats),
            &count
        );

    if (result != KERN_SUCCESS) {
        return 0;
    }

    uint64_t pageSize = 0;

    host_page_size(
        host,
        reinterpret_cast<vm_size_t*>(&pageSize)
    );

    uint64_t usedPages =
        vmStats.active_count
        + vmStats.wire_count
        + vmStats.compressor_page_count;

    return usedPages * pageSize;

#else

    std::ifstream file("/proc/meminfo");

    std::string line;

    uint64_t totalKb = 0;
    uint64_t availableKb = 0;

    while (std::getline(file, line)) {

        std::istringstream stream(line);

        std::string key;
        uint64_t value = 0;

        stream >> key >> value;

        if (key == "MemTotal:") {
            totalKb = value;
        }
        else if (key == "MemAvailable:") {
            availableKb = value;
        }

        if (totalKb > 0 && availableKb > 0) {
            break;
        }
    }

    if (totalKb < availableKb) {
        return 0;
    }

    return (totalKb - availableKb) * 1024;

#endif
}
CpuTimes readCpuTimes() {

#ifdef __APPLE__

    host_cpu_load_info_data_t cpuInfo{};

    mach_msg_type_number_t count =
        HOST_CPU_LOAD_INFO_COUNT;

    kern_return_t result =
        host_statistics(
            mach_host_self(),
            HOST_CPU_LOAD_INFO,
            reinterpret_cast<host_info_t>(&cpuInfo),
            &count
        );

    if (result != KERN_SUCCESS) {
        return {0, 0};
    }

    uint64_t user =
        cpuInfo.cpu_ticks[CPU_STATE_USER];

    uint64_t system =
        cpuInfo.cpu_ticks[CPU_STATE_SYSTEM];

    uint64_t idle =
        cpuInfo.cpu_ticks[CPU_STATE_IDLE];

    uint64_t nice =
        cpuInfo.cpu_ticks[CPU_STATE_NICE];

    uint64_t total =
        user + system + idle + nice;

    return {
        idle,
        total
    };

#else

    std::ifstream file("/proc/stat");

    std::string cpu;

    uint64_t user = 0;
    uint64_t nice = 0;
    uint64_t system = 0;
    uint64_t idle = 0;
    uint64_t iowait = 0;
    uint64_t irq = 0;
    uint64_t softirq = 0;
    uint64_t steal = 0;

    file >> cpu
         >> user
         >> nice
         >> system
         >> idle
         >> iowait
         >> irq
         >> softirq
         >> steal;

    uint64_t idleTime =
        idle + iowait;

    uint64_t totalTime =
        user
        + nice
        + system
        + idle
        + iowait
        + irq
        + softirq
        + steal;

    return {
        idleTime,
        totalTime
    };

#endif
}

double calculateCpuUsage(
        const CpuTimes& previous,
        const CpuTimes& current) {

    uint64_t totalDelta =
        current.total - previous.total;

    uint64_t idleDelta =
        current.idle - previous.idle;

    if (totalDelta == 0) {
        return 0.0;
    }

    return 100.0 *
           static_cast<double>(
               totalDelta - idleDelta
           ) /
           static_cast<double>(
               totalDelta
           );
}


int main() {

    std::cout << "Forge Worker starting...\n";

    const std::string hostname = getHostname();
    const std::string workerId = hostname + "-worker";

    const unsigned int cpuCores =
        std::thread::hardware_concurrency();

    const uint64_t totalMemoryBytes =
        getTotalMemoryBytes();

    std::cout << "Hostname: " << hostname << "\n";
    std::cout << "Worker ID: " << workerId << "\n";
    std::cout << "CPU cores: " << cpuCores << "\n";
    std::cout << "Memory: "
              << totalMemoryBytes
              << " bytes\n";


    // -------------------------------------------------
    // Connect to Forge controller
    // -------------------------------------------------

    auto channel = grpc::CreateChannel(
        "localhost:50051",
        grpc::InsecureChannelCredentials()
    );

    auto stub =
        forge::v1::ForgeController::NewStub(channel);


    // -------------------------------------------------
    // Register worker
    // -------------------------------------------------

    forge::v1::RegisterWorkerRequest registerRequest;

    registerRequest.set_worker_id(workerId);
    registerRequest.set_hostname(hostname);
    registerRequest.set_cpu_cores(cpuCores);
    registerRequest.set_memory_bytes(totalMemoryBytes);
    
    #ifdef __APPLE__
        const std::string operatingSystem = "macOS";
    #else
        const std::string operatingSystem = "Linux";
    #endif

    registerRequest.set_operating_system(
    operatingSystem
    );

    forge::v1::RegisterWorkerResponse registerResponse;

    grpc::ClientContext registerContext;

    grpc::Status registerStatus =
        stub->RegisterWorker(
            &registerContext,
            registerRequest,
            &registerResponse
        );

    if (!registerStatus.ok()) {

        std::cerr
            << "Failed to register worker: "
            << registerStatus.error_message()
            << "\n";

        return 1;
    }

    if (!registerResponse.accepted()) {

        std::cerr
            << "Controller rejected worker registration\n";

        return 1;
    }

    std::cout << "\nController response:\n";
    std::cout << registerResponse.message() << "\n";


    // -------------------------------------------------
    // Open long-lived bidirectional command stream
    // -------------------------------------------------

    grpc::ClientContext streamContext;

    std::shared_ptr<
        grpc::ClientReaderWriter<
            forge::v1::WorkerMessage,
            forge::v1::ControllerMessage
        >
    > commandStream(
        stub->ConnectWorker(&streamContext).release()
    );


    // -------------------------------------------------
    // Send WorkerHello through stream
    // -------------------------------------------------

    forge::v1::WorkerMessage helloMessage;

    auto* hello =
        helloMessage.mutable_hello();

    hello->set_worker_id(workerId);
    hello->set_hostname(hostname);
    hello->set_cpu_cores(cpuCores);
    hello->set_memory_bytes(totalMemoryBytes);
    hello->set_operating_system(
        operatingSystem
    );


    if (!commandStream->Write(helloMessage)) {

        std::cerr
            << "Failed to send WorkerHello\n";

        return 1;
    }


    std::cout
        << "Command stream connected.\n";


    // -------------------------------------------------
    // Background thread waits for controller commands
    // -------------------------------------------------

    std::thread commandReader(
        [commandStream]() {

            forge::v1::ControllerMessage message;

            while (commandStream->Read(&message)) {

                if (message.has_task_assignment()) {

                    const auto& task =
                        message.task_assignment();

                    std::cout << "\n";
                    std::cout
                        << "=== TASK RECEIVED ===\n";

                    std::cout
                        << "Task ID: "
                        << task.task_id()
                        << "\n";

                    std::cout
                        << "Command: "
                        << task.command()
                        << "\n";

                    std::cout
                        << "Arguments:";

                    for (const auto& argument :
                            task.arguments()) {

                        std::cout
                            << " "
                            << argument;
                    }

                    std::cout << "\n";
                    std::cout
                        << "=====================\n";
                }
            }

            std::cerr
                << "Controller command stream closed.\n";
        }
    );

    commandReader.detach();


    // -------------------------------------------------
    // Continue sending existing unary heartbeats
    // -------------------------------------------------

    CpuTimes previousCpuTimes =
        readCpuTimes();

    while (true) {

        std::this_thread::sleep_for(
            std::chrono::seconds(5)
        );

        CpuTimes currentCpuTimes =
            readCpuTimes();

        double cpuUsage =
            calculateCpuUsage(
                previousCpuTimes,
                currentCpuTimes
            );

        previousCpuTimes =
            currentCpuTimes;

        uint64_t memoryUsed =
            getMemoryUsedBytes();


        forge::v1::HeartbeatRequest heartbeat;

        heartbeat.set_worker_id(workerId);
        heartbeat.set_cpu_usage_percent(cpuUsage);
        heartbeat.set_memory_used_bytes(memoryUsed);
        heartbeat.set_running_tasks(0);


        forge::v1::HeartbeatResponse heartbeatResponse;

        grpc::ClientContext heartbeatContext;

        grpc::Status heartbeatStatus =
            stub->Heartbeat(
                &heartbeatContext,
                heartbeat,
                &heartbeatResponse
            );


        if (heartbeatStatus.ok()
            && heartbeatResponse.accepted()) {

            double memoryGb =
                static_cast<double>(memoryUsed)
                / 1024.0
                / 1024.0
                / 1024.0;

            std::cout
                << std::fixed
                << std::setprecision(1)
                << "[heartbeat] CPU="
                << cpuUsage
                << "% RAM="
                << memoryGb
                << " GB\n";
        }
        else {

            std::cerr
                << "[heartbeat] FAILED: "
                << heartbeatStatus.error_message()
                << "\n";
        }
    }


    return 0;
}