// `json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n"`.

import { compareStr } from "./catalog.mjs";

function render(value, depth) {
  if (typeof value === "string") {
    return JSON.stringify(value);
  }
  const keys = Object.keys(value).sort(compareStr);
  if (!keys.length) {
    return "{}";
  }
  const indent = "  ".repeat(depth + 1);
  const items = keys.map(
    (key) =>
      `${indent}${JSON.stringify(key)}: ${render(value[key], depth + 1)}`,
  );
  return `{\n${items.join(",\n")}\n${"  ".repeat(depth)}}`;
}

export function renderJson(data) {
  return `${render(data, 0)}\n`;
}
