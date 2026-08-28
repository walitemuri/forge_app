#pragma once

#include <cstdint>
#include <string>
#include <vector>


struct ProcessResult {

    int exitCode;

    std::string stdoutOutput;

    std::string stderrOutput;

    bool timedOut;
};


ProcessResult executeProcess(
    const std::string& command,
    const std::vector<std::string>& arguments,
    std::uint32_t timeoutSeconds
);