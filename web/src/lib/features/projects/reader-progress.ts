/** The step a `#step-N` link names, when that step belongs to the project. */
export function stepIdFromHash(
  hash: string,
  stepIds: readonly number[],
): number | null {
  const match = /^#step-(\d+)$/.exec(hash);
  if (!match) return null;
  const stepId = Number(match[1]);
  return Number.isSafeInteger(stepId) && stepIds.includes(stepId)
    ? stepId
    : null;
}
