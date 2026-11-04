"""Container process supervision without shell scripts or nested Docker."""
import argparse
import os
import signal
import subprocess
import sys
import time
import psutil
from pathlib import Path


def commands(mode):
    api = [sys.executable, "-m", "uvicorn", "services.pipeline_api:create_app", "--factory",
           "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
    worker = [sys.executable, "-m", "services.pipeline_worker"]
    return [api] if mode == "api" else [worker] if mode == "worker" else [api, worker]


def stop_children(children, grace=30):
    # Jobs now own separate sessions. Capture descendants before stopping their
    # worker parent so those sessions do not become untracked GPU orphans.
    descendants = []
    for child in children:
        try:
            descendants.extend(psutil.Process(child.pid).children(recursive=True))
        except psutil.NoSuchProcess:
            pass
    for process in descendants:
        try:
            process.terminate()
        except psutil.NoSuchProcess:
            pass
    # Signal the worker's process group too: its GPU subprocess must not survive.
    for child in children:
        if os.name == "posix":
            try:
                os.killpg(child.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        elif child.poll() is None:
            child.terminate()
    deadline = time.monotonic() + grace
    for child in children:
        try:
            child.wait(timeout=max(0, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            if os.name == "posix":
                try:
                    os.killpg(child.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            else:
                child.kill()
            child.wait()
    _, alive = psutil.wait_procs(descendants, timeout=max(0, deadline - time.monotonic()))
    for process in alive:
        try:
            process.kill()
        except psutil.NoSuchProcess:
            pass
    psutil.wait_procs(alive, timeout=5)


def stop_job_sessions():
    """The worker may have died before its children could be enumerated."""
    from services.job_store import JobStore
    from services.job_control import job_lock, stop_execution
    root = Path(os.environ.get('TTOBAGI_JOB_ROOT', '/data/jobs'))
    if not (root / 'jobs.sqlite3').is_file():
        return
    store = JobStore(root)
    with store.connect() as db:
        ids = [row[0] for row in db.execute(
            "SELECT id FROM jobs WHERE state IN ('RUNNING','CANCELLING') AND process_pid IS NOT NULL")]
    for analysis_id in ids:
        try:
            with job_lock(root, analysis_id):
                stop_execution(store.get(analysis_id), grace=1, deadline=time.monotonic() + 5)
        except Exception as exc:
            print(f'analysis={analysis_id} shutdown errorType={type(exc).__name__}', flush=True)


def supervise(mode):
    required = (["TTOBAGI_AI_API_TOKEN"] if mode == "api" else
                ["TTOBAGI_BE_BASE_URL", "TTOBAGI_CALLBACK_TOKEN"] if mode == "worker" else
                ["TTOBAGI_AI_API_TOKEN", "TTOBAGI_BE_BASE_URL", "TTOBAGI_CALLBACK_TOKEN"])
    if any(not os.getenv(name) for name in required):
        raise RuntimeError("Missing required service environment: " + ", ".join(required))
    children = []
    stopping = False

    def request_stop(signum, frame):
        nonlocal stopping
        stopping = True

    previous = {sig: signal.signal(sig, request_stop) for sig in (signal.SIGTERM, signal.SIGINT)}
    try:
        for command in commands(mode):
            children.append(subprocess.Popen(command, start_new_session=(os.name == "posix")))
        while not stopping:
            for child in children:
                code = child.poll()
                if code is not None:
                    # A long-lived service exiting cleanly is still unexpected.
                    return code or 1
            time.sleep(0.2)
        return 0
    finally:
        stop_children(children)
        if mode != 'api':
            stop_job_sessions()
        for sig, handler in previous.items():
            signal.signal(sig, handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("api", "worker", "all"), nargs="?", default="all")
    args = parser.parse_args()
    raise SystemExit(supervise(args.mode))


if __name__ == "__main__":
    main()
