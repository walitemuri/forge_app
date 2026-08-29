#include "ProcessExecutor.h"

#include <cerrno>
#include <chrono>
#include <csignal>
#include <fcntl.h>
#include <poll.h>
#include <sys/wait.h>
#include <unistd.h>

#include <vector>


namespace {


void setNonBlocking(
        int fd) {

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


void signalProcessGroup(
        pid_t pid,
        int signal) {

    /*
     * Negative PID means:
     * send signal to the entire process group.
     */
    if (kill(
            -pid,
            signal
        ) == -1) {

        /*
         * Fallback if process-group creation
         * somehow failed.
         */
        kill(
            pid,
            signal
        );
    }
}


}


ProcessResult executeProcess(
        const std::string& command,
        const std::vector<std::string>& arguments,
        std::uint32_t timeoutSeconds,
        const CancellationFlag& cancellationFlag) {

    int stdoutPipe[2];

    int stderrPipe[2];


    if (pipe(stdoutPipe) == -1
            || pipe(stderrPipe) == -1) {

        return {
            -1,
            "",
            "Failed to create process pipes",
            false,
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
            false,
            false
        };
    }


    // =================================================
    // CHILD
    // =================================================

    if (pid == 0) {

        /*
         * Every Forge execution gets its own process
         * group so cancellation also kills subprocesses.
         */
        setpgid(
            0,
            0
        );


        close(stdoutPipe[0]);
        close(stderrPipe[0]);


        dup2(
            stdoutPipe[1],
            STDOUT_FILENO
        );

        dup2(
            stderrPipe[1],
            STDERR_FILENO
        );


        close(stdoutPipe[1]);
        close(stderrPipe[1]);


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

    setpgid(
        pid,
        pid
    );


    close(stdoutPipe[1]);
    close(stderrPipe[1]);


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

    bool cancelled =
        false;

    bool forceKillSent =
        false;

    bool childExited =
        false;


    int status =
        0;


    const auto startedAt =
        std::chrono::steady_clock::now();


    auto cancellationStartedAt =
        startedAt;


    while (stdoutOpen
            || stderrOpen
            || !childExited) {

        const auto now =
            std::chrono::steady_clock::now();


        /*
         * Check whether the child has exited without
         * blocking this executor thread.
         */
        if (!childExited) {

            pid_t waitResult =
                waitpid(
                    pid,
                    &status,
                    WNOHANG
                );


            if (waitResult == pid) {

                childExited =
                    true;
            }
        }


        // =============================================
        // User/controller cancellation
        // =============================================

        if (!cancelled
                && cancellationFlag
                && cancellationFlag->load()) {

            cancelled =
                true;

            cancellationStartedAt =
                now;


            /*
             * First ask the process to terminate
             * gracefully.
             */
            signalProcessGroup(
                pid,
                SIGTERM
            );
        }


        /*
         * Give SIGTERM two seconds.
         *
         * We still target the process group even if
         * the direct child exited because descendants
         * may remain alive.
         */
        if (cancelled
                && !forceKillSent) {

            const auto cancellationElapsed =
                std::chrono::duration_cast<
                    std::chrono::seconds
                >(
                    now
                    - cancellationStartedAt
                );


            if (cancellationElapsed.count()
                    >= 2) {

                signalProcessGroup(
                    pid,
                    SIGKILL
                );


                forceKillSent =
                    true;
            }
        }


        // =============================================
        // Execution timeout
        // =============================================

        if (!cancelled
                && !timedOut
                && timeoutSeconds > 0) {

            const auto elapsed =
                std::chrono::duration_cast<
                    std::chrono::seconds
                >(
                    now
                    - startedAt
                );


            if (elapsed.count()
                    >= timeoutSeconds) {

                timedOut =
                    true;

                forceKillSent =
                    true;


                signalProcessGroup(
                    pid,
                    SIGKILL
                );
            }
        }


        // =============================================
        // Drain stdout / stderr
        // =============================================

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


        int pollResult =
            poll(
                descriptors,
                2,
                100
            );


        if (pollResult < 0
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


    /*
     * If waitpid(WNOHANG) never reaped the child,
     * reap it now.
     */
    if (!childExited) {

        waitpid(
            pid,
            &status,
            0
        );
    }


    int exitCode =
        -1;


    if (timedOut) {

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
    else {

        if (WIFEXITED(status)) {

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


        if (cancelled) {

            if (!stderrOutput.empty()
                    && stderrOutput.back() != '\n') {

                stderrOutput +=
                    '\n';
            }


            stderrOutput +=
                "Process cancelled by controller\n";
        }
    }


    return {
        exitCode,
        stdoutOutput,
        stderrOutput,
        timedOut,
        cancelled
    };
}