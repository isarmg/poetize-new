const INVALID_TOKENS = new Set(["", "null", "undefined"]);

function normalizeToken(value) {
  if (typeof value !== "string") {
    return "";
  }
  const token = value.trim();
  return INVALID_TOKENS.has(token.toLowerCase()) ? "" : token;
}

export function getStoredUserToken() {
  try {
    const storedToken = localStorage.getItem("userToken");
    const token = normalizeToken(storedToken);
    if (!token && storedToken !== null) {
      localStorage.removeItem("userToken");
    }
    return token;
  } catch {
    return "";
  }
}

export function saveStoredUserToken(value) {
  const token = normalizeToken(value);
  try {
    if (token) {
      localStorage.setItem("userToken", token);
    } else {
      localStorage.removeItem("userToken");
    }
  } catch {
    return "";
  }
  return token;
}

export function clearStoredUserToken() {
  try {
    localStorage.removeItem("userToken");
  } catch {
    // 存储不可用时，调用方仍可清理内存中的登录状态。
  }
}
