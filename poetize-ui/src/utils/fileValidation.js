const SAFE_MIME_TYPES = new Set([
  "image/jpeg", "image/png", "image/gif", "image/webp", "image/avif", "image/bmp", "image/x-icon",
  "video/mp4", "video/webm", "video/ogg", "video/quicktime", "video/x-msvideo", "video/x-matroska",
  "audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav", "audio/x-wav", "audio/webm", "audio/aac", "audio/flac"
]);

export function matchesFileAccept(file, accept) {
  const mimeType = String(file?.type || "").trim().toLowerCase();
  if (!SAFE_MIME_TYPES.has(mimeType)) {
    return false;
  }
  const fileName = String(file?.name || "").toLowerCase();
  return String(accept || "")
    .split(",")
    .map(item => item.trim().toLowerCase())
    .filter(Boolean)
    .some(item => item.endsWith("/*")
      ? mimeType.startsWith(item.slice(0, -1))
      : item.startsWith(".")
        ? fileName.endsWith(item)
        : mimeType === item);
}
