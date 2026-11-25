import { createServer } from 'node:http';

const port = Number(process.env['STATIC_HOST_MOCK_API_PORT'] ?? 18081);
const server = createServer((request, response) => {
  if (request.url === '/__ready') {
    response.writeHead(204).end();
    return;
  }

  if (request.url?.startsWith('/api/')) {
    if (request.url.startsWith('/api/v1/static-host-probe-upstream-failure')) {
      request.socket.destroy();
      return;
    }

    if (request.url === '/api/v1/static-host-probe-error') {
      response.writeHead(403, {
        'Cache-Control': 'no-store',
        'Content-Type': 'application/problem+json; charset=utf-8',
        Pragma: 'no-cache',
        'X-Static-Host-Proxy': 'reached-api',
      });
      response.end(
        JSON.stringify({
          type: 'about:blank',
          title: 'Forbidden',
          status: 403,
          detail: 'This endpoint is not available in the current API slice.',
          code: 'FORBIDDEN',
        }),
      );
      return;
    }

    response.writeHead(200, {
      'Cache-Control': 'no-store',
      'Content-Type': 'application/json; charset=utf-8',
      Pragma: 'no-cache',
      'X-Static-Host-Proxy': 'reached-api',
    });
    response.end(JSON.stringify({ proxied: true, path: request.url }));
    return;
  }

  response.writeHead(404).end();
});

server.listen(port, '0.0.0.0');
