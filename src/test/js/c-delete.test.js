const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const dir = path.resolve(__dirname, "../../main/resources/static/c");
const archiveSource = readFileSync(path.join(dir, "archive-client.js"), "utf8");
const deleteSource = readFileSync(path.join(dir, "c-delete.js"), "utf8");

/** Just enough DOM for the delete confirmation. */
class Node {
    constructor(tag = "div", id = null) {
        this.tagName = tag.toUpperCase();
        this.id = id;
        this.listeners = new Map();
        this.attributes = new Map();
        this.className = "";
        this.textContent = "";
        this.hidden = false;
        this.disabled = false;
        this.inert = false;
        this.isConnected = true;
        this.focused = 0;
        this.classList = {
            add: (n) => { if (!this.className.split(" ").includes(n)) this.className = (this.className + " " + n).trim(); },
            remove: (n) => { this.className = this.className.split(" ").filter((c) => c !== n).join(" "); },
            toggle: (n, on) => { (on ? this.classList.add : this.classList.remove)(n); return on; },
            contains: (n) => this.className.split(" ").includes(n)
        };
    }
    setAttribute(name, value) { this.attributes.set(name, String(value)); }
    getAttribute(name) { return this.attributes.get(name) ?? null; }
    addEventListener(type, fn) { this.listeners.set(type, [...(this.listeners.get(type) || []), fn]); }
    dispatch(type, event = {}) {
        const e = {
            key: "", shiftKey: false, target: this,
            preventDefault() { this.defaultPrevented = true; }, stopPropagation() { this.stopped = true; }, ...event
        };
        (this.listeners.get(type) || []).forEach((fn) => fn(e));
        return e;
    }
    // A disabled button ignores clicks, as in a browser
    click() { return this.disabled ? null : this.dispatch("click"); }
    focus() { this.focused++; this.ownerDocument.activeElement = this; }
    blur() { if (this.ownerDocument.activeElement === this) this.ownerDocument.activeElement = this.ownerDocument.body; }
}

const IDS = ["delete-confirm", "del-dialog", "del-title", "del-cover", "del-thumb", "del-name", "del-meta", "del-text",
    "del-draft", "del-status", "del-ok", "del-cancel", "viewer", "sv-more"];

const NO_CONTENT = Symbol("204");

// Covers are measured before they reach the wall: every image "loads" on the next turn
class FakeImage {
    set src(url) { this._src = url; setImmediate(() => { this.naturalWidth = 600; this.naturalHeight = 800; this.onload?.(); }); }
    get src() { return this._src; }
}

