(function (global) {
  "use strict";

  const textEncoder = new TextEncoder();
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

  function dosDateTime(date) {
    const year = Math.max(1980, date.getFullYear());
    const dosTime = (date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() >> 1);
    const dosDate = ((year - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate();
    return { dosTime, dosDate };
  }

  function uint16(value) {
    return new Uint8Array([value & 0xff, (value >>> 8) & 0xff]);
  }

  function uint32(value) {
    return new Uint8Array([
      value & 0xff,
      (value >>> 8) & 0xff,
      (value >>> 16) & 0xff,
      (value >>> 24) & 0xff
    ]);
  }

  function concat(parts) {
    let size = 0;
    for (const part of parts) {
      size += part.length;
    }
    const out = new Uint8Array(size);
    let offset = 0;
    for (const part of parts) {
      out.set(part, offset);
      offset += part.length;
    }
    return out;
  }

  function toBytes(data) {
    if (data instanceof Uint8Array) {
      return data;
    }
    if (data instanceof ArrayBuffer) {
      return new Uint8Array(data);
    }
    if (ArrayBuffer.isView(data)) {
      return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    }
    return textEncoder.encode(String(data));
  }

  function create(files) {
    const now = new Date();
    const time = dosDateTime(now);
    const localParts = [];
    const centralParts = [];
    let offset = 0;

    for (const file of files) {
      const nameBytes = textEncoder.encode(file.name.replace(/^\/+/, ""));
      const body = toBytes(file.data);
      const crc = crc32(body);
      const localHeader = concat([
        uint32(0x04034b50),
        uint16(20),
        uint16(0x0800),
        uint16(0),
        uint16(time.dosTime),
        uint16(time.dosDate),
        uint32(crc),
        uint32(body.length),
        uint32(body.length),
        uint16(nameBytes.length),
        uint16(0),
        nameBytes
      ]);
      localParts.push(localHeader, body);

      const centralHeader = concat([
        uint32(0x02014b50),
        uint16(20),
        uint16(20),
        uint16(0x0800),
        uint16(0),
        uint16(time.dosTime),
        uint16(time.dosDate),
        uint32(crc),
        uint32(body.length),
        uint32(body.length),
        uint16(nameBytes.length),
        uint16(0),
        uint16(0),
        uint16(0),
        uint16(0),
        uint32(0),
        uint32(offset),
        nameBytes
      ]);
      centralParts.push(centralHeader);
      offset += localHeader.length + body.length;
    }

    const central = concat(centralParts);
    const local = concat(localParts);
    const end = concat([
      uint32(0x06054b50),
      uint16(0),
      uint16(0),
      uint16(files.length),
      uint16(files.length),
      uint32(central.length),
      uint32(local.length),
      uint16(0)
    ]);

    return new Blob([local, central, end], { type: "application/zip" });
  }

  global.ZipStore = { create };
}(this));
