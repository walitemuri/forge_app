#include "ProcessExecutor.h"

#include <cerrno>
#include <chrono>
#include <csignal>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <sys/wait.h>
#include <unistd.h>

#include <vector>


namespace {

void setNonBlocking(int fd) {

    int flags =
        fcntl(
            fd,
            F_GETFL,
            0
        );


    if (flags != -1) {

        fcntl(
            fd,
            F_SETFL,
            flags | O_NONBLOCK
        );
    }
}


void readAvailable(
        int fd,
        std::string& output,
        bool& open) {

    char buffer[4096];


    while (true) {

        ssize_t bytesRead =
            read(
                fd,
                buffer,
                sizeof(buffer)
            );


        if (bytesRead > 0) {

            output.append(
                buffer,
                static_cast<size_t>(
                    bytesRead
                )
            );
        }
        else if (bytesRead == 0) {

            close(fd);

            open = false;

            return;
        }
        else {

            if (errno == EAGAIN
                    || errno == EWOULDBLOCK) {

                return;
            }


            close(fd);

            open = false;

            return;
        }
    }
}

}


ProcessResult executeProcess(
        const std::string& command,
        const std::vector<std::string>& arguments,
        std::uint32_t timeoutSeconds) {

    int stdoutPipe[2];

    int stderrPipe[2];


    if (pipe(stdoutPipe) == -1
            || pipe(stderrPipe) == -1) {

        return {
            -1,
            "",
            "Failed to create process pipes",
            false
        };
    }


    pid_t pid =
        fork();


    if (pid == -1) {

        close(stdoutPipe[0]);
        close(stdoutPipe[1]);

        close(stderrPipe[0]);
        close(stderrPipe[1]);


        return {
            -1,
            "",
            "Failed to fork process",
            false
        };
    }


    // =================================================
    // CHILD
    // =================================================

    if (pid == 0) {

        /*
         * Give this task its own process group.
         *
         * That lets the worker kill not only the direct
         * child, but also subprocesses created by it.
         */
        setpgid(
            0,
            0
        );


        close(
            stdoutPipe[0]
        );

        close(
            stderrPipe[0]
        );


        dup2(
            stdoutPipe[1],
            STDOUT_FILENO
        );

        dup2(
            stderrPipe[1],
            STDERR_FILENO
        );


        close(
            stdoutPipe[1]
        );

        close(
            stderrPipe[1]
        );


        std::vector<char*> argv;


        argv.push_back(
            const_cast<char*>(
                command.c_str()
            )
        );


        for (const auto& argument :
                arguments) {

            argv.push_back(
                const_cast<char*>(
                    argument.c_str()
                )
            );
        }


        argv.push_back(
            nullptr
        );


        execvp(
            command.c_str(),
            argv.data()
        );


        const char message[] =
            "Failed to execute command\n";


        write(
            STDERR_FILENO,
            message,
            sizeof(message) - 1
        );


        _exit(
            127
        );
    }


    // =================================================
    // PARENT
    // =================================================

    /*
     * Also attempt to establish the group from the
     * parent side. Either parent or child may win
     * the scheduling race, which is fine.
     */
    setpgid(
        pid,
        pid
    );


    close(
        stdoutPipe[1]
    );

    close(
        stderrPipe[1]
    );


    setNonBlocking(
        stdoutPipe[0]
    );

    setNonBlocking(
        stderrPipe[0]
    );


    std::string stdoutOutput;

    std::string stderrOutput;


    bool stdoutOpen =
        true;

    bool stderrOpen =
        true;

    bool timedOut =
        false;


    const auto startedAt =
        std::chrono::steady_clock::now();


    while (stdoutOpen
            || stderrOpen) {

        /*
         * timeoutSeconds == 0 means unlimited.
         */
        if (!timedOut
                && timeoutSeconds > 0) {

            const auto elapsed =
                std::chrono::duration_cast<
                    std::chrono::seconds
                >(
                    std::chrono::steady_clock::now()
                    - startedAt
                );


            if (elapsed.count()
                    >= timeoutSeconds) {

                timedOut =
                    true;


                /*
                 * Negative PID targets the entire
                 * process group.
                 */
                if (kill(
                        -pid,
                        SIGKILL
                    ) == -1) {

                    /*
                     * Fallback in case process-group
                     * establishment somehow failed.
                     */
                    kill(
                        pid,
                        SIGKILL
                    );
                }
            }
        }


        pollfd descriptors[2]{};


        descriptors[0].fd =
            stdoutOpen
                ? stdoutPipe[0]
                : -1;


        descriptors[0].events =
            POLLIN | POLLHUP;


        descriptors[1].fd =
            stderrOpen
                ? stderrPipe[0]
                : -1;


        descriptors[1].events =
            POLLIN | POLLHUP;


        int result =
            poll(
                descriptors,
                2,
                100
            );


        if (result < 0
                && errno != EINTR) {

            break;
        }


        if (stdoutOpen
                && (
                    descriptors[0].revents
                    & (POLLIN | POLLHUP)
                )) {

            readAvailable(
                stdoutPipe[0],
                stdoutOutput,
                stdoutOpen
            );
        }


        if (stderrOpen
                && (
                    descriptors[1].revents
                    & (POLLIN | POLLHUP)
                )) {

            readAvailable(
                stderrPipe[0],
                stderrOutput,
                stderrOpen
            );
        }
    }


    int status =
        0;


    waitpid(
        pid,
        &status,
        0
    );


    int exitCode =
        -1;


    if (timedOut) {

        /*
         * 124 is the conventional timeout exit code.
         */
        exitCode =
            124;


        if (!stderrOutput.empty()
                && stderrOutput.back() != '\n') {

            stderrOutput +=
                '\n';
        }


        stderrOutput +=
            "Process exceeded timeout of "
            + std::to_string(
                timeoutSeconds
            )
            + " second(s)\n";
    }
    else if (WIFEXITED(status)) {

        exitCode =
            WEXITSTATUS(
                status
            );
    }
    else if (WIFSIGNALED(status)) {

        exitCode =
            128
            + WTERMSIG(
                status
            );
    }


    return {
        exitCode,
        stdoutOutput,
        stderrOutput,
        timedOut
    };
}