const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const dir = path.resolve(__dirname, "../../main/resources/static/c");
const archiveSource = readFileSync(path.join(dir, "archive-client.js"), "utf8");
const editorSource = readFileSync(path.join(dir, "c-editor.js"), "utf8");

/** Just enough DOM for the edit panel and the discard confirmation. */
class Node {
    constructor(tag = "div", id = null) {
        this.tagName = tag.toUpperCase();
        this.id = id;
        this.children = [];
        this.listeners = new Map();
        this.attributes = new Map();
        this.className = "";
        this.textContent = "";
        this.value = "";
        this.hidden = false;
        this.disabled = false;
        this.isConnected = true;
        this.focused = 0;
        this.styles = {};
        this.style = { setProperty: (k, v) => { this.styles[k] = v; } };
        this.classList = {
            add: (n) => { if (!this.className.split(" ").includes(n)) this.className = (this.className + " " + n).trim(); },
            remove: (n) => { this.className = this.className.split(" ").filter((c) => c !== n).join(" "); },
            toggle: (n, on) => { (on ? this.classList.add : this.classList.remove)(n); return on; },
            contains: (n) => this.className.split(" ").includes(n)
        };
    }
    set innerHTML(html) { this.children = []; this.html = html; }
    appendChild(child) { child.parent = this; this.children.push(child); return child; }
    insertBefore(child, ref) { child.parent = this; this.children.splice(this.children.indexOf(ref), 0, child); return child; }
    append(...nodes) { nodes.forEach((n) => (typeof n === "string" ? (this.textContent += n) : this.appendChild(n))); }
    remove() { if (this.parent) this.parent.children = this.parent.children.filter((c) => c !== this); }
    querySelectorAll(selector) {
        const cls = selector.slice(1);
        const all = (n) => n.children.flatMap((c) => [...(c.classList.contains(cls) ? [c] : []), ...all(c)]);
        return all(this);
    }
    setAttribute(name, value) { this.attributes.set(name, String(value)); }
    getAttribute(name) { return this.attributes.get(name) ?? null; }
    addEventListener(type, fn) { this.listeners.set(type, [...(this.listeners.get(type) || []), fn]); }
    dispatch(type, event = {}) {
        const e = {
            key: "", ctrlKey: false, metaKey: false, isComposing: false, keyCode: 0, target: this,
            preventDefault() { this.defaultPrevented = true; }, stopPropagation() { this.stopped = true; }, ...event
        };
        (this.listeners.get(type) || []).forEach((fn) => fn(e));
        return e;
    }
    click() { return this.dispatch("click"); }
    focus() { this.focused++; this.ownerDocument.activeElement = this; }
}

const IDS = ["viewer", "sv-editor", "edit-form", "ed-fields", "ed-title", "ed-source", "ed-note", "ed-author", "ed-author-clear",
    "ed-tags", "ed-status", "ed-error", "ed-dirty", "ed-save", "ed-cancel", "sv-edit-toggle", "confirm", "confirm-ok", "confirm-cancel"];

function setup(responses) {
    const document = { activeElement: null };
    const make = (tag, id) => { const n = new Node(tag, id); n.ownerDocument = document; return n; };
    const ids = Object.fromEntries(IDS.map((id) => [id, make(id.startsWith("ed-title") || id.startsWith("ed-source") ? "input" : "div", id)]));
    ids["confirm"].hidden = true;
    document.getElementById = (id) => ids[id];
    document.createElement = (tag) => make(tag);
    const requests = [];
    const toasts = [];
    const pickers = [];
    const window = { addEventListener() {}, innerWidth: 1400 };
    const context = {
        window, document, console, URLSearchParams, AbortController, Image: class {},
        fetch: async (url, init = {}) => {
            const key = init.method && init.method !== "GET" ? `${init.method} ${url}` : url;
            requests.push({ key, body: init.body ? JSON.parse(init.body) : undefined });
            let reply = responses[key];
            if (typeof reply === "function") reply = await reply();
            if (reply === 204) return { ok: true, status: 204, json: async () => { throw new SyntaxError("no body"); } };
            if (typeof reply === "number") return { ok: false, status: reply, json: async () => ({}) };
            return { ok: true, status: 200, json: async () => reply };
        }
    };
    vm.createContext(context);
    vm.runInContext(archiveSource, context);
    window.CFilters = {
        learn() {}, known: () => null, closePicker() {},
        nameOf: (kind, id) => `${kind}#${id}`,
        openPicker: (kind, options) => pickers.push({ kind, options })
    };
    window.GalleryApi = { toast: (t) => toasts.push(t), showChrome() {} };
    vm.runInContext(editorSource, context);
    return { ids, requests, toasts, pickers, Archive: window.Archive, Editor: window.CEditor, document };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));
const item = { id: 5, title: "Work 5", author: null, coverAssetId: 50, coverMimeType: "video/mp4", assetCount: 1,
    createdAt: "2026-10-28T10:00:00", assets: [{ id: 50, mimeType: "video/mp4", sortOrder: 0 }] };
