"""Cancellation contract, recovery, and real isolated process trees (no GPU)."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import httpx
from fastapi.testclient import TestClient

from services.job_control import cancel_job, job_lock, process_identity, recover_cancellations
from services.job_store import Conflict, JobStore
from services.pipeline_api import create_app
from services.pipeline_worker import deliver_one, deliver_progress, process_one


class CancellationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.app = create_app(root=self.root, token='secret')
        self.store = self.app.state.store
        self.client = TestClient(self.app)
        self.headers = {'Authorization': 'Bearer secret'}

    def submit(self, job=1):
        source = self.root / 'uploads' / f'{job}.csv'
        source.write_text('input', encoding='utf-8')
        self.store.submit(job, str(job), {'masking': True, 'translation': False}, source)
        return source

    def cancel(self, job=1):
        return self.client.post(f'/jobs/{job}/cancel', headers=self.headers)

    def test_api_contract_and_queued_job_never_runs(self):
        source = self.submit()
        other = self.submit(2)
        directory = self.root / 'analysis-1'
        directory.mkdir()
        (directory / 'partial').write_text('partial')
        self.assertEqual(self.client.post('/jobs/1/cancel').status_code, 401)
        self.assertEqual(self.cancel(0).status_code, 422)
        self.assertEqual(self.cancel(999).status_code, 404)
        response = self.cancel()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {'analysisId': 1, 'status': 'CANCELED'})
        self.assertFalse(source.exists())
        self.assertFalse(directory.exists())
        self.assertTrue(other.exists())
        self.assertEqual(self.cancel().status_code, 409)
        self.assertEqual(self.store.claim()['id'], 2)
        row = self.store.get(1)
        self.assertIsNotNone(row['cancel_requested'])
        self.assertIsNotNone(row['cancel_completed'])
        self.assertEqual(row['delivery'], 'SUPPRESSED')

    def test_cancel_wins_finish_and_suppresses_all_callbacks(self):
        self.submit()
        self.store.claim()
        self.assertEqual(self.cancel().status_code, 200)
        self.store.finish(1, {'status': 'FAILED'})
        with httpx.Client(transport=httpx.MockTransport(lambda request: self.fail('callback sent'))) as client:
            deliver_progress(self.store, 1, 'ANALYZING', 'https://be.example', 'token', client)
            self.assertFalse(deliver_one(self.store, 'https://be.example', 'token', client))
        self.assertEqual(self.store.get(1)['state'], 'CANCELED')
        self.assertIsNone(self.store.get(1)['payload'])

    def test_completion_wins_even_with_pending_callback(self):
        self.submit()
        self.store.claim()
        self.store.finish(1, {'status': 'COMPLETED'})
        self.assertEqual(self.cancel().status_code, 409)
        self.assertTrue((self.root / 'uploads/1.csv').exists())

    def test_cleanup_failure_and_restart_recovery(self):
        source = self.submit()
        with patch('services.job_control.cleanup_job', side_effect=PermissionError):
            self.assertEqual(self.cancel().status_code, 503)
        self.assertEqual(self.store.get(1)['state'], 'CANCELLING')
        self.assertEqual(self.store.get(1)['cleanup_error'], 'PermissionError')
        restarted = JobStore(self.root)
        recover_cancellations(restarted)
        self.assertEqual(restarted.get(1)['state'], 'CANCELED')
        self.assertFalse(source.exists())

    def test_unsafe_input_path_is_preserved(self):
        shared = self.root / 'shared.csv'
        shared.write_text('shared')
        self.store.submit(1, '1', {}, shared)
        self.assertEqual(self.cancel().status_code, 503)
        self.assertTrue(shared.exists())

    def test_process_identity_mismatch_never_signals_an_unrelated_process(self):
        self.submit()
        self.store.claim()
        self.store.set_process(1, os.getpid(), process_identity(os.getpid()) - 1)
        self.assertEqual(self.cancel().status_code, 503)
        self.assertTrue((self.root / 'uploads/1.csv').exists())

    def test_foreign_pid_namespace_never_signals_or_deletes_inputs(self):
        self.submit()
        self.store.claim()
        self.store.set_process(1, os.getpid(), process_identity(os.getpid()))
        with self.store.connect() as db:
            db.execute("UPDATE jobs SET process_namespace='another-container' WHERE id=1")
        with patch('services.job_control.psutil.Process') as process:
            self.assertEqual(self.cancel().status_code, 503)
            process.assert_not_called()
        self.assertTrue((self.root / 'uploads/1.csv').exists())

    def test_legacy_running_job_without_supervised_process_is_not_deleted(self):
        self.submit()
        with self.store.connect() as db:
            db.execute("UPDATE jobs SET state='RUNNING' WHERE id=1")
        self.assertEqual(self.cancel().status_code, 503)
        self.assertTrue((self.root / 'uploads/1.csv').exists())

    def test_progress_send_and_cancel_are_serialized(self):
        self.submit()
        self.store.claim()
        sending, release = threading.Event(), threading.Event()
        calls = []

        def backend(request):
            calls.append(self.store.get(1)['state'])
            sending.set()
            release.wait(5)
            return httpx.Response(200)

        with httpx.Client(transport=httpx.MockTransport(backend)) as client:
            sender = threading.Thread(target=deliver_progress, args=(self.store, 1, 'ANALYZING', 'https://be', 'x', client))
            sender.start()
            self.assertTrue(sending.wait(5))
            result = []
            canceller = threading.Thread(target=lambda: result.append(self.cancel().status_code))
            canceller.start()
            time.sleep(.1)
            self.assertEqual(self.store.get(1)['state'], 'RUNNING')
            release.set()
            sender.join(5)
            canceller.join(5)
        self.assertEqual(calls, ['RUNNING'])
        self.assertEqual(result, [200])

    @unittest.skipUnless(os.name == 'posix', 'Production process supervision uses POSIX sessions')
    def test_running_cancellation_kills_stubborn_grandchild_and_next_job_runs(self):
        self.submit()
        row = self.store.claim()
        directory = self.root / 'analysis-1'
        directory.mkdir()
        marker = directory / 'ready'
        script = ("import subprocess,sys,time,signal; from pathlib import Path; "
                  "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
                  "p=subprocess.Popen([sys.executable,'-c',"
                  "'import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(60)']); "
                  f"Path({str(marker)!r}).write_text(str(p.pid)); time.sleep(60)")
        with job_lock(self.root, 1):
            child = subprocess.Popen([sys.executable, '-c', script], start_new_session=True)
            self.store.set_process(1, child.pid, process_identity(child.pid))
        try:
            deadline = time.monotonic() + 5
            while not marker.exists() and time.monotonic() < deadline:
                time.sleep(.02)
            self.assertTrue(marker.exists())
            with patch.dict(os.environ, {'TTOBAGI_CANCEL_GRACE': '.1'}):
                self.assertEqual(self.cancel().status_code, 200)
            self.assertIsNotNone(child.poll())
            self.assertFalse(directory.exists())
            self.submit(2)
            process_one(self.store, runner=lambda source, job, **options: 1)
            self.assertEqual(self.store.get(2)['state'], 'FAILED')
        finally:
            if child.poll() is None:
                import signal
                os.killpg(child.pid, signal.SIGKILL)
            child.wait()

    @unittest.skipUnless(os.name == 'posix', 'Production process supervision uses POSIX sessions')
    def test_registered_job_process_reports_input_failure_without_gpu_imports(self):
        self.submit()
        with patch.dict(os.environ, {'TTOBAGI_STANDALONE': 'true'}):
            self.assertTrue(process_one(self.store))
        self.assertEqual(self.store.get(1)['state'], 'FAILED')
        self.assertEqual(self.store.get(1)['delivery'], 'PENDING')

    @unittest.skipUnless(os.name == 'posix', 'Production process supervision uses POSIX sessions')
    def test_orphaned_descendant_is_found_after_leader_exits(self):
        self.submit()
        self.store.claim()
        script = "import subprocess,sys,time; subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)']); time.sleep(.2)"
        child = subprocess.Popen([sys.executable, '-c', script], start_new_session=True)
        self.store.set_process(1, child.pid, process_identity(child.pid))
        child.wait(timeout=5)
        self.assertEqual(self.cancel().status_code, 200)

    @unittest.skipUnless(os.name == 'posix', 'Production process supervision uses POSIX sessions')
    def test_supervisor_stops_registered_session_even_if_worker_already_died(self):
        from services.container_entrypoint import stop_job_sessions
        from services.job_control import execution_processes
        self.submit()
        self.store.claim()
        script = "import subprocess,sys,time; subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)']); time.sleep(.2)"
        child = subprocess.Popen([sys.executable, '-c', script], start_new_session=True)
        self.store.set_process(1, child.pid, process_identity(child.pid))
        child.wait(timeout=5)
        with patch.dict(os.environ, {'TTOBAGI_JOB_ROOT': str(self.root)}):
            stop_job_sessions()
        self.assertEqual(execution_processes(self.store.get(1)), [])

    @unittest.skipUnless(os.name == 'posix', 'Production process supervision uses POSIX sessions')
    def test_unregistered_job_process_cannot_write_analysis_files(self):
        self.submit()
        self.store.claim()
        child = subprocess.run([sys.executable, '-m', 'services.job_process', str(self.root), '1'],
                               timeout=10, check=True)
        self.assertFalse((self.root / 'analysis-1').exists())


if __name__ == '__main__':
    unittest.main()
