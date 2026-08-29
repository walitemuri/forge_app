#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>


struct ProcessResult {

    int exitCode;

    std::string stdoutOutput;

    std::string stderrOutput;

    bool timedOut;

    bool cancelled;
};


using CancellationFlag =
    std::shared_ptr<std::atomic<bool>>;


ProcessResult executeProcess(
    const std::string& command,
    const std::vector<std::string>& arguments,
    std::uint32_t timeoutSeconds,
    const CancellationFlag& cancellationFlag
);