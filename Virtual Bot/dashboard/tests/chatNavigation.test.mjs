import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readChatSelection, rememberChatSelection } from '../src/panels/chat/chatNavigation.ts';

test('returning to Chat restores the selection from this loaded page', () => {
  const page = {};
  rememberChatSelection(page, 'chosen-chat');
  assert.equal(readChatSelection(page), 'chosen-chat');
  assert.equal(readChatSelection(page), 'chosen-chat');
});

test('a new visit never opens an arbitrary saved conversation', () => {
  const oldPage = {};
  rememberChatSelection(oldPage, 'old-chat');
  assert.equal(readChatSelection({}), '');
});

test('new conversation remains selected when returning from another section', () => {
  const page = {};
  rememberChatSelection(page, 'chosen-chat');
  rememberChatSelection(page, '');
  assert.equal(readChatSelection(page), '');
});

test('project selections are scoped and main Chat remembers the last chosen one', () => {
  const page = {};
  rememberChatSelection(page, 'cats-chat', 'cats');
  rememberChatSelection(page, 'dogs-chat', 'dogs');
  assert.equal(readChatSelection(page, 'cats'), 'cats-chat');
  assert.equal(readChatSelection(page, 'dogs'), 'dogs-chat');
  assert.equal(readChatSelection(page), 'dogs-chat');
  assert.equal(readChatSelection(page, 'new-project'), '');
});
