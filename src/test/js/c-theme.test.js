const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = readFileSync(path.resolve(__dirname, "../../main/resources/static/c/theme-mode.js"), "utf8");

function theme({ transitions = false, reduced = false, saved = null, glass = null } = {}) {
    const style = {};
    const root = { dataset: {}, style: { setProperty: (name, value) => { style[name] = value; } } };
    const pending = [];
    const calls = [];
    const stored = {};
    const document = { documentElement: root };
    if (transitions) document.startViewTransition = (change) => { pending.push(change); };
    const window = { iaDesktop: (action, value) => calls.push([action, value]) };
    vm.runInNewContext(source, {
        window, document,
        matchMedia: (query) => ({ matches: query.includes("reduced-motion") ? reduced : false }),
        localStorage: { getItem: (key) => (key === "ia-c-glass" ? glass : saved), setItem: (key, value) => { stored[key] = value; } }
    });
    return { root, style, pending, calls, stored, CTheme: window.CTheme };
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

test("glass transparency: the middle is the original look, the ends stay readable, and it applies before the first paint", () => {
    const { style, CTheme } = theme({ glass: "75" });
    const { glassScale, glassLevel } = CTheme._internal;
    assert.equal(glassScale(50), 1);
    assert.equal(glassScale(0), 1.4);
    assert.equal(glassScale(100), 0.5);
    assert.ok(glassScale(20) > glassScale(30) && glassScale(70) > glassScale(80), "more transparent all the way");
    assert.equal(style["--glass-k"], "0.75");
    assert.equal(CTheme.glass(), 75);
    for (const [given, level] of [[null, 50], ["", 50], ["abc", 50], ["-5", 0], ["130", 100], ["33.6", 34]]) {
        assert.equal(glassLevel(given), level, String(given));
    }
});

test("dragging the glass slider only repaints; letting go saves", () => {
    const { style, stored, CTheme } = theme();
    assert.equal(style["--glass-k"], "1", "nothing saved means the original look");
    CTheme.setGlass("90", false);
    assert.equal(style["--glass-k"], "0.6");
    assert.equal(stored["ia-c-glass"], undefined);
    CTheme.setGlass("90");
    assert.equal(stored["ia-c-glass"], "90");
});
