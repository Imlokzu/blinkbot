import assert from 'node:assert/strict';
import test from 'node:test';
import { siteIconCandidates, siteIconUrl } from '../src/panels/chat/siteIcons.ts';

test('website icons use the actual source origin without article paths or query data', () => {
  assert.equal(siteIconUrl('https://www.python.org/docs/guide?topic=private#section', 'python.org'),
    'https://www.python.org/favicon.ico');
  assert.equal(siteIconUrl('https://uk.wikipedia.org/wiki/Ada?tracking=1', 'uk.wikipedia.org'),
    'https://uk.wikipedia.org/favicon.ico');
  assert.equal(siteIconUrl('https://EXAMPLE.org:443/a/nested/page', 'example.org'),
    'https://example.org/favicon.ico');
  assert.equal(siteIconUrl('https://example.org:8443/a', 'example.org'),
    'https://example.org:8443/favicon.ico');
  assert.equal(siteIconUrl('https://xn--bcher-kva.de/article'),
    'https://xn--bcher-kva.de/favicon.ico');
});

test('the icon host must match the source host, with only www and case normalized', () => {
  assert.equal(siteIconUrl('https://www.example.org/article', 'EXAMPLE.ORG'),
    'https://www.example.org/favicon.ico');
  assert.equal(siteIconUrl('https://docs.example.org/article', 'example.org'), null);
  assert.equal(siteIconUrl('https://example.org/article', 'other.org'), null);
  assert.equal(siteIconUrl('https://example.org/article', ''), null);
});

test('unsafe schemes, credentials, malformed URLs and local names keep the local fallback', () => {
  const urls = [
    '', '/article', 'https:example.org', 'https://', 'not a URL',
    'http://example.org/article', 'ftp://example.org/article',
    'javascript:alert(1)', 'data:image/png;base64,AAAA',
    'https://user:password@example.org/article', 'https://user@example.org/article',
    'https://localhost/article', 'https://sub.localhost/article',
    'https://robot.local/article', 'https://robot.lan/article',
    'https://server.internal/article', 'https://intranet/article',
    'https://router.home/article', 'https://server.localdomain/article',
    'https://fixture.test/article', 'https://fixture.invalid/article',
    'https://service.onion/article', 'https://bad_label.example.org/article',
    'https://-bad.example.org/article', 'https://example.org:invalid/article',
  ];
  for (const url of urls) assert.equal(siteIconUrl(url), null, url);
});

test('IP literals never make icon requests, including browser-normalized representations', () => {
  const hosts = [
    '127.0.0.1', '192.168.1.12', '10.0.0.1', '172.16.0.1', '169.254.169.254',
    '8.8.8.8', '127.1', '2130706433', '0x7f000001', '0177.0.0.1',
    '[::1]', '[fe80::1]', '[2001:4860:4860::8888]',
  ];
  for (const host of hosts) assert.equal(siteIconUrl(`https://${host}/article`), null, host);
});

test('blocked origin icons have two bounded host-only cached fallbacks', () => {
  const urls = siteIconCandidates('https://www.wikipedia.org/wiki/Private?query=personal#fragment', 'wikipedia.org');
  assert.deepEqual(urls, [
    'https://www.wikipedia.org/favicon.ico',
    'https://www.google.com/s2/favicons?domain=www.wikipedia.org&sz=32',
    'https://icons.duckduckgo.com/ip3/www.wikipedia.org.ico',
  ]);
  assert.ok(urls.every(url => !url.includes('personal') && !url.includes('Private')));
  assert.deepEqual(siteIconCandidates('https://www.wikipedia.org/another?query=different', 'wikipedia.org'), urls,
    'article changes must not reset a working cached icon');
});

test('public HTTP citations get HTTPS icon candidates while source links stay untouched', () => {
  const source = 'http://example.org/article?private=1';
  assert.equal(siteIconCandidates(source, 'example.org')[0], 'https://example.org/favicon.ico');
  assert.equal(siteIconCandidates('http://example.org:80/article')[0], 'https://example.org/favicon.ico');
  assert.equal(source, 'http://example.org/article?private=1');
});

test('invalid/private/credentialled sources never reach a directory service', () => {
  for (const source of ['https://127.0.0.1/article', 'https://localhost', 'http://robot.local/article',
    'https://user:password@example.org/article', 'file:///article', 'data:text/plain,test',
    'javascript:alert(1)', '/article', 'https:example.org', 'https://home.arpa/article',
    'https://router.home.arpa/article', 'http://device.HOME.ARPA./article']) {
    assert.deepEqual(siteIconCandidates(source), [], source);
  }
  assert.deepEqual(siteIconCandidates('https://example.org/article', 'different.org'), []);
});

test('home-network names do not exclude similarly named public hosts', () => {
  assert.equal(siteIconCandidates('https://home.arpa.example.org/article').length, 3);
});

test('custom ports keep their own favicon instead of guessing the parent site brand', () => {
  assert.deepEqual(siteIconCandidates('https://example.org:8443/article', 'example.org'),
    ['https://example.org:8443/favicon.ico']);
});
