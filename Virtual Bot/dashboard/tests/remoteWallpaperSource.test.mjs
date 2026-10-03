import assert from 'node:assert/strict';
import test from 'node:test';
import { probeWallpaperSource } from '../src/panels/chat/remoteWallpaperSource.ts';

function mediaFixture(context) {
  const originals = { image: globalThis.Image, document: globalThis.document };
  const images = [];
  const videos = [];
  class Media extends EventTarget {
    src = '';
    naturalWidth = 1280;
    naturalHeight = 720;
    videoWidth = 1280;
    videoHeight = 720;
    playCalls = 0;
    pauseCalls = 0;
    loadCalls = 0;
    removeAttribute(name) { if (name === 'src') this.src = ''; }
    play() { this.playCalls++; }
    pause() { this.pauseCalls++; }
    load() { this.loadCalls++; }
  }
  globalThis.Image = class extends Media { constructor() { super(); images.push(this); } };
  globalThis.document = { createElement: tag => {
    assert.equal(tag, 'video');
    const video = new Media();
    videos.push(video);
    return video;
  } };
  context.after(() => {
    if (originals.image === undefined) delete globalThis.Image;
    else globalThis.Image = originals.image;
    if (originals.document === undefined) delete globalThis.document;
    else globalThis.document = originals.document;
  });
  return { images, videos };
}

test('a link check requires a real image with positive dimensions', async context => {
  const { images } = mediaFixture(context);
  const controller = new AbortController();
  const good = probeWallpaperSource('https://media.test/image.jpg', 'image', controller.signal);
  images[0].dispatchEvent(new Event('load'));
  await good;
  assert.equal(images[0].src, '', 'completed checks release their network source');

  const bad = probeWallpaperSource('https://media.test/not-an-image', 'image', controller.signal);
  images[1].naturalWidth = 0;
  images[1].dispatchEvent(new Event('load'));
  await assert.rejects(bad, /wallpaperSourceUnavailable/);
});

test('video checks wait for a decoded frame and never start playback', async context => {
  const { videos } = mediaFixture(context);
  const pending = probeWallpaperSource('https://media.test/video.mp4', 'video', new AbortController().signal);
  const video = videos[0];
  assert.equal(video.muted, true);
  assert.equal(video.defaultMuted, true);
  let completed = false;
  void pending.then(() => { completed = true; });
  video.dispatchEvent(new Event('loadedmetadata'));
  await Promise.resolve();
  assert.equal(completed, false, 'metadata alone cannot prove the browser can decode the video');
  video.dispatchEvent(new Event('loadeddata'));
  await pending;
  assert.equal(video.playCalls, 0);
  assert.equal(video.src, '');
  assert.equal(video.pauseCalls, 1);
});

test('unavailable media fails without waiting for the deadline', async context => {
  const { videos } = mediaFixture(context);
  const pending = probeWallpaperSource('https://media.test/missing.mp4', 'video', new AbortController().signal);
  videos[0].dispatchEvent(new Event('error'));
  await assert.rejects(pending, /wallpaperSourceUnavailable/);
  assert.equal(videos[0].src, '');
});

test('an unresponsive link check expires and releases its media source', async context => {
  const { images } = mediaFixture(context);
  context.mock.timers.enable({ apis: ['setTimeout'] });
  const pending = probeWallpaperSource('https://media.test/hung.jpg', 'image', new AbortController().signal);
  const rejected = assert.rejects(pending, /wallpaperSourceUnavailable/);
  context.mock.timers.tick(10_000);
  await rejected;
  assert.equal(images[0].src, '');
});

test('cancellation rejects the pending check and ignores late media events', async context => {
  const { images } = mediaFixture(context);
  const controller = new AbortController();
  const pending = probeWallpaperSource('https://media.test/slow.jpg', 'image', controller.signal);
  controller.abort();
  images[0].dispatchEvent(new Event('load'));
  await assert.rejects(pending, { name: 'AbortError' });
  assert.equal(images[0].src, '');

  const alreadyCancelled = probeWallpaperSource('https://media.test/unused.jpg', 'image', controller.signal);
  await assert.rejects(alreadyCancelled, { name: 'AbortError' });
  assert.equal(images[1].src, '', 'an already-cancelled check never attaches a URL');
});
