#include "ProcessExecutor.h"

#include <atomic>
#include <cstring>
#include <iostream>
#include <memory>
#include <string>

#include <unistd.h>


namespace {


bool writeAll(
        int fd,
        const std::string& data) {

    std::size_t offset = 0;


    while (offset < data.size()) {

        const ssize_t written =
            write(
                fd,
                data.data() + offset,
                data.size() - offset
            );


        if (written <= 0) {

            return false;
        }


        offset +=
            static_cast<std::size_t>(
                written
            );
    }


    return true;
}


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

    /*
     * Give this test process a known stdin payload.
     *
     * If ProcessExecutor incorrectly inherits stdin,
     * the child will read this value and fail.
     *
     * A correctly isolated Forge task receives
     * /dev/null instead and therefore sees EOF.
     */
    int inputPipe[2];


    if (pipe(inputPipe) == -1) {

        return fail(
            "could not create stdin test pipe"
        );
    }


    const std::string payload =
        "FORGE_SHOULD_NOT_INHERIT_THIS\n";


    if (!writeAll(
            inputPipe[1],
            payload
        )) {

        close(inputPipe[0]);
        close(inputPipe[1]);

        return fail(
            "could not write stdin payload"
        );
    }


    close(
        inputPipe[1]
    );


    const int originalStdin =
        dup(
            STDIN_FILENO
        );


    if (originalStdin == -1) {

        close(
            inputPipe[0]
        );

        return fail(
            "could not preserve stdin"
        );
    }


    if (dup2(
            inputPipe[0],
            STDIN_FILENO
        ) == -1) {

        close(
            inputPipe[0]
        );

        close(
            originalStdin
        );

        return fail(
            "could not replace test stdin"
        );
    }


    close(
        inputPipe[0]
    );


    const auto cancellationFlag =
        std::make_shared<
            std::atomic<bool>
        >(
            false
        );


    const ProcessResult result =
        executeProcess(
            "/bin/sh",
            {
                "-c",
                R"(
if IFS= read -r line; then
    printf 'inherited:%s\n' "$line"
    exit 42
fi

printf 'stdin-eof\n'
)"
            },
            5,
            cancellationFlag
        );


    /*
     * Restore the test runner's stdin before doing
     * anything else.
     */
    if (dup2(
            originalStdin,
            STDIN_FILENO
        ) == -1) {

        close(
            originalStdin
        );

        return fail(
            "could not restore stdin"
        );
    }


    close(
        originalStdin
    );


    if (result.timedOut) {

        return fail(
            "child unexpectedly timed out"
        );
    }


    if (result.cancelled) {

        return fail(
            "child unexpectedly cancelled"
        );
    }


    if (result.exitCode != 0) {

        std::cerr
            << "stdout:\n"
            << result.stdoutOutput
            << "\n";

        std::cerr
            << "stderr:\n"
            << result.stderrOutput
            << "\n";

        return fail(
            "child inherited parent stdin"
        );
    }


    if (
        result.stdoutOutput.find(
            "stdin-eof"
        ) == std::string::npos
    ) {

        return fail(
            "child did not observe stdin EOF"
        );
    }


    if (
        result.stdoutOutput.find(
            "FORGE_SHOULD_NOT_INHERIT_THIS"
        ) != std::string::npos
    ) {

        return fail(
            "parent stdin leaked into task"
        );
    }


    std::cout
        << "PASS: Forge task stdin "
        << "is isolated\n";


    return 0;
}
