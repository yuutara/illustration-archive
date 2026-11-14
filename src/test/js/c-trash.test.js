const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const dir = path.resolve(__dirname, "../../main/resources/static/c");
const trashSource = readFileSync(path.join(dir, "c-trash.js"), "utf8");
const trashHtml = readFileSync(path.join(dir, "trash.html"), "utf8");

class Node {
    constructor(tag = "div", id = null) {
        this.tagName = tag.toUpperCase();
        this.id = id;
        this.children = [];
        this.listeners = new Map();
        this.attributes = new Map();
        this.dataset = {};
        this.style = {};
        this.className = "";
        this.textContent = "";
        this.hidden = false;
        this.disabled = false;
        this.classList = {
            add: (...names) => names.forEach((n) => { if (n && !this.classList.contains(n)) this.className = `${this.className} ${n}`.trim(); }),
            remove: (...names) => { this.className = this.className.split(" ").filter((c) => c && !names.includes(c)).join(" "); },
            toggle: (n, on) => { const next = on === undefined ? !this.classList.contains(n) : !!on; (next ? this.classList.add : this.classList.remove)(n); return next; },
            contains: (n) => this.className.split(" ").includes(n)
        };
    }
    appendChild(child) {
        if (child && child.tagName === "#FRAGMENT") {
            child.children.forEach((c) => this.appendChild(c));
            child.children = [];
            return child;
        }
        this.children.push(child);
        return child;
    }
    append(...nodes) { nodes.forEach((n) => this.appendChild(n)); }
    replaceChildren(...nodes) { this.children = []; nodes.forEach((n) => this.appendChild(n)); }
    setAttribute(k, v) { this.attributes.set(k, String(v)); }
    getAttribute(k) { return this.attributes.get(k); }
    addEventListener(type, fn) { this.listeners.set(type, [...(this.listeners.get(type) || []), fn]); }
    dispatch(type, event = {}) {
        const e = { target: this, preventDefault() {}, stopPropagation() {}, ...event };
        (this.listeners.get(type) || []).forEach((fn) => fn(e));
        return e;
    }
    click() { return this.dispatch("click"); }
    focus() { if (this.ownerDocument) this.ownerDocument.activeElement = this; }
    pause() {}
    removeAttribute() {}
    load() {}
    querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
    querySelectorAll(sel) {
        const wantClass = sel.startsWith(".") ? sel.slice(1) : null;
        const wantTag = !wantClass ? sel.toUpperCase() : null;
        const out = [];
        const walk = (node) => {
            for (const child of node.children) {
                if ((wantClass && child.classList.contains(wantClass)) || (wantTag && child.tagName === wantTag)) out.push(child);
                walk(child);
            }
        };
        walk(this);
        return out;
    }
    get text() { return [this.textContent, ...this.children.map((c) => c.text)].join(" ").trim(); }
}

const IDS = [
    "trash-count", "trash-select-page", "trash-empty-btn", "trash-note",
    "trash-loading", "trash-error", "trash-error-text", "trash-retry",
    "trash-empty", "trash-empty-title", "trash-empty-text", "trash-back-link",
    "trash-grid", "trash-pager", "trash-prev", "trash-next", "trash-page",
    "trash-bar", "trash-selected", "trash-clear-btn", "trash-restore-btn", "trash-purge-btn",
    "trash-confirm", "trash-confirm-title", "trash-confirm-text", "trash-confirm-error",
    "trash-confirm-cancel", "trash-confirm-ok", "toast"
];

const settle = () => new Promise((r) => setTimeout(r, 0)).then(() => new Promise((r) => setTimeout(r, 0)));

function setup({ desktop = true, handler } = {}) {
    const document = { activeElement: null, listeners: new Map() };
    const make = (tag, id) => { const n = new Node(tag, id); n.ownerDocument = document; return n; };
    const ids = Object.fromEntries(IDS.map((id) => [id, make("div", id)]));
    ids["trash-confirm"].hidden = true;
    ids["trash-bar"].hidden = true;
    document.getElementById = (id) => ids[id];
    document.createElement = (tag) => make(tag, null);
    document.createDocumentFragment = () => make("#fragment", null);
    document.addEventListener = (type, fn) => document.listeners.set(type, [...(document.listeners.get(type) || []), fn]);

    const calls = [];
    let refreshedTrash = 0;
    const window = {
        UiCapabilities: { desktop },
        AppShell: { refreshTrash: () => { refreshedTrash++; } },
        addEventListener() {}
    };
    const context = {
        window, document, console, Date,
        setTimeout: () => 1, clearTimeout() {},
        fetch: async (url, init = {}) => {
            const req = { url, method: init.method || "GET", body: init.body ? JSON.parse(init.body) : null };
            calls.push(req);
            return handler(req);
        }
    };
    vm.createContext(context);
    vm.runInContext(trashSource, context);
    return { ids, calls, window, document, refreshedTrash: () => refreshedTrash };
}

