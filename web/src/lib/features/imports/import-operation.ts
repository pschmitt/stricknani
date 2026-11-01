import type { ImportPreview } from "./import-api";
import type { ImportProjectEditor } from "./import-draft";

export interface ImportOperationIdentity {
  userId: number;
  userGeneration: number;
  operationGeneration: number;
  draft: ImportProjectEditor | null;
  preview: ImportPreview | null;
}

/** Return whether a delayed import result still belongs to the visible review. */
export function isCurrentImportOperation(
  captured: ImportOperationIdentity,
  current: ImportOperationIdentity,
): boolean {
  return (
    captured.userId === current.userId &&
    captured.userGeneration === current.userGeneration &&
    captured.operationGeneration === current.operationGeneration &&
    captured.draft === current.draft &&
    captured.preview === current.preview
  );
}
