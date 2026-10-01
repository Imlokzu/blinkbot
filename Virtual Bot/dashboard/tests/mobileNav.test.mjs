import assert from 'node:assert/strict';
import test from 'node:test';
import { MOBILE_SECTION_IDS } from '../src/components/shell/mobileNavData.ts';

test('phone navigation keeps the core destinations one tap away', () => {
  assert.deepEqual(MOBILE_SECTION_IDS, ['overview', 'chat', 'memory', 'settings']);
  assert.equal(new Set(MOBILE_SECTION_IDS).size, MOBILE_SECTION_IDS.length);
});
