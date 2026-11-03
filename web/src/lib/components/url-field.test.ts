import { describe, expect, it } from "vitest";
import { displayDomain, parseHttpUrl, stripNewlines } from "./url-field";

describe("url field helpers", () => {
  it("accepts only http(s) URLs", () => {
    expect(parseHttpUrl(" https://www.example.com/a?b=1 ")?.hostname).toBe(
      "www.example.com",
    );
    expect(parseHttpUrl("http://example.com")).not.toBeNull();
    expect(parseHttpUrl("javascript:alert(1)")).toBeNull();
    expect(parseHttpUrl("ftp://example.com")).toBeNull();
    expect(parseHttpUrl("example.com")).toBeNull();
    expect(parseHttpUrl("")).toBeNull();
  });

  it("shows the domain without www", () => {
    expect(displayDomain(parseHttpUrl("https://www.example.com/x"))).toBe(
      "example.com",
    );
    expect(displayDomain(null)).toBe("");
  });

  it("strips newlines from pasted text", () => {
    expect(stripNewlines("https://a.test/\r\nb\nc")).toBe("https://a.test/bc");
  });
});
