import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, realpathSync, renameSync, rmSync, rmdirSync, writeFileSync } from 'node:fs';
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';

export const sourceHead = root => execFileSync('git', ['-C', root, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();

export function canonicalPath(path) {
  const absolute = resolve(path);
  if (existsSync(absolute)) return realpathSync(absolute);
  return join(canonicalPath(dirname(absolute)), basename(absolute));
}

export function pathsOverlap(left, right) {
  const inside = (child, parent) => {
    const path = relative(parent, child);
    return path === '' || (path !== '..' && !path.startsWith(`..${sep}`) && !isAbsolute(path));
  };
  return inside(left, right) || inside(right, left);
}

// Read only build inputs: never crawl the repository or inspect environment files.
export function sourceFingerprint(dashboardRoot) {
  const hash = createHash('sha256');
  function visit(path) {
    if (!existsSync(path)) return;
    for (const entry of readdirSync(path, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      if (entry.name.startsWith('.')) continue;
      const child = join(path, entry.name);
      if (entry.isDirectory()) visit(child);
      else if (entry.isFile()) hash.update(relative(dashboardRoot, child)).update('\0').update(readFileSync(child)).update('\0');
    }
  }
  for (const folder of ['src', 'public']) visit(join(dashboardRoot, folder));
  for (const entry of readdirSync(dashboardRoot).sort()) {
    if (/^(index\.html|package(?:-lock)?\.json|vite\.config\.[cm]?[jt]s|tsconfig(?:\.[^.]+)?\.json)$/.test(entry)) {
      hash.update(entry).update('\0').update(readFileSync(join(dashboardRoot, entry))).update('\0');
    }
  }
  return hash.digest('hex');
}

function buildInfo(root) {
  const info = JSON.parse(readFileSync(join(root, 'build-info.json'), 'utf8'));
  if (!/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(info.sourceRevision) || typeof info.buildStartedAt !== 'string' ||
      !Number.isFinite(Date.parse(info.buildStartedAt))) {
    throw new Error('Invalid dashboard build information');
  }
  return { sourceRevision: info.sourceRevision, buildStartedAt: info.buildStartedAt };
}

export function publishDashboard({ artifactRoot, destinationRoot, sourceRoot, expectedHead, verifySource = () => {} }) {
  artifactRoot = canonicalPath(artifactRoot);
  destinationRoot = canonicalPath(destinationRoot);
  if (pathsOverlap(artifactRoot, destinationRoot)) {
    throw new Error('Dashboard artifacts must be isolated from the live directory');
  }
  const candidate = buildInfo(artifactRoot);
  const index = readFileSync(join(artifactRoot, 'index.html'));
  mkdirSync(destinationRoot, { recursive: true });
  const lock = join(destinationRoot, '.publish-lock');
  try { mkdirSync(lock); } catch (error) {
    if (error.code === 'EEXIST') throw new Error('Another dashboard publication is in progress; retry after it finishes');
    throw error;
  }
  const temporaries = new Set();
  function atomicFile(from, to, content) {
    mkdirSync(dirname(to), { recursive: true });
    const temporary = join(dirname(to), `.publish-${randomUUID()}`);
    temporaries.add(temporary);
    if (content === undefined) copyFileSync(from, temporary);
    else writeFileSync(temporary, content);
    renameSync(temporary, to);
    temporaries.delete(temporary);
  }
  function checkSource() {
    if (candidate.sourceRevision !== expectedHead || sourceHead(sourceRoot) !== expectedHead) {
      throw new Error('Dashboard source revision changed; rebuild before publishing');
    }
    verifySource();
  }
  try {
    checkSource();
    if (existsSync(join(destinationRoot, 'build-info.json')) &&
        Date.parse(buildInfo(destinationRoot).buildStartedAt) >= Date.parse(candidate.buildStartedAt)) {
      throw new Error('A newer dashboard build is already installed');
    }
    function copyTree(from, to) {
      for (const entry of readdirSync(from, { withFileTypes: true })) {
        if (from === artifactRoot && ['index.html', 'sw.js', 'registerSW.js', 'build-info.json'].includes(entry.name)) continue;
        if (entry.isDirectory()) copyTree(join(from, entry.name), join(to, entry.name));
        else if (entry.isFile()) atomicFile(join(from, entry.name), join(to, entry.name));
        else throw new Error('Dashboard artifacts must contain regular files and directories');
      }
    }
    // Keep previous hashed chunks alive for tabs that still hold the old index.
    copyTree(artifactRoot, destinationRoot);
    checkSource();
    const replaced = [];
    try {
      for (const entry of ['registerSW.js', 'sw.js', 'build-info.json']) {
        if (!existsSync(join(artifactRoot, entry))) continue;
        const target = join(destinationRoot, entry);
        const previous = existsSync(target) ? readFileSync(target) : null;
        atomicFile(join(artifactRoot, entry), target, entry === 'build-info.json' ? JSON.stringify(candidate) + '\n' : undefined);
        replaced.push([target, previous]);
      }
      // All chunks and the matching worker exist before the shell changes atomically.
      atomicFile(null, join(destinationRoot, 'index.html'), index);
    } catch (error) {
      // A failed shell switch must not advertise a newer version or block its retry.
      for (const [target, previous] of replaced.reverse()) {
        if (previous === null) rmSync(target, { force: true });
        else atomicFile(null, target, previous);
      }
      throw error;
    }
  } finally {
    for (const temporary of temporaries) rmSync(temporary, { force: true });
    rmdirSync(lock);
  }
}
