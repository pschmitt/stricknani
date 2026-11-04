/**
 * Client-side check for the typed confirmation before account deletion. The
 * server repeats it; this only keeps the button disabled until it can pass.
 */
export function confirmationMatches(input: string, email: string): boolean {
  const typed = input.trim();
  if (!typed) return false;
  return (
    typed === "DELETE" || typed.toLowerCase() === email.trim().toLowerCase()
  );
}
