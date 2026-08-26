
#pragma once

#include <string>
#include <vector>

struct ProcessResult {
    int exitCode;
    std::string stdoutOutput;
    std::string stderrOutput;
};

ProcessResult executeProcess(
    const std::string& command,
    const std::vector<std::string>& arguments
);