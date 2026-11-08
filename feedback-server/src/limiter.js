'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

// Limit zgłoszeń trzymany w pliku, nie w pamięci: serwer aplikacji na
// hostingu współdzielonym uruchamia kilka procesów naraz i ubija je po
// bezczynności, więc licznik w pamięci każdego procesu praktycznie nie działa.
//
// Adresy IP nie są zapisywane wprost, tylko jako skrót z solą — do liczenia
// wystarczy, a plik nie staje się listą adresów zgłaszających.

const LOCK_STALE_MS = 5000;

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

class FileLimiter {
  constructor({ dir, salt, perIpPerHour = 5, perIpPerDay = 20, globalPerDay = 100, now = Date.now }) {
    if (!dir) throw new Error('FileLimiter: brak katalogu');
    if (!salt) throw new Error('FileLimiter: brak soli');
    this.file = path.join(dir, 'limits.json');
    this.lock = path.join(dir, 'limits.lock');
    this.salt = salt;
    this.perIpPerHour = perIpPerHour;
    this.perIpPerDay = perIpPerDay;
    this.globalPerDay = globalPerDay;
    this.now = now;
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  }

  key(ip) {
    return crypto.createHmac('sha256', this.salt).update(String(ip)).digest('hex').slice(0, 32);
  }

  async withLock(fn) {
    for (let attempt = 0; attempt < 100; attempt++) {
      try {
        fs.mkdirSync(this.lock);
        try {
          return fn();
        } finally {
          fs.rmSync(this.lock, { recursive: true, force: true });
        }
      } catch (e) {
        if (e.code !== 'EEXIST') throw e;
        try {
          const age = this.now() - fs.statSync(this.lock).mtimeMs;
          if (age > LOCK_STALE_MS) fs.rmSync(this.lock, { recursive: true, force: true });
        } catch (_) { /* blokada zniknęła w międzyczasie */ }
        await sleep(20 + Math.random() * 30);
      }
    }
    throw new Error('FileLimiter: nie udało się zająć blokady');
  }

  read() {
    try {
      const data = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      return { ips: data.ips || {}, global: data.global || [] };
    } catch (_) {
      return { ips: {}, global: [] };
    }
  }

  write(data) {
    const tmp = `${this.file}.${process.pid}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(data), { mode: 0o600 });
    fs.renameSync(tmp, this.file);
  }

  // Sprawdza limity i — gdy jest miejsce — od razu rezerwuje zgłoszenie.
  // Wynik: { allowed: true } albo { allowed: false, retryAfterSec }.
  async take(ip) {
    return this.withLock(() => {
      const now = this.now();
      const hour = 3600_000;
      const day = 24 * hour;
      const data = this.read();

      data.global = data.global.filter((t) => now - t < day);
      for (const [k, times] of Object.entries(data.ips)) {
        const kept = times.filter((t) => now - t < day);
        if (kept.length) data.ips[k] = kept; else delete data.ips[k];
      }

      const k = this.key(ip);
      const mine = data.ips[k] || [];
      const lastHour = mine.filter((t) => now - t < hour);

      let retryAfterMs = 0;
      if (lastHour.length >= this.perIpPerHour) retryAfterMs = hour - (now - Math.min(...lastHour));
      else if (mine.length >= this.perIpPerDay) retryAfterMs = day - (now - Math.min(...mine));
      else if (data.global.length >= this.globalPerDay) retryAfterMs = day - (now - Math.min(...data.global));

      if (retryAfterMs > 0) {
        this.write(data);
        return { allowed: false, retryAfterSec: Math.max(1, Math.ceil(retryAfterMs / 1000)) };
      }

      mine.push(now);
      data.ips[k] = mine;
      data.global.push(now);
      this.write(data);
      return { allowed: true };
    });
  }
}

module.exports = { FileLimiter };
