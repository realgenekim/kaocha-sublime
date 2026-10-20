"""Tests for bin/sublime-loop. No JVM: liveness is decided by injectable probes.

Run: python3 -m unittest discover -s test -p 'test_*.py' -v
"""
import importlib.machinery
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from contextlib import redirect_stdout

SCRIPT = Path(__file__).resolve().parent.parent / 'bin' / 'sublime-loop'


def load_cli():
    loader = importlib.machinery.SourceFileLoader('sublime_loop', str(SCRIPT))
    spec = importlib.util.spec_from_loader('sublime_loop', loader)
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


cli = load_cli()

GEN = '11111111-2222-3333-4444-555555555555'


def record(root, pid, port=45678, generation=GEN):
    return {'root': str(root), 'pid': pid, 'generation': generation,
            'role': 'test-only', 'port': port}


def marker_command(root, generation=GEN):
    return ('/usr/bin/java -Xmx512m -Ddev.loop.generation=' + generation +
            ' -Ddev.loop.root=' + str(root) + ' -classpath x clojure.main -m kaocha-sublime.runner')


def fake_probe(exists=True, zombie=False, command=''):
    return lambda pid: {'exists': exists, 'zombie': zombie, 'command': command}


def dead_pid():
    process = subprocess.Popen(['true'])
    process.wait()
    return process.pid


class Workspace(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name).resolve()
        (self.root / 'deps.edn').write_text('{}')
        self.run_dir = self.root / '.dev-loop'
        self.run_dir.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def write_session(self, session):
        (self.run_dir / 'session.json').write_text(json.dumps(session))

    def invoke(self, *args):
        out = io.StringIO()
        with redirect_stdout(out):
            code = cli.main(['--root', str(self.root), *args])
        return json.loads(out.getvalue().strip().splitlines()[-1]), code

    def archives(self):
        return sorted(self.run_dir.glob('session.stale-*.json'))


class ClassifyTest(Workspace):
    def test_dead_pid_is_stale(self):
        verdict = cli.classify(record(self.root, 4242), self.root,
                               probe=fake_probe(exists=False), listeners=lambda port: set())
        self.assertEqual('stale', verdict['state'])
        self.assertFalse(verdict['owned'])

    def test_zombie_is_stale(self):
        verdict = cli.classify(record(self.root, 4242), self.root,
                               probe=fake_probe(zombie=True, command=marker_command(self.root)),
                               listeners=lambda port: {4242})
        self.assertEqual('stale', verdict['state'])

    def test_unrelated_process_is_stale_and_not_owned(self):
        verdict = cli.classify(record(self.root, 4242), self.root,
                               probe=fake_probe(command='sleep 60'), listeners=lambda port: set())
        self.assertEqual('stale', verdict['state'])
        self.assertFalse(verdict['owned'])

    def test_port_not_owned_by_pid_is_stale(self):
        verdict = cli.classify(record(self.root, 4242), self.root,
                               probe=fake_probe(command=marker_command(self.root)),
                               listeners=lambda port: {9999})
        self.assertEqual('stale', verdict['state'])
        self.assertEqual('port-not-owned', verdict['reason'])

    def test_nobody_on_port_is_stale(self):
        verdict = cli.classify(record(self.root, 4242), self.root,
                               probe=fake_probe(command=marker_command(self.root)),
                               listeners=lambda port: set())
        self.assertEqual('stale', verdict['state'])

    def test_live_owned_process_is_live(self):
        verdict = cli.classify(record(self.root, 4242), self.root,
                               probe=fake_probe(command=marker_command(self.root)),
                               listeners=lambda port: {4242})
        self.assertEqual('live', verdict['state'])
        self.assertTrue(verdict['owned'])

    def test_other_generation_java_on_port_is_not_owned(self):
        verdict = cli.classify(record(self.root, 4242), self.root,
                               probe=fake_probe(command=marker_command(self.root, 'other-gen')),
                               listeners=lambda port: {4242})
        self.assertEqual('stale', verdict['state'])
        self.assertFalse(verdict['owned'])

    def test_record_without_pid_or_port_is_stale(self):
        verdict = cli.classify({'generation': GEN}, self.root,
                               probe=fake_probe(command=marker_command(self.root)),
                               listeners=lambda port: {4242})
        self.assertEqual('stale', verdict['state'])


class ArchiveTest(Workspace):
    def test_archive_never_overwrites(self):
        session_path = self.run_dir / 'session.json'
        clock = lambda: '20261009T000000Z'
        existing = self.run_dir / 'session.stale-77-20261009T000000Z.json'
        existing.write_text('original')
        paths = []
        for i in range(2):
            session_path.write_text(json.dumps({'pid': 77, 'n': i}))
            paths.append(cli.archive_session(self.run_dir, session_path, 77, clock=clock))
        self.assertEqual('original', existing.read_text())
        self.assertEqual(3, len({existing, *paths}))
        self.assertEqual([0, 1], [json.loads(p.read_text())['n'] for p in paths])
        self.assertFalse(session_path.exists())


