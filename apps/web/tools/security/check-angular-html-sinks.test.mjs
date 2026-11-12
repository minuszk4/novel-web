import assert from 'node:assert/strict';
import test from 'node:test';
import { findUnsafeHtmlSinks } from './check-angular-html-sinks.mjs';

test('allows text interpolation and ordinary text bindings', () => {
  const source = '<p>{{ chapter.title }}</p> <p [attr.title]="chapter.title">';

  assert.deepEqual(findUnsafeHtmlSinks(source), []);
});

test('rejects Angular raw HTML bindings', () => {
  assert.equal(
    findUnsafeHtmlSinks('<section [innerHTML]="chapter.body"></section>')[0]?.name,
    'Angular innerHTML binding',
  );
});

test('rejects DOM HTML assignments and insertion APIs', () => {
  for (const source of [
    'element.innerHTML = chapter.body;',
    "element['innerHTML'] = chapter.body;",
    'element.outerHTML += chapter.body;',
    '<iframe [srcdoc]="chapter.body"></iframe>',
    "element.insertAdjacentHTML('beforeend', chapter.body);",
    'range.createContextualFragment(chapter.body);',
    'document.write(chapter.body);',
  ]) {
    assert.equal(findUnsafeHtmlSinks(source).length, 1, source);
  }
});

test('rejects sanitizer bypass and Renderer2 HTML property assignment', () => {
  for (const source of [
    'sanitizer.bypassSecurityTrustHtml(chapter.body);',
    "renderer.setProperty(element, 'innerHTML', chapter.body);",
  ]) {
    assert.equal(findUnsafeHtmlSinks(source).length, 1, source);
  }
});
