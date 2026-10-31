const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = readFileSync(path.resolve(__dirname, "../../main/resources/static/c/c-close.js"), "utf8");

/** Just enough DOM for the close question: the page's markup is built by the script, so its parts are prepared here. */
class Node {
    constructor(document) {
        this.document = document;
        this.listeners = new Map();
        this.hidden = false;
        this.checked = false;
        this.isConnected = true;
        this.textContent = "";
    }
    addEventListener(type, fn) { this.listeners.set(type, fn); }
    dispatch(type, event = {}) {
        const e = { key: "", shiftKey: false, target: this, preventDefault() { this.prevented = true; }, stopPropagation() { this.stopped = true; }, ...event };
        this.listeners.get(type)?.(e);
        return e;
    }
    click() { this.dispatch("click"); }
    focus() { this.document.activeElement = this; }
}

const PARTS = ["close-tray", "close-exit", "close-remember", "close-cancel", "close-warning"];

function setup({ host = true, importing = false, dirty = false, save = async () => ({ ok: true, status: 204 }) } = {}) {
    const document = { activeElement: null };
    const parts = Object.fromEntries(PARTS.map((id) => [id, new Node(document)]));
    const box = new Node(document);
    box.querySelector = (selector) => parts[selector.slice(1)];
    document.createElement = () => box;
    document.body = { appendChild: () => { box.appended = true; } };
    const opener = new Node(document);
    document.activeElement = opener;
    const told = [];
    const saved = [];
    const window = { CImport: { isRunning: () => importing }, CEditor: { isDirty: () => dirty } };
    if (host) window.iaDesktop = (action, value) => told.push([action, value]);
    vm.runInNewContext(source, {
        window, document,
        fetch: async (url, options) => { saved.push([url, options]); return save(); }
    });
    return { window, document, box, parts, opener, told, saved };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

test("in a plain browser there is no window and nothing to ask", () => {
    assert.equal(setup({ host: false }).window.CClose, undefined);
});

test("asking tells the window at once, so it does not put up its own prompt", () => {
    const { window, box, parts, document, told } = setup();
    window.CClose.ask();
    assert.deepEqual(told, [["close", "asking"]]);
    assert.equal(box.hidden, false);
    assert.equal(box.appended, true);
    assert.equal(document.activeElement, parts["close-tray"], "the action that loses nothing is the default");
});

test("each choice goes straight to the window and saves nothing unless remembered", async () => {
    for (const [button, choice] of [["close-tray", "tray"], ["close-exit", "exit"]]) {
        const { window, parts, told, saved, box, document, opener } = setup();
        window.CClose.ask();
        parts[button].click();
        await settle();
        assert.deepEqual(told.at(-1), ["close", choice]);
        assert.equal(saved.length, 0);
        assert.equal(box.hidden, true);
        assert.equal(document.activeElement, opener, "focus is where it was when the window comes back");
    }
});

test("remembering saves the choice before the window acts on it", async () => {
    let release;
    const { window, parts, told, saved } = setup({ save: () => new Promise((resolve) => { release = resolve; }) });
    window.CClose.ask();
    parts["close-remember"].checked = true;
    parts["close-exit"].click();
    await settle();
    assert.equal(saved[0][0], "/api/desktop/settings/close-action");
    assert.equal(saved[0][1].method, "PUT");
    assert.equal(saved[0][1].body, '{"action":"EXIT"}');
    assert.deepEqual(told, [["close", "asking"]], "quitting first would cut the save off");
    release({ ok: true, status: 204 });
    await settle();
    assert.deepEqual(told.at(-1), ["close", "exit"]);
});

test("a failed save still closes as asked; the question just comes back next time", async () => {
    const { window, parts, told } = setup({ save: () => Promise.reject(new TypeError("offline")) });
    window.CClose.ask();
    parts["close-remember"].checked = true;
    parts["close-tray"].click();
    await settle();
    assert.deepEqual(told.at(-1), ["close", "tray"]);
});

test("cancel, Esc and the backdrop keep the window open and the choice unsaved", async () => {
    const dismissals = [
        ({ parts }) => parts["close-cancel"].click(),
        ({ box }) => box.dispatch("keydown", { key: "Escape" }),
        ({ box }) => box.dispatch("mousedown", { target: box })
    ];
    for (const dismiss of dismissals) {
        const page = setup();
        page.window.CClose.ask();
        page.parts["close-remember"].checked = true;
        dismiss(page);
        await settle();
        assert.equal(page.box.hidden, true);
        assert.deepEqual(page.told, [["close", "asking"]]);
        assert.equal(page.saved.length, 0);
        assert.equal(page.document.activeElement, page.opener);
    }
});

test("keys stay inside the question and never reach the gallery's shortcuts", () => {
    const { window, box, parts, document } = setup();
    window.CClose.ask();
    assert.equal(box.dispatch("keydown", { key: "Delete" }).stopped, true);

    const order = ["close-exit", "close-remember", "close-cancel", "close-tray"];
    for (const id of order) {
        box.dispatch("keydown", { key: "Tab" });
        assert.equal(document.activeElement, parts[id]);
    }
    box.dispatch("keydown", { key: "Tab", shiftKey: true });
    assert.equal(document.activeElement, parts["close-cancel"], "Shift+Tab wraps backwards");

    box.dispatch("keydown", { key: "ArrowDown" });
    assert.equal(document.activeElement, parts["close-exit"]);
    box.dispatch("keydown", { key: "ArrowUp" });
    assert.equal(document.activeElement, parts["close-tray"]);
});

test("closing twice does not lose where focus was or the box already ticked", () => {
    const { window, parts, opener, document } = setup();
    window.CClose.ask();
    parts["close-remember"].checked = true;
    window.CClose.ask();
    assert.equal(parts["close-remember"].checked, true);
    parts["close-cancel"].click();
    assert.equal(document.activeElement, opener);
});

test("a remembered tick from an earlier close does not carry over", () => {
    const { window, parts } = setup();
    window.CClose.ask();
    parts["close-remember"].checked = true;
    parts["close-cancel"].click();
    window.CClose.ask();
    assert.equal(parts["close-remember"].checked, false);
});

test("the question warns about what only quitting would lose", () => {
    const quiet = setup();
    quiet.window.CClose.ask();
    assert.equal(quiet.parts["close-warning"].hidden, true);

    const importing = setup({ importing: true });
    importing.window.CClose.ask();
    assert.equal(importing.parts["close-warning"].hidden, false);
    assert.match(importing.parts["close-warning"].textContent, /导入/);

    const editing = setup({ dirty: true });
    editing.window.CClose.ask();
    assert.match(editing.parts["close-warning"].textContent, /未保存/);
});
