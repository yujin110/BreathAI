"""One isolated POSIX session owns preparation, inference, and result building."""
import os
import sys

import httpx

from services.job_control import job_lock, process_identity
from services.job_store import JobStore
from services.pipeline_worker import process_claimed, deliver_progress


def main():
    store = JobStore(sys.argv[1])
    analysis_id = int(sys.argv[2])
    with job_lock(store.root, analysis_id):
        row = store.get(analysis_id)
        if (row is None or row['state'] != 'RUNNING' or row['process_pid'] != os.getpid()
                or row['process_started'] != process_identity(os.getpid())):
            return  # Parent died before registering, or cancellation won launch.
    standalone = os.environ.get('TTOBAGI_STANDALONE') == 'true'
    with httpx.Client(timeout=5, follow_redirects=False) as client:
        progress = None if standalone else lambda job, status: deliver_progress(
            store, job, status, os.environ['TTOBAGI_BE_BASE_URL'], os.environ['TTOBAGI_CALLBACK_TOKEN'], client)
        process_claimed(store, row, on_progress=progress)


if __name__ == '__main__':
    main()
