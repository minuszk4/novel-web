import { spawn } from 'node:child_process';

const composeArgs = [
  'compose',
  '--project-name',
  'novel-web-static-host-test',
  '-f',
  'deploy/compose.yaml',
];
const mockApiPort = 18081;
const webPort = 18080;
const testEnvironment = {
  ...process.env,
  API_UPSTREAM: `http://host.docker.internal:${mockApiPort}`,
  FIREBASE_AUTH_DOMAIN: 'novel-static-test.firebaseapp.com',
  STATIC_HOST_LOG_SENTINEL: 'static-host-access-log-sentinel-20261126',
  STATIC_HOST_MOCK_API_PORT: String(mockApiPort),
  STATIC_HOST_URL: `http://127.0.0.1:${webPort}`,
  WEB_PORT: String(webPort),
};
let mockApi;
let testExitCode = 1;

try {
  mockApi = spawn(process.execPath, ['tools/security/static-host-mock-api.mjs'], {
    env: testEnvironment,
    stdio: 'inherit',
  });
  await waitForHttp(`http://127.0.0.1:${mockApiPort}/__ready`, 30_000, mockApi);

  const upResult = await run(
    'docker',
    [...composeArgs, 'up', '--build', '--force-recreate', '-d'],
    {
      env: testEnvironment,
    },
  );
  if (upResult !== 0) throw new Error(`The static web container failed to start (${upResult}).`);
  await waitForHttp(`${testEnvironment.STATIC_HOST_URL}/sign-in`, 180_000);

  testExitCode = await run(
    process.execPath,
    ['node_modules/@playwright/test/cli.js', 'test', '--config=playwright.static-host.config.ts'],
    { env: testEnvironment },
  );
  if (testExitCode === 0) {
    const logs = await capture('docker', [...composeArgs, 'logs', '--no-color', 'web'], {
      env: testEnvironment,
    });
    if (logs.exitCode !== 0) {
      throw new Error('Could not inspect the static host access log during the privacy test.');
    }
    if (logs.output.includes(testEnvironment.STATIC_HOST_LOG_SENTINEL)) {
      throw new Error('A synthetic cursor or invitation token appeared in the static host logs.');
    }
    if (!logs.output.includes('method=GET status=200 duration=')) {
      throw new Error('The redacted static host access log format was not observed.');
    }
  }
} catch (error) {
  process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
} finally {
  await run('docker', [...composeArgs, 'down', '--remove-orphans'], { env: testEnvironment });
  mockApi?.kill();
}

process.exitCode = testExitCode;

async function waitForHttp(url, timeoutMs, child) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (child?.exitCode !== null && child?.exitCode !== undefined) {
      throw new Error(`The API fixture exited with code ${child.exitCode}.`);
    }
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(1500) });
      if (response.status < 500) return;
    } catch {
      // The containers may still be starting.
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`Timed out waiting for ${url}.`);
}

function run(command, args, options) {
  return new Promise((resolve) => {
    const child = spawn(command, args, { ...options, stdio: 'inherit' });
    child.once('error', (error) => {
      process.stderr.write(`${error.message}\n`);
      resolve(1);
    });
    child.once('exit', (code) => resolve(code ?? 1));
  });
}

function capture(command, args, options) {
  return new Promise((resolve) => {
    const child = spawn(command, args, { ...options, stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    child.stdout.on('data', (chunk) => {
      output += chunk.toString();
    });
    child.stderr.on('data', (chunk) => {
      output += chunk.toString();
    });
    child.once('error', (error) => {
      resolve({ exitCode: 1, output: error.message });
    });
    child.once('exit', (code) => {
      resolve({ exitCode: code ?? 1, output });
    });
  });
}
