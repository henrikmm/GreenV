// What an .npz holds, read from its headers alone.
//
// `app/src/lib/npz.ts` is the browser's reader and it inflates everything, because the browser
// wants the numbers. Import wants only the SHAPES — how many frames, how big a depth map — and a
// 112-frame bundle is 105 MB of which 104.9 MB is payload it would throw away. So this walks the
// zip central directory and inflates the first few KB of each member, which is enough to reach
// the npy header and no more. Measured on `fixtures/door/504px-112f/verge-result.npz`: four
// members described from 8 KB of reads instead of 105 MB of inflation.
//
// Deliberately NOT a second copy of the parser: nothing here decodes an array. The moment a
// caller needs the floats it should use the browser reader, or the inspector's, which already
// share one implementation through `scripts/inspect/bridge.ts`.

import { constants, inflateRawSync } from "node:zlib";

const EOCD_SIG = 0x06054b50;
const CDIR_SIG = 0x02014b50;

/** npy headers are padded to a 64-byte boundary and are never anywhere near this long. */
const HEADER_PROBE_BYTES = 8192;

/**
 * Inflate just enough of a deflated member to read its header.
 *
 * `Z_SYNC_FLUSH` is what makes a truncated stream legal: without it zlib treats the missing tail
 * as a corrupt archive and throws, because it cannot see the end-of-stream marker it expects.
 */
function headBytes(compressed, method) {
  if (method === 0) return compressed;
  if (method !== 8) throw new Error(`unsupported zip compression method ${method}`);
  return inflateRawSync(compressed, { finishFlush: constants.Z_SYNC_FLUSH });
}

function parseNpyHeader(bytes, name) {
  if (bytes.length < 10 || bytes[0] !== 0x93 || bytes.subarray(1, 6).toString("latin1") !== "NUMPY") {
    throw new Error(`${name} is not an npy array`);
  }
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const major = bytes[6];
  const headerLength = major >= 2 ? view.getUint32(8, true) : view.getUint16(8, true);
  const headerStart = major >= 2 ? 12 : 10;
  if (bytes.length < headerStart + headerLength) {
    throw new Error(`${name} has a header longer than ${HEADER_PROBE_BYTES} bytes`);
  }
  const header = bytes.subarray(headerStart, headerStart + headerLength).toString("latin1");
  const dtype = /'descr':\s*'([^']+)'/.exec(header)?.[1];
  const shapeText = /'shape':\s*\(([^)]*)\)/.exec(header)?.[1];
  if (!dtype || shapeText === undefined) throw new Error(`${name} has an unreadable npy header`);
  return {
    dtype,
    fortranOrder: /'fortran_order':\s*True/.test(header),
    shape: shapeText.split(",").map((part) => part.trim()).filter(Boolean).map(Number),
  };
}

/**
 * Describe every array in an .npz: `{ name: { dtype, shape, fortranOrder } }`.
 *
 * Throws on anything that is not a readable npz, which is the point — this is the first thing an
 * imported file meets, so "the upload was not an npz at all" is answered here rather than three
 * stages downstream where it would surface as an empty point cloud.
 */
export function readNpzMeta(buffer) {
  const bytes = Buffer.isBuffer(buffer) ? buffer : Buffer.from(buffer);
  if (bytes.length < 22) throw new Error("not a zip archive: too short to hold a directory");
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);

  // Scan back past any trailing comment for the end-of-central-directory record.
  let eocd = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65558); i--) {
    if (view.getUint32(i, true) === EOCD_SIG) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new Error("not a zip archive: no end-of-central-directory record");

  const entryCount = view.getUint16(eocd + 10, true);
  let offset = view.getUint32(eocd + 16, true);
  const arrays = {};

  for (let i = 0; i < entryCount; i++) {
    if (view.getUint32(offset, true) !== CDIR_SIG) throw new Error("zip central directory is corrupt");
    const method = view.getUint16(offset + 10, true);
    const compressedSize = view.getUint32(offset + 20, true);
    const nameLength = view.getUint16(offset + 28, true);
    const extraLength = view.getUint16(offset + 30, true);
    const commentLength = view.getUint16(offset + 32, true);
    const localOffset = view.getUint32(offset + 42, true);
    const name = bytes.subarray(offset + 46, offset + 46 + nameLength).toString("latin1");

    // The local header carries its own name and extra lengths, which need not match the
    // directory's — the payload starts after those, not after the directory's copy.
    const localNameLength = view.getUint16(localOffset + 26, true);
    const localExtraLength = view.getUint16(localOffset + 28, true);
    const dataOffset = localOffset + 30 + localNameLength + localExtraLength;
    const probe = bytes.subarray(dataOffset, dataOffset + Math.min(compressedSize, HEADER_PROBE_BYTES));

    arrays[name.replace(/\.npy$/, "")] = parseNpyHeader(headBytes(probe, method), name);
    offset += 46 + nameLength + extraLength + commentLength;
  }

  return arrays;
}
