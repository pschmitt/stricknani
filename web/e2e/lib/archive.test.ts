import { deflateRawSync } from "node:zlib";
import { describe, expect, it } from "vitest";

import { ArchiveError, makeTarGz, readArchive, readZip } from "./archive";

function zipOf(
  files: { path: string; data: Buffer; deflate: boolean }[],
  zip64 = false,
): Buffer {
  const locals: Buffer[] = [];
  const central: Buffer[] = [];
  let offset = 0;
  for (const file of files) {
    const name = Buffer.from(file.path);
    const body = file.deflate ? deflateRawSync(file.data) : file.data;
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(file.deflate ? 8 : 0, 8);
    local.writeUInt32LE(body.length, 18);
    local.writeUInt32LE(file.data.length, 22);
    local.writeUInt16LE(name.length, 26);
    locals.push(local, name, body);
    const entry = Buffer.alloc(46);
    entry.writeUInt32LE(0x02014b50, 0);
    entry.writeUInt16LE(file.deflate ? 8 : 0, 10);
    entry.writeUInt32LE(zip64 ? 0xffffffff : body.length, 20);
    entry.writeUInt32LE(zip64 ? 0xffffffff : file.data.length, 24);
    entry.writeUInt16LE(name.length, 28);
    entry.writeUInt32LE(offset, 42);
    const extra = Buffer.alloc(zip64 ? 20 : 0);
    if (zip64) {
      extra.writeUInt16LE(1, 0);
      extra.writeUInt16LE(16, 2);
      extra.writeBigUInt64LE(BigInt(file.data.length), 4);
      extra.writeBigUInt64LE(BigInt(body.length), 12);
      entry.writeUInt16LE(extra.length, 30);
    }
    central.push(entry, name, extra);
    offset += local.length + name.length + body.length;
  }
  const directory = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, directory, end]);
}

describe("archive readers", () => {
  const files = [
    { path: "top/manifest.json", data: Buffer.from('{"a":1}') },
    { path: "top/media/x.bin", data: Buffer.alloc(1000, 7) },
  ];

  it("round-trips a tar.gz in order", () => {
    const entries = readArchive(makeTarGz(files));
    expect(entries.map((e) => e.path)).toEqual(files.map((f) => f.path));
    expect(entries[1]?.data.equals(files[1]!.data)).toBe(true);
  });

  it("reads stored and deflated zip entries in file order", () => {
    const zip = zipOf([
      { ...files[0]!, deflate: true },
      { ...files[1]!, deflate: false },
    ]);
    const entries = readZip(zip);
    expect(entries.map((e) => e.path)).toEqual(files.map((f) => f.path));
    expect(entries[0]?.data.toString()).toBe('{"a":1}');
    expect(entries[1]?.data.equals(files[1]!.data)).toBe(true);
  });

  it("reads sizes from the zip64 extra field", () => {
    const zip = zipOf(
      [
        { ...files[0]!, deflate: true },
        { ...files[1]!, deflate: false },
      ],
      true,
    );
    const entries = readZip(zip);
    expect(entries[0]?.data.toString()).toBe('{"a":1}');
    expect(entries[1]?.data.equals(files[1]!.data)).toBe(true);
  });

  it("rejects unknown containers without echoing content", () => {
    expect(() => readArchive(Buffer.from("secret text"))).toThrow(ArchiveError);
    try {
      readArchive(Buffer.from("secret text"));
    } catch (error) {
      expect(String(error)).not.toContain("secret");
    }
  });
});
