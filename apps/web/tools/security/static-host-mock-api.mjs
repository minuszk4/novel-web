import { createServer } from 'node:http';

const port = Number(process.env['STATIC_HOST_MOCK_API_PORT'] ?? 18081);
const server = createServer((request, response) => {
  if (request.url === '/__ready') {
    response.writeHead(204).end();
    return;
  }

  if (request.url?.startsWith('/api/')) {
    response.writeHead(200, {
      'Cache-Control': 'no-store',
      'Content-Type': 'application/json; charset=utf-8',
      'X-Static-Host-Proxy': 'reached-api',
    });
    response.end(JSON.stringify({ proxied: true, path: request.url }));
    return;
  }

  response.writeHead(404).end();
});

server.listen(port, '0.0.0.0');
