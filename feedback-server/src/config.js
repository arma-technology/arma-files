'use strict';

const fs = require('node:fs');

// Minimalny czytnik pliku .env: KLUCZ=wartość, komentarze od #, wartości
// opcjonalnie w cudzysłowach. Bez zależności, bo serwer ma ich mieć jak najmniej.
function parseEnv(text) {
  const out = {};
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const eq = line.indexOf('=');
    if (eq < 1) continue;
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    out[key] = value;
  }
  return out;
}

const REQUIRED = ['GITHUB_REPO', 'GITHUB_TOKEN', 'DATA_DIR', 'LIMIT_SALT'];

function loadConfig(file, env = process.env) {
  const fromFile = file && fs.existsSync(file) ? parseEnv(fs.readFileSync(file, 'utf8')) : {};
  const config = { ...fromFile, ...pick(env, Object.keys(fromFile).concat(REQUIRED)) };
  const missing = REQUIRED.filter((k) => !config[k]);
  if (missing.length) throw new Error(`Brak ustawień: ${missing.join(', ')} (plik: ${file || '-'})`);
  return config;
}

function pick(env, keys) {
  const out = {};
  for (const k of keys) if (env[k]) out[k] = env[k];
  return out;
}

module.exports = { parseEnv, loadConfig };
