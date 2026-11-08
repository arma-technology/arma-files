'use strict';

const http = require('node:http');
const { validateReport, isHoneypotFilled } = require('./validate');
const { createIssue } = require('./github');

const MAX_BODY_BYTES = 16 * 1024;
const startedAt = Date.now();

// Kody błędów idą też w nagłówku X-Error-Code: hosting współdzielony potrafi
// podmienić ciało odpowiedzi 4xx/5xx na własną stronę HTML, nagłówki zostają.
function send(res, status, payload, extraHeaders = {}) {
  const body = JSON.stringify(payload);
  const headers = {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    ...extraHeaders,
  };
  if (payload.error) headers['X-Error-Code'] = payload.error;
  res.writeHead(status, headers);
  res.end(body);
}

// Za serwerem WWW hostingu adres klienta jest w X-Forwarded-For. Klient może
// sam wpisać tam cokolwiek, ale serwer pośredniczący dopisuje prawdziwy adres
// na KOŃCU listy — dlatego bierzemy ostatni wpis, a nie pierwszy.
function clientIp(req, trustProxy) {
  if (trustProxy) {
    const xff = req.headers['x-forwarded-for'];
    if (typeof xff === 'string' && xff.trim()) {
      const parts = xff.split(',').map((s) => s.trim()).filter(Boolean);
      if (parts.length) return parts[parts.length - 1];
    }
  }
  return req.socket.remoteAddress || 'unknown';
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('too large'), { code: 'too_large' }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

// deps: { config, limiter, mailer, createIssueImpl, log }
function createHandler(deps) {
  const { config, limiter, mailer, log = console } = deps;
  const createIssueImpl = deps.createIssueImpl || createIssue;
  const trustProxy = config.TRUST_PROXY !== 'false';

  async function handleFeedback(req, res) {
    // Nie jest to zabezpieczenie (nagłówek widać w otwartym kodzie), tylko sito
    // na przypadkowe boty, które wysyłają formularze pod każdy znaleziony adres.
    if (!String(req.headers['x-arma-client'] || '').startsWith('arma-files/')) {
      return send(res, 400, { error: 'invalid' });
    }
    if (!String(req.headers['content-type'] || '').startsWith('application/json')) {
      return send(res, 415, { error: 'invalid' });
    }

    let input;
    try {
      input = JSON.parse(await readBody(req));
    } catch (e) {
      return send(res, e.code === 'too_large' ? 413 : 400, { error: 'invalid' });
    }

    // Bot dostaje „sukces”, żeby nie próbował dalej, a nic nie powstaje.
    if (isHoneypotFilled(input)) return send(res, 201, { ok: true });

    const result = validateReport(input);
    if (!result.ok) return send(res, 400, { error: result.error, field: result.field });

    const ip = clientIp(req, trustProxy);
    const gate = await limiter.take(ip);
    if (!gate.allowed) {
      return send(res, 429, { error: 'rate_limited' }, { 'Retry-After': String(gate.retryAfterSec) });
    }

    let issue;
    try {
      issue = await createIssueImpl({
        repo: config.GITHUB_REPO,
        token: config.GITHUB_TOKEN,
        report: result.report,
        label: config.GITHUB_LABEL || 'from-app',
        apiBase: config.GITHUB_API || undefined,
      });
    } catch (e) {
      log.error(`[feedback] GitHub: ${e.message}`);
      return send(res, 502, { error: 'upstream' });
    }

    if (mailer) {
      // Poczta nie blokuje odpowiedzi: zgłoszenie już istnieje, a użytkownik
      // nie powinien czekać na serwer SMTP ani dostać błędu, gdy ten zawiedzie.
      Promise.resolve()
        .then(() => mailer.send({ report: result.report, issue }))
        .catch((e) => log.error(`[feedback] e-mail do #${issue.number}: ${e.message}`));
    }

    log.info?.(`[feedback] #${issue.number} ${result.report.type}`);
    return send(res, 201, { ok: true, number: issue.number, url: issue.url });
  }

  return async function handler(req, res) {
    try {
      const url = new URL(req.url, 'http://localhost');
      if (url.pathname === '/health' && req.method === 'GET') {
        return send(res, 200, { ok: true, uptimeSec: Math.round((Date.now() - startedAt) / 1000) });
      }
      if (url.pathname === '/v1/feedback') {
        if (req.method !== 'POST') return send(res, 405, { error: 'invalid' }, { Allow: 'POST' });
        return await handleFeedback(req, res);
      }
      return send(res, 404, { error: 'not_found' });
    } catch (e) {
      log.error(`[feedback] ${e.stack || e.message}`);
      if (!res.headersSent) send(res, 500, { error: 'server' });
    }
  };
}

function createServer(deps) {
  return http.createServer(createHandler(deps));
}

module.exports = { createServer, createHandler, clientIp };