const detail = { id: 5, title: "Work 5", author: { id: 7, displayName: "雨宫ゆき" }, sourceUrl: null, note: null,
    tags: [{ id: 1, name: "海" }], assets: [] };
const base = () => ({
    "/api/illustrations?page=0&size=30": { page: 0, size: 30, totalElements: 1, totalPages: 1, items: [item] },
    "/api/illustrations/5": detail
});

async function opened(extra = {}) {
    const env = setup({ ...base(), ...extra });
    await env.Archive.setQuery({});
    env.Editor.open(5);
    assert.equal(env.ids["ed-fields"].disabled, true, "fields stay locked while the detail loads");
    assert.match(env.ids["ed-status"].textContent, /正在载入/);
    await settle();
    assert.equal(env.ids["ed-fields"].disabled, false);
    assert.equal(env.ids["ed-title"].value, "Work 5");
    return env;
}

const typeInto = (node, value) => { node.value = value; node.dispatch("input"); };
const patches = (env) => env.requests.filter((r) => r.key.startsWith("PATCH"));

test("cancel with changes asks first: 继续编辑 keeps the draft, 放弃修改 closes without writing", async () => {
    const env = await opened();
    const { ids } = env;
    assert.equal(ids["ed-save"].disabled, true, "nothing to save yet");
    typeInto(ids["ed-title"], "新标题");
    assert.equal(ids["ed-dirty"].hidden, false);
    assert.equal(ids["ed-save"].disabled, false);

    const first = env.Editor.requestLeave();
    assert.equal(ids["confirm"].hidden, false);
    assert.equal(env.Editor.isConfirming(), true);
    ids["confirm-cancel"].click();
    assert.equal(await first, false);
    assert.equal(env.Editor.isOpen(), true);
    assert.equal(ids["ed-title"].value, "新标题", "the draft is still there");

    // Esc inside the panel takes the same path; Esc on the confirmation means “keep editing”
    const esc = ids["edit-form"].dispatch("keydown", { key: "Escape", target: ids["ed-title"] });
    assert.equal(esc.stopped, true);
    assert.equal(ids["confirm"].hidden, false);
    assert.equal(ids["confirm"].dispatch("keydown", { key: "Escape" }).stopped, true);
    assert.equal(env.Editor.isOpen(), true);

    const second = env.Editor.requestLeave();
    ids["confirm-ok"].click();
    assert.equal(await second, true);
    assert.equal(env.Editor.isOpen(), false);
    assert.equal(ids["sv-editor"].hidden, true);
    assert.equal(patches(env).length, 0, "cancel writes nothing");
});

test("leaving without changes needs no confirmation", async () => {
    const env = await opened();
    assert.equal(await env.Editor.requestLeave(), true);
    assert.equal(env.ids["confirm"].hidden, true);
    assert.equal(env.Editor.isOpen(), false);
});

test("keys typed in the panel never reach gallery shortcuts; Enter in a one-line field does not save", async () => {
    const env = await opened();
    const form = env.ids["edit-form"];
    for (const key of ["ArrowLeft", "ArrowRight", "ArrowDown", "e", "i", "Delete", "0", "+"]) {
        assert.equal(form.dispatch("keydown", { key, target: env.ids["ed-title"] }).stopped, true, key);
    }
    typeInto(env.ids["ed-title"], "改了");
    const enter = form.dispatch("keydown", { key: "Enter", target: env.ids["ed-title"] });
    assert.equal(enter.defaultPrevented, true);
    const imeEnter = form.dispatch("keydown", { key: "Enter", ctrlKey: true, isComposing: true, keyCode: 229, target: env.ids["ed-title"] });
    assert.equal(imeEnter.defaultPrevented, undefined, "an IME confirmation is left to the IME");
    await settle();
    assert.equal(patches(env).length, 0);
});

test("Ctrl+Enter saves once; while saving the form is locked, repeat submits are ignored and leaving is refused", async () => {
    let release;
    const env = await opened({ "PATCH /api/illustrations/5": () => new Promise((r) => { release = () => r(204); }) });
    const { ids } = env;
    typeInto(ids["ed-note"], "新的备注");
    ids["edit-form"].dispatch("keydown", { key: "Enter", ctrlKey: true, target: ids["ed-note"] });
    await settle();
    assert.equal(ids["ed-fields"].disabled, true);
    assert.equal(ids["ed-save"].disabled, true);
    assert.equal(ids["ed-cancel"].disabled, true);
    assert.equal(ids["ed-save"].textContent, "正在保存…");
    ids["edit-form"].dispatch("submit");
    env.Editor.save();
    assert.equal(await env.Editor.requestLeave(), false, "cannot leave mid-save");
    assert.match(env.toasts.at(-1), /正在保存/);
    release();
    await settle(); await settle();
    assert.equal(patches(env).length, 1);
    assert.deepEqual(patches(env)[0].body, { note: "新的备注" });
    assert.equal(env.Editor.isOpen(), false);
    assert.equal(env.toasts.at(-1), "已保存");
});

