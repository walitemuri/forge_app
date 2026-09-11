#include "ProcessExecutor.h"

#include <atomic>
#include <iostream>
#include <memory>
#include <string>


namespace {


int fail(
        const std::string& message) {

    std::cerr
        << "FAIL: "
        << message
        << "\n";

    return 1;
}


}


int main() {

    const auto cancellationFlag =
        std::make_shared<
            std::atomic<bool>
        >(
            false
        );


    /*
     * Generate more than 2 MiB on each stream.
     *
     * The final markers prove that Forge retains
     * the tail rather than only the beginning.
     */
    const ProcessResult result =
        executeProcess(
            "/bin/sh",
            {
                "-c",
                R"(
dd if=/dev/zero bs=1048576 count=2 2>/dev/null \
    | tr '\000' 'A'

printf '\nSTDOUT_TAIL\n'

dd if=/dev/zero bs=1048576 count=2 2>/dev/null \
    | tr '\000' 'B' >&2

printf '\nSTDERR_TAIL\n' >&2
)"
            },
            10,
            cancellationFlag
        );


    if (result.timedOut) {

        return fail(
            "large-output process timed out"
        );
    }


    if (result.cancelled) {

        return fail(
            "large-output process was cancelled"
        );
    }


    if (result.exitCode != 0) {

        return fail(
            "large-output process failed"
        );
    }


    /*
     * 1 MiB retained data plus a small
     * human-readable truncation marker.
     */
    constexpr std::size_t MAX_EXPECTED =
        (1024 * 1024) + 256;


    if (
        result.stdoutOutput.size()
            > MAX_EXPECTED
    ) {

        return fail(
            "stdout exceeded capture limit"
        );
    }


    if (
        result.stderrOutput.size()
            > MAX_EXPECTED
    ) {

        return fail(
            "stderr exceeded capture limit"
        );
    }


    if (
        result.stdoutOutput.find(
            "stdout truncated"
        ) == std::string::npos
    ) {

        return fail(
            "stdout truncation marker missing"
        );
    }


    if (
        result.stderrOutput.find(
            "stderr truncated"
        ) == std::string::npos
    ) {

        return fail(
            "stderr truncation marker missing"
        );
    }


    if (
        result.stdoutOutput.find(
            "STDOUT_TAIL"
        ) == std::string::npos
    ) {

        return fail(
            "stdout tail was not retained"
        );
    }


    if (
        result.stderrOutput.find(
            "STDERR_TAIL"
        ) == std::string::npos
    ) {

        return fail(
            "stderr tail was not retained"
        );
    }


    std::cout
        << "PASS: process output is bounded "
        << "and retains stream tails\n";


    return 0;
}
