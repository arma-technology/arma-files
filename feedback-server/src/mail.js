'use strict';

// Prywatna wiadomość do opiekunów: tu (i tylko tu) trafia adres zgłaszającego,
// ustawiony jako „Odpowiedz do”, żeby dało się odpisać jednym kliknięciem.

function buildMessage({ report, issue, from, to }) {
  const kind = report.type === 'bug' ? 'Błąd' : 'Pomysł';
  const device = Object.entries(report.device).map(([k, v]) => `${k}: ${v}`).join('\n');
  return {
    from,
    to,
    replyTo: report.email || undefined,
    subject: `[ARMA Files] ${kind} #${issue.number}: ${report.title}`,
    text: [
      `${kind} zgłoszony z aplikacji.`,
      `Zgłoszenie: ${issue.url}`,
      `Kontakt: ${report.email || '(nie podano)'}`,
      '',
      report.description,
      '',
      device,
    ].join('\n'),
  };
}

function createMailer(config, nodemailer = require('nodemailer')) {
  if (!config.SMTP_HOST || !config.MAIL_TO) return null;
  const port = Number(config.SMTP_PORT || 465);
  const transport = nodemailer.createTransport({
    host: config.SMTP_HOST,
    port,
    secure: port === 465,
    auth: config.SMTP_USER ? { user: config.SMTP_USER, pass: config.SMTP_PASS } : undefined,
    connectionTimeout: 10000,
    greetingTimeout: 10000,
    socketTimeout: 15000,
  });
  const from = config.MAIL_FROM || config.SMTP_USER;
  return {
    send: ({ report, issue }) => transport.sendMail(buildMessage({ report, issue, from, to: config.MAIL_TO })),
  };
}

module.exports = { createMailer, buildMessage };
