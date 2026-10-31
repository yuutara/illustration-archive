const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = readFileSync(path.resolve(__dirname, "../../main/resources/static/c/shell.js"), "utf8");

class Element {
    constructor(found = {}) { this.found = found; }
    closest(selector) { return this.found[selector] || null; }
}

function shell({ host = true, selection = "", wall = null, stored = {}, reduced = false } = {}) {
    const calls = [];
    const listeners = {};
    const posted = [];
    const original = { style: {} };
    const document = {
        querySelector: (selector) => (selector === ".wall-wrap" ? wall : null),
        getElementById: (id) => (id === "sv-original" ? original : null),
        addEventListener: (type, listener) => { listeners[type] = listener; }
    };
    const window = {
        UiCapabilities: { desktop: true },
        CTheme: { get: () => "dark" },
        getSelection: () => selection
    };
    if (host) window.iaDesktop = (action, value) => calls.push([action, value]);
    vm.runInNewContext(source, {
        window, document, Element, location: { origin: "http://127.0.0.1:47391" },
        fetch: async (url, options) => { posted.push([url, options]); return { ok: false }; },
        matchMedia: () => ({ matches: reduced }),
        sessionStorage: { getItem: (key) => stored[key] ?? null, setItem: (key, value) => { stored[key] = value; } }
    });
    const fire = (type, event) => {
        let prevented = false;
        listeners[type]({ ...event, preventDefault: () => { prevented = true; } });
        return prevented;
    };
    return { calls, fire, original, posted, appShell: window.AppShell };
}

const wall = () => {
    const played = [];
    return { played, animate: (frames, options) => played.push(options) };
};

const link = (href, attributes = {}) => {
    const url = new URL(href);
    return { href, origin: url.origin, protocol: url.protocol, hasAttribute: (name) => name in attributes };
};
const clickOn = (anchor) => ({ target: new Element({ "a[href]": anchor }) });

test("in the app window a web link goes to the default browser and the app's own links stay", () => {
    const { calls, fire } = shell();
    assert.equal(fire("click", clickOn(link("https://www.pixiv.net/artworks/1"))), true);
    assert.deepEqual(calls.at(-1), ["open", "https://www.pixiv.net/artworks/1"]);
    assert.equal(fire("click", clickOn(link("http://127.0.0.1:47391/c/x.html"))), false);
    assert.equal(calls.filter(([action]) => action === "open").length, 1);
});

test("in a plain browser only marked links go through the server, and nothing else changes", () => {
    const { posted, fire, original } = shell({ host: false });
    assert.equal(fire("click", clickOn(link("https://www.pixiv.net/artworks/1"))), false);
    assert.equal(fire("click", clickOn(link("https://x.com/a/status/1", { "data-external": "" }))), true);
    assert.equal(posted.length, 1);
    assert.equal(original.style.display, undefined);
});

test("browser shortcuts do nothing in the app window, and the ones the page uses still work", () => {
    const { fire } = shell();
    const key = (key, modifiers = {}) => fire("keydown", { key, ctrlKey: false, shiftKey: false, altKey: false, ...modifiers });
    for (const pressed of ["F5", "F12"]) assert.equal(key(pressed), true, pressed);
    for (const letter of ["f", "p", "r", "u", "F"]) assert.equal(key(letter, { ctrlKey: true }), true, letter);
    assert.equal(key("I", { ctrlKey: true, shiftKey: true }), true);
    for (const letter of ["a", "c", "v", "x", "z"]) assert.equal(key(letter, { ctrlKey: true }), false, letter);
    for (const plain of ["a", "s", "e", "Delete", "Escape", "ArrowRight"]) assert.equal(key(plain), false, plain);
});

test("the right-click menu stays where copying and pasting need it", () => {
    assert.equal(shell().fire("contextmenu", { target: new Element() }), true);
    assert.equal(shell().fire("contextmenu", { target: new Element({ "input, textarea": {} }) }), false);
    assert.equal(shell({ selection: "こんにちは" }).fire("contextmenu", { target: new Element() }), false);
});

test("the window learns the theme, and the tab-only link is gone", () => {
    const { calls, original } = shell();
    assert.deepEqual(calls[0], ["theme", "dark"]);
    assert.equal(original.style.display, "none");
});

test("the window hears once that the first screen is ready", () => {
    const { calls, appShell } = shell();
    appShell.ready();
    appShell.ready();
    assert.equal(calls.filter(([action]) => action === "ready").length, 1);
});

test("the works settle in once per launch, not on every page change", () => {
    const stored = {};
    const first = wall();
    shell({ wall: first, stored }).appShell.ready();
    assert.equal(first.played.length, 1);

    const later = wall();
    shell({ wall: later, stored }).appShell.ready();
    assert.equal(later.played.length, 0);
});

test("reduced motion skips the settling but still reveals the window", () => {
    const works = wall();
    const { calls, appShell } = shell({ wall: works, reduced: true });
    appShell.ready();
    assert.equal(works.played.length, 0);
    assert.equal(calls.filter(([action]) => action === "ready").length, 1);
});

test("in a plain browser there is no window to tell", () => {
    const works = wall();
    shell({ host: false, wall: works }).appShell.ready();
    assert.equal(works.played.length, 0);
});
