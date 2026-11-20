/** Source dates are server metadata; missing historical values stay unknown. */
export function sourceFreshness(value?: string | null, now = new Date()) {
  const date = value ? new Date(value) : null;
  if (!date || !Number.isFinite(date.getTime())) return { date: null, label: "unbekannt", older: false };
  const cutoff = new Date(now);
  const month = cutoff.getUTCMonth();
  cutoff.setUTCFullYear(cutoff.getUTCFullYear() - 3);
  if (cutoff.getUTCMonth() !== month) cutoff.setUTCDate(0); // 29 February → 28 February.
  return {
    date: date.toISOString(),
    label: new Intl.DateTimeFormat("de-DE", { day: "2-digit", month: "2-digit", year: "numeric", timeZone: "UTC" }).format(date),
    older: date.getTime() < cutoff.getTime(),
  };
}
