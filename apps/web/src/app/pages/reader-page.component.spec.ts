import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import { demoChapters } from '../core/demo-story';
import { ReaderPageComponent } from './reader-page.component';

function createReaderHarness() {
  const router = { navigate: vi.fn() };
  const component = Object.assign(Object.create(ReaderPageComponent.prototype), {
    chapterIndex: signal(0),
    chapters: demoChapters,
    tocOpen: signal(false),
    settingsOpen: signal(false),
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
