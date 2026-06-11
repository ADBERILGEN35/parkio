/** Formats a backend ISO instant for display; falls back to the raw value. */
export function formatInstant(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}
