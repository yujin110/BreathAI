"""Cross-process job control. Locks are outside disposable analysis directories."""
from contextlib import contextmanager
import os
from pathlib import Path
import shutil
import time
import math

import psutil


@contextmanager
def job_lock(root, analysis_id, timeout=30):
    directory = Path(root) / 'control'
    directory.mkdir(exist_ok=True)
    with (directory / f'{analysis_id}.lock').open('a+b') as handle:
        handle.write(b'0')
        handle.flush()
        deadline = time.monotonic() + timeout
        while True:
            try:
                if os.name == 'nt':
                    import msvcrt
                    handle.seek(0)
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except (BlockingIOError, OSError):
                if time.monotonic() >= deadline:
                    raise TimeoutError('Job control is busy')
                time.sleep(.05)
        try:
            yield
        finally:
            if os.name == 'nt':
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def process_identity(pid):
    return psutil.Process(pid).create_time()


def process_namespace():
    return os.readlink('/proc/self/ns/pid') if os.name == 'posix' else 'windows'


def execution_processes(row):
    """Linux sessions include orphaned grandchildren after the leader has exited."""
    pid, started = row['process_pid'], row['process_started']
    if not pid:
        return []
    if row['process_namespace'] != process_namespace():
        raise RuntimeError('Job belongs to a different PID namespace; refusing to signal or clean up')
    try:
        leader = psutil.Process(pid)
        if leader.create_time() != started:
            raise RuntimeError('Process identity changed; refusing to signal')
    except psutil.NoSuchProcess:
        leader = None
    if os.name != 'posix':
        return ([] if leader is None else leader.children(recursive=True) + [leader])
    found = []
    for process in psutil.process_iter(['pid', 'create_time', 'status']):
        try:
            if (os.getsid(process.pid) == pid and process.create_time() >= started
                    and process.status() != psutil.STATUS_ZOMBIE):
                found.append(process)
        except (ProcessLookupError, psutil.NoSuchProcess):
            continue
    return found


def stop_execution(row, grace, deadline):
    processes = execution_processes(row)
    if not processes:
        return
    # Signal the complete isolated session, including descendants whose parent exited.
    for process in processes:
        try:
            process.terminate()
        except psutil.NoSuchProcess:
            pass
    soft_deadline = min(deadline, time.monotonic() + grace)
    while execution_processes(row) and time.monotonic() < soft_deadline:
        time.sleep(.05)
    for process in execution_processes(row):
        try:
            process.kill()
        except psutil.NoSuchProcess:
            pass
    while execution_processes(row):
        if time.monotonic() >= deadline:
            raise TimeoutError('Analysis processes have not exited')
        time.sleep(.05)


def cleanup_job(store, row):
    uploads = store.root / 'uploads'
    source = Path(row['input_path'])
    # Do not follow a symlink into a shared resource, including parent symlinks.
    if source.is_symlink() or source.resolve().parent != uploads.resolve() or uploads.is_symlink():
        raise ValueError('Input path is outside the dedicated upload directory')
    directory = store.root / f"analysis-{row['id']}"
    if directory.is_symlink() or directory.resolve().parent != store.root:
        raise ValueError('Unsafe analysis directory')
    source.unlink(missing_ok=True)
    if directory.exists():
        shutil.rmtree(directory)


def cancel_job(store, analysis_id, timeout=30, grace=10, recover=False):
    if not math.isfinite(timeout) or not math.isfinite(grace) or timeout <= 0 or grace < 0:
        raise ValueError('Cancellation timeout must be positive and grace nonnegative')
    deadline = time.monotonic() + timeout
    with job_lock(store.root, analysis_id, timeout):
        row = store.get(analysis_id)
        if row is None:
            raise KeyError(analysis_id)
        if row['state'] == 'CANCELED' and recover:
            return
        if row['state'] in {'COMPLETED', 'FAILED', 'CANCELED'}:
            from services.job_store import Conflict
            raise Conflict('Job already finished')
        if row['state'] == 'RUNNING' and not row['execution_managed']:
            raise RuntimeError('Legacy running job needs operator reconciliation before cancellation')
        store.request_cancel(analysis_id)
        row = store.get(analysis_id)
        try:
            stop_execution(row, grace, deadline)
            if time.monotonic() >= deadline:
                raise TimeoutError('Cancellation deadline exceeded')
            cleanup_job(store, row)
            if time.monotonic() >= deadline:
                raise TimeoutError('Cancellation cleanup deadline exceeded')
            store.complete_cancel(analysis_id)
        except Exception as exc:
            store.cancel_error(analysis_id, type(exc).__name__)
            raise


def recover_cancellations(store):
    for analysis_id in store.cancelling():
        try:
            cancel_job(store, analysis_id, recover=True,
                       timeout=float(os.environ.get('TTOBAGI_CANCEL_TIMEOUT', '30')),
                       grace=float(os.environ.get('TTOBAGI_CANCEL_GRACE', '10')))
        except Exception as exc:
            print(f'analysis={analysis_id} cancellation recovery={type(exc).__name__}', flush=True)
