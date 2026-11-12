import assert from 'node:assert/strict';
import test from 'node:test';
import { findUnsafeHtmlSinks } from './check-angular-html-sinks.mjs';

test('allows text interpolation and ordinary text bindings', () => {
  const source = '<p>{{ chapter.title }}</p> <p [attr.title]="chapter.title">';

  assert.deepEqual(findUnsafeHtmlSinks(source), []);
});

test('rejects Angular raw HTML bindings', () => {
  for (const source of [
    '<section [innerHTML]="chapter.body"></section>',
    '<section [outerHTML]="chapter.body"></section>',
  ]) {
    assert.equal(findUnsafeHtmlSinks(source)[0]?.name, 'Angular raw HTML binding');
  }
});

test('rejects DOM HTML assignments and insertion APIs', () => {
  for (const source of [
    'element.innerHTML = chapter.body;',
    "element['innerHTML'] = chapter.body;",
    'element.outerHTML += chapter.body;',
    '<iframe [srcdoc]="chapter.body"></iframe>',
    'frame.srcdoc = chapter.body;',
    "frame.setAttribute('srcdoc', chapter.body);",
    "element.insertAdjacentHTML('beforeend', chapter.body);",
    'range.createContextualFragment(chapter.body);',
    "new DOMParser().parseFromString(chapter.body, 'text/html');",
    'element.setHTMLUnsafe(chapter.body);',
    'document.write(chapter.body);',
  ]) {
    assert.equal(findUnsafeHtmlSinks(source).length, 1, source);
  }
});

test('rejects inline event handler content', () => {
  assert.equal(findUnsafeHtmlSinks('<img onerror="run()">', 'fixture.html').length, 1);
  assert.equal(findUnsafeHtmlSinks("element.setAttribute('onerror', code);").length, 1);
  assert.deepEqual(findUnsafeHtmlSinks('element.onerror = run;'), []);
});

test('allows DOM event callbacks that do not parse HTML', () => {
  for (const source of [
    'element.onerror = handleError;',
    "element.addEventListener('error', handleError);",
  ]) {
    assert.deepEqual(findUnsafeHtmlSinks(source), [], source);
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
