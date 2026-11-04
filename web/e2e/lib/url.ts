/**
 * Minimal URL splitter that mirrors the parts of Python's `urllib.parse.urlsplit`
 * the fixture helpers rely on. WHATWG `URL` normalises away default ports,
 * numeric host forms and empty queries, which the loopback validators must see.
 */
export interface SplitUrl {
  scheme: string;
  netloc: string;
  /** Present when the authority has an `@`, even if the user part is empty. */
  userinfo: string | null;
  /** Lower-cased host without IPv6 brackets; `null` when absent. */
  hostname: string | null;
  /** `null` when absent; throws from {@link splitUrl} when malformed. */
  port: number | null;
  path: string;
  query: string;
  fragment: string;
}

export class UrlParseError extends Error {}

const URL_PATTERN =
  /^([A-Za-z][A-Za-z0-9+.-]*):\/\/([^/?#]*)([^?#]*)(?:\?([^#]*))?(?:#(.*))?$/s;

export function splitUrl(value: string): SplitUrl {
  const match = URL_PATTERN.exec(value);
  if (match === null) throw new UrlParseError("invalid URL");
  const [, scheme = "", netloc = "", path = "", query = "", fragment = ""] =
    match;
  const at = netloc.lastIndexOf("@");
  const userinfo = at >= 0 ? netloc.slice(0, at) : null;
  const hostport = at >= 0 ? netloc.slice(at + 1) : netloc;
  let host: string;
  let portText = "";
  if (hostport.startsWith("[")) {
    const close = hostport.indexOf("]");
    if (close < 0) throw new UrlParseError("invalid URL");
    host = hostport.slice(1, close);
    const rest = hostport.slice(close + 1);
    if (rest !== "") {
      if (!rest.startsWith(":")) throw new UrlParseError("invalid URL");
      portText = rest.slice(1);
    }
  } else {
    const colon = hostport.lastIndexOf(":");
    if (colon >= 0) {
      host = hostport.slice(0, colon);
      portText = hostport.slice(colon + 1);
    } else {
      host = hostport;
    }
  }
  let port: number | null = null;
  if (portText !== "") {
    if (!/^[0-9]+$/.test(portText)) throw new UrlParseError("invalid port");
    port = Number(portText);
    if (port > 65535) throw new UrlParseError("invalid port");
  }
  return {
    scheme: scheme.toLowerCase(),
    netloc,
    userinfo,
    hostname: host === "" ? null : host.toLowerCase(),
    port,
    path,
    query,
    fragment,
  };
}
