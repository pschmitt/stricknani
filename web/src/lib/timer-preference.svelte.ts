export const PROJECT_TIMERS_STORAGE_PREFIX = "stricknani.projectTimers.";

type PreferenceStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

let enabledByUser = $state<Record<number, boolean>>({});

function storage(): PreferenceStorage | null {
  try {
    return typeof localStorage === "undefined" ? null : localStorage;
  } catch {
    return null;
  }
}

function storedValue(userId: number): boolean {
  try {
    return (
      storage()?.getItem(`${PROJECT_TIMERS_STORAGE_PREFIX}${userId}`) !== "0"
    );
  } catch {
    return true;
  }
}

function onStorage(event: StorageEvent): void {
  const storageKey = event.key;
  if (storageKey === null) {
    for (const userId of Object.keys(enabledByUser).map(Number)) {
      enabledByUser[userId] = storedValue(userId);
    }
    return;
  }
  if (!storageKey.startsWith(PROJECT_TIMERS_STORAGE_PREFIX)) return;
  const userId = Number(storageKey.slice(PROJECT_TIMERS_STORAGE_PREFIX.length));
  if (!Number.isSafeInteger(userId) || userId <= 0) return;
  enabledByUser[userId] = event.newValue !== "0";
}

if (typeof window !== "undefined") {
  try {
    window.addEventListener("storage", onStorage);
  } catch {
    // Cross-tab updates are optional when storage events are unavailable.
  }
}

export const projectTimerPreference = {
  isEnabled(userId: number | null): boolean {
    if (userId === null) return true;
    return enabledByUser[userId] ?? storedValue(userId);
  },

  setEnabled(userId: number, enabled: boolean): void {
    if (!Number.isSafeInteger(userId) || userId <= 0) return;
    enabledByUser[userId] = enabled;
    try {
      const target = storage();
      if (enabled)
        target?.removeItem(`${PROJECT_TIMERS_STORAGE_PREFIX}${userId}`);
      else target?.setItem(`${PROJECT_TIMERS_STORAGE_PREFIX}${userId}`, "0");
    } catch {
      // Keep the preference for this visit when browser storage is unavailable.
    }
  },

  reload(userId: number): void {
    if (!Number.isSafeInteger(userId) || userId <= 0) return;
    enabledByUser[userId] = storedValue(userId);
  },
};
