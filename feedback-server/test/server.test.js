'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createServer, clientIp } = require('../src/server');
const { FileLimiter } = require('../src/limiter');
const { validateReport } = require('../src/validate');
const { issueBody, issueTitle } = require('../src/github');
const { buildMessage } = require('../src/mail');
const { parseEnv, loadConfig } = require('../src/config');

const good = {
  type: 'bug',
  title: 'Podgląd PDF się zamyka',
  description: 'Po otwarciu pliku z SMB aplikacja wraca do listy.',
  email: 'tester@example.com',
  device: { appVersion: '0.11.0 (22)', android: '15 (SDK 35)', model: 'motorola edge 40', language: 'pl' },
};

function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'feedback-test-'));
}

async function startServer(overrides = {}) {
  const issues = [];
  const mails = [];
  const limiter = overrides.limiter || new FileLimiter({ dir: tmpDir(), salt: 's' });
  const server = createServer({
    config: { GITHUB_REPO: 'o/r', GITHUB_TOKEN: 't', TRUST_PROXY: 'true' },
    limiter,
    mailer: { send: async (m) => { mails.push(m); } },
    createIssueImpl: overrides.createIssueImpl || (async ({ report }) => {
      issues.push(report);
      return { number: issues.length, url: `https://github.com/o/r/issues/${issues.length}` };
    }),
    log: { error() {}, info() {} },
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  return { server, base, issues, mails };
}

function post(base, body, headers = {}) {
  return fetch(`${base}/v1/feedback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Arma-Client': 'arma-files/0.11.0', ...headers },
    body: typeof body === 'string' ? body : JSON.stringify(body),
  });
}

test('poprawne zgłoszenie zakłada issue i wysyła e-mail', async () => {
  const { server, base, issues, mails } = await startServer();
  try {
    const res = await post(base, good);
    assert.equal(res.status, 201);
    const data = await res.json();
    assert.equal(data.number, 1);
    assert.match(data.url, /issues\/1$/);
    assert.equal(issues.length, 1);
    await new Promise((r) => setImmediate(r));
    assert.equal(mails.length, 1);
    assert.equal(mails[0].report.email, 'tester@example.com');
  } finally { server.close(); }
});

test('bez nagłówka klienta — odmowa', async () => {
  const { server, base, issues } = await startServer();
  try {
    const res = await post(base, good, { 'X-Arma-Client': '' });
    assert.equal(res.status, 400);
    assert.equal(issues.length, 0);
  } finally { server.close(); }
});

test('wypełniona pułapka udaje sukces i nic nie zakłada', async () => {
  const { server, base, issues } = await startServer();
  try {
    const res = await post(base, { ...good, website: 'http://spam' });
    assert.equal(res.status, 201);
    assert.equal(issues.length, 0);
  } finally { server.close(); }
});

test('błąd walidacji ma kod w nagłówku', async () => {
  const { server, base } = await startServer();
  try {
    const res = await post(base, { ...good, title: 'x' });
    assert.equal(res.status, 400);
    assert.equal(res.headers.get('x-error-code'), 'invalid');
    assert.equal((await res.json()).field, 'title');
  } finally { server.close(); }
});

test('za duże ciało i zły JSON', async () => {
  const { server, base } = await startServer();
  try {
    assert.equal((await post(base, '{nie json')).status, 400);
    const big = await post(base, { ...good, description: 'a'.repeat(20000) }).catch(() => null);
    assert.ok(!big || big.status === 413);
  } finally { server.close(); }
});

test('limit z jednego adresu: szóste zgłoszenie w godzinie odrzucone', async () => {
  const { server, base, issues } = await startServer();
  try {
    for (let i = 0; i < 5; i++) assert.equal((await post(base, good)).status, 201);
    const res = await post(base, good);
    assert.equal(res.status, 429);
    assert.equal(res.headers.get('x-error-code'), 'rate_limited');
    assert.ok(Number(res.headers.get('retry-after')) > 0);
    // Inny adres (ostatni wpis X-Forwarded-For) ma własny limit.
    assert.equal((await post(base, good, { 'X-Forwarded-For': '10.0.0.9' })).status, 201);
    assert.equal(issues.length, 6);
  } finally { server.close(); }
});

test('awaria GitHuba daje 502 upstream', async () => {
  const { server, base } = await startServer({
    createIssueImpl: async () => { throw new Error('boom'); },
  });
  try {
    const res = await post(base, good);
    assert.equal(res.status, 502);
    assert.equal(res.headers.get('x-error-code'), 'upstream');
  } finally { server.close(); }
});

test('health i nieznane ścieżki', async () => {
  const { server, base } = await startServer();
  try {
    const h = await fetch(`${base}/health`);
    assert.equal(h.status, 200);
    assert.equal((await h.json()).ok, true);
    assert.equal((await fetch(`${base}/.env`)).status, 404);
    assert.equal((await fetch(`${base}/v1/feedback`)).status, 405);
  } finally { server.close(); }
});

test('clientIp bierze ostatni wpis X-Forwarded-For', () => {
  const req = { headers: { 'x-forwarded-for': '1.1.1.1, 2.2.2.2' }, socket: { remoteAddress: '127.0.0.1' } };
  assert.equal(clientIp(req, true), '2.2.2.2');
  assert.equal(clientIp(req, false), '127.0.0.1');
});

test('walidacja czyści znaki sterujące i odrzuca zły e-mail', () => {
  const r = validateReport({ ...good, title: '  Tytuł‮ z\u0007 sterowaniem  ', email: '' });
  assert.equal(r.ok, true);
  assert.equal(r.report.title, 'Tytuł z sterowaniem');
  assert.equal(r.report.email, '');
  assert.equal(validateReport({ ...good, email: 'nie-email' }).field, 'email');
  assert.equal(validateReport({ ...good, type: 'other' }).field, 'type');
  assert.equal(validateReport(null).ok, false);
});

test('treść zgłoszenia nie zawiera e-maila i nie woła nikogo przez @', () => {
  const report = validateReport({ ...good, title: 'Pytanie do @arturotolinski', description: 'Napisz do @octocat proszę bardzo.' }).report;
  const body = issueBody(report);
  assert.ok(!body.includes('tester@example.com'));
  assert.ok(!/@[A-Za-z]/.test(body));
  assert.ok(!/@[A-Za-z]/.test(issueTitle(report)));
  assert.match(body, /motorola edge 40/);
  assert.match(body, /contact address, shared privately/);
});

test('e-mail do opiekunów ma odpowiedź do zgłaszającego', () => {
  const report = validateReport(good).report;
  const m = buildMessage({ report, issue: { number: 7, url: 'u' }, from: 'a@b.pl', to: 'c@d.pl' });
  assert.equal(m.replyTo, 'tester@example.com');
  assert.match(m.subject, /#7/);
  const noMail = buildMessage({ report: { ...report, email: '' }, issue: { number: 1, url: 'u' }, from: 'a', to: 'b' });
  assert.equal(noMail.replyTo, undefined);
});

test('limiter: blokada równoległa nie gubi zgłoszeń', async () => {
  const limiter = new FileLimiter({ dir: tmpDir(), salt: 's', perIpPerHour: 1000, perIpPerDay: 1000, globalPerDay: 30 });
  const results = await Promise.all(Array.from({ length: 40 }, (_, i) => limiter.take(`ip${i}`)));
  assert.equal(results.filter((r) => r.allowed).length, 30);
});

test('limiter zapisuje skróty, nie adresy', async () => {
  const dir = tmpDir();
  const limiter = new FileLimiter({ dir, salt: 's' });
  await limiter.take('203.0.113.7');
  assert.ok(!fs.readFileSync(path.join(dir, 'limits.json'), 'utf8').includes('203.0.113.7'));
});

test('konfiguracja: plik .env i brakujące klucze', () => {
  assert.deepEqual(parseEnv('# k\nA=1\nB="dwa"\n\nzle\n'), { A: '1', B: 'dwa' });
  const dir = tmpDir();
  const f = path.join(dir, '.env');
  fs.writeFileSync(f, 'GITHUB_REPO=o/r\nGITHUB_TOKEN=t\nDATA_DIR=/x\nLIMIT_SALT=s\n');
  assert.equal(loadConfig(f, {}).GITHUB_REPO, 'o/r');
  fs.writeFileSync(f, 'GITHUB_REPO=o/r\n');
  assert.throws(() => loadConfig(f, {}), /GITHUB_TOKEN/);
});
