"""Service lifecycle checks without Docker or spawning GPU processes."""
import os
import subprocess
import unittest
from unittest.mock import Mock, patch

from services.container_entrypoint import stop_children, supervise


class ContainerEntrypointTest(unittest.TestCase):
    def test_missing_configuration_does_not_start_services(self):
        with patch.dict(os.environ, {}, clear=True), patch('services.container_entrypoint.subprocess.Popen') as spawn:
            with self.assertRaises(RuntimeError):
                supervise('all')
            spawn.assert_not_called()

    def test_worker_exit_stops_api_and_returns_failure(self):
        api, worker = Mock(), Mock()
        api.poll.return_value = None
        worker.poll.return_value = 3
        env = {'TTOBAGI_AI_API_TOKEN': 'api', 'TTOBAGI_CALLBACK_TOKEN': 'callback',
               'TTOBAGI_BE_BASE_URL': 'https://be.example'}
        with patch.dict(os.environ, env), patch('services.container_entrypoint.subprocess.Popen', side_effect=[api, worker]), \
                patch('services.container_entrypoint.stop_children') as cleanup:
            self.assertEqual(supervise('all'), 3)
            cleanup.assert_called_once_with([api, worker])

    def test_cleanup_signals_whole_worker_process_group_on_linux(self):
        child = Mock(pid=12345)
        child.wait.side_effect = [subprocess.TimeoutExpired('worker', 0), 0]
        with patch('services.container_entrypoint.os.name', 'posix'), \
                patch('services.container_entrypoint.psutil.Process') as process, \
                patch('services.container_entrypoint.os.killpg', create=True) as kill, \
                patch('services.container_entrypoint.signal.SIGKILL', 9, create=True):
            process.return_value.children.return_value = []
            stop_children([child], grace=0)
        self.assertEqual(kill.call_count, 2)  # TERM then KILL, covering GPU descendants.
        child.kill.assert_not_called()


if __name__ == '__main__':
    unittest.main()