class TransportTest(Workspace):
    def test_mismatched_request_id_is_refused(self):
        path = self.run_dir / 'transport-x.json'
        path.write_text(json.dumps({'request-id': 'someone-else', 'value': {'status': 'pass'}}))
        with self.assertRaisesRegex(RuntimeError, 'mismatch'):
            cli.read_transport(path, 'mine')

    def test_matching_request_id_returns_value(self):
        path = self.run_dir / 'transport-y.json'
        path.write_text(json.dumps({'request-id': 'mine', 'value': {'status': 'pass'}}))
        self.assertEqual({'status': 'pass'}, cli.read_transport(path, 'mine'))


class StaleRecoveryTest(Workspace):
    """End-to-end through main() with real ps/lsof probes; never needs nREPL."""

    def test_dead_pid_status_archives_and_reports_stopped(self):
        pid = dead_pid()
        self.write_session(record(self.root, pid))
        receipt, code = self.invoke('status')
        self.assertEqual(0, code)
        self.assertEqual('stopped', receipt['status'])
        self.assertEqual('dev-loop.v1', receipt['schema'])
        self.assertEqual(1, len(self.archives()))
        self.assertEqual(str(self.archives()[0]), receipt['stale_record'])
        self.assertFalse((self.run_dir / 'session.json').exists())
        self.assertTrue(Path(receipt['wrapper_receipt']).exists())
        self.assertTrue((self.run_dir / 'last-result.json').exists())

    def test_reused_pid_is_archived_and_never_killed_by_down(self):
        sleeper = subprocess.Popen(['sleep', '60'])
        try:
            self.write_session(record(self.root, sleeper.pid))
            receipt, code = self.invoke('down')
            self.assertEqual(0, code)
            self.assertEqual('stopped', receipt['status'])
            self.assertEqual(str(self.archives()[0]), receipt['stale_record'])
            time.sleep(0.2)
            self.assertIsNone(sleeper.poll(), 'unrelated process must survive down')
        finally:
            sleeper.kill()
            sleeper.wait()

    def test_check_on_stale_record_fails_and_says_run_up(self):
        self.write_session(record(self.root, dead_pid()))
        receipt, code = self.invoke('check', 'sample.core-test')
        self.assertNotEqual(0, code)
        self.assertEqual('unknown', receipt['status'])
        self.assertIn('up', receipt['error'])
        self.assertEqual(1, len(self.archives()))

    def test_no_session_status_is_an_error(self):
        receipt, code = self.invoke('status')
        self.assertEqual(2, code)
        self.assertEqual('unknown', receipt['status'])


class FingerprintTest(Workspace):
    def test_environment_includes_library_sources_from_script_location(self):
        library = cli.LIBRARY
        self.assertEqual(SCRIPT.parent.parent, library)
        before = cli.environment_fingerprint(self.root)
        (self.root / 'tests.edn').write_text('{}')
        self.assertNotEqual(before, cli.environment_fingerprint(self.root))


class LaunchCommandTest(Workspace):
    def test_library_alias_is_injected_last_with_absolute_library_path(self):
        command = cli.runner_command(self.root, ':test:dev', GEN)
        self.assertEqual('-M:test:dev' + cli.LIBRARY_ALIAS, command[-1])
        sdeps = command[command.index('-Sdeps') + 1]
        self.assertIn('{:local/root ' + json.dumps(str(cli.LIBRARY)) + '}', sdeps)
        self.assertTrue(cli.LIBRARY.is_absolute())
        self.assertIn('"-m" "kaocha-sublime.runner"', sdeps)
        for name, _ in cli.RUNNER_DEPS:
            self.assertIn(name, sdeps)
        self.assertIn('-J' + cli.GENERATION_PROP + GEN, command)

    def test_default_aliases_add_optional_repo_sublime_alias(self):
        self.assertEqual(':test', cli.default_aliases(self.root))
        (self.root / 'deps.edn').write_text('{:aliases\n {:test {}\n  :sublime {:extra-paths ["dev"]}}}')
        self.assertEqual(':test:sublime', cli.default_aliases(self.root))

    def test_malformed_aliases_are_refused(self):
        with self.assertRaisesRegex(RuntimeError, '--aliases'):
            cli.main(['--root', str(self.root), '--aliases', 'test', 'status'])


