import { randomBytes } from 'node:crypto';
import { existsSync } from 'node:fs';
import net from 'node:net';
import { resolve } from 'node:path';
import { spawn } from 'node:child_process';

const apiDirectory = resolve('..', 'api');
if (!existsSync(resolve(apiDirectory, '.env'))) {
  process.stderr.write(
    'Copy apps/api/.env.example to apps/api/.env, set matching local database passwords, then retry.\n',
  );
  process.exit(1);
}

const projectName = `novel-web-full-stack-smoke-${process.pid}-${randomBytes(3).toString('hex')}`;
const webPort = await findFreePort();
const postgresPort = await findFreePort();
const webOrigin = `http://localhost:${webPort}`;
const environment = {
  ...process.env,
  POSTGRES_PORT: String(postgresPort),
  WEB_PORT: String(webPort),
  STACK_GOOGLE_CALLBACK_ORIGIN: webOrigin,
  STACK_GOOGLE_AUTH_CREDENTIAL_CALLBACK_URI: `${webOrigin}/api/v1/auth/google/credential`,
  STACK_CORS_ALLOWED_ORIGINS: webOrigin,
  FULL_STACK_URL: webOrigin,
};
const composeArgs = [
  'compose',
  '--project-name',
  projectName,
  '-f',
  'compose.yaml',
  '--profile',
  'full-stack',
];
let resultCode = 1;
let stackStarted = false;

try {
  const upCode = await run(
    'docker',
    [...composeArgs, 'up', '--build', '--detach', '--wait', '--wait-timeout', '300'],
    { cwd: apiDirectory, env: environment },
  );
  if (upCode !== 0) throw new Error(`The full-stack containers failed to start (${upCode}).`);
  stackStarted = true;
  const stagingCode = await run(
    'docker',
    [
      ...composeArgs,
      'exec',
      '--no-TTY',
      'api',
      'sh',
      '-c',
      'test -w /var/lib/novel/import-staging',
    ],
    { cwd: apiDirectory, env: environment },
  );
  if (stagingCode !== 0)
    throw new Error('The API cannot write to its persistent import staging volume.');
  resultCode = await run(
    process.execPath,
    ['node_modules/@playwright/test/cli.js', 'test', '--config=playwright.full-stack.config.ts'],
    { cwd: process.cwd(), env: environment },
  );
} catch (error) {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
} finally {
  if (stackStarted) {
    const downCode = await run(
      'docker',
      [...composeArgs, 'down', '--volumes', '--remove-orphans'],
      { cwd: apiDirectory, env: environment },
    );
    if (downCode !== 0 && resultCode === 0) resultCode = downCode;
  } else {
    await run('docker', [...composeArgs, 'down', '--volumes', '--remove-orphans'], {
      cwd: apiDirectory,
      env: environment,
    });
  }
}

process.exitCode = resultCode;

function findFreePort() {
  return new Promise((resolvePort, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      server.close((error) => {
        if (error) reject(error);
        else if (address && typeof address !== 'string') resolvePort(address.port);
        else reject(new Error('Could not reserve a loopback port.'));
      });
    });
  });
}

function run(command, args, options) {
  return new Promise((resolveExit) => {
    const child = spawn(command, args, { ...options, stdio: 'inherit' });
    child.once('error', (error) => {
      process.stderr.write(`${error.message}\n`);
      resolveExit(1);
    });
    child.once('exit', (code) => resolveExit(code ?? 1));
  });
}
