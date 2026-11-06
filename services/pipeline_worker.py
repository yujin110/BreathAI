"""One worker per job root/GPU. Persistent callbacks are sent once conservatively."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import time
import subprocess
import sys
from urllib.parse import urlparse

import httpx

from services.callback_payload import build_callback
from services.job_store import JobStore
from services.run_job import run_job
from services.job_control import job_lock, process_identity, recover_cancellations, stop_execution


@contextmanager
def worker_lock(root):
    handle = (Path(root) / "worker.lock").open("a+b")
    try:
        if os.name == "nt":
            import msvcrt
            handle.write(b"0")
            handle.flush()
            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally:
        handle.close()


def deliver_progress(store, analysis_id, status, base_url, token, client):
    with job_lock(store.root, analysis_id):
        _deliver_progress(store, analysis_id, status, base_url, token, client)


def _deliver_progress(store, analysis_id, status, base_url, token, client):
    if status not in {"PREPROCESSING", "ANALYZING"} or not store.claim_progress(analysis_id, status):
        return
    delivery = "UNKNOWN"
    try:
        response = client.post(
            f"{base_url.rstrip('/')}/api/v1/dashboard/analyze/callback/{analysis_id}",
            json={"status": status}, headers={"Authorization": f"Bearer {token}"}, timeout=5)
        response.raise_for_status()
        delivery = "DELIVERED"
    except Exception:
        pass  # Ambiguous writes are not retried, just like terminal callbacks.
    finally:
        store.finish_progress(analysis_id, status, delivery)
        print(f"analysis={analysis_id} progress={status} delivery={delivery}", flush=True)


def process_one(store, runner=run_job, on_progress=None):
    row = store.claim()
    if not row:
        return False
    if runner is run_job:
        # The child gates all file writes on its durable PID registration. If this
        # worker crashes during launch, an unregistered child cannot start analysis.
        with job_lock(store.root, row['id']):
            if store.get(row['id'])['state'] != 'RUNNING':
                return True
            if os.name != 'posix':
                raise RuntimeError('GPU job supervision requires a POSIX runtime')
            child = subprocess.Popen(
                [sys.executable, '-u', '-m', 'services.job_process', str(store.root), str(row['id'])],
                cwd=Path(__file__).resolve().parents[1], start_new_session=True)
            try:
                store.set_process(row['id'], child.pid, process_identity(child.pid))
            except BaseException:
                child.kill()
                child.wait()
                raise
        child.wait()
        with job_lock(store.root, row['id']):
            stop_execution(store.get(row['id']), grace=1, deadline=time.monotonic() + 10)
            if store.get(row['id'])['state'] == 'RUNNING':
                store.finish(row['id'], {'status': 'FAILED', 'errorMessage': 'AI analysis process exited unexpectedly'})
        return True
    return process_claimed(store, row, runner, on_progress)


def process_claimed(store, row, runner=run_job, on_progress=None):
    metadata = json.loads(row["metadata"])
    job_dir = store.root / f"analysis-{row['id']}"
    try:
        options = {} if on_progress is None else {"on_progress": lambda status: on_progress(row['id'], status)}
        for key, option in (("minClusterSize", "min_cluster_size"), ("minSamples", "min_samples")):
            if key in metadata:
                options[option] = metadata[key]
        code = runner(row["input_path"], job_dir, masking=metadata["masking"], translation=metadata["translation"],
                      period_start=metadata.get("periodStartDate"), period_end=metadata.get("periodEndDate"), **options)
        if code:
            raise RuntimeError("Pipeline failed; inspect job logs")
        payload = build_callback(job_dir)
    except Exception as exc:
        print(f"analysis={row['id']} failed errorType={type(exc).__name__} logs={job_dir / 'logs/stdout.log'}", flush=True)
        # Avoid disclosing raw input, DB details, or secrets to callbacks.
        payload = {"status": "FAILED", "errorMessage": f"AI job failed ({type(exc).__name__}); inspect AI logs"}
    with job_lock(store.root, row['id']):
        store.finish(row["id"], payload)
    return True


def deliver_one(store, base_url, token, client):
    row = store.pending_callback()
    if not row:
        return False
    with job_lock(store.root, row['id']):
        return _deliver_one(store, row['id'], base_url, token, client)


def _deliver_one(store, analysis_id, base_url, token, client):
    row = store.get(analysis_id)
    if row['state'] not in {'COMPLETED', 'FAILED'} or row['delivery'] != 'PENDING':
        return False
    store.set_delivery(row["id"], "SENDING")
    try:
        response = client.post(
            f"{base_url.rstrip('/')}/api/v1/dashboard/analyze/callback/{row['id']}",
            json=json.loads(row["payload"]), headers={"Authorization": f"Bearer {token}"})
        response.raise_for_status()
    except Exception:
        # BE may have committed before a timeout/error. No automatic duplicate writes.
        store.set_delivery(row["id"], "UNKNOWN")
    else:
        store.set_delivery(row["id"], "DELIVERED")
    return True


def main():
    standalone = os.environ.get('TTOBAGI_STANDALONE') == 'true'
    base = os.environ.get("TTOBAGI_BE_BASE_URL", '')
    token = os.environ.get("TTOBAGI_CALLBACK_TOKEN", '')
    if not standalone and (urlparse(base).scheme not in {"http", "https"} or not urlparse(base).netloc or not token):
        raise ValueError("Configure a valid BE URL and callback token")
    store = JobStore(os.environ.get("TTOBAGI_JOB_ROOT", "/data/jobs"))
    with worker_lock(store.root), httpx.Client(timeout=30, follow_redirects=False) as client:
        store.recover_callbacks()
        while True:
            recover_cancellations(store)
            delivered = False if standalone else deliver_one(store, base, token, client)
            progress = None if standalone else lambda job_id, status: deliver_progress(store, job_id, status, base, token, client)
            worked = process_one(store, on_progress=progress)
            if not delivered and not worked:
                time.sleep(1)


if __name__ == "__main__":
    main()
