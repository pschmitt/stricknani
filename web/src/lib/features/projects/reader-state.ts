export interface FavoriteMutationState {
  generation: number;
  busy: boolean;
}

export function mergeFavoriteMutationIntoCurrent<
  T extends { is_favorite: boolean },
>(current: T, favorite: boolean): T {
  return { ...current, is_favorite: favorite };
}

export function mergeArchiveDetailPreservingFavorite<
  T extends { is_favorite: boolean },
>(
  refreshed: T,
  current: T,
  stateAtRequest: FavoriteMutationState,
  latestState: FavoriteMutationState,
): T {
  const favoriteMayHaveChanged =
    stateAtRequest.busy ||
    latestState.busy ||
    stateAtRequest.generation !== latestState.generation;
  return favoriteMayHaveChanged
    ? { ...refreshed, is_favorite: current.is_favorite }
    : refreshed;
}
