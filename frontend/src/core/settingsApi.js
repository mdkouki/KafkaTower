// Shared by the Settings tabs: every tab verifies its parameters on the backend before saving.
export async function loadSettings(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

/** PUTs the body; the backend verifies the parameters first and answers 422 with the reason if they fail. */
export async function saveSettings(url, body) {
  const res = await fetch(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || `HTTP ${res.status}`);
  return res.json();
}
