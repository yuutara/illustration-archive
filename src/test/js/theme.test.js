const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const staticDir = path.resolve(__dirname, "../../main/resources/static");
const source = readFileSync(path.join(staticDir, "theme.js"), "utf8");
const key = "illustration-archive.theme.v1";

// A head-script fixture: the root exists before the buttons are parsed.
function setup({ saved, readFails = false, writeFails = false, ready = false } = {}) {
    const values = new Map(saved === undefined ? [] : [[key, saved]]);
    const root = { setAttribute(name, value) { this[name] = value; } };
    const buttons = [];
    let onReady;
    const document = {
        documentElement: root,
        readyState: ready ? "complete" : "loading",
        addEventListener(type, action, options) {
            assert.equal(type, "DOMContentLoaded");
            assert.equal(options.once, true);
            onReady = action;
        },
        querySelectorAll(selector) {
            assert.equal(selector, ".theme-toggle");
            return buttons;
        }
    };
    const window = {
        get localStorage() {
            if (readFails === "access") throw new Error("Storage blocked");
            return {
                getItem(name) {
                    if (readFails) throw new Error("Storage read failed");
                    return values.get(name) ?? null;
                },
                setItem(name, value) {
                    if (writeFails) throw new Error("Storage quota exceeded");
                    values.set(name, value);
                }
            };
        }
    };
    function addButton() {
        const button = {
            hidden: true,
            setAttribute(name, value) { this[name] = value; },
            addEventListener(type, action) { assert.equal(type, "click"); this.click = action; }
        };
        buttons.push(button);
        return button;
    }
    return {
        root, values, addButton,
        run: () => vm.runInNewContext(source, { window, document }),
        finishLoading: () => { document.readyState = "interactive"; onReady(); }
    };
}

test("defaults to Light before the body is parsed and binds the button when ready", () => {
    const env = setup();
    env.run();
    assert.equal(env.root["data-theme"], "light");
    assert.equal(env.values.has(key), false);
    const button = env.addButton();
    env.finishLoading();
    assert.equal(button.hidden, false);
    assert.equal(button["aria-pressed"], "false");
});

test("restores saved Dark immediately and reflects it in the toggle", () => {
    const env = setup({ saved: "dark" });
    env.run();
    assert.equal(env.root["data-theme"], "dark");
    const button = env.addButton();
    env.finishLoading();
    assert.equal(button["aria-pressed"], "true");
});

test("toggle updates the root, saved choice and all button pressed states", () => {
    const env = setup({ ready: true });
    const first = env.addButton(), second = env.addButton();
    env.run();
    first.click();
    assert.equal(env.root["data-theme"], "dark");
    assert.equal(env.values.get(key), "dark");
    assert.equal(first["aria-pressed"], "true");
    assert.equal(second["aria-pressed"], "true");
    second.click();
    assert.equal(env.root["data-theme"], "light");
    assert.equal(env.values.get(key), "light");
    assert.equal(first["aria-pressed"], "false");
    assert.equal(second["aria-pressed"], "false");
});

test("saved Light and invalid values resolve to Light without rewriting storage", () => {
    for (const saved of ["light", "system", "DARK", "", "null", '{"theme":"dark"}']) {
        const env = setup({ saved, ready: true });
        env.run();
        assert.equal(env.root["data-theme"], "light");
        assert.equal(env.values.get(key), saved);
    }
});

test("blocked storage does not prevent initialization or in-page switching", () => {
    for (const readFails of [true, "access"]) {
        const env = setup({ readFails, ready: true });
        const button = env.addButton();
        assert.doesNotThrow(env.run);
        assert.equal(env.root["data-theme"], "light");
        assert.doesNotThrow(() => button.click());
        assert.equal(env.root["data-theme"], "dark");
        assert.equal(button["aria-pressed"], "true");
    }
});

test("a failed write keeps the chosen theme and leaves the saved value intact", () => {
    const env = setup({ saved: "dark", writeFails: true, ready: true });
    const button = env.addButton();
    env.run();
    assert.doesNotThrow(() => button.click());
    assert.equal(env.root["data-theme"], "light");
    assert.equal(button["aria-pressed"], "false");
    assert.equal(env.values.get(key), "dark");
});

test("all pages initialize synchronously before CSS and offer a non-submit toggle", () => {
    for (const file of ["index.html", "detail.html", "x-import.html"]) {
        const html = readFileSync(path.join(staticDir, file), "utf8");
        const script = html.match(/<script\b[^>]*src="\/theme\.js"[^>]*><\/script>/g);
        assert.equal(script?.length, 1, file);
        assert.doesNotMatch(script[0], /\b(?:defer|async)\b|\btype\s*=/, file);
        assert.ok(html.indexOf(script[0]) < html.indexOf('<link rel="stylesheet" href="/style.css">'), file);
        assert.ok(html.indexOf(script[0]) < html.indexOf("</head>"), file);
        assert.match(html, /<button class="theme-toggle" type="button" aria-pressed="false" hidden>深色模式<\/button>/, file);
    }
});
