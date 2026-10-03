import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, parse } from 'node:path';
import test from 'node:test';
import { buildDashboard } from '../scripts/build-dashboard.mjs';
import { publishDashboard, sourceFingerprint, sourceHead } from '../scripts/publish-dashboard.mjs';

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'dashboard-publication-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const dashboard = join(root, 'Virtual Bot', 'dashboard');
  const destinationRoot = join(root, 'Virtual Bot', 'static', 'dash');
  const put = (path, content) => { mkdirSync(dirname(path), { recursive: true }); writeFileSync(path, content); };
  put(join(dashboard, 'src', 'entry.js'), 'export const current = true;');
  put(join(dashboard, 'package.json'), '{"type":"module"}');
  put(join(dashboard, 'scripts', 'copy-excalidraw-fonts.mjs'), '');
  // A small build fixture proves the wrapper's actual CLI and publication behavior.
  put(join(dashboard, 'node_modules', 'vite', 'bin', 'vite.js'), `
    import { mkdirSync, writeFileSync } from 'node:fs';
    import { join } from 'node:path';
    const argument = process.argv.find(arg => arg.startsWith('--outDir='));
    const output = argument ? argument.slice(9) : process.argv[process.argv.indexOf('--outDir') + 1];
    mkdirSync(join(output, 'assets'), { recursive: true });
    writeFileSync(join(output, 'index.html'), '<script src="assets/current.js"></script>');
    writeFileSync(join(output, 'assets', 'current.js'), 'new build');
  `);
  execFileSync('git', ['init', '-q', root]);
  execFileSync('git', ['-C', root, 'add', 'Virtual Bot/dashboard/src/entry.js']);
  execFileSync('git', ['-C', root, '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'fixture']);
  const expectedHead = sourceHead(root);
  function candidate(label, buildStartedAt = '2026-10-03T10:00:00.000Z', sourceRevision = expectedHead) {
    const artifactRoot = join(root, label);
    put(join(artifactRoot, 'assets', `${label}.js`), label);
    put(join(artifactRoot, 'index.html'), `<script src="assets/${label}.js"></script>`);
    put(join(artifactRoot, 'sw.js'), `worker ${label}`);
    put(join(artifactRoot, 'build-info.json'), JSON.stringify({ sourceRevision, buildStartedAt }));
    return artifactRoot;
  }
  const publish = (artifactRoot, extra = {}) => publishDashboard({ artifactRoot, destinationRoot, sourceRoot: root, expectedHead, ...extra });
  return { root, dashboard, destinationRoot, expectedHead, put, candidate, publish };
}

test('publishing retains old chunks and switches the index only after all assets exist', t => {
  const f = fixture(t);
  f.publish(f.candidate('old'));
  f.publish(f.candidate('new', '2026-10-03T10:01:00.000Z'), {
    verifySource() {
      assert.match(readFileSync(join(f.destinationRoot, 'index.html'), 'utf8'), /assets\/old.js/);
    },
  });
  assert.equal(readFileSync(join(f.destinationRoot, 'assets', 'old.js'), 'utf8'), 'old');
  assert.equal(readFileSync(join(f.destinationRoot, 'assets', 'new.js'), 'utf8'), 'new');
  assert.match(readFileSync(join(f.destinationRoot, 'index.html'), 'utf8'), /assets\/new.js/);
  assert.equal(readFileSync(join(f.destinationRoot, 'sw.js'), 'utf8'), 'worker new');
  assert.deepEqual(Object.keys(JSON.parse(readFileSync(join(f.destinationRoot, 'build-info.json'), 'utf8'))),
    ['sourceRevision', 'buildStartedAt']);
});

test('an older slow build cannot replace a newer installed dashboard', t => {
  const f = fixture(t);
  f.publish(f.candidate('new', '2026-10-03T10:01:00.000Z'));
  assert.throws(() => f.publish(f.candidate('old')), /newer dashboard build/);
  assert.match(readFileSync(join(f.destinationRoot, 'index.html'), 'utf8'), /assets\/new.js/);
  assert.equal(existsSync(join(f.destinationRoot, 'assets', 'old.js')), false);
  assert.equal(existsSync(join(f.destinationRoot, '.publish-lock')), false);
});

test('a snapshot from an old revision is rejected before publishing assets', t => {
  const f = fixture(t);
  const old = f.candidate('old');
  f.put(join(f.dashboard, 'src', 'entry.js'), 'export const current = false;');
  execFileSync('git', ['-C', f.root, 'add', 'Virtual Bot/dashboard/src/entry.js']);
  execFileSync('git', ['-C', f.root, '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'next']);
  assert.throws(() => f.publish(old), /source revision changed/);
  assert.equal(existsSync(join(f.destinationRoot, 'index.html')), false);
  assert.equal(existsSync(join(f.destinationRoot, '.publish-lock')), false);
});

test('failure while copying assets keeps the previous index and releases the lock', t => {
  const f = fixture(t);
  f.publish(f.candidate('old'));
  const next = f.candidate('new', '2026-10-03T10:01:00.000Z');
  mkdirSync(join(f.destinationRoot, 'assets', 'new.js'));
  assert.throws(() => f.publish(next));
  assert.match(readFileSync(join(f.destinationRoot, 'index.html'), 'utf8'), /assets\/old.js/);
  assert.equal(existsSync(join(f.destinationRoot, '.publish-lock')), false);
  rmSync(join(f.destinationRoot, 'assets', 'new.js'), { recursive: true });
  f.publish(next);
  assert.match(readFileSync(join(f.destinationRoot, 'index.html'), 'utf8'), /assets\/new.js/);
});

test('an active publisher fails immediately without removing the other publisher lock', t => {
  const f = fixture(t);
  mkdirSync(join(f.destinationRoot, '.publish-lock'), { recursive: true });
  assert.throws(() => f.publish(f.candidate('new')), /publication is in progress/);
  assert.equal(existsSync(join(f.destinationRoot, '.publish-lock')), true);
});

test('failure switching the index restores the worker and version information', t => {
  const f = fixture(t);
  f.publish(f.candidate('old'));
  rmSync(join(f.destinationRoot, 'index.html'));
  mkdirSync(join(f.destinationRoot, 'index.html'));
  const next = f.candidate('new', '2026-10-03T10:01:00.000Z');
  assert.throws(() => f.publish(next));
  assert.equal(readFileSync(join(f.destinationRoot, 'sw.js'), 'utf8'), 'worker old');
  assert.equal(JSON.parse(readFileSync(join(f.destinationRoot, 'build-info.json'), 'utf8')).buildStartedAt,
    '2026-10-03T10:00:00.000Z');
  assert.equal(existsSync(join(f.destinationRoot, '.publish-lock')), false);
  rmSync(join(f.destinationRoot, 'index.html'), { recursive: true });
  f.publish(next);
  assert.match(readFileSync(join(f.destinationRoot, 'index.html'), 'utf8'), /assets\/new.js/);
});

test('explicit output builds artifacts only and never changes the live dashboard', t => {
  const f = fixture(t);
  f.publish(f.candidate('old'));
  const output = join(f.root, 'artifact-only');
  buildDashboard({ root: f.dashboard, args: ['--outDir', output], sourceRoot: f.root, expectedHead: f.expectedHead });
  assert.equal(existsSync(join(output, 'index.html')), true);
  assert.equal(existsSync(join(output, 'build-info.json')), true);
  assert.match(readFileSync(join(f.destinationRoot, 'index.html'), 'utf8'), /assets\/old.js/);
  assert.throws(() => buildDashboard({ root: f.dashboard, args: ['--outDir', f.destinationRoot],
    sourceRoot: f.root, expectedHead: f.expectedHead }), /safely publish/);
});

test('normal builds publish without deleting the previous live chunks', t => {
  const f = fixture(t);
  f.publish(f.candidate('old'));
  buildDashboard({ root: f.dashboard, args: [], sourceRoot: f.root, expectedHead: f.expectedHead });
  assert.match(readFileSync(join(f.destinationRoot, 'index.html'), 'utf8'), /assets\/current.js/);
  assert.equal(existsSync(join(f.destinationRoot, 'assets', 'old.js')), true);
});

test('duplicate output flags and symlink aliases cannot bypass live-output isolation', t => {
  const f = fixture(t);
  f.publish(f.candidate('old'));
  const options = { root: f.dashboard, sourceRoot: f.root, expectedHead: f.expectedHead };
  assert.throws(() => buildDashboard({ ...options, args: ['--outDir', join(f.root, 'scratch'), `--outDir=${f.destinationRoot}`] }),
    /only once/);
  const alias = join(f.root, 'alias');
  symlinkSync(f.destinationRoot, alias);
  assert.throws(() => buildDashboard({ ...options, args: [`--outDir=${alias}`] }), /isolated --outDir/);
  assert.throws(() => f.publish(alias), /isolated from the live directory/);
  assert.match(readFileSync(join(f.destinationRoot, 'index.html'), 'utf8'), /assets\/old.js/);
});

test('filesystem-root output is rejected before the builder or publisher runs', t => {
  const f = fixture(t);
  f.publish(f.candidate('old'));
  // Even if the guard regresses, the fixture must never write outside its directory.
  f.put(join(f.dashboard, 'node_modules', 'vite', 'bin', 'vite.js'),
    'throw new Error("The output guard must run before Vite");');
  const filesystemRoot = parse(f.root).root;
  assert.throws(() => buildDashboard({ root: f.dashboard, sourceRoot: f.root, expectedHead: f.expectedHead,
    args: ['--outDir', filesystemRoot] }), /isolated --outDir/);
  assert.throws(() => f.publish(filesystemRoot), /isolated from the live directory/);
  assert.match(readFileSync(join(f.destinationRoot, 'index.html'), 'utf8'), /assets\/old.js/);
});

test('an option terminator cannot hide the isolated output argument from Vite', t => {
  const f = fixture(t);
  f.publish(f.candidate('old'));
  f.put(join(f.dashboard, 'node_modules', 'vite', 'bin', 'vite.js'),
    'throw new Error("The option guard must run before Vite");');
  for (const args of [['--'], ['--outDir', join(f.root, 'scratch'), '--']]) {
    assert.throws(() => buildDashboard({ root: f.dashboard, sourceRoot: f.root,
      expectedHead: f.expectedHead, args }), /option terminator/);
  }
  assert.match(readFileSync(join(f.destinationRoot, 'index.html'), 'utf8'), /assets\/old.js/);
});

test('public build information contains only the safe revision and timestamp fields', t => {
  const f = fixture(t);
  const artifact = f.candidate('new');
  const info = join(artifact, 'build-info.json');
  f.put(info, JSON.stringify({ ...JSON.parse(readFileSync(info, 'utf8')), sourcePath: '/local/private/path', localValue: 'private fixture' }));
  f.publish(artifact);
  assert.deepEqual(JSON.parse(readFileSync(join(f.destinationRoot, 'build-info.json'), 'utf8')),
    { sourceRevision: f.expectedHead, buildStartedAt: '2026-10-03T10:00:00.000Z' });
});

test('source fingerprints cover source changes and exclude local environment files', t => {
  const f = fixture(t);
  const original = sourceFingerprint(f.dashboard);
  f.put(join(f.dashboard, '.env'), 'LOCAL_TEST_VALUE=ignored');
  f.put(join(f.dashboard, 'public', '.env.local'), 'LOCAL_TEST_VALUE=ignored');
  assert.equal(sourceFingerprint(f.dashboard), original);
  f.put(join(f.dashboard, 'src', 'entry.js'), 'export const current = false;');
  assert.notEqual(sourceFingerprint(f.dashboard), original);
});

test('changing source during the build prevents publication', t => {
  const f = fixture(t);
  f.publish(f.candidate('old'));
  const vite = join(f.dashboard, 'node_modules', 'vite', 'bin', 'vite.js');
  f.put(vite, readFileSync(vite, 'utf8') + '\nwriteFileSync("src/entry.js", "changed while building");');
  assert.throws(() => buildDashboard({ root: f.dashboard, args: [], sourceRoot: f.root, expectedHead: f.expectedHead }),
    /sources changed during the build/);
  assert.match(readFileSync(join(f.destinationRoot, 'index.html'), 'utf8'), /assets\/old.js/);
});
