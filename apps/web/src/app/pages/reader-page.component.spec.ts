import { signal } from '@angular/core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { demoChapters } from '../core/demo-story';
import { ReaderPageComponent } from './reader-page.component';

afterEach(() => {
  document.querySelectorAll('[data-reader-shortcut-test]').forEach((element) => element.remove());
});

function createReaderHarness() {
  const router = { navigate: vi.fn() };
  const component = Object.assign(Object.create(ReaderPageComponent.prototype), {
    chapterIndex: signal(0),
    chapters: demoChapters,
    tocOpen: signal(false),
    settingsOpen: signal(false),
    panelReturnFocus: null,
    router,
  }) as ReaderPageComponent;

  return { component, router };
}

function keydown(key: string, init: KeyboardEventInit = {}): KeyboardEvent {
  return new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });
}

describe('ReaderPageComponent keyboard shortcuts', () => {
  it('opens the table of contents with Alt+Shift+T and closes reader settings', () => {
    const { component } = createReaderHarness();
    component['settingsOpen'].set(true);
    const event = keydown('t', { altKey: true, shiftKey: true });

    component.onKeydown(event);

    expect(component['tocOpen']()).toBe(true);
    expect(component['settingsOpen']()).toBe(false);
    expect(event.defaultPrevented).toBe(true);
  });

  it('moves to the next chapter with Alt+Shift+J', () => {
    const { component, router } = createReaderHarness();
    const event = keydown('j', { altKey: true, shiftKey: true });

    component.onKeydown(event);

    expect(router.navigate).toHaveBeenCalledWith(['/read/demo-story', 'chapter-2']);
    expect(event.defaultPrevented).toBe(true);
  });

  it('moves to the previous chapter with Alt+Shift+K', () => {
    const { component, router } = createReaderHarness();
    component['chapterIndex'].set(1);
    const event = keydown('k', { altKey: true, shiftKey: true });

    component.onKeydown(event);

    expect(router.navigate).toHaveBeenCalledWith(['/read/demo-story', 'chapter-1']);
    expect(event.defaultPrevented).toBe(true);
  });

  it('does not consume ordinary typing or shortcuts from a text input', () => {
    const { component } = createReaderHarness();
    const ordinaryKey = keydown('t');

    component.onKeydown(ordinaryKey);

    const input = document.createElement('input');
    const inputShortcut = keydown('t', { altKey: true, shiftKey: true });
    input.dispatchEvent(inputShortcut);
    component.onKeydown(inputShortcut);

    expect(component['tocOpen']()).toBe(false);
    expect(ordinaryKey.defaultPrevented).toBe(false);
    expect(inputShortcut.defaultPrevented).toBe(false);
  });

  it('does not consume shortcuts that include Control or Meta', () => {
    const { component } = createReaderHarness();
    const controlShortcut = keydown('t', { altKey: true, ctrlKey: true, shiftKey: true });
    const metaShortcut = keydown('t', { altKey: true, metaKey: true, shiftKey: true });

    component.onKeydown(controlShortcut);
    component.onKeydown(metaShortcut);

    expect(component['tocOpen']()).toBe(false);
    expect(controlShortcut.defaultPrevented).toBe(false);
    expect(metaShortcut.defaultPrevented).toBe(false);
  });
});

describe('ReaderPageComponent modal panel focus', () => {
  function appendPanel(id: string) {
    const panel = document.createElement('aside');
    panel.id = id;
    panel.dataset['readerShortcutTest'] = 'true';
    const first = document.createElement('button');
    const last = document.createElement('button');
    panel.append(first, last);
    document.body.append(panel);
    return { panel, first, last };
  }

  it('wraps Tab and Shift+Tab inside an open panel', () => {
    const { component } = createReaderHarness();
    const { first, last } = appendPanel('reader-toc-panel');
    component['tocOpen'].set(true);

    first.focus();
    const reverseTab = keydown('Tab', { shiftKey: true });
    component.onKeydown(reverseTab);
    expect(reverseTab.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(last);

    last.focus();
    const forwardTab = keydown('Tab');
    component.onKeydown(forwardTab);
    expect(forwardTab.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(first);
  });

  it('restores focus to the opener when Escape closes a panel', () => {
    const { component } = createReaderHarness();
    const opener = document.createElement('button');
    opener.dataset['readerShortcutTest'] = 'true';
    document.body.append(opener);
    appendPanel('reader-toc-panel');
    component['tocOpen'].set(true);
    component['panelReturnFocus'] = opener;

    const escape = keydown('Escape');
    component.onKeydown(escape);

    expect(component['tocOpen']()).toBe(false);
    expect(component['settingsOpen']()).toBe(false);
    expect(escape.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(opener);
  });
});
