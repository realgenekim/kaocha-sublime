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
from contextlib import redirect_stderr, redirect_stdout

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
        with redirect_stdout(out), redirect_stderr(io.StringIO()):
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


class CheckTargetTest(Workspace):
    """check accepts namespaces and ns/test-var targets; failures come from the runner."""

    def loop(self, value):
        loop = cli.Loop(self.root, ':test', {}, False)
        session = dict(record(self.root, os.getpid()), aliases=':test',
                       environment=cli.environment_fingerprint(self.root))
        loop.load_session = lambda: (session, None)
        self.sent = []
        loop.evaluate = lambda session, expression: self.sent.append(expression) or dict(value)
        return loop

    def test_namespace_and_var_targets_are_accepted(self):
        for name in ('a.core-test', 'a.core-test/adds', 'a.core-test/odd-name?', 'a.b_c-test/x->y!*',
                     "a.core-test/prime'"):
            self.assertTrue(cli.valid_target(name), name)
        for name in ('a.core-test/', '/adds', 'a.core-test/x/y', '9a.core-test', 'a.core-test/x y', '(evil)'):
            self.assertFalse(cli.valid_target(name), name)

    def test_var_targets_reach_the_runner_unchanged(self):
        loop = self.loop({'status': 'pass', 'counts': {'tests': 1}})
        receipt = loop.command('check', ['a.core-test/adds', 'b.db-test'])
        self.assertEqual('pass', receipt['status'])
        self.assertIn('["a.core-test/adds", "b.db-test"]', self.sent[0])
        self.assertEqual(['a.core-test/adds', 'b.db-test'], receipt['checks'])

    def test_a_malformed_target_is_refused_before_the_runner(self):
        with self.assertRaisesRegex(RuntimeError, 'test namespaces or test vars'):
            self.loop({}).command('check', ['a.core-test/x y'])
        self.assertEqual([], self.sent)

    def test_a_missing_var_refusal_leaves_the_runner_untainted(self):
        loop = self.loop({'status': 'fail', 'stage': 'focus', 'error': 'no such test var: a.core-test/nope'})
        receipt = loop.command('check', ['a.core-test/nope'])
        self.assertEqual('focus', receipt['stage'])
        self.assertFalse(loop.taint.exists(), 'a typo must not cost a cold restart')

    def test_a_reload_failure_still_taints(self):
        loop = self.loop({'status': 'fail', 'stage': 'reload', 'error': 'Syntax error'})
        loop.command('check', ['a.core-test'])
        self.assertTrue(loop.taint.exists())

    def test_runner_failures_are_kept_and_the_events_fallback_is_not_used(self):
        events = self.run_dir / 'run.events'
        events.write_text('FAIL ' + json.dumps({'kind': 'fail', 'test': 'from/events'}) + '\n')
        runner_failures = [{'kind': 'fail', 'test': 'a.core-test/adds', 'message': 'm', 'output': 'hi\n'}]
        receipt = self.loop({'status': 'fail', 'counts': {'tests': 1}, 'events': str(events),
                             'failures': runner_failures, 'failures-omitted': 0}).command('check', ['a.core-test'])
        self.assertEqual(runner_failures, receipt['failures'])

    def test_an_older_runner_without_failures_falls_back_to_the_events_file(self):
        events = self.run_dir / 'run.events'
        events.write_text('FAIL ' + json.dumps({'kind': 'fail', 'test': 'from/events'}) + '\n')
        receipt = self.loop({'status': 'fail', 'counts': {'tests': 1}, 'events': str(events)}).command(
            'check', ['a.core-test'])
        self.assertEqual('from/events', receipt['failures'][0]['test'])


class RenderFailuresTest(unittest.TestCase):
    RECEIPT = {'status': 'fail', 'stage': 'probe', 'path': '/r/run.edn',
               'counts': {'tests': 4, 'fail': 1, 'error': 1},
               'failures': [{'kind': 'fail', 'test': 'demo.core-test/adds-wrong', 'at': 'test/demo/core_test.clj:12',
                             'message': 'maps should match', 'context': 'small numbers',
                             'expr': '(= {:a 1} {:a 2})', 'expected': '{:a 1}', 'actual': '{:a 2}',
                             'output': '\n'.join('line %d' % i for i in range(8)) + '\n'},
                            {'kind': 'error', 'test': 'demo.core-test/throws', 'at': 'test/demo/core_test.clj:16',
                             'actual': 'clojure.lang.ExceptionInfo: boom\n  multi\n  line'}],
               'failures-omitted': 3}

    def test_each_failure_gets_a_readable_stanza(self):
        text = cli.render_failures(self.RECEIPT)
        self.assertIn('test-focus: FAIL -- 4 tests, 1 fail, 1 error', text)
        self.assertIn('FAIL demo.core-test/adds-wrong  test/demo/core_test.clj:12', text)
        self.assertIn('  message:  maps should match', text)
        self.assertIn('  expected: {:a 1}', text)
        self.assertIn('  actual:   {:a 2}', text)
        self.assertIn('ERROR demo.core-test/throws  test/demo/core_test.clj:16', text)
        self.assertIn('  actual:   clojure.lang.ExceptionInfo: boom multi line', text, 'values are one-liners')
        self.assertIn('(last 5 of 8 lines)', text)
        self.assertIn('    | line 7', text)
        self.assertNotIn('| line 2', text)
        self.assertIn('3 more failures not shown; see the Kaocha receipt at /r/run.edn', text)

    def test_a_refusal_without_failures_shows_its_error(self):
        text = cli.render_failures({'status': 'fail', 'stage': 'focus', 'error': 'no such test var: a/b'})
        self.assertEqual('test-focus: FAIL at stage focus: no such test var: a/b\n', text)

    def test_a_pass_renders_nothing(self):
        self.assertEqual('', cli.render_failures({'status': 'pass', 'counts': {'tests': 1}}))