test("a failed save keeps the panel, the draft and the reason; the retry sends the same change", async () => {
    const env = await opened({ "PATCH /api/illustrations/5": 500 });
    const { ids } = env;
    typeInto(ids["ed-source"], "https://example.com/new");
    ids["edit-form"].dispatch("submit");
    await settle(); await settle();
    assert.equal(env.Editor.isOpen(), true);
    assert.equal(ids["ed-error"].hidden, false);
    assert.match(ids["ed-error"].textContent, /保存失败：服务没有保存这次修改（500）.*修改仍保留/);
    assert.equal(ids["ed-source"].value, "https://example.com/new");
    assert.equal(ids["ed-fields"].disabled, false);
    assert.equal(ids["ed-save"].disabled, false);

    const leaving = env.Editor.requestLeave();
    assert.equal(ids["confirm"].hidden, false, "the unsaved draft still asks before leaving");
    ids["confirm-cancel"].click();
    await leaving;

    ids["edit-form"].dispatch("submit");
    await settle(); await settle();
    assert.deepEqual(patches(env).map((p) => p.body), [{ sourceUrl: "https://example.com/new" }, { sourceUrl: "https://example.com/new" }]);
});

test("author and tags: pick, clear the author, remove a tag; the pickers come from server candidates", async () => {
    let saved = false;
    const env = await opened({ "PATCH /api/illustrations/5": () => { saved = true; return 204; } });
    const { ids } = env;
    assert.equal(ids["ed-author-clear"].hidden, false);
    ids["ed-author"].click();
    const authorPicker = env.pickers.at(-1);
    assert.equal(authorPicker.kind, "author");
    assert.equal(authorPicker.options.single, true);
    assert.deepEqual([...authorPicker.options.selected()], [7]);
    authorPicker.options.onToggle(9);
    assert.match(ids["ed-author"].children.map((c) => c.textContent).join(""), /author#9/);
    ids["ed-author-clear"].click();
    assert.equal(ids["ed-author-clear"].hidden, true);

    const tagChip = ids["ed-tags"].querySelectorAll(".ed-tag")[0];
    tagChip.children.find((c) => c.tagName === "BUTTON").click();
    assert.equal(ids["ed-tags"].querySelectorAll(".ed-tag").length, 0);
    ids["ed-tags"].querySelectorAll(".ed-tag-add")[0].click();
    const tagPicker = env.pickers.at(-1);
    assert.equal(tagPicker.kind, "tag");
    tagPicker.options.onToggle(3);
    tagPicker.options.onToggle(4);
    tagPicker.options.onToggle(3);
    ids["edit-form"].dispatch("submit");
    await settle(); await settle();
    assert.equal(saved, true);
    assert.deepEqual(patches(env)[0].body, { authorId: null, tagIds: [4] });
});

test("a query change cannot drop an unsaved draft or interrupt a save; it goes through once the editor is left", async () => {
    let release;
    const env = await opened({
        "/api/illustrations?page=0&size=30&q=x": { page: 0, size: 30, totalElements: 0, totalPages: 0, items: [] },
        "PATCH /api/illustrations/5": () => new Promise((r) => { release = () => r(204); })
    });
    const { ids } = env;
    const listRequests = () => env.requests.filter((r) => r.key.includes("q=x")).length;
    const blocked = [];
    env.Archive.subscribe((e) => { if (e.type === "blocked") blocked.push(e.type); });

    typeInto(ids["ed-title"], "未保存的标题");
    assert.equal(await env.Archive.setQuery({ q: "x" }), false, "refused while the draft is unsaved");
    assert.equal(blocked.length, 1);
    assert.equal(env.Editor.isOpen(), true);
    assert.equal(ids["ed-title"].value, "未保存的标题");
    assert.equal(env.Archive.works.length, 1, "the list was not cleared");

    ids["edit-form"].dispatch("submit");
    await settle();
    assert.equal(await env.Archive.setQuery({ q: "x" }), false, "refused while saving");
    assert.equal(env.Editor.isOpen(), true);
    release();
    await settle(); await settle();
    assert.equal(env.Editor.isOpen(), false, "the save finished and closed the editor");
    assert.equal(listRequests(), 0);

    await env.Archive.setQuery({ q: "x" });
    assert.equal(listRequests(), 1, "with the editor closed the query changes normally");
});

test("a detail that arrives for a work the editor has already left does not fill the panel", async () => {
    let release;
    const env = setup({ ...base(), "/api/illustrations/5": () => new Promise((r) => { release = () => r(detail); }),
        "/api/illustrations/6": { ...detail, id: 6, title: "Work 6" } });
    await env.Archive.setQuery({});
    env.Editor.open(5);
    env.Editor.close();
    release();
    await settle();
    assert.equal(env.ids["ed-title"].value, "", "the late detail for work 5 is ignored");
    assert.equal(env.Editor.isOpen(), false);
});
