/**
 * One Home section's data: loaded for the signed-in user, reloaded when the user changes or on
 * retry, and ignored if it arrives for a previous user or an outdated request. Must be created
 * during component initialisation (it registers an effect).
 */
export type SectionStatus = "loading" | "ready" | "error";

export interface HomeSection<T> {
  readonly status: SectionStatus;
  readonly items: T[];
  retry(): void;
}

export function homeSection<T>(
  owner: () => number | null,
  load: (signal: AbortSignal) => Promise<T[]>,
): HomeSection<T> {
  let state = $state<{
    ownerId: number | null;
    status: SectionStatus;
    items: T[];
  }>({ ownerId: null, status: "loading", items: [] });
  let attempt = $state(0);

  $effect(() => {
    const ownerId = owner();
    void attempt;
    if (ownerId === null) {
      state = { ownerId: null, status: "loading", items: [] };
      return;
    }
    const controller = new AbortController();
    state = { ownerId, status: "loading", items: [] };
    load(controller.signal)
      .then((items) => {
        if (!controller.signal.aborted && owner() === ownerId)
          state = { ownerId, status: "ready", items };
      })
      .catch(() => {
        if (!controller.signal.aborted && owner() === ownerId)
          state = { ownerId, status: "error", items: [] };
      });
    return () => controller.abort();
  });

  return {
    get status() {
      return state.ownerId === owner() ? state.status : "loading";
    },
    get items() {
      return state.ownerId === owner() ? state.items : [];
    },
    retry() {
      attempt += 1;
    },
  };
}