class TestFocusBlockTest(unittest.TestCase):
    """test-focus: stdout is exactly the JSON receipt; the readable block follows it on stderr."""

    def test_the_block_goes_to_stderr_after_the_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            bin_dir = Path(tmp) / 'bin'
            bin_dir.mkdir()
            (bin_dir / 'test-focus').write_text((SCRIPT.parent / 'test-focus').read_text())
            fake = bin_dir / 'sublime-loop'
            fake.write_text('#!/usr/bin/env bash\n'
                            'for a in "$@"; do last=$a; done\n'
                            'case " $* " in\n'
                            '  *" status "*) echo \'{"status": "ready"}\' ;;\n'
                            '  *" check "*) echo \'{"status": "fail"}\'; echo "FAIL a.core-test/adds" >&2; exit 1 ;;\n'
                            'esac\n')
            for f in (fake, bin_dir / 'test-focus'):
                f.chmod(0o755)
            focus = str(bin_dir / 'test-focus')
            alone = subprocess.run([focus, 'a.core-test/adds'], capture_output=True, text=True, timeout=30)
            self.assertEqual(1, alone.returncode)
            self.assertEqual('{"status": "fail"}\n', alone.stdout, 'stdout carries exactly one receipt')
            self.assertEqual('FAIL a.core-test/adds\n', alone.stderr)
            merged = subprocess.run(['bash', '-c', '"$0" a.core-test/adds 2>&1', focus],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual('{"status": "fail"}\nFAIL a.core-test/adds\n', merged.stdout,
                             'the block is the last thing on screen, after the long JSON line')


if __name__ == '__main__':
    unittest.main()


class UpAliasTest(Workspace):
    """`up` must not report ready for a live session started with other aliases."""

    def loop(self, aliases, explicit):
        loop = cli.Loop(self.root, aliases, {}, explicit)
        live = dict(record(self.root, os.getpid()), aliases=':test')
        loop.load_session = lambda: (live, None)
        return loop

    def test_up_refuses_a_live_session_with_different_explicit_aliases(self):
        with self.assertRaisesRegex(RuntimeError, r'aliases :test -> :standalone:test.*down then up'):
            self.loop(':standalone:test', True).command('up', [])

    def test_up_reuses_a_live_session_with_matching_aliases(self):
        receipt = self.loop(':test', True).command('up', [])
        self.assertEqual('ready', receipt['status'])

    def test_up_without_explicit_aliases_reuses_the_live_session(self):
        receipt = self.loop(':test', False).command('up', [])
        self.assertEqual('ready', receipt['status'])


# ---------------------------------------------------------------- ks-1ag: probes under load

from unittest import mock

IDLE = {'load_1m': 0.8, 'cpus': 8, 'per_core': 0.1}
LOADED = {'load_1m': 70.0, 'cpus': 8, 'per_core': 8.75}


class ProbeBudgetTest(unittest.TestCase):
    # INTENT-TEST: LOOP-PROBE-UNVERIFIED-003
    def test_budget_scales_with_load_per_core_and_is_bounded(self):
        idle = cli.probe_budget(0.1)
        self.assertEqual(2, idle['attempts'])
        self.assertLessEqual(idle['timeout_s'], 6)
        loaded = cli.probe_budget(8.75)
        self.assertEqual(3, loaded['attempts'])
        self.assertGreater(loaded['timeout_s'], 8, 'the measured false negative was an 8 s wait at load 70')
        for per_core in (0, 0.5, 1, 3, 8.75, 15, 500, None):
            b = cli.probe_budget(per_core)
            worst = b['attempts'] * (b['timeout_s'] + b['client_margin_s']) + sum(b['backoff_s'])
            self.assertLess(worst, 120, 'a probe must finish inside the 120 s lock wait')
            self.assertLessEqual(b['timeout_s'], cli.PROBE_MAX_S)
            self.assertEqual(b['attempts'] - 1, len(b['backoff_s']))

    # INTENT-TEST: LOOP-PROBE-UNVERIFIED-003
    def test_unknown_load_is_treated_as_busy(self):
        self.assertEqual(cli.probe_budget(1.0), cli.probe_budget(None))
        self.assertEqual(None, cli.load_per_core(getloadavg=lambda: (_ for _ in ()).throw(OSError()),
                                                 cpus=lambda: 8)['per_core'])
        self.assertEqual(8.75, cli.load_per_core(getloadavg=lambda: (70.0, 1, 1), cpus=lambda: 8)['per_core'])


class AttestUnderLoadTest(Workspace):
    """attest() with a stubbed transport: timeouts are retried, then reported unverified."""

    def loop(self, answers, load=LOADED):
        loop = cli.Loop(self.root, ':test', {}, False)
        loop.load = lambda: load
        self.sleeps, self.calls = [], []
        loop.sleep = lambda s: self.sleeps.append(s) or self.on_sleep()
        self.on_sleep = lambda: None
        answers = list(answers)

        def evaluate(session, expression, timeout=120, margin=3):
            self.calls.append(timeout)
            answer = answers.pop(0)
            if isinstance(answer, Exception):
                raise answer
            return answer
        loop.evaluate = evaluate
        return loop

    def session(self):
        return record(self.root, 4242)

    def timeout(self, n=0):
        rid = 'req-' + str(n)
        return cli.NoReceipt('nREPL returned no terminal receipt: clj-nrepl-eval timed out after 8s',
                             self.run_dir / ('transport-' + rid + '.json'), rid)

    # INTENT-TEST: LOOP-PROBE-UNVERIFIED-003
    def test_every_probe_timing_out_is_unverified_with_evidence(self):
        loop = self.loop([self.timeout(n) for n in range(3)])
        with self.assertRaises(cli.Unverified) as caught:
            loop.attest(self.session())
        evidence = caught.exception.evidence
        self.assertEqual([20, 20, 20], self.calls, 'the wait scales with load per core')
        self.assertEqual([1, 2], self.sleeps, 'retries back off')
        self.assertEqual(3, len(evidence['attempts']))
        self.assertEqual((4242, 45678, GEN), (evidence['pid'], evidence['port'], evidence['generation']))
        self.assertEqual(8.75, evidence['per_core'])
        self.assertIn('timed out', evidence['attempts'][0]['detail'])
        self.assertIn('not starting another JVM', str(caught.exception))

    # INTENT-TEST: LOOP-PROBE-UNVERIFIED-003
    def test_a_retry_that_answers_attests(self):
        loop = self.loop([self.timeout(), dict(self.session())])
        self.assertEqual(4242, loop.attest(self.session())['pid'])
        self.assertEqual(2, len(self.calls))

    # INTENT-TEST: LOOP-PROBE-UNVERIFIED-003
    def test_a_late_answer_to_an_earlier_probe_counts(self):
        first = self.timeout()
        loop = self.loop([first])
        late = {'request-id': first.request_id, 'value': self.session()}
        self.on_sleep = lambda: first.response_path.write_text(json.dumps(late))
        self.assertEqual(GEN, loop.attest(self.session())['generation'])
        self.assertEqual(1, len(self.calls), 'no second probe once the first one answered late')

    # INTENT-TEST: LOOP-PROBE-UNVERIFIED-003
    def test_an_identity_mismatch_is_not_retried(self):
        loop = self.loop([dict(self.session(), generation='other')])
        with self.assertRaisesRegex(RuntimeError, 'identity mismatch'):
            loop.attest(self.session())
        self.assertEqual(1, len(self.calls))


class UnverifiedSessionTest(Workspace):
    """Through main(): a live, owned, unanswering runner is never replaced or called ready."""

    def setUp(self):
        super().setUp()
        self.write_session(dict(record(self.root, 4242), aliases=':test'))
        self.patches = [
            mock.patch.object(cli, 'classify', lambda session, root: {'state': 'live', 'reason': 'owned-and-listening', 'owned': True}),
            mock.patch.object(cli, 'load_per_core', lambda: LOADED),
            mock.patch.object(cli.Loop, 'evaluate', self.no_answer),
            mock.patch.object(cli.subprocess, 'Popen', self.no_new_jvm),
            mock.patch.object(cli.time, 'sleep', lambda s: None),
        ]
        for p in self.patches:
            p.start()

    def tearDown(self):
        for p in self.patches:
            p.stop()
        super().tearDown()

    def no_answer(self, session, expression, timeout=120, margin=3):
        raise cli.NoReceipt('nREPL returned no terminal receipt: clj-nrepl-eval timed out after 8s')

    def no_new_jvm(self, *args, **kwargs):
        raise AssertionError('a second JVM must never start for an unverified root')

    # INTENT-TEST: LOOP-PROBE-UNVERIFIED-003
    def test_status_and_up_report_unverified_and_keep_the_record(self):
        for command in ('status', 'up', 'check'):
            args = (command, 'a.core-test') if command == 'check' else (command,)
            receipt, code = self.invoke(*args)
            self.assertEqual('unverified', receipt['status'], (command, receipt.get('error')))
            self.assertEqual(2, code)
            self.assertEqual(4242, receipt['pid'])
            self.assertEqual(3, len(receipt['evidence']['attempts']))
            self.assertNotIn('no owned test process', receipt['error'])
        self.assertTrue((self.run_dir / 'session.json').exists(), 'the record is kept')
        self.assertEqual([], self.archives())
        self.assertFalse((self.run_dir / 'tainted').exists())

    # INTENT-TEST: LOOP-PROBE-UNVERIFIED-003
    def test_down_stops_an_owned_runner_that_does_not_answer(self):
        stopped = []
        with mock.patch.object(cli.Loop, 'terminate', lambda loop, pid: stopped.append(pid)):
            receipt, code = self.invoke('down')
        self.assertEqual(('stopped', 0, [4242]), (receipt['status'], code, stopped))


def fake_focus(tmp, status_json):
    """A copy of test-focus beside a fake sublime-loop that logs its commands."""
    bin_dir = Path(tmp) / 'bin'
    bin_dir.mkdir()
    (bin_dir / 'test-focus').write_text((SCRIPT.parent / 'test-focus').read_text())
    log = Path(tmp) / 'calls.log'
    fake = bin_dir / 'sublime-loop'
    fake.write_text('#!/usr/bin/env bash\n'
                    'echo "$*" >> ' + str(log) + '\n'
                    'case " $* " in\n'
                    "  *' status '*) echo '" + status_json + "' ;;\n"
                    "  *' up '*) echo '{\"status\": \"ready\"}' ;;\n"
                    "  *' check '*) echo '{\"status\": \"pass\"}' ;;\n"
                    'esac\n')
    for f in (fake, bin_dir / 'test-focus'):
        f.chmod(0o755)
    return str(bin_dir / 'test-focus'), log


class TestFocusStatusRoutingTest(unittest.TestCase):
    def run_focus(self, status_json):
        with tempfile.TemporaryDirectory() as tmp:
            focus, log = fake_focus(tmp, status_json)
            done = subprocess.run([focus, 'a.core-test'], capture_output=True, text=True, timeout=30)
            return done, log.read_text().split()

    # INTENT-TEST: LOOP-PROBE-UNVERIFIED-003
    def test_unverified_is_reported_and_never_cold_started(self):
        done, calls = self.run_focus('{"status": "unverified", "pid": 7503}')
        self.assertEqual(2, done.returncode)
        self.assertIn('UNVERIFIED', done.stderr)
        self.assertNotIn('COLD START', done.stderr)
        self.assertEqual('{"status": "unverified", "pid": 7503}\n', done.stdout)
        self.assertNotIn('up', calls)
        self.assertNotIn('check', calls)

    # INTENT-TEST: LOOP-PROBE-UNVERIFIED-003
    def test_a_loop_error_is_reported_and_never_cold_started(self):
        done, calls = self.run_focus('{"status": "unknown", "error": "runner busy for 120 seconds"}')
        self.assertEqual(2, done.returncode)
        self.assertNotIn('COLD START', done.stderr)
        self.assertNotIn('up', calls)

    # INTENT-TEST: LOOP-PROBE-UNVERIFIED-003
    def test_no_session_is_still_a_cold_start(self):
        done, calls = self.run_focus('{"status": "unknown", "error": "no owned test process; run sublime-loop up"}')
        self.assertEqual(0, done.returncode)
        self.assertIn('COLD START -- no warm JVM for this root', done.stderr)
        self.assertIn('up', calls)
        self.assertEqual('{"status": "pass"}\n', done.stdout)


# ---------------------------------------------------------------- ks-y5y: parsed deps fingerprint

DEPS = '''{:paths ["src"]
 :deps {org.clojure/clojure {:mvn/version "1.12.0"}}
 :aliases {:test {:extra-paths ["test"]
                  :extra-deps {lambdaisland/kaocha {:mvn/version "1.91.1392"}}
                  :jvm-opts ["-Xmx512m"]}
           :build {:deps {io.github.clojure/tools.build {:mvn/version "0.10.5"}}}}}
'''


class EnvironmentFingerprintTest(Workspace):
    def env(self, text, aliases=':test'):
        (self.root / 'deps.edn').write_text(text)
        return cli.environment_fingerprint(self.root, aliases)

    # INTENT-TEST: LOOP-ENV-FINGERPRINT-004
    def test_comments_whitespace_and_key_order_do_not_restart(self):
        base = self.env(DEPS)
        commented = DEPS.replace(':jvm-opts', ';; @spec LOOP-1 the heap cap (a ; in a comment)\n :jvm-opts')
        self.assertEqual(base, self.env(commented))
        self.assertEqual(base, self.env(' \n'.join(DEPS.split('\n')) + '\n\n;; trailing\n'))
        self.assertEqual(base, self.env(DEPS.replace('{:paths ["src"]\n :deps', '{:deps').replace(
            ':aliases', ':paths ["src"],\n :aliases')))
        self.assertEqual(base, self.env(DEPS.replace(':jvm-opts', '#_ :old-key #_ [1 2]\n :jvm-opts')))

    # INTENT-TEST: LOOP-ENV-FINGERPRINT-004
    def test_an_unused_alias_does_not_restart(self):
        base = self.env(DEPS)
        self.assertEqual(base, self.env(DEPS.replace('0.10.5', '0.10.6')))

    # INTENT-TEST: LOOP-ENV-FINGERPRINT-004
    def test_what_the_runner_classpath_and_jvm_use_does_restart(self):
        base = self.env(DEPS)
        for old, new in (('"1.12.0"', '"1.12.1"'),          # :deps
                         ('["src"]', '["src" "dev"]'),      # :paths
                         ('["test"]', '["test" "fx"]'),     # used alias :extra-paths
                         ('1.91.1392', '1.91.1393'),        # used alias :extra-deps
                         ('-Xmx512m', '-Xmx1g')):           # used alias :jvm-opts
            self.assertNotEqual(base, self.env(DEPS.replace(old, new)), new)
        self.assertNotEqual(base, self.env(DEPS, ':test:build'), 'another alias set restarts')
        self.assertNotEqual(base, self.env(DEPS.replace(':aliases {', ':mvn/repos {"x" {:url "u"}}\n :aliases {')))

    # INTENT-TEST: LOOP-ENV-FINGERPRINT-004
    def test_unreadable_deps_fall_back_to_bytes(self):
        broken = DEPS + '}'
        base = self.env(broken)
        self.assertNotEqual(base, self.env(broken + ';; comment'), 'unparsed: any edit restarts')

    # INTENT-TEST: LOOP-ENV-FINGERPRINT-004
    def test_the_reader_handles_config_edn_forms(self):
        text = ('{:a #{3 1 2} :b \\c :c "x;y\\"z" :d #kaocha/v1 {:k ^:meta [1]} :e #"re" '
                ':f ##Inf :g #:ns{:x 1} :h (1 2)}')
        self.assertEqual('{:a #{1 2 3} :b \\c :c "x;y\\"z" :d #kaocha/v1 {:k [1]} :e #"re" :f ##Inf '
                         ':g #:ns {:x 1} :h (1 2)}', cli.canonical(cli.read_edn(text)))
        for bad in ('{:a}', '{:a 1', '[1] [2]', '"open', ')'):
            with self.assertRaises(cli.EdnError, msg=bad):
                cli.read_edn(bad)

    # INTENT-TEST: LOOP-ENV-FINGERPRINT-004
    def test_a_comment_only_deps_edit_does_not_ask_for_a_restart(self):
        (self.root / 'deps.edn').write_text(DEPS)
        loop = cli.Loop(self.root, ':test', {}, False)
        session = dict(record(self.root, os.getpid()), aliases=':test',
                       environment=cli.environment_fingerprint(self.root, ':test'))
        loop.load_session = lambda attest=True: (session, None)
        loop.evaluate = lambda session, expression: {'status': 'pass', 'counts': {'tests': 1}}
        (self.root / 'deps.edn').write_text(';; @spec tags added\n' + DEPS)
        self.assertEqual('pass', loop.command('check', ['a.core-test'])['status'])
        (self.root / 'deps.edn').write_text(DEPS.replace('-Xmx512m', '-Xmx1g'))
        with self.assertRaisesRegex(RuntimeError, 'runner or dependencies changed'):
            loop.command('check', ['a.core-test'])


# ---------------------------------------------------------------- ks-9p5: reload failures

class ReloadFailureTest(CheckTargetTest):
    FAILURE = {'status': 'fail', 'stage': 'reload',
               'error': 'reload of a.core failed at src/a/core.clj:4: EOF while reading',
               'reload-failure': {'namespace': 'a.core', 'at': 'src/a/core.clj:4', 'file': 'src/a/core.clj',
                                  'line': 4, 'phase': 'read-source', 'message': 'EOF while reading'},
               'failures': [{'kind': 'load', 'test': 'a.core', 'at': 'src/a/core.clj:4',
                             'message': 'EOF while reading'}],
               'failures-omitted': 0, 'not-reloaded': ['a.core', 'a.core-test']}

    # INTENT-TEST: LOOP-RELOAD-FAILURE-005
    def test_a_reload_failure_in_a_usable_jvm_is_reported_and_keeps_it_warm(self):
        receipt = self.loop(dict(self.FAILURE, **{'jvm-usable': True})).command('check', ['a.core-test'])
        self.assertEqual('fail', receipt['status'])
        self.assertEqual('a.core', receipt['reload-failure']['namespace'])
        self.assertFalse(self.run_dir.joinpath('tainted').exists(), 'a syntax error must not cost a cold start')
        text = cli.render_failures(receipt)
        self.assertIn('LOAD a.core  src/a/core.clj:4', text)
        self.assertIn('message:  EOF while reading', text)

    # INTENT-TEST: LOOP-RELOAD-FAILURE-005
    def test_an_unhealthy_jvm_still_taints(self):
        self.loop(dict(self.FAILURE, **{'jvm-usable': False})).command('check', ['a.core-test'])
        self.assertTrue(self.run_dir.joinpath('tainted').exists())

    # INTENT-TEST: LOOP-RELOAD-FAILURE-005
    def test_a_reload_failure_is_never_a_pass(self):
        receipt = self.loop(dict(self.FAILURE, status='pass', counts={'tests': 1}, **{'jvm-usable': True})).command(
            'check', ['a.core-test'])
        self.assertEqual('fail', receipt['status'])

    # INTENT-TEST: LOOP-RELOAD-FAILURE-005
    def test_a_failed_dependency_scan_keeps_the_jvm_warm(self):
        self.loop({'status': 'fail', 'stage': 'dependency-scan', 'error': 'bad ns form',
                   'jvm-usable': True}).command('check', ['a.core-test'])
        self.assertFalse(self.run_dir.joinpath('tainted').exists())


class StaleImageTest(CheckTargetTest):
    STALE = {'status': 'fail', 'stage': 'stale-image', 'jvm-usable': True,
             'error': 'stale image: 1 mapping(s) in the warm JVM do not match the files',
             'stale': {'refers': [{'ns': 'a.core', 'sym': 'render', 'class': 'refused', 'lib': 'a.render',
                                   'home': 'render', 'expected': "#'a.render/render",
                                   'defined-at': 'src/a/core.clj:214'}],
                       'defs': [{'ns': 'a.core', 'var': 'render', 'at': 'src/a/core.clj:214', 'unmapped': True}],
                       'check-ms': 2.1},
             'failures': [{'kind': 'stale', 'test': 'a.core/render', 'at': 'src/a/core.clj:1',
                           'message': "a.core's ns form refers render from a.render, but ..."}],
             'failures-omitted': 0}

    # INTENT-TEST: LOOP-STALE-REFER-011
    def test_a_stale_image_fail_is_terminal_and_keeps_the_jvm_warm(self):
        loop = self.loop(self.STALE)
        receipt = loop.command('check', ['a.core-test'])
        self.assertEqual('fail', receipt['status'])
        self.assertEqual('stale-image', receipt['stage'])
        self.assertFalse(loop.taint.exists(), 'a stale image the runner can name must not cost a cold start')
        older = self.loop(dict(self.STALE, **{'jvm-usable': None}))
        older.command('check', ['a.core-test'])
        self.assertTrue(older.taint.exists(), 'without the runner saying the JVM is usable: tainted')

    # INTENT-TEST: LOOP-STALE-REFER-011
    def test_stale_entries_render_like_failures(self):
        text = cli.render_check(dict(self.STALE, load_1m=1.0, cpus=8, per_core=0.13))
        self.assertIn('STALE a.core/render  src/a/core.clj:1', text)
        self.assertIn("message:  a.core's ns form refers render from a.render", text)
        self.assertIn('test-focus: unmapped 1 definition(s) the files no longer have: a.core/render', text)
        clean = cli.render_stale({'status': 'pass', 'stale': {'check-ms': 1.2}})
        self.assertEqual('', clean, 'a clean image adds no line')

    # INTENT-TEST: LOOP-STALE-REPAIR-012
    def test_repair_stale_reaches_the_runner_only_when_asked(self):
        loop = self.loop({'status': 'pass', 'counts': {'tests': 1}})
        loop.command('check', ['a.core-test'])
        loop.command('check', ['a.core-test'], {'repair_stale': True})
        self.assertNotIn('repair-stale', self.sent[0])
        self.assertIn(':repair-stale true}', self.sent[1])
        self.assertEqual({'repeat': 1, 'slowest': 5, 'perf': False, 'repair_stale': True},
                         cli.check_options(cli.parse(['--repair-stale', 'check', 'a.core-test'])))
        text = cli.render_stale({'stale': {'repaired': [{'ns': 'a.core', 'var': 'render', 'was': None,
                                                         'now': "#'a.render/render"}]}})
        self.assertEqual("test-focus: repaired a.core/render: unmapped -> #'a.render/render\n", text)


# ---------------------------------------------------------------- ks-216, ks-mn0, ks-wzy

def timing(test, outcome='pass', ms=1.0):
    return {'test': test, 'ms': ms, 'outcome': outcome}


def run_receipt(status, *timings, **extra):
    fail = sum(t['outcome'] == 'fail' for t in timings)
    error = sum(t['outcome'] == 'error' for t in timings)
    return dict({'status': status, 'stage': 'probe', 'path': '/r/%s.edn' % status,
                 'counts': {'tests': len(timings), 'fail': fail, 'error': error},
                 'slowest': sorted(timings, key=lambda t: -t['ms'])[:5],
                 'test-timings': list(timings)}, **extra)


class ScriptedCheckTest(Workspace):
    """A Loop whose runner answers from a script, one receipt per check! call."""

    def loop(self, *answers, on_call=None):
        loop = cli.Loop(self.root, ':test', {}, False)
        session = dict(record(self.root, os.getpid()), aliases=':test',
                       environment=cli.environment_fingerprint(self.root))
        loop.load_session = lambda: (session, None)
        self.sent = []
        script = list(answers)

        def evaluate(session, expression):
            self.sent.append(expression)
            if on_call:
                on_call(len(self.sent))
            return dict(script.pop(0))
        loop.evaluate = evaluate
        return loop


class ReceiptLoadSlowestTest(ScriptedCheckTest):
    def invoke_check(self, answer, *args, loads=({'load_1m': 2.4, 'cpus': 8, 'per_core': 0.3},
                                                 {'load_1m': 2.6, 'cpus': 8, 'per_core': 0.33})):
        loads = list(loads)
        err = io.StringIO()
        out = io.StringIO()
        with mock.patch.object(cli, 'load_per_core', lambda: loads.pop(0)), \
                mock.patch.object(cli.Loop, 'load_session', lambda loop: (self.session, None)), \
                mock.patch.object(cli.Loop, 'evaluate', lambda loop, s, e: self.sent.append(e) or dict(answer)), \
                redirect_stdout(out), redirect_stderr(err):
            code = cli.main(['--root', str(self.root), *args])
        return json.loads(out.getvalue().strip().splitlines()[-1]), code, err.getvalue()

    def setUp(self):
        super().setUp()
        self.sent = []
        self.session = dict(record(self.root, os.getpid()), aliases=':test',
                            environment=cli.environment_fingerprint(self.root))

    # INTENT-TEST: LOOP-RECEIPT-LOAD-SLOWEST-006
    def test_a_check_receipt_records_load_at_start_and_end_and_the_slowest_tests(self):
        answer = run_receipt('pass', timing('a.core-test/quick', ms=1.2), timing('a.core-test/slow', ms=812.4))
        answer.pop('test-timings')
        receipt, code, err = self.invoke_check(answer, 'check', 'a.core-test')
        self.assertEqual(0, code)
        self.assertEqual((2.4, 8, 0.3, 2.6), (receipt['load_1m'], receipt['cpus'], receipt['per_core'],
                                               receipt['load_1m_end']))
        self.assertEqual('a.core-test/slow', receipt['slowest'][0]['test'])
        for key in ('status', 'counts', 'checks', 'wall_s', 'queue_s', 'source', 'generation',
                    'verification_complete', 'wrapper_receipt'):
            self.assertIn(key, receipt, 'existing fields are kept: ' + key)
        wrapper = json.loads(Path(receipt['wrapper_receipt']).read_text())
        self.assertEqual(2.6, wrapper['load_1m_end'], 'the wrapper receipt carries the same fields')
        self.assertEqual('test-focus: load 2.4 on 8 cpus (0.3/core), 2.6 at end; slowest a.core-test/slow 812.4 ms\n',
                         err, 'a passing check prints exactly one line')
        self.assertIn(':slowest 5', self.sent[0])

    # INTENT-TEST: LOOP-RECEIPT-LOAD-SLOWEST-006
    def test_a_refused_check_still_records_the_load(self):
        receipt, code, err = self.invoke_check({}, 'check', 'a.core-test/x y')
        self.assertEqual(('unknown', 2), (receipt['status'], code))
        self.assertEqual(2.4, receipt['load_1m'])
        self.assertTrue(err.startswith('test-focus: load 2.4 on 8 cpus'))
        status, _, _ = self.invoke_check({}, 'status')
        self.assertNotIn('load_1m', status, 'only check receipts carry the load')

    # INTENT-TEST: LOOP-RECEIPT-LOAD-SLOWEST-006
    def test_slowest_n_is_passed_to_the_runner_and_bounded(self):
        self.invoke_check(run_receipt('pass', timing('a/b')), '--slowest', '12', 'check', 'a.core-test')
        self.assertIn(':slowest 12', self.sent[-1])
        for bad in ('51', 'x', '-1'):
            receipt, code, _ = self.invoke_check({}, '--slowest', bad, 'check', 'a.core-test')
            self.assertEqual(('unknown', 2), (receipt['status'], code), bad)
            self.assertIn('--slowest', receipt['error'])

    # INTENT-TEST: LOOP-RECEIPT-LOAD-SLOWEST-006
    def test_the_load_line_flags_a_busy_machine_and_an_unknown_load(self):
        busy = cli.render_load_line({'load_1m': 40.0, 'cpus': 8, 'per_core': 5.0, 'load_1m_end': 41.0})
        self.assertEqual('test-focus: load 40.0 on 8 cpus (5.0/core), 41.0 at end '
                         '[busy: timings measure the machine]\n', busy)
        self.assertEqual('test-focus: load unknown\n', cli.render_load_line({'load_1m': None, 'cpus': 8}))
        failed = cli.render_check(dict(RenderFailuresTest.RECEIPT, load_1m=1.0, cpus=8, per_core=0.12))
        self.assertTrue(failed.startswith('test-focus: load 1.0 on 8 cpus (0.12/core)\ntest-focus: FAIL'),
                        'the load line comes first, then the failure block')


class RepeatTest(ScriptedCheckTest):
    A, B = 'a.core-test/steady', 'a.core-test/wobbly'

    # INTENT-TEST: LOOP-REPEAT-007
    def test_a_test_that_passes_and_fails_across_runs_is_flaky(self):
        loop = self.loop(run_receipt('pass', timing(self.A), timing(self.B)),
                         run_receipt('fail', timing(self.A), timing(self.B, 'fail'),
                                     failures=[{'kind': 'fail', 'test': self.B}]),
                         run_receipt('pass', timing(self.A), timing(self.B)))
        receipt = loop.command('check', ['a.core-test'], {'repeat': 3})
        rep = receipt['repeat']
        self.assertEqual(('fail', 'flaky'), (receipt['status'], rep['verdict']))
        self.assertEqual([{'test': self.B, 'runs': 3, 'pass': 2, 'fail': 1, 'error': 0, 'verdict': 'flaky'},
                          {'test': self.A, 'runs': 3, 'pass': 3, 'fail': 0, 'error': 0, 'verdict': 'stable-pass'}],
                         rep['tally'], 'flaky rows first')
        self.assertEqual((3, 3, 1, 0, 1), (rep['requested'], rep['runs'], rep['flaky'], rep['stable_fail'],
                                           rep['stable_pass']))
        self.assertEqual(self.B, receipt['failures'][0]['test'], 'top-level fields are the first failing run')
        self.assertEqual('/r/fail.edn', receipt['path'])
        self.assertNotIn('test-timings', receipt, 'per-run timings are folded into the tally')
        self.assertIn(':reload true', self.sent[0])
        self.assertTrue(all(':reload false' in s and ':timings true' in s for s in self.sent[1:]),
                        'only the first run reloads when no file changes')
        self.assertEqual([True, False, False], [r['reloaded'] for r in rep['per_run']])
        self.assertFalse(loop.taint.exists())
        text = cli.render_repeat(receipt)
        self.assertIn('test-focus: REPEAT 3 of 3 runs -- flaky (1 flaky, 0 stable-fail, 1 stable-pass)', text)
        self.assertIn('  flaky       a.core-test/wobbly  2/3 pass', text)
        self.assertNotIn('steady', text, 'stable passes are counted, not listed')

    # INTENT-TEST: LOOP-REPEAT-007
    def test_every_run_passing_is_stable_pass_and_every_run_failing_is_stable_fail(self):
        ok = self.loop(*[run_receipt('pass', timing(self.A))] * 4).command('check', ['a.core-test'], {'repeat': 4})
        self.assertEqual(('pass', 'stable-pass', 4), (ok['status'], ok['repeat']['verdict'], ok['repeat']['runs']))
        bad = self.loop(*[run_receipt('fail', timing(self.A), timing(self.B, 'error'))] * 2).command(
            'check', ['a.core-test'], {'repeat': 2})
        self.assertEqual(('fail', 'stable-fail'), (bad['status'], bad['repeat']['verdict']))
        self.assertEqual({'test': self.B, 'runs': 2, 'pass': 0, 'fail': 0, 'error': 2, 'verdict': 'stable-fail'},
                         bad['repeat']['tally'][0])

    # INTENT-TEST: LOOP-REPEAT-007
    def test_a_file_change_between_runs_reloads_and_makes_the_result_stale(self):
        src = self.root / 'src'
        src.mkdir()

        def edit(call):
            if call == 1:
                (src / 'a.clj').write_text('(ns a)')
        loop = self.loop(*[run_receipt('pass', timing(self.A))] * 3, on_call=edit)
        receipt = loop.command('check', ['a.core-test'], {'repeat': 3})
        self.assertEqual('stale', receipt['status'])
        self.assertTrue(receipt['repeat']['source_changed'])
        self.assertEqual('stale', receipt['repeat']['per_run'][0]['status'], 'run 1 saw its files change')
        self.assertEqual('stable-pass', receipt['repeat']['verdict'], 'a stale run is not a flake')
        self.assertEqual([True, True, False], [r['reloaded'] for r in receipt['repeat']['per_run']],
                         'an edit during run 1 is reloaded before run 2, then not again')

    # INTENT-TEST: LOOP-REPEAT-007
    def test_a_change_between_runs_triggers_a_reload(self):
        src = self.root / 'src'
        src.mkdir()
        loop = self.loop(*[run_receipt('pass', timing(self.A))] * 3)
        original = loop.run_once

        def run_once(*args):
            result = original(*args)
            if len(self.sent) == 1:
                (src / 'a.clj').write_text('(ns a)')   # edited after run 1 finished
            return result
        loop.run_once = run_once
        receipt = loop.command('check', ['a.core-test'], {'repeat': 3})
        self.assertEqual([True, True, False], [r['reloaded'] for r in receipt['repeat']['per_run']])
        self.assertEqual('stale', receipt['status'], 'the tally spans two versions of the code')
        self.assertIn('source files changed between runs', cli.render_repeat(receipt))

    # INTENT-TEST: LOOP-REPEAT-007
    def test_a_run_without_test_results_stops_the_repeat(self):
        reload_fail = {'status': 'fail', 'stage': 'reload', 'error': 'EOF', 'jvm-usable': True,
                       'failures': [{'kind': 'load', 'test': 'a.core'}]}
        loop = self.loop(run_receipt('pass', timing(self.A)), reload_fail, run_receipt('pass', timing(self.A)))
        receipt = loop.command('check', ['a.core-test'], {'repeat': 3})
        self.assertEqual(('fail', 'reload', 'incomplete'), (receipt['status'], receipt['stage'],
                                                            receipt['repeat']['verdict']))
        self.assertEqual(2, len(self.sent), 'no third run after a broken one')
        unknown = self.loop({'status': 'unknown', 'stage': 'probe'}).command('check', ['a.core-test'], {'repeat': 5})
        self.assertEqual(('unknown', 'incomplete'), (unknown['status'], unknown['repeat']['verdict']))
        self.assertTrue((self.run_dir / 'tainted').exists(), 'a non-terminal run still taints')

    # INTENT-TEST: LOOP-REPEAT-007
    def test_repeat_is_bounded(self):
        for bad in (0, 51):
            with self.assertRaisesRegex(RuntimeError, '--repeat must be a whole number from 1 to 50'):
                self.loop().command('check', ['a.core-test'], {'repeat': bad})
        self.assertEqual([], self.sent)
        receipt = self.loop(run_receipt('pass', timing(self.A))).command('check', ['a.core-test'], {'repeat': 1})
        self.assertNotIn('repeat', receipt, 'one run is an ordinary check')
        self.assertIn(':timings false', self.sent[0])


class PerfModeTest(ScriptedCheckTest):
    # INTENT-TEST: LOOP-PERF-MODE-008
    def test_perf_may_name_no_namespace_and_asks_the_runner_for_perf_tests(self):
        answer = run_receipt('pass', timing('a.render-test/big', ms=900.0))
        answer['perf-tests'] = answer.pop('test-timings')
        receipt = self.loop(answer).command('check', [], {'perf': True})
        self.assertEqual('pass', receipt['status'])
        self.assertIn('/check! [] {:reload true :perf true', self.sent[0])
        self.assertEqual('test-focus: PERF -- 1 timing tests\n       900.0 ms  pass    a.render-test/big\n',
                         cli.render_perf(receipt))
        with self.assertRaisesRegex(RuntimeError, 'test namespaces'):
            self.loop().command('check', [], {})

    # INTENT-TEST: LOOP-PERF-MODE-008
    def test_no_perf_tests_is_a_focus_refusal_that_keeps_the_jvm_warm(self):
        loop = self.loop({'status': 'fail', 'stage': 'focus', 'error': 'no ^:perf test vars in: a.core-test',
                          'perf-selected': []})
        receipt = loop.command('check', ['a.core-test'], {'perf': True})
        self.assertEqual('focus', receipt['stage'])
        self.assertFalse(loop.taint.exists())
        self.assertIn('no ^:perf test vars', cli.render_check(receipt))


class TestFocusOptionsTest(unittest.TestCase):
    def run_focus(self, *args):
        with tempfile.TemporaryDirectory() as tmp:
            focus, log = fake_focus(tmp, '{"status": "ready"}')
            done = subprocess.run([focus, *args], capture_output=True, text=True, timeout=30)
            return done, log.read_text().splitlines()

    # INTENT-TEST: LOOP-REPEAT-007
    def test_repeat_and_slowest_reach_only_the_check(self):
        done, calls = self.run_focus('--repeat', '3', 'a.core-test', '--slowest', '9')
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertEqual(['status', '--repeat 3 --slowest 9 check a.core-test'], calls)

    # INTENT-TEST: LOOP-STALE-REPAIR-012
    def test_repair_stale_reaches_only_the_check(self):
        done, calls = self.run_focus('--repair-stale', 'a.core-test')
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertEqual(['status', '--repair-stale check a.core-test'], calls)

    # INTENT-TEST: LOOP-PERF-MODE-008
    def test_perf_prints_the_load_first_and_needs_no_namespace(self):
        done, calls = self.run_focus('--perf')
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertEqual(['status', '--perf check'], calls)
        first = done.stderr.splitlines()[0]
        self.assertRegex(first, r'^test-focus: --perf; load averages [0-9.]+[ ,]+[0-9.]+[ ,]+[0-9.]+ on \d+ cpus, before the run$')
        refused = subprocess.run([str(SCRIPT.parent / 'test-focus')], capture_output=True, text=True, timeout=30)
        self.assertEqual(2, refused.returncode)
        self.assertIn('(or use --perf)', refused.stderr)
