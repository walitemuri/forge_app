#!/usr/bin/env python3

import os
import signal
import subprocess
import sys
import time

import worker_session_fencing_smoke_test as helpers


WORKERS_URL = "http://localhost:8080/api/workers"

POLL = 0.20


def db_environment():

    environment = dict(
        os.environ
    )

    environment["PGPASSWORD"] = "forge"

    return environment


def db_scalar(sql):

    result = subprocess.run(
        [
            "psql",
            "-h",
            "127.0.0.1",
            "-p",
            "5433",
            "-X",
            "-q",
            "-A",
            "-t",
            "-U",
            "forge",
            "-d",
            "forge",
            "-c",
            sql,
        ],
        text=True,
        capture_output=True,
        env=db_environment(),
    )


    if result.returncode != 0:

        raise RuntimeError(
            "Database command failed:\n"
            + result.stderr
        )


    lines = [
        line.strip()
        for line in result.stdout.splitlines()
        if line.strip()
    ]


    if not lines:
        return None

    return lines[0]


def sql_escape(value):

    return value.replace(
        "'",
        "''"
    )


def authority_session(worker_id):

    worker = sql_escape(
        worker_id
    )

    return db_scalar(
        f"""
        SELECT session_id
        FROM worker_authorities
        WHERE worker_id = '{worker}';
        """
    )


def recovery_count(
        worker_id,
        session_id):

    worker = sql_escape(
        worker_id
    )

    session = sql_escape(
        session_id
    )

    value = db_scalar(
        f"""
        SELECT COUNT(*)
        FROM worker_session_recoveries
        WHERE worker_id = '{worker}'
          AND session_id = '{session}';
        """
    )

    return int(
        value or "0"
    )


def get_worker(worker_id):

    workers = helpers.get(
        WORKERS_URL
    )

    for worker in workers:

        if worker["id"] == worker_id:
            return worker

    return None


def wait_until(
        predicate,
        description,
        timeout):

    deadline = time.time() + timeout

    while time.time() < deadline:

        value = predicate()

        if value:
            return value

        time.sleep(
            POLL
        )

    raise AssertionError(
        f"Timed out waiting for {description}"
    )


