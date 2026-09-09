import { localStorage } from "./local.mjs";
import { s3Storage } from "./s3.mjs";

export function objectStorage(config) {
  return config.adapter === "s3" ? s3Storage(config) : localStorage(config);
}
