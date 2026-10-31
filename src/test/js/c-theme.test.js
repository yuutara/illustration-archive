const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = readFileSync(path.resolve(__dirname, "../../main/resources/static/c/theme-mode.js"), "utf8");

function theme({ transitions = false, reduced = false, saved = null } = {}) {
    const root = { dataset: {} };
    const pending = [];
    const calls = [];
    const stored = {};
    const document = { documentElement: root };
    if (transitions) document.startViewTransition = (change) => { pending.push(change); };
    const window = { iaDesktop: (action, value) => calls.push([action, value]) };
    vm.runInNewContext(source, {
        window, document,
        matchMedia: (query) => ({ matches: query.includes("reduced-motion") ? reduced : false }),
        localStorage: { getItem: () => saved, setItem: (key, value) => { stored[key] = value; } }
    });
    return { root, pending, calls, stored, CTheme: window.CTheme };
}

test("the saved mode applies right away, before the first paint", () => {
    const { root, CTheme } = theme({ saved: "dark" });
    assert.equal(root.dataset.mode, "dark");
    assert.equal(CTheme.get(), "dark");
});

test("without view transitions the page changes at once", () => {
    const { root, CTheme } = theme();
    CTheme.set("dark");
    assert.equal(root.dataset.mode, "dark");
});

test("during a view transition the new mode can already be read and the page changes in the callback", () => {
    const { root, pending, calls, stored, CTheme } = theme({ transitions: true });
    CTheme.set("dark");
    assert.equal(CTheme.get(), "dark", "the theme button reads this straight after setting it");
    assert.equal(root.dataset.mode, "light", "the old look must be captured before the page changes");
    assert.deepEqual(calls, [["theme", "dark"]]);
    assert.equal(stored["ia-c-theme-mode"], "dark");
    pending[0]();
    assert.equal(root.dataset.mode, "dark");
});

test("reduced motion changes the page without a transition", () => {
    const { root, pending, CTheme } = theme({ transitions: true, reduced: true });
    CTheme.set("dark");
    assert.equal(pending.length, 0);
    assert.equal(root.dataset.mode, "dark");
});
