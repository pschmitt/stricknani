/** Pure selection logic of the shared segmented control. */

export type SegmentedOptionState = { disabled?: boolean };

/** Index of the selected option, or -1 when none matches. */
export function selectedIndex<V>(
  options: readonly { value: V }[],
  value: V,
): number {
  return options.findIndex((option) => option.value === value);
}

/**
 * The option a key moves the selection to, skipping disabled options and
 * wrapping at the ends. Returns null when the key is not a navigation key or
 * no other option can take the selection.
 */
export function nextIndex(
  options: readonly SegmentedOptionState[],
  from: number,
  key: string,
  rtl = false,
): number | null {
  const enabled = (index: number) => !options[index]?.disabled;
  const count = options.length;
  if (key === "Home" || key === "End") {
    const order = [...options.keys()];
    if (key === "End") order.reverse();
    return order.find(enabled) ?? null;
  }
  let step: 1 | -1;
  if (key === "ArrowRight") step = rtl ? -1 : 1;
  else if (key === "ArrowLeft") step = rtl ? 1 : -1;
  else if (key === "ArrowDown") step = 1;
  else if (key === "ArrowUp") step = -1;
  else return null;
  for (let offset = 1; offset <= count; offset += 1) {
    const index = (((from + step * offset) % count) + count) % count;
    if (index !== from && enabled(index)) return index;
  }
  return null;
}

/** The option that takes the Tab stop: the selected one, else the first enabled. */
export function tabStopIndex(
  options: readonly SegmentedOptionState[],
  selected: number,
): number {
  if (selected >= 0 && !options[selected]?.disabled) return selected;
  return options.findIndex((option) => !option.disabled);
}
