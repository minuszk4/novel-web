import { createReadStream, existsSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const directory = resolve(fileURLToPath(new URL('.', import.meta.url)), '../../dist/web/browser');
const indexFile = resolve(directory, 'index.html');
const mimeTypes = new Map([
  ['.css', 'text/css; charset=utf-8'],
  ['.html', 'text/html; charset=utf-8'],
  ['.ico', 'image/x-icon'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.svg', 'image/svg+xml'],
  ['.webp', 'image/webp'],
  ['.woff', 'font/woff'],
  ['.woff2', 'font/woff2'],
]);

if (!existsSync(indexFile)) {
  throw new Error(`Production build not found: ${indexFile}. Run npm run build first.`);
}

const server = createServer((request, response) => {
  let pathname;
  try {
    pathname = decodeURIComponent(new URL(request.url ?? '/', 'http://127.0.0.1').pathname);
  } catch {
    response.writeHead(400).end('Bad request');
    return;
  }

  const target = resolve(directory, `.${pathname}`);
  if (target !== directory && !target.startsWith(`${directory}${sep}`)) {
    response.writeHead(403).end('Forbidden');
    return;
  }

  const file = existsSync(target) && statSync(target).isFile() ? target : null;
  if (!file && extname(pathname)) {
    response.writeHead(404).end('Not found');
    return;
  }

  const selectedFile = file ?? indexFile;
  response.writeHead(200, {
    'Cache-Control': 'no-store',
    'Content-Type': mimeTypes.get(extname(selectedFile)) ?? 'application/octet-stream',
    'X-Content-Type-Options': 'nosniff',
  });
  createReadStream(selectedFile).pipe(response);
});

server.listen(4310, '127.0.0.1');

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
