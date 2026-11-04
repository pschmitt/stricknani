import { describe, expect, it } from "vitest";
import {
  extractSharedUrl,
  importPrefillQuery,
  prefillUrlFromSearch,
  sharedUrlFromSearch,
} from "./shared-link";

describe("extractSharedUrl", () => {
  it("accepts a plain URL", () => {
    expect(
      extractSharedUrl("https://www.garnstudio.com/pattern.php?id=12752&cid=9"),
    ).toBe("https://www.garnstudio.com/pattern.php?id=12752&cid=9");
  });

  it("takes the link out of a title plus URL share", () => {
    expect(extractSharedUrl("Cosy sweater\nhttps://example.com/a")).toBe(
      "https://example.com/a",
    );
    expect(extractSharedUrl("See http://example.com/x?y=1 now")).toBe(
      "http://example.com/x?y=1",
    );
  });

  it("takes the first link and trims wrapping punctuation", () => {
    expect(
      extractSharedUrl("(https://a.example/p). and https://b.example"),
    ).toBe("https://a.example/p");
    expect(extractSharedUrl("<https://a.example/p>")).toBe(
      "https://a.example/p",
    );
  });

  it("returns null without an http(s) link", () => {
    expect(extractSharedUrl(null)).toBeNull();
    expect(extractSharedUrl("")).toBeNull();
    expect(extractSharedUrl("just words")).toBeNull();
    expect(extractSharedUrl("example.com/pattern")).toBeNull();
    expect(extractSharedUrl("file:///etc/passwd")).toBeNull();
    expect(extractSharedUrl("javascript:alert(1)")).toBeNull();
    expect(extractSharedUrl("ftp://example.com/x")).toBeNull();
    expect(extractSharedUrl("file:///a?u=https://example.com/x")).toBeNull();
    expect(extractSharedUrl("https://user:pw@example.com/x")).toBeNull();
  });
});

describe("sharedUrlFromSearch", () => {
  it("prefers url, then text, then title", () => {
    expect(
      sharedUrlFromSearch(
        "?title=T&text=Look%20https%3A%2F%2Ftext.example%2F&url=https%3A%2F%2Furl.example%2F",
      ),
    ).toBe("https://url.example/");
    expect(
      sharedUrlFromSearch("?text=Look%20https%3A%2F%2Ftext.example%2F"),
    ).toBe("https://text.example/");
    expect(sharedUrlFromSearch("?title=https%3A%2F%2Ftitle.example%2F")).toBe(
      "https://title.example/",
    );
  });

  it("returns null when no parameter carries a link", () => {
    expect(sharedUrlFromSearch("")).toBeNull();
    expect(sharedUrlFromSearch("?text=hello&title=world")).toBeNull();
    expect(sharedUrlFromSearch("?url=file%3A%2F%2F%2Fx")).toBeNull();
  });
});

describe("prefillUrlFromSearch", () => {
  it("accepts only an http(s) url parameter", () => {
    expect(prefillUrlFromSearch("?url=https%3A%2F%2Fexample.com%2Fa")).toBe(
      "https://example.com/a",
    );
    expect(prefillUrlFromSearch("?url=javascript%3Aalert(1)")).toBeNull();
    expect(prefillUrlFromSearch("?text=https%3A%2F%2Fexample.com")).toBeNull();
    expect(prefillUrlFromSearch("")).toBeNull();
  });

  it("round-trips through importPrefillQuery", () => {
    const url = "https://example.com/p.php?id=1&cid=9#x";
    expect(prefillUrlFromSearch(importPrefillQuery(url))).toBe(url);
  });
});
