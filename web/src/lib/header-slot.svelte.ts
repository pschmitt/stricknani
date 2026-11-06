/**
 * The app header's action slot. The layout registers the element on the
 * right of the phone header bar; a page header that opts in moves its action
 * buttons there while the rail is hidden (phones), and back beside its title
 * on wider screens.
 */
export const headerSlot = $state<{ target: HTMLElement | null }>({
  target: null,
});
