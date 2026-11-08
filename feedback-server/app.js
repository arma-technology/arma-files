'use strict';

// Plik startowy dla serwera aplikacji hostingu (Passenger szuka app.js
// w katalogu domeny). Sekrety leżą POZA katalogiem serwowanym po HTTP —
// ścieżkę podaje FEEDBACK_CONFIG, domyślnie ~/arma-zgloszenia-sekrety/.env.

const path = require('node:path');
const os = require('node:os');
const { loadConfig } = require('./src/config');
const { FileLimiter } = require('./src/limiter');
const { createMailer } = require('./src/mail');
const { createServer } = require('./src/server');

const configFile = process.env.FEEDBACK_CONFIG || path.join(os.homedir(), 'arma-zgloszenia-sekrety', '.env');
const config = loadConfig(configFile);

const limiter = new FileLimiter({
  dir: config.DATA_DIR,
  salt: config.LIMIT_SALT,
  perIpPerHour: Number(config.LIMIT_PER_IP_HOUR || 5),
  perIpPerDay: Number(config.LIMIT_PER_IP_DAY || 20),
  globalPerDay: Number(config.LIMIT_GLOBAL_DAY || 100),
});

const server = createServer({ config, limiter, mailer: createMailer(config) });
server.listen(Number(process.env.PORT || 3000), () => {
  console.log(`[feedback] nasłuch, konfiguracja: ${configFile}`);
});
