function pad(value: number): string {
  return String(value).padStart(2, '0');
}

/** `21/11/2026` in the time zone of the viewer. */
export function formatDate(iso: string): string {
  const date = new Date(iso);
  return `${pad(date.getDate())}/${pad(date.getMonth() + 1)}/${date.getFullYear()}`;
}

/** `21/11 14:02` — short label of an axis. */
export function formatShortDate(iso: string): string {
  const date = new Date(iso);
  return `${pad(date.getDate())}/${pad(date.getMonth() + 1)}`;
}

/** `21/11/2026 14:02` in the time zone of the viewer. */
export function formatDateTime(iso: string): string {
  const date = new Date(iso);
  return `${formatDate(iso)} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}
