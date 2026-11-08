'use strict';

// Walidacja zgłoszenia z aplikacji. Wszystko, co przychodzi z sieci, jest
// obce: przycinamy, ograniczamy długość i wycinamy znaki sterujące, zanim
// cokolwiek trafi do publicznego zgłoszenia na GitHubie albo do e-maila.

const LIMITS = {
  title: { min: 3, max: 120 },
  description: { min: 10, max: 5000 },
  email: { max: 254 },
  device: { max: 100 },
};

const TYPES = new Set(['bug', 'feature']);
const DEVICE_FIELDS = ['appVersion', 'android', 'model', 'language'];
const EMAIL_RE = /^[^\s@<>()",;:]+@[^\s@<>()",;:]+\.[^\s@<>()",;:]{2,}$/;

// Znaki sterujące poza tabulatorem i nową linią, do tego znaki kierunku
// tekstu, którymi da się „odwrócić” fragment zgłoszenia.
const CONTROL_RE = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F‪-‮⁦-⁩]/g;

function clean(value, { multiline = false } = {}) {
  if (typeof value !== 'string') return '';
  let s = value.replace(/\r\n?/g, '\n').replace(CONTROL_RE, '');
  if (!multiline) s = s.replace(/\s+/g, ' ');
  return s.trim();
}

// Wynik: { ok: true, report } albo { ok: false, error, field }.
function validateReport(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    return { ok: false, error: 'invalid', field: 'body' };
  }
  const type = clean(input.type);
  if (!TYPES.has(type)) return { ok: false, error: 'invalid', field: 'type' };

  const title = clean(input.title);
  if (title.length < LIMITS.title.min || title.length > LIMITS.title.max) {
    return { ok: false, error: 'invalid', field: 'title' };
  }
  const description = clean(input.description, { multiline: true });
  if (description.length < LIMITS.description.min || description.length > LIMITS.description.max) {
    return { ok: false, error: 'invalid', field: 'description' };
  }

  let email = '';
  if (input.email != null && input.email !== '') {
    email = clean(input.email);
    if (email.length > LIMITS.email.max || !EMAIL_RE.test(email)) {
      return { ok: false, error: 'invalid', field: 'email' };
    }
  }

  const device = {};
  const rawDevice = input.device && typeof input.device === 'object' ? input.device : {};
  for (const key of DEVICE_FIELDS) {
    const v = clean(rawDevice[key]);
    if (v) device[key] = v.slice(0, LIMITS.device.max);
  }

  return { ok: true, report: { type, title, description, email, device } };
}

// Pułapka na boty: pole, którego aplikacja nigdy nie wypełnia.
function isHoneypotFilled(input) {
  return Boolean(input && typeof input.website === 'string' && input.website.trim() !== '');
}

module.exports = { validateReport, isHoneypotFilled, LIMITS, clean };