def git_repo_with_project(base, name='proj'):
    """A git repository at `base` holding the Clojure project `<base>/<name>`."""
    subprocess.run(['git', 'init', '-q', str(base)], check=True)
    project = base / name
    project.mkdir()
    (project / 'deps.edn').write_text('{}')
    return project


class RootResolutionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name).resolve()
        self.project = git_repo_with_project(self.base)

    def tearDown(self):
        self.tmp.cleanup()

    def test_relative_root_resolves_from_the_checkout_top(self):
        self.assertEqual(self.project, cli.resolve_root('proj', self.base))

    def test_relative_root_resolves_from_inside_the_project_itself(self):
        # 2026-10-20: `--root reddit-scraper` run from inside reddit-scraper/ resolved
        # reddit-scraper/reddit-scraper and failed.
        self.assertEqual(self.project, cli.resolve_root('proj', self.project))

    def test_relative_root_resolves_from_a_sibling_directory(self):
        other = self.base / 'other'
        other.mkdir()
        self.assertEqual(self.project, cli.resolve_root('proj', other))

    def test_dot_and_absolute_roots_are_taken_as_given(self):
        self.assertEqual(self.project, cli.resolve_root('.', self.project))
        self.assertEqual(self.project, cli.resolve_root(str(self.project), self.base))

    def test_unknown_root_names_every_path_tried(self):
        with self.assertRaisesRegex(RuntimeError, 'not a Clojure project root') as caught:
            cli.resolve_root('nope', self.project)
        self.assertIn(str(self.project / 'nope'), str(caught.exception))
        self.assertIn(str(self.base / 'nope'), str(caught.exception))

    def test_outside_git_only_the_working_directory_counts(self):
        with tempfile.TemporaryDirectory() as plain:
            plain = Path(plain).resolve()
            (plain / 'deps.edn').write_text('{}')
            self.assertEqual(plain, cli.resolve_root('.', plain))
            with self.assertRaisesRegex(RuntimeError, 'not a Clojure project root'):
                cli.resolve_root('proj', plain)


class FailureSummaryTest(Workspace):
    def events(self, *lines):
        path = self.run_dir / 'run.events'
        path.write_text('\n'.join(lines) + '\n')
        return path

    def event(self, line_kind, **fields):
        return line_kind + ' ' + json.dumps(fields)

    def test_first_failures_are_lifted_with_their_location_and_values(self):
        path = self.events(
            self.event('RUN-START', v=1),
            self.event('FAIL', kind='fail', test='a.core-test/adds', file='test/a/core_test.clj', line=12,
                       expr='(= 3 (add 1 1))', expected='(= 3 (add 1 1))', actual='(not (= 3 2))',
                       context='small numbers'),
            self.event('FAIL', kind='error', test='a.core-test/reads', file='test/a/core_test.clj', line=20,
                       expr='(read! x)', actual='java.lang.NullPointerException: x is null', context=''),
            self.event('RUN-DONE', fail=1, error=1))
        failures = cli.failure_summary(path)
        self.assertEqual(2, len(failures))
        self.assertEqual({'kind': 'fail', 'test': 'a.core-test/adds', 'at': 'test/a/core_test.clj:12',
                          'expr': '(= 3 (add 1 1))', 'expected': '(= 3 (add 1 1))',
                          'actual': '(not (= 3 2))', 'context': 'small numbers'}, failures[0])
        self.assertEqual('a.core-test/reads', failures[1]['test'])
        self.assertNotIn('context', failures[1], 'blank fields are left out')

    def test_the_summary_is_bounded(self):
        lines = [self.event('FAIL', kind='fail', test='t/n' + str(i), actual='x' * 5000) for i in range(9)]
        failures = cli.failure_summary(self.events(*lines))
        self.assertEqual(cli.FAILURE_LIMIT, len(failures))
        self.assertLessEqual(len(failures[0]['actual']), cli.FIELD_LIMIT + 1)

    def test_missing_or_unreadable_events_give_no_summary(self):
        self.assertEqual([], cli.failure_summary(self.run_dir / 'absent.events'))
        self.assertEqual([], cli.failure_summary(None))
        self.assertEqual([], cli.failure_summary(self.events('FAIL {not json')))


class TestFocusRootTest(unittest.TestCase):
    def test_a_wrong_root_is_refused_without_a_cold_start(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp).resolve()
            git_repo_with_project(base)
            done = subprocess.run([str(SCRIPT.parent / 'test-focus'), '--root', 'nope', 'a.core-test'],
                                  cwd=base, capture_output=True, text=True, timeout=60)
            self.assertEqual(2, done.returncode)
            self.assertNotIn('COLD START', done.stderr)
            self.assertIn('not a Clojure project root', done.stdout)
            self.assertFalse((base / 'nope').exists())


if __name__ == '__main__':
    unittest.main()
