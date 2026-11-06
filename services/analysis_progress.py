"""Atomic child progress events and per-stage resource measurements (no GPU imports)."""
from contextlib import contextmanager, nullcontext
import os
import json
from pathlib import Path
import sys
import time
import threading

from services.analysis_selection import write_json

PHASES = ('QUEUED', 'CLASSIFYING', 'PREPROCESSING', 'EMBEDDING', 'DIMENSION_REDUCTION',
          'CLUSTERING', 'FAQ_GENERATION', 'RETRIEVING', 'ANSWER_PIPELINE', 'FINALIZING')
_active = None


def release():
    global _active
    _active = None


def measure(name):
    return _active.stage(name) if _active is not None else nullcontext()


def count(name, amount=1):
    if _active is not None:
        counters = _active.value['counters']
        counters[name] = counters.get(name, 0) + amount


def report(phase, completed=None, total=None, unit=None):
    if phase not in PHASES:
        raise ValueError('Unknown analysis phase')
    if (completed is None) != (total is None) or (total is not None and not 0 <= completed <= total):
        raise ValueError('Invalid progress counters')
    path = os.environ.get('TTOBAGI_PROGRESS_PATH')
    if path:
        value = dict(phase=phase, completedUnits=completed, totalUnits=total, unit=unit)
        write_json(Path(path), value)
        print('TTOBAGI_DETAIL:' + json.dumps(value), flush=True)


class Performance:
    def __init__(self, path):
        global _active
        self.path = Path(path)
        self.started = time.monotonic()
        self.value = {'schemaVersion': 1, 'stages': [], 'skippedStages': [], 'counters': {}}
        _active = self

    def save(self):
        self.value['elapsedSeconds'] = round(time.monotonic() - self.started, 6)
        for key in ('cpuPeakRssBytes', 'gpuPeakAllocatedBytes', 'gpuPeakReservedBytes'):
            values = [stage[key] for stage in self.value['stages'] if stage.get(key) is not None]
            self.value[key] = max(values) if values else None
        write_json(self.path, self.value)

    @contextmanager
    def stage(self, name):
        started = time.monotonic()
        torch = sys.modules.get('torch')
        if torch is not None and torch.cuda.is_available():
            torch.cuda.reset_peak_memory_stats()
        record = {'name': name, 'status': 'FAILED'}
        stopped = threading.Event()
        try:
            import psutil
            process = psutil.Process()
        except ImportError:
            process = None
        peak_rss = [0]
        def sample():
            while not stopped.is_set():
                if process:
                    try:
                        peak_rss[0] = max(peak_rss[0], process.memory_info().rss)
                    except psutil.Error:
                        pass
                stopped.wait(.2)
        sampler = threading.Thread(target=sample, daemon=True)
        sampler.start()
        self.value['stages'].append(record)
        self.save()
        try:
            yield
            record['status'] = 'COMPLETED'
        finally:
            stopped.set()
            sampler.join(timeout=1)
            record['elapsedSeconds'] = round(time.monotonic() - started, 6)
            try:
                import psutil
                info = psutil.Process().memory_info()
                record['cpuRssBytes'] = info.rss
                record['cpuPeakRssBytes'] = max(peak_rss[0], info.rss)
                record['cpuPeakSamplingIntervalSeconds'] = .2
            except ImportError:
                record['cpuRssBytes'] = record['cpuPeakRssBytes'] = None
            torch = sys.modules.get('torch')
            gpu = torch is not None and torch.cuda.is_available()
            record['gpuPeakAllocatedBytes'] = torch.cuda.max_memory_allocated() if gpu else None
            record['gpuPeakReservedBytes'] = torch.cuda.max_memory_reserved() if gpu else None
            self.save()

    def skip(self, names, reason):
        self.value['skippedStages'].extend({'name': name, 'reason': reason} for name in names)
        self.save()
