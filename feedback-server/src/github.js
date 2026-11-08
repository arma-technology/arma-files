'use strict';

// Treść publicznego zgłoszenia. E-mail zgłaszającego NIGDY tu nie trafia —
// zgłoszenia na GitHubie widzi każdy, a adres idzie osobno, prywatnie, mailem.

const DEVICE_LABELS = {
  appVersion: 'App',
  android: 'Android',
  model: 'Device',
  language: 'Language',
};

// „@nazwa” w treści powiadomiłoby właściciela takiego konta na GitHubie;
// znak o zerowej szerokości po „@” zostawia tekst czytelnym, a nikogo nie woła.
function defuseMentions(text) {
  return text.replace(/@(?=[A-Za-z0-9-])/g, '@​');
}

function issueTitle(report) {
  return defuseMentions(report.title);
}

function issueBody(report) {
  const lines = [defuseMentions(report.description), '', '---'];
  const device = Object.entries(DEVICE_LABELS)
    .filter(([key]) => report.device[key])
    .map(([key, label]) => `**${label}:** ${defuseMentions(report.device[key])}`);
  if (device.length) lines.push(device.join(' · '));
  lines.push(
    report.email
      ? '_Sent from the app. The reporter left a contact address, shared privately with the maintainers._'
      : '_Sent from the app._'
  );
  return lines.join('\n');
}

function issueLabels(report, extraLabel) {
  const labels = [report.type === 'bug' ? 'bug' : 'enhancement'];
  if (extraLabel) labels.push(extraLabel);
  return labels;
}

// Zakłada zgłoszenie i zwraca { number, url }. fetchImpl do testów.
async function createIssue({ repo, token, report, label, apiBase = 'https://api.github.com', fetchImpl = fetch }) {
  const res = await fetchImpl(`${apiBase}/repos/${repo}/issues`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'Content-Type': 'application/json',
      'User-Agent': 'arma-files-feedback',
    },
    body: JSON.stringify({
      title: issueTitle(report),
      body: issueBody(report),
      labels: issueLabels(report, label),
    }),
    signal: AbortSignal.timeout(15000),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    const err = new Error(`GitHub ${res.status}: ${text.slice(0, 300)}`);
    err.status = res.status;
    throw err;
  }
  const data = await res.json();
  return { number: data.number, url: data.html_url };
}

module.exports = { createIssue, issueBody, issueTitle, issueLabels, defuseMentions };
