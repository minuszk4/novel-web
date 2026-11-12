import { readdir, readFile } from 'node:fs/promises';
import { dirname, extname, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const SINK_RULES = [
  { name: 'Angular raw HTML binding', pattern: /\[\s*(?:innerHTML|outerHTML)\s*\]/i },
  {
    name: 'DOM innerHTML/outerHTML assignment',
    pattern:
      /(?:\b(?:innerHTML|outerHTML)\b|\[\s*['"`](?:innerHTML|outerHTML)['"`]\s*\])\s*(?:\+|\|\|)?=/i,
  },
  {
    name: 'iframe srcdoc HTML assignment',
    pattern:
      /(?:\[\s*srcdoc\s*\]|\.\s*srcdoc\s*=|\[\s*['"`]srcdoc['"`]\s*\]\s*=|\.\s*setAttribute\s*\(\s*['"`]srcdoc['"`]|(?:^|\s)srcdoc\s*=)/i,
  },
  {
    name: 'HTML parsing or insertion API',
    pattern: /\b(?:insertAdjacentHTML|createContextualFragment|setHTMLUnsafe)\s*\(/i,
  },
  { name: 'DOMParser HTML parsing', pattern: /\bDOMParser\s*\(\s*\)\s*\.\s*parseFromString\s*\(/i },
  {
    name: 'inline event handler insertion',
    pattern: /\.\s*setAttribute\s*\(\s*['"`]on[a-z]+['"`]/i,
  },
  { name: 'document.write', pattern: /\bdocument\s*\.\s*write(?:ln)?\s*\(/i },
  { name: 'bypassSecurityTrustHtml', pattern: /\bbypassSecurityTrustHtml\s*\(/i },
  {
    name: 'Renderer2 HTML property assignment',
    pattern: /\bsetProperty\s*\([^,]+,\s*['"`](?:innerHTML|outerHTML|srcdoc)['"`]/i,
  },
];

export function findUnsafeHtmlSinks(source, filePath = '') {
  const findings = [];
  const rules = [...SINK_RULES];

  if (filePath.toLowerCase().endsWith('.html')) {
    rules.push({
      name: 'inline event handler HTML attribute',
      pattern: /\s+on[a-z]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/i,
    });
  }

  for (const rule of rules) {
    const match = rule.pattern.exec(source);
    if (!match) continue;

    const line = source.slice(0, match.index).split('\n').length;
    findings.push({ name: rule.name, line });
  }

  return findings;
}

async function collectApplicationSources(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];

  for (const entry of entries) {
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await collectApplicationSources(path)));
      continue;
    }

    if (
      !entry.isFile() ||
      !['.html', '.ts'].includes(extname(entry.name)) ||
      entry.name.endsWith('.spec.ts') ||
      entry.name.endsWith('.test.ts')
    ) {
      continue;
    }

    files.push(path);
  }

  return files;
}

export async function scanAngularApplication(applicationRoot) {
  const files = await collectApplicationSources(applicationRoot);
  const violations = [];

  for (const file of files) {
    const source = await readFile(file, 'utf8');
    const relativeFile = relative(applicationRoot, file).split(sep).join('/');
    for (const finding of findUnsafeHtmlSinks(source, relativeFile)) {
      violations.push({
        file: relativeFile,
        ...finding,
      });
    }
  }

  return { fileCount: files.length, violations };
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : null;
if (invokedPath && pathToFileURL(invokedPath).href === import.meta.url) {
  const scriptDirectory = dirname(fileURLToPath(import.meta.url));
  const applicationRoot = resolve(scriptDirectory, '../../src/app');
  const { fileCount, violations } = await scanAngularApplication(applicationRoot);

  if (violations.length > 0) {
    for (const violation of violations) {
      process.stderr.write(
        `${violation.file}:${violation.line}: prohibited HTML sink (${violation.name})\n`,
      );
    }
    process.stderr.write(
      'Render untrusted text with Angular interpolation. Any rich-HTML exception requires an explicit security review.\n',
    );
    process.exitCode = 1;
  } else {
    process.stdout.write(
      `Checked ${fileCount} Angular source files; no prohibited raw HTML sinks found.\n`,
    );
  }
}
