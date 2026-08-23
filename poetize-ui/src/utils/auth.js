const INVALID_TOKENS = new Set(["", "null", "undefined"]);

function normalizeToken(value) {
  if (typeof value !== "string") {
    return "";
  }
  const token = value.trim();
  return INVALID_TOKENS.has(token.toLowerCase()) ? "" : token;
}

export function clearToken(key) {
  try {
    localStorage.removeItem(key);
  } catch {
    // 存储不可用时，调用方仍可清理内存中的登录状态。
  }
}

export function getValidToken(key) {
  try {
    const storedToken = localStorage.getItem(key);
    const token = normalizeToken(storedToken);
    if (!token && storedToken !== null) {
      localStorage.removeItem(key);
    }
    return token;
  } catch {
    return "";
  }
}

export function hasValidToken(key) {
  return getValidToken(key) !== "";
}

export function saveToken(key, value) {
  const token = normalizeToken(value);
  try {
    if (token) {
      localStorage.setItem(key, token);
    } else {
      localStorage.removeItem(key);
    }
  } catch {
    return "";
  }
  return token;
}
