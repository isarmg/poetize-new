import assert from "node:assert/strict";
import test from "node:test";

import {clearStoredUserToken, getStoredUserToken, saveStoredUserToken} from "../src/utils/auth.js";

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

test("chat authentication uses only normalized, stored tokens", () => {
  const values = new Map();
  const storage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key)
  };

  withStorage(storage, () => {
    assert.equal(saveStoredUserToken("  valid-token  "), "valid-token");
    assert.equal(getStoredUserToken(), "valid-token");
    values.set("userToken", "undefined");
    assert.equal(getStoredUserToken(), "");
    assert.equal(values.has("userToken"), false);
    assert.equal(saveStoredUserToken(" null "), "");
    clearStoredUserToken();
    assert.equal(getStoredUserToken(), "");
  });
});

test("chat authentication remains usable when browser storage is blocked", () => {
  const storage = {
    getItem: () => { throw new Error("blocked"); },
    setItem: () => { throw new Error("blocked"); },
    removeItem: () => { throw new Error("blocked"); }
  };
  withStorage(storage, () => {
    assert.equal(getStoredUserToken(), "");
    assert.equal(saveStoredUserToken("valid-token"), "");
    assert.doesNotThrow(clearStoredUserToken);
  });
});
