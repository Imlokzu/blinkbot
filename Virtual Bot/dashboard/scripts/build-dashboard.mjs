import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { canonicalPath, pathsOverlap, publishDashboard, sourceFingerprint, sourceHead } from './publish-dashboard.mjs';

const dashboardRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');

export function buildDashboard({ root = dashboardRoot, args = process.argv.slice(2),
  sourceRoot = process.env.DASHBOARD_SOURCE_ROOT || resolve(root, '..', '..'),
  expectedHead = process.env.DASHBOARD_EXPECTED_HEAD || sourceHead(sourceRoot) } = {}) {
  if (args.includes('--')) throw new Error('A -- option terminator is not supported for dashboard builds');
  const buildStartedAt = new Date().toISOString();
  const live = join(sourceRoot, 'Virtual Bot', 'static', 'dash');
  const outputOptions = args.map((argument, index) => argument === '--outDir' || argument.startsWith('--outDir=') ? index : -1).filter(index => index >= 0);
  if (outputOptions.length > 1) throw new Error('Specify --outDir only once');
  const outIndex = outputOptions[0] ?? -1;
  const requested = outIndex < 0 ? null : args[outIndex].includes('=') ? args[outIndex].slice('--outDir='.length) : args[outIndex + 1];
  if (outIndex >= 0 && (!requested || requested.startsWith('--'))) throw new Error('--outDir requires a directory');
  const output = requested ? canonicalPath(resolve(root, requested)) : null;
  for (const protectedPath of [live, ...['src', 'public', 'scripts', 'node_modules'].map(entry => join(root, entry))]) {
    const protectedRoot = canonicalPath(protectedPath);
    if (output && pathsOverlap(output, protectedRoot)) {
      throw new Error('Choose an isolated --outDir; use npm run build without it to safely publish the live dashboard');
    }
  }
  if (sourceHead(sourceRoot) !== expectedHead) throw new Error('Dashboard source revision changed before the build');
  execFileSync(process.execPath, [join(root, 'scripts', 'copy-excalidraw-fonts.mjs')], { cwd: root, stdio: 'inherit' });
  const realDashboard = join(sourceRoot, 'Virtual Bot', 'dashboard');
  const ownFingerprint = sourceFingerprint(root);
  const realFingerprint = sourceFingerprint(realDashboard);
  function verifySource() {
    if (sourceHead(sourceRoot) !== expectedHead || sourceFingerprint(root) !== ownFingerprint ||
        sourceFingerprint(realDashboard) !== realFingerprint) {
      throw new Error('Dashboard sources changed during the build; rebuild before publishing');
    }
  }
  const temporary = output ? null : mkdtempSync(join(tmpdir(), 'claude-dashboard-'));
  const artifactRoot = output || join(temporary, 'dash');
  try {
    const viteArgs = args.filter((argument, index) => index !== outIndex && !(index === outIndex + 1 && args[outIndex] === '--outDir'));
    viteArgs.push('--outDir', artifactRoot);
    execFileSync(process.execPath, [join(root, 'node_modules', 'vite', 'bin', 'vite.js'), 'build', ...viteArgs],
      { cwd: root, stdio: 'inherit' });
    verifySource();
    mkdirSync(artifactRoot, { recursive: true });
    writeFileSync(join(artifactRoot, 'build-info.json'), JSON.stringify({ sourceRevision: expectedHead, buildStartedAt }) + '\n');
    if (!output) publishDashboard({ artifactRoot, destinationRoot: live, sourceRoot, expectedHead, verifySource });
    return output || live;
  } finally {
    if (temporary) rmSync(temporary, { recursive: true, force: true });
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { buildDashboard(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
