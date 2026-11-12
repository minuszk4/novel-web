import { readdir, readFile } from 'node:fs/promises';
import { dirname, extname, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const SINK_RULES = [
  { name: 'Angular innerHTML binding', pattern: /\[\s*innerHTML\s*\]/i },
  {
    name: 'DOM innerHTML/outerHTML assignment',
    pattern:
      /(?:\b(?:innerHTML|outerHTML)\b|\[\s*['"`](?:innerHTML|outerHTML)['"`]\s*\])\s*(?:\+|\|\|)?=/i,
  },
  { name: 'iframe srcdoc HTML binding', pattern: /(?:\[\s*srcdoc\s*\]|\bsrcdoc\s*=)/i },
  { name: 'insertAdjacentHTML', pattern: /\binsertAdjacentHTML\s*\(/i },
  { name: 'createContextualFragment', pattern: /\bcreateContextualFragment\s*\(/i },
  { name: 'document.write', pattern: /\bdocument\s*\.\s*write(?:ln)?\s*\(/i },
  { name: 'bypassSecurityTrustHtml', pattern: /\bbypassSecurityTrustHtml\s*\(/i },
  {
    name: 'Renderer2 HTML property assignment',
    pattern: /\bsetProperty\s*\([^,]+,\s*['"`](?:innerHTML|outerHTML)['"`]/i,
  },
];

export function findUnsafeHtmlSinks(source) {
  const findings = [];

  for (const rule of SINK_RULES) {
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
    for (const finding of findUnsafeHtmlSinks(source)) {
      violations.push({
        file: relative(applicationRoot, file).split(sep).join('/'),
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
