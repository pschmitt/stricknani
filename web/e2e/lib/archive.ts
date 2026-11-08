/**
 * Minimal readers for the data-export containers (docs/data-export.md) and a
 * tar.gz writer for crafted archives, so the Backups journey needs no extra
 * dependency. Readers return entries in archive order and never echo contents
 * in their error messages.
 */
import { gunzipSync, gzipSync, inflateRawSync } from "node:zlib";

export interface ArchiveEntry {
  /** Full path inside the archive, `/`-separated. */
  path: string;
  data: Buffer;
}

export class ArchiveError extends Error {}

function cString(block: Buffer, start: number, length: number): string {
  const slice = block.subarray(start, start + length);
  const end = slice.indexOf(0);
  return slice.subarray(0, end < 0 ? slice.length : end).toString("utf8");
}

function octal(block: Buffer, start: number, length: number): number {
  const text = cString(block, start, length).trim();
  if (text === "") return 0;
  if (!/^[0-7]+$/.test(text)) throw new ArchiveError("invalid tar number");
  return Number.parseInt(text, 8);
}

function paxRecords(data: Buffer): Map<string, string> {
  const records = new Map<string, string>();
  let offset = 0;
  while (offset < data.length) {
    const space = data.indexOf(0x20, offset);
    if (space < 0) break;
    const length = Number.parseInt(data.toString("ascii", offset, space), 10);
    if (!Number.isInteger(length) || length <= 0) {
      throw new ArchiveError("invalid pax record");
    }
    const record = data.toString("utf8", space + 1, offset + length - 1);
    const equals = record.indexOf("=");
    if (equals > 0)
      records.set(record.slice(0, equals), record.slice(equals + 1));
    offset += length;
  }
  return records;
}

/** Regular files of an uncompressed ustar/pax tar, in archive order. */
export function readTar(tar: Buffer): ArchiveEntry[] {
  const entries: ArchiveEntry[] = [];
  let offset = 0;
  let pendingPath: string | undefined;
  while (offset + 512 <= tar.length) {
    const header = tar.subarray(offset, offset + 512);
    if (header.every((byte) => byte === 0)) break;
    const size = octal(header, 124, 12);
    const type = String.fromCharCode(header[156] ?? 0);
    const prefix = cString(header, 345, 155);
    const name = cString(header, 0, 100);
    const dataStart = offset + 512;
    if (dataStart + size > tar.length) throw new ArchiveError("truncated tar");
    const data = tar.subarray(dataStart, dataStart + size);
    offset = dataStart + Math.ceil(size / 512) * 512;
    if (type === "x") {
      pendingPath = paxRecords(data).get("path") ?? pendingPath;
    } else if (type === "L") {
      pendingPath = cString(data, 0, data.length);
    } else if (type === "g") {
      continue;
    } else {
      const path = pendingPath ?? (prefix ? `${prefix}/${name}` : name);
      pendingPath = undefined;
      if (type === "0" || type === "\0") {
        entries.push({ path, data: Buffer.from(data) });
      }
    }
  }
  return entries;
}

export function readTarGz(archive: Buffer): ArchiveEntry[] {
  return readTar(gunzipSync(archive));
}

/** Regular files of a zip, ordered by their position in the file. */
export function readZip(zip: Buffer): ArchiveEntry[] {
  let eocd = -1;
  for (
    let i = zip.length - 22;
    i >= Math.max(0, zip.length - 22 - 65535);
    i--
  ) {
    if (zip.readUInt32LE(i) === 0x06054b50) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0)
    throw new ArchiveError("no zip end-of-central-directory record");
  const count = zip.readUInt16LE(eocd + 10);
  let cursor = zip.readUInt32LE(eocd + 16);
  const found: { offset: number; entry: ArchiveEntry }[] = [];
  for (let n = 0; n < count; n++) {
    if (zip.readUInt32LE(cursor) !== 0x02014b50) {
      throw new ArchiveError("invalid zip central directory");
    }
    const method = zip.readUInt16LE(cursor + 10);
    let compressed = zip.readUInt32LE(cursor + 20);
    const uncompressed = zip.readUInt32LE(cursor + 24);
    const nameLength = zip.readUInt16LE(cursor + 28);
    const extraLength = zip.readUInt16LE(cursor + 30);
    const commentLength = zip.readUInt16LE(cursor + 32);
    let local = zip.readUInt32LE(cursor + 42);
    const path = zip.toString("utf8", cursor + 46, cursor + 46 + nameLength);
    // Streamed entries carry their real sizes in the zip64 extra field.
    let extra = cursor + 46 + nameLength;
    const extraEnd = extra + extraLength;
    while (extra + 4 <= extraEnd) {
      const id = zip.readUInt16LE(extra);
      const size = zip.readUInt16LE(extra + 2);
      if (id === 0x0001) {
        let at = extra + 4;
        if (uncompressed === 0xffffffff) at += 8;
        if (compressed === 0xffffffff) {
          compressed = Number(zip.readBigUInt64LE(at));
          at += 8;
        }
        if (local === 0xffffffff) local = Number(zip.readBigUInt64LE(at));
      }
      extra += 4 + size;
    }
    cursor += 46 + nameLength + extraLength + commentLength;
    if (path.endsWith("/")) continue;
    if (zip.readUInt32LE(local) !== 0x04034b50) {
      throw new ArchiveError("invalid zip local header");
    }
    const start =
      local + 30 + zip.readUInt16LE(local + 26) + zip.readUInt16LE(local + 28);
    const raw = zip.subarray(start, start + compressed);
    let data: Buffer;
    if (method === 0) data = Buffer.from(raw);
    else if (method === 8) data = inflateRawSync(raw);
    else throw new ArchiveError("unsupported zip compression method");
    found.push({ offset: local, entry: { path, data } });
  }
  return found.sort((a, b) => a.offset - b.offset).map((item) => item.entry);
}

/** Reads either container, detected from the first bytes. */
export function readArchive(archive: Buffer): ArchiveEntry[] {
  if (archive[0] === 0x1f && archive[1] === 0x8b) return readTarGz(archive);
  if (archive[0] === 0x50 && archive[1] === 0x4b) return readZip(archive);
  throw new ArchiveError("unknown archive container");
}

function tarHeader(path: string, size: number): Buffer {
  const header = Buffer.alloc(512);
  const nameBytes = Buffer.from(path, "utf8");
  if (nameBytes.length > 100) throw new ArchiveError("tar path too long");
  nameBytes.copy(header, 0);
  header.write("0000644\0", 100, "ascii");
  header.write("0000000\0", 108, "ascii");
  header.write("0000000\0", 116, "ascii");
  header.write(`${size.toString(8).padStart(11, "0")}\0`, 124, "ascii");
  header.write("00000000000\0", 136, "ascii");
  header.write("        ", 148, "ascii");
  header.write("0", 156, "ascii");
  header.write("ustar\0", 257, "ascii");
  header.write("00", 263, "ascii");
  let sum = 0;
  for (const byte of header) sum += byte;
  header.write(`${sum.toString(8).padStart(6, "0")}\0 `, 148, "ascii");
  return header;
}

/** A gzip-compressed ustar archive of the given regular files. */
export function makeTarGz(files: { path: string; data: Buffer }[]): Buffer {
  const parts: Buffer[] = [];
  for (const file of files) {
    parts.push(tarHeader(file.path, file.data.length), file.data);
    const pad = (512 - (file.data.length % 512)) % 512;
    if (pad > 0) parts.push(Buffer.alloc(pad));
  }
  parts.push(Buffer.alloc(1024));
  return gzipSync(Buffer.concat(parts));
}
