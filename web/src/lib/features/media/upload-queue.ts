export interface UploadProgress {
  /** Files finished so far, successful or not. */
  done: number;
  total: number;
  /** One-based position of the file being sent, for "Uploading photo 2 of 5". */
  current: number;
}

export interface UploadFailure {
  file: File;
  error: unknown;
}

export interface UploadQueueResult {
  uploaded: number;
  failures: UploadFailure[];
}

/**
 * Upload files one after the other (the API takes one file per request). A failing file does not
 * stop the rest; the caller reports the failures afterwards. `shouldStop` cancels the remaining
 * files, for example when the route changed.
 */
export async function uploadSequentially(
  files: readonly File[],
  uploadOne: (file: File) => Promise<unknown>,
  onProgress?: (progress: UploadProgress) => void,
  shouldStop?: () => boolean,
): Promise<UploadQueueResult> {
  const failures: UploadFailure[] = [];
  let uploaded = 0;
  for (const [index, file] of files.entries()) {
    if (shouldStop?.()) break;
    onProgress?.({ done: index, total: files.length, current: index + 1 });
    try {
      await uploadOne(file);
      uploaded += 1;
    } catch (error) {
      failures.push({ file, error });
    }
  }
  onProgress?.({
    done: files.length,
    total: files.length,
    current: files.length,
  });
  return { uploaded, failures };
}