function setup(responses, { viewerOpen = true, editorState = {} } = {}) {
    const document = { activeElement: null };
    const make = (tag, id) => { const n = new Node(tag, id); n.ownerDocument = document; return n; };
    const ids = Object.fromEntries(IDS.map((id) => [id, make(id.startsWith("del-ok") || id.startsWith("del-cancel") ? "button" : "div", id)]));
    ids["delete-confirm"].hidden = true;
    document.body = make("body");
    // Everything named del-* sits inside the confirmation
    ids["delete-confirm"].contains = (n) => !!n && /^del-/.test(n.id || "");
    document.getElementById = (id) => ids[id];
    const requests = [];
    const toasts = [];
    const inert = [];
    const window = { addEventListener() {} };
    const context = {
        window, document, console, URLSearchParams, AbortController, Image: FakeImage,
        fetch: async (url, init = {}) => {
            const key = init.method && init.method !== "GET" ? `${init.method} ${url}` : url;
            requests.push(key);
            let reply = responses[key];
            if (typeof reply === "function") reply = await reply();
            if (reply instanceof Error) throw reply;
            if (reply === NO_CONTENT) return { ok: true, status: 204, json: async () => { throw new SyntaxError("no body"); } };
            if (typeof reply === "number") return { ok: false, status: reply, json: async () => ({}) };
            return { ok: true, status: 200, json: async () => reply };
        }
    };
    vm.createContext(context);
    vm.runInContext(archiveSource, context);
    const editor = { saving: false, dirty: false, editingId: null, ...editorState };
    window.CEditor = { isSaving: () => editor.saving, isDirty: () => editor.dirty, editingId: () => editor.editingId };
    window.GalleryApi = {
        toast: (t) => toasts.push(t),
        isViewerOpen: () => viewerOpen,
        setBackgroundInert: (on) => inert.push(on)
    };
    vm.runInContext(deleteSource, context);
    const events = [];
    window.Archive.subscribe((e) => events.push(JSON.parse(JSON.stringify(e))));
    return { ids, requests, toasts, inert, editor, events, Archive: window.Archive, Delete: window.CDelete, document };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

function held(reply) {
    let release;
    const gate = new Promise((resolve) => { release = () => resolve(reply); });
    const fn = () => gate;
    fn.release = () => release();
    return fn;
}

const asset = (id, sortOrder) => ({ id, mimeType: "image/png", sortOrder });
const work3 = { id: 3, title: "三联画", author: { id: 1, displayName: "雨宫" }, coverAssetId: 30, coverMimeType: "image/png",
    assetCount: 3, createdAt: "2026-10-28T10:00:00", assets: [asset(30, 0), asset(31, 1), asset(32, 2)] };
const work4 = { id: 4, title: "", author: null, coverAssetId: 40, coverMimeType: "image/png", assetCount: 1,
    createdAt: "2026-10-28T10:00:00", assets: [asset(40, 0)] };
const list = (items) => ({ "/api/illustrations?page=0&size=30": { page: 0, size: 30, totalElements: items.length, totalPages: 1, items } });

test("the confirmation names the work and its pages, says it is permanent, starts on Cancel and locks the viewer behind", async () => {
    const env = await readyEnv();
    const viewerFocus = env.ids["sv-more"];
    assert.equal(env.Delete.request(3, { returnFocus: viewerFocus }), true);
    assert.equal(env.ids["delete-confirm"].hidden, false);
    assert.equal(env.ids["del-title"].textContent, "永久删除这件作品？");
    assert.equal(env.ids["del-name"].textContent, "三联画");
    assert.match(env.ids["del-meta"].textContent, /雨宫/);
    assert.match(env.ids["del-meta"].textContent, /3 页，共 3 张图片/);
    assert.match(env.ids["del-text"].textContent, /全部 3 张图片.*永久删除，无法恢复/);
    assert.equal(env.ids["del-cover"].classList.contains("is-stack"), true, "a multi-page work shows a stack");
    assert.equal(env.ids["del-draft"].hidden, true);
    assert.equal(env.document.activeElement, env.ids["del-cancel"], "the safe choice has the focus");
    assert.equal(env.ids["viewer"].inert, true);
    assert.equal(env.ids["del-ok"].textContent, "永久删除");
    assert.equal(env.Delete.request(4), false, "only one confirmation at a time");
    assert.equal(env.requests.filter((r) => r.startsWith("DELETE")).length, 0, "opening it sends nothing");
});

test("Cancel, Esc and a click on the backdrop delete nothing, keep the draft and return the focus", async () => {
    const env = await readyEnv({ editorState: { dirty: true, editingId: 3 } });
    const back = env.ids["sv-more"];
    for (const leave of [
        () => env.ids["del-cancel"].click(),
        () => env.ids["delete-confirm"].dispatch("keydown", { key: "Escape" }),
        () => env.ids["delete-confirm"].dispatch("mousedown", { target: env.ids["delete-confirm"] })
    ]) {
        env.Delete.request(3, { returnFocus: back });
        assert.equal(env.ids["del-draft"].hidden, false, "unsaved edits are mentioned");
        assert.match(env.ids["del-draft"].textContent, /未保存的修改/);
        leave();
        assert.equal(env.ids["delete-confirm"].hidden, true);
        assert.equal(env.ids["viewer"].inert, false);
        assert.equal(env.document.activeElement, back);
        assert.equal(env.editor.dirty, true, "the draft is untouched");
    }
    assert.equal(env.requests.filter((r) => r.startsWith("DELETE")).length, 0);
    assert.equal(env.Archive.listed(3), true);
    assert.equal(env.events.filter((e) => e.type === "removed").length, 0);
});

test("while the editor is saving the work cannot be deleted", async () => {
    const env = await readyEnv({ editorState: { saving: true, editingId: 3 } });
    assert.equal(env.Delete.request(3), false);
    assert.equal(env.ids["delete-confirm"].hidden, true);
    assert.match(env.toasts.at(-1), /正在保存/);
});

test("deleting locks both buttons, ignores repeats and Esc, then removes the work and reports it", async () => {
    const gate = held(NO_CONTENT);
    const env = await readyEnv({ extra: { "DELETE /api/illustrations/3": gate } });
    env.Delete.request(3, { returnFocus: env.ids["sv-more"] });
    env.ids["del-ok"].click();
    assert.equal(env.ids["del-ok"].disabled, true);
    assert.equal(env.ids["del-cancel"].disabled, true);
    assert.equal(env.ids["del-ok"].textContent, "正在删除…");
    assert.equal(env.ids["delete-confirm"].getAttribute("aria-busy"), "true");
    assert.equal(env.document.activeElement, env.ids["del-dialog"], "focus stays in the dialog while its buttons are locked");
    env.ids["del-ok"].click();
    env.ids["del-ok"].dispatch("click");
    env.ids["delete-confirm"].dispatch("keydown", { key: "Escape" });
    env.ids["delete-confirm"].dispatch("mousedown", { target: env.ids["delete-confirm"] });
    assert.equal(env.ids["delete-confirm"].hidden, false, "it cannot be dismissed mid-request");
    const key = env.ids["delete-confirm"].dispatch("keydown", { key: "Delete" });
    assert.equal(key.stopped, true, "keys inside the dialog never reach the gallery shortcuts");
    gate.release();
    await settle();
    await settle();
    assert.equal(env.requests.filter((r) => r === "DELETE /api/illustrations/3").length, 1, "one request only");
    assert.equal(env.ids["delete-confirm"].hidden, true);
    assert.equal(env.ids["viewer"].inert, false);
    assert.deepEqual(env.events.filter((e) => e.type === "removed"), [{ type: "removed", id: 3, index: 0 }]);
    assert.equal(env.Archive.listed(3), false);
    assert.equal(env.Archive.total(), 1);
    assert.equal(env.toasts.at(-1), "已永久删除“三联画”（3 张图片）");
    assert.equal(env.document.activeElement, env.document.body,
        "the hidden dialog lets go of the focus, so the gallery can place it; it is not sent back to the closed viewer's menu");
});

test("a refused delete stays open with the reason and can be retried; the list is untouched", async () => {
    let replies = [403, NO_CONTENT];
    const env = await readyEnv({ extra: { "DELETE /api/illustrations/4": () => replies.shift() }, viewerOpen: false });
    env.Delete.request(4);
    assert.deepEqual(env.inert, [true], "from the wall, the gallery behind is made inert");
    env.ids["del-ok"].click();
    await settle();
    assert.equal(env.ids["delete-confirm"].hidden, false);
    assert.equal(env.ids["del-status"].hidden, false);
    assert.match(env.ids["del-status"].textContent, /^没有删除：本机访问会话已失效/);
    assert.equal(env.ids["del-ok"].textContent, "重试删除");
    assert.equal(env.ids["del-ok"].disabled, false);
    assert.equal(env.document.activeElement, env.ids["del-cancel"], "after a failure the focus is back on the safe choice");
    assert.equal(env.Archive.listed(4), true);
    env.ids["del-ok"].click();
    await settle();
    await settle();
    assert.equal(env.requests.filter((r) => r === "DELETE /api/illustrations/4").length, 2);
    assert.equal(env.ids["delete-confirm"].hidden, true);
    assert.deepEqual(env.inert, [true, false]);
    assert.equal(env.Archive.listed(4), false);
    assert.equal(env.toasts.at(-1), "已永久删除“未命名作品”", "a single image has no count");
});

test("a lost reply that cannot be checked says so; Re-check only reads and then confirms the delete", async () => {
    let detail = new TypeError("Failed to fetch");
    const env = await readyEnv({ extra: {
        "DELETE /api/illustrations/3": new TypeError("Failed to fetch"),
        "/api/illustrations/3": () => detail
    } });
    env.Delete.request(3, { returnFocus: env.ids["sv-more"] });
    env.ids["del-ok"].click();
    await settle();
    assert.equal(env.ids["del-title"].textContent, "无法确认是否已删除");
    assert.match(env.ids["del-status"].textContent, /无法确认/);
    assert.doesNotMatch(env.ids["del-status"].textContent, /没有删除|已删除/);
    assert.equal(env.ids["del-ok"].textContent, "重新核实");
    assert.equal(env.ids["del-ok"].className, "btn btn-ink", "re-checking is not a destructive action");
    assert.equal(env.ids["del-cancel"].textContent, "关闭");
    assert.equal(env.Archive.listed(3), true, "an unconfirmed delete keeps the work listed");

    detail = 404;
    env.ids["del-ok"].click();
    assert.equal(env.ids["del-ok"].textContent, "正在核实…");
    await settle();
    await settle();
    assert.equal(env.requests.filter((r) => r === "DELETE /api/illustrations/3").length, 1, "re-checking never deletes again");
    assert.equal(env.ids["delete-confirm"].hidden, true);
    assert.equal(env.Archive.listed(3), false);
    assert.match(env.toasts.at(-1), /^已永久删除“三联画”（3 张图片）。删除时没有收到确切回应，已重新读取确认$/);
});

test("closing an unconfirmed delete keeps the work and says the result is unknown", async () => {
    const env = await readyEnv({ extra: {
        "DELETE /api/illustrations/3": 500,
        "/api/illustrations/3": 500
    } });
    env.Delete.request(3, { returnFocus: env.ids["sv-more"] });
    env.ids["del-ok"].click();
    await settle();
    assert.match(env.ids["del-status"].textContent, /服务返回 500/);
    env.ids["del-cancel"].click();
    assert.equal(env.ids["delete-confirm"].hidden, true);
    assert.equal(env.Archive.listed(3), true);
    assert.match(env.toasts.at(-1), /无法确认是否已删除/);
    assert.equal(env.document.activeElement, env.ids["sv-more"]);
});

test("a work already deleted elsewhere (404) is removed from the list with that explanation", async () => {
    const env = await readyEnv({ extra: { "DELETE /api/illustrations/3": 404 } });
    env.Delete.request(3);
    env.ids["del-ok"].click();
    await settle();
    await settle();
    assert.equal(env.Archive.listed(3), false);
    assert.match(env.toasts.at(-1), /已不存在/);
});

test("Tab moves only between the two buttons", async () => {
    const env = await readyEnv();
    env.Delete.request(3);
    const box = env.ids["delete-confirm"];
    assert.equal(env.document.activeElement, env.ids["del-cancel"]);
    box.dispatch("keydown", { key: "Tab" });
    assert.equal(env.document.activeElement, env.ids["del-ok"]);
    box.dispatch("keydown", { key: "Tab" });
    assert.equal(env.document.activeElement, env.ids["del-cancel"]);
    box.dispatch("keydown", { key: "Tab", shiftKey: true });
    assert.equal(env.document.activeElement, env.ids["del-ok"]);
});

async function readyEnv({ extra = {}, viewerOpen = true, editorState = {} } = {}) {
    const env = setup({ ...list([work3, work4]), ...extra }, { viewerOpen, editorState });
    await env.Archive.setQuery({});
    return env;
}