test("trash.html stays wired cleanly for desktop navigation", () => {
    assert.match(trashHtml, /href="\/c\/trash\.html" aria-current="page"/);
    assert.match(trashHtml, /src="c-trash\.js" defer/);
    assert.match(trashHtml, /role="alertdialog"/);
});

test("server mode shows desktop-only notice without calling /api/trash", async () => {
    const env = setup({ desktop: false, handler: () => { throw new Error("unexpected fetch"); } });
    await settle();
    assert.equal(env.calls.length, 0);
    assert.equal(env.ids["trash-empty"].hidden, false);
    assert.match(env.ids["trash-empty-title"].textContent, /桌面端/);
    assert.equal(env.ids["trash-back-link"].hidden, false);
});

test("desktop trash renders dates, restores selected works, and confirms permanent purge & empty separately", async () => {
    let items = [
        {
            id: 11, title: "星海", author: { id: 2, name: "画师A" }, coverAssetId: 101, coverMimeType: "image/png", assetCount: 2,
            trashedAt: "2026-10-01T00:00:00Z", purgeAt: "2026-10-31T00:00:00Z"
        },
        {
            id: 12, title: "动图", author: null, coverAssetId: 102, coverMimeType: "video/mp4", assetCount: 1,
            trashedAt: "2026-10-02T00:00:00Z", purgeAt: "2026-11-01T00:00:00Z"
        }
    ];
    const env = setup({
        desktop: true,
        handler: async (req) => {
            if (req.url.startsWith("/api/trash?")) {
                return { ok: true, status: 200, json: async () => ({ items, page: 0, size: 24, totalItems: items.length, totalPages: items.length ? 1 : 0, retentionDays: 30 }) };
            }
            if (req.url === "/api/trash/restore") {
                items = items.filter((i) => !req.body.ids.includes(i.id));
                return { ok: true, status: 200, json: async () => ({ requestedCount: req.body.ids.length, restoredCount: req.body.ids.length, restoredIds: req.body.ids }) };
            }
            if (req.url === "/api/trash/purge") {
                items = items.filter((i) => !req.body.ids.includes(i.id));
                return { ok: true, status: 200, json: async () => ({ requestedCount: req.body.ids.length, purgedCount: req.body.ids.length, purgedIds: req.body.ids, cleanedFiles: 1, pendingFileCleanups: 0 }) };
            }
            if (req.url === "/api/trash/purge-all") {
                const count = items.length;
                items = [];
                return { ok: true, status: 200, json: async () => ({ requestedCount: count, purgedCount: count, purgedIds: [12], cleanedFiles: 1, pendingFileCleanups: 0 }) };
            }
            return { ok: false, status: 404, json: async () => ({}) };
        }
    });
    await settle();

    assert.equal(env.ids["trash-count"].textContent, "2 件");
    const cards = env.ids["trash-grid"].children;
    assert.equal(cards.length, 2);
    assert.match(cards[0].text, /删除于/);
    assert.match(cards[0].text, /自动清理/);
    assert.equal(cards[0].querySelectorAll("IMG").length, 1);
    assert.equal(cards[1].querySelectorAll("VIDEO").length, 1);

    // Multi-select item 11 and restore via toolbar
    cards[0].querySelector(".trash-check").click();
    assert.equal(env.ids["trash-bar"].hidden, false);
    assert.equal(env.ids["trash-selected"].textContent, "已选 1 件");
    env.ids["trash-restore-btn"].click();
    await settle();

    assert.deepEqual(env.calls.find((c) => c.url === "/api/trash/restore").body, { ids: [11] });
    assert.equal(env.ids["trash-grid"].children.length, 1);
    assert.equal(env.refreshedTrash(), 1);
    assert.match(env.ids["toast"].textContent, /已恢复 1 件作品/);

    // Selected permanent purge opens separate danger confirmation
    cardsNow(env)[0].querySelectorAll("BUTTON").find((b) => b.textContent === "永久删除").click();
    assert.equal(env.ids["trash-confirm"].hidden, false);
    assert.match(env.ids["trash-confirm-title"].textContent, /永久删除这 1 件作品/);
    assert.equal(env.ids["trash-confirm-ok"].textContent, "永久删除");
    env.ids["trash-confirm-cancel"].click();
    assert.equal(env.ids["trash-confirm"].hidden, true);

    // Empty all trash has its own distinct confirmation & calls /api/trash/purge-all
    env.ids["trash-empty-btn"].click();
    assert.equal(env.ids["trash-confirm"].hidden, false);
    assert.match(env.ids["trash-confirm-title"].textContent, /清空回收站里的全部 1 件作品/);
    assert.equal(env.ids["trash-confirm-ok"].textContent, "清空回收站");
    env.ids["trash-confirm-ok"].click();
    await settle();

    assert.ok(env.calls.some((c) => c.url === "/api/trash/purge-all" && c.method === "POST"));
    assert.equal(env.ids["trash-empty"].hidden, false);
    assert.equal(env.refreshedTrash(), 2);
});

function cardsNow(env) {
    return env.ids["trash-grid"].children;
}
