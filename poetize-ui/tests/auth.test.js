import assert from "node:assert/strict";
import test from "node:test";

import {clearToken, getValidToken, saveToken} from "../src/utils/auth.js";

function withStorage(storage, check) {
  const previous = Object.getOwnPropertyDescriptor(globalThis, "localStorage");
  Object.defineProperty(globalThis, "localStorage", {configurable: true, value: storage});
  try {
    check();
  } finally {
    if (previous) {
      Object.defineProperty(globalThis, "localStorage", previous);
    } else {
      delete globalThis.localStorage;
    }
  }
}

test("main-site authentication uses only normalized, stored tokens", () => {
  const values = new Map();
  const storage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key)
  };

  withStorage(storage, () => {
    assert.equal(saveToken("userToken", "  valid-token  "), "valid-token");
    assert.equal(getValidToken("userToken"), "valid-token");
    values.set("userToken", "undefined");
    assert.equal(getValidToken("userToken"), "");
    assert.equal(values.has("userToken"), false);
    assert.equal(saveToken("userToken", " null "), "");
    clearToken("userToken");
    assert.equal(getValidToken("userToken"), "");
  });
});

test("main-site authentication remains usable when browser storage is blocked", () => {
  const storage = {
    getItem: () => { throw new Error("blocked"); },
    setItem: () => { throw new Error("blocked"); },
    removeItem: () => { throw new Error("blocked"); }
  };
  withStorage(storage, () => {
    assert.equal(getValidToken("userToken"), "");
    assert.equal(saveToken("userToken", "valid-token"), "");
    assert.doesNotThrow(() => clearToken("userToken"));
  });
});
