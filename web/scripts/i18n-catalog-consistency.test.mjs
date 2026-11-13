import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const baseEnglish = JSON.parse(
  readFileSync(new URL("../messages/en.json", import.meta.url), "utf8"),
);
const americanEnglish = JSON.parse(
  readFileSync(new URL("../messages/en-US.json", import.meta.url), "utf8"),
);

test("en-US mirrors base English", () => {
  assert.deepEqual(americanEnglish, baseEnglish);
});

const { locales } = JSON.parse(
  readFileSync(new URL("../project.inlang/settings.json", import.meta.url), "utf8"),
);
const placeholders = (text) =>
  (text.match(/\{\w+\}|%(?:\d+\$)?(?:ll|l)?[@diufs]/g) ?? []).sort();

test("every supported locale covers the catalog and keeps message variables", () => {
  const keys = Object.keys(baseEnglish).sort();
  for (const locale of locales) {
    const messages = JSON.parse(
      readFileSync(new URL(`../messages/${locale}.json`, import.meta.url), "utf8"),
    );
    assert.deepEqual(Object.keys(messages).sort(), keys, locale);
    for (const key of keys) {
      assert.ok(messages[key].trim(), `${locale}: ${key} is empty`);
      assert.deepEqual(
        placeholders(messages[key]),
        placeholders(baseEnglish[key]),
        `${locale}: ${key}`,
      );
    }
  }
});