def start_authority_lock(
        worker_id):

    worker = sql_escape(
        worker_id
    )

    sql = f"""
    BEGIN;

    SELECT 1
    FROM worker_authorities
    WHERE worker_id = '{worker}'
    FOR UPDATE;

    SELECT pg_sleep(120);
    /* FORGE_ATOMICITY_LOCK */
    """


    process = subprocess.Popen(
        [
            "psql",
            "-h",
            "127.0.0.1",
            "-p",
            "5433",
            "-X",
            "-q",
            "-A",
            "-t",
            "-U",
            "forge",
            "-d",
            "forge",
            "-c",
            sql,
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        env=db_environment(),
    )


    def lock_ready():

        value = db_scalar(
            """
            SELECT COUNT(*)
            FROM pg_stat_activity
            WHERE datname = 'forge'
              AND wait_event = 'PgSleep'
              AND query LIKE
                  '%FORGE_ATOMICITY_LOCK%';
            """
        )

        return int(
            value or "0"
        ) > 0


    wait_until(
        lock_ready,
        "authority row lock",
        timeout=10,
    )


    return process


def transfer_is_blocked():

    value = db_scalar(
        """
        SELECT COUNT(*)
        FROM pg_stat_activity
        WHERE datname = 'forge'
          AND wait_event_type = 'Lock'
          AND query ILIKE
              '%UPDATE worker_authorities%';
        """
    )

    return int(
        value or "0"
    ) > 0


def terminate_authority_lock():

    value = db_scalar(
        """
        SELECT COUNT(*)
        FROM (
            SELECT
                pg_terminate_backend(pid)
                    AS terminated
            FROM pg_stat_activity
            WHERE datname = 'forge'
              AND pid <> pg_backend_pid()
              AND query LIKE
                  '%FORGE_ATOMICITY_LOCK%'
        ) AS terminated_backends
        WHERE terminated;
        """
    )

    return int(
        value or "0"
    )


def terminate_blocked_authority_transfers():

    value = db_scalar(
        """
        SELECT COUNT(*)
        FROM (
            SELECT
                pg_terminate_backend(pid)
                    AS terminated
            FROM pg_stat_activity
            WHERE datname = 'forge'
              AND pid <> pg_backend_pid()
              AND wait_event_type = 'Lock'
              AND query ILIKE
                  '%UPDATE worker_authorities%'
        ) AS terminated_backends
        WHERE terminated;
        """
    )

    return int(
        value or "0"
    )


def process_exists(pid):

    try:

        os.kill(
            pid,
            0
        )

        return True

    except ProcessLookupError:

        return False


def main():

    if not sys.platform.startswith(
            "linux"):

        raise RuntimeError(
            "This test requires Linux/WSL"
        )


    print(
        "Forge worker takeover atomicity test"
    )
    print(
        "===================================="
    )


    worker_a = (
        helpers.require_one_worker()
    )

    worker_id = worker_a["id"]
    session_a = worker_a["sessionId"]

    pid_a = (
        helpers.require_one_local_worker()
    )


    environment = (
        helpers.read_process_environment(
            pid_a
        )
    )


    print("Worker:", worker_id)
    print("Session A:", session_a)
    print("PID A:", pid_a)


    durable_authority = (
        authority_session(
            worker_id
        )
    )


    if durable_authority != session_a:

        raise AssertionError(
            "Initial durable authority does not "
            "match live worker. "
            f"DB={durable_authority} "
            f"live={session_a}"
        )


    if recovery_count(
            worker_id,
            session_a) != 0:

        raise AssertionError(
            "Session A already has a pending "
            "recovery row"
        )


    lock_process = None
    process_b = None
    replacement_ready = False
    failure = None


    try:

        # ====================================================
        # Make A eligible for takeover.
        # ====================================================

        print(
            "\n[CHAOS] SIGSTOP session A"
        )


        os.kill(
            pid_a,
            signal.SIGSTOP
        )


        print(
            "[WAIT] waiting for controller "
            "to mark A offline"
        )


        wait_until(
            lambda:
                (
                    (worker := get_worker(
                        worker_id
                    ))
                    is not None
                    and not worker["online"]
                ),
            "session A to become offline",
            timeout=30,
        )


        print(
            "[VERIFY] A is offline"
        )


        # ====================================================
        # Lock the durable authority row.
        #
        # B may begin the takeover, but PostgreSQL will not
        # allow A -> B to complete yet.
        # ====================================================

        print(
            "[DB] locking worker authority row"
        )


        lock_process = (
            start_authority_lock(
                worker_id
            )
        )


        print(
            "[VERIFY] authority row locked"
        )


        # ====================================================
        # Start B.
        #
        # registerWorker() should reach the authority UPDATE
        # and block on our row lock.
        # ====================================================

        print(
            "[TAKEOVER] starting session B"
        )


        process_b = (
            helpers.launch_worker(
                environment
            )
        )


        print(
            "[WAIT] waiting for authority "
            "transfer to block"
        )


        wait_until(
            transfer_is_blocked,
            "blocked authority transfer",
            timeout=15,
        )


        print(
            "[VERIFY] authority transfer is blocked"
        )


        # ====================================================
        # Core invariant.
        #
        # While A -> B is unable to commit, the recovery row
        # must not be externally visible either.
        #
        # Current pre-fix Forge is expected to FAIL here.
        # ====================================================

        current_authority = (
            authority_session(
                worker_id
            )
        )


        if current_authority != session_a:

            failure = AssertionError(
                "Authority changed despite "
                "the PostgreSQL row lock"
            )


        visible_recoveries = (
            recovery_count(
                worker_id,
                session_a
            )
        )


        print(
            "[OBSERVE] durable authority:",
            current_authority,
        )

        print(
            "[OBSERVE] visible recovery rows:",
            visible_recoveries,
        )


        if (
            failure is None
            and visible_recoveries != 0
        ):

            failure = AssertionError(
                "HALF-HANDOFF OBSERVED: "
                "the old-session recovery row "
                "committed while the authority "
                "transfer was still blocked"
            )


        if failure is None:

            print(
                "[VERIFY] no half-handoff "
                "became visible"
            )


    finally:

        # ====================================================
        # Stop replacement B before removing the artificial
        # database stall.
        # ====================================================

        if process_b is not None:

            try:

                if process_b.poll() is None:

                    os.kill(
                        process_b.pid,
                        signal.SIGKILL
                    )

                    process_b.wait(
                        timeout=5
                    )

                    print(
                        "[CLEANUP] killed blocked replacement B"
                    )

            except Exception:

                pass


        # ====================================================
        # Abort the controller transaction currently blocked
        # on the authority compare-and-swap.
        # ====================================================

        try:

            terminated = (
                terminate_blocked_authority_transfers()
            )

            print(
                "[CLEANUP] terminated blocked "
                f"takeover transaction(s): {terminated}"
            )

        except Exception as exc:

            if failure is None:
                failure = exc


        # ====================================================
        # Release the PostgreSQL authority-row lock.
        #
        # Killing only the local psql process is insufficient:
        # its PostgreSQL backend may still be inside pg_sleep()
        # and therefore continue holding the row lock.
        #
        # Terminate the server-side backend explicitly.
        # ====================================================

        try:

            terminated_locks = (
                terminate_authority_lock()
            )

            print(
                "[CLEANUP] terminated authority "
                f"lock backend(s): {terminated_locks}"
            )

        except Exception as exc:

            if failure is None:
                failure = exc


        if lock_process is not None:

            try:

                lock_process.wait(
                    timeout=5
                )

            except subprocess.TimeoutExpired:

                try:
                    lock_process.kill()
                    lock_process.wait(
                        timeout=5
                    )

                except Exception:
                    pass


        # ====================================================
        # Verify the aborted handoff rolled back BOTH writes.
        #
        # A remains SIGSTOP'd here intentionally.
        # ====================================================

        try:

            def rollback_visible():

                return (
                    authority_session(
                        worker_id
                    ) == session_a
                    and recovery_count(
                        worker_id,
                        session_a
                    ) == 0
                )


            wait_until(
                rollback_visible,
                "atomic takeover rollback",
                timeout=10,
            )


            print(
                "[VERIFY] authority after abort:",
                authority_session(
                    worker_id
                ),
            )

            print(
                "[VERIFY] recovery rows after abort:",
                recovery_count(
                    worker_id,
                    session_a
                ),
            )


        except Exception as exc:

            if failure is None:
                failure = exc


        # ====================================================
        # A was deliberately paused beyond the health timeout.
        #
        # Do not depend on its old gRPC command stream becoming
        # usable again. Kill it while it is still stopped.
        # ====================================================

        try:

            if process_exists(
                    pid_a):

                os.kill(
                    pid_a,
                    signal.SIGKILL
                )

                print(
                    "[CLEANUP] killed old session A"
                )

        except ProcessLookupError:

            pass


        # ====================================================
        # Start a fresh worker C.
        #
        # Controller state already considers A offline, so C
        # may perform an ordinary durable A -> C takeover.
        # ====================================================

        try:

            cleanup_process = (
                helpers.launch_worker(
                    environment
                )
            )


            worker_c = (
                helpers.wait_for_session(
                    worker_id,
                    lambda worker:
                        worker["sessionId"]
                        != session_a
                        and worker["online"]
                        and worker[
                            "commandStreamConnected"
                        ],
                    timeout=20,
                )
            )


            session_c = (
                worker_c["sessionId"]
            )


            print(
                "[CLEANUP] healthy replacement session:",
                session_c,
            )


            durable_c = (
                authority_session(
                    worker_id
                )
            )


            if durable_c != session_c:

                if failure is None:

                    failure = AssertionError(
                        "Cleanup worker became connected "
                        "without matching durable authority"
                    )


        except Exception as exc:

            if failure is None:
                failure = exc


    if failure is not None:

        raise failure


    print()
    print(
        "WORKER TAKEOVER ATOMICITY PASS"
    )

    print(
        "Blocked takeover remained invisible "
        "and the aborted handoff rolled back "
        "as one transaction."
    )


if __name__ == "__main__":

    try:

        main()

    except Exception as exc:

        print()
        print(
            f"FAIL: {exc}"
        )

        sys.exit(1)
