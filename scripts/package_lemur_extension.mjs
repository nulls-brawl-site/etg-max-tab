import { readdirSync, readFileSync, statSync, writeFileSync, mkdirSync } from "node:fs";
import { join, relative, sep } from "node:path";

const root = new URL("..", import.meta.url).pathname;
const sourceDir = join(root, "lemur-max-exporter");
const outDir = join(root, "dist");
const outFile = join(outDir, "lemur-max-exporter.zip");

const encoder = new TextEncoder();
const crcTable = new Uint32Array(256);

for (let i = 0; i < 256; i++) {
  let c = i;
  for (let k = 0; k < 8; k++) {
    c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
  }
  crcTable[i] = c >>> 0;
}

function crc32(bytes) {
  let crc = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) {
    crc = crcTable[(crc ^ bytes[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function u16(value) {
  return Buffer.from([value & 0xff, (value >>> 8) & 0xff]);
}

function u32(value) {
  return Buffer.from([
    value & 0xff,
    (value >>> 8) & 0xff,
    (value >>> 16) & 0xff,
    (value >>> 24) & 0xff
  ]);
}

function dosDateTime(date) {
  const year = Math.max(1980, date.getFullYear());
  return {
    time: (date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() >> 1),
    date: ((year - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate()
  };
}

function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir).sort()) {
    const path = join(dir, name);
    const st = statSync(path);
    if (st.isDirectory()) {
      out.push(...walk(path));
    } else if (st.isFile()) {
      out.push(path);
    }
  }
  return out;
}

function buildZip(entries) {
  const now = dosDateTime(new Date());
  const local = [];
  const central = [];
  let offset = 0;

  for (const entry of entries) {
    const nameBytes = Buffer.from(encoder.encode(entry.name));
    const body = readFileSync(entry.path);
    const crc = crc32(body);
    const localHeader = Buffer.concat([
      u32(0x04034b50),
      u16(20),
      u16(0x0800),
      u16(0),
      u16(now.time),
      u16(now.date),
      u32(crc),
      u32(body.length),
      u32(body.length),
      u16(nameBytes.length),
      u16(0),
      nameBytes
    ]);
    local.push(localHeader, body);
    central.push(Buffer.concat([
      u32(0x02014b50),
      u16(20),
      u16(20),
      u16(0x0800),
      u16(0),
      u16(now.time),
      u16(now.date),
      u32(crc),
      u32(body.length),
      u32(body.length),
      u16(nameBytes.length),
      u16(0),
      u16(0),
      u16(0),
      u16(0),
      u32(0),
      u32(offset),
      nameBytes
    ]));
    offset += localHeader.length + body.length;
  }

  const localBuffer = Buffer.concat(local);
  const centralBuffer = Buffer.concat(central);
  const end = Buffer.concat([
    u32(0x06054b50),
    u16(0),
    u16(0),
    u16(entries.length),
    u16(entries.length),
    u32(centralBuffer.length),
    u32(localBuffer.length),
    u16(0)
  ]);
  return Buffer.concat([localBuffer, centralBuffer, end]);
}

mkdirSync(outDir, { recursive: true });
const entries = walk(sourceDir).map((path) => ({
  path,
  name: relative(sourceDir, path).split(sep).join("/")
}));
writeFileSync(outFile, buildZip(entries));
console.log(`${outFile} ${entries.length} files`);
