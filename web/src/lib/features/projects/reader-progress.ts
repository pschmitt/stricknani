const positions = new Map<string, number>();

function positionKey(userId: number, projectId: number): string | null {
  if (!Number.isSafeInteger(userId) || userId < 1) return null;
  if (!Number.isSafeInteger(projectId) || projectId < 1) return null;
  return `${userId}:${projectId}`;
}

export function rememberReadingStep(
  userId: number,
  projectId: number,
  stepId: number,
): void {
  const key = positionKey(userId, projectId);
  if (!key || !Number.isSafeInteger(stepId) || stepId < 1) return;
  positions.set(key, stepId);
}

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

export function resolveReadingStep(
  userId: number,
  projectId: number,
  hash: string,
  stepIds: readonly number[],
): number | null {
  const fromHash = stepIdFromHash(hash, stepIds);
  if (fromHash !== null) return fromHash;
  const key = positionKey(userId, projectId);
  const remembered = key ? positions.get(key) : undefined;
  return remembered !== undefined && stepIds.includes(remembered)
    ? remembered
    : (stepIds[0] ?? null);
}

export function stepHash(stepId: number): string {
  return Number.isSafeInteger(stepId) && stepId > 0 ? `#step-${stepId}` : "";
}
