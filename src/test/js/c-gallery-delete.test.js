const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

/*
 * The hand-off between the delete confirmation (c-delete.js) and the gallery (c-gallery.js): after a delete the
 * gallery decides where the keyboard focus goes. Both real scripts run on the real data layer over a small fake DOM.
 */
const dir = path.resolve(__dirname, "../../main/resources/static/c");
const sources = ["archive-client.js", "c-gallery.js", "c-delete.js"].map((f) => readFileSync(path.join(dir, f), "utf8"));

// Simple selectors only: "#id", ".cls", "tag", "tag.cls", and comma lists. Anything else (descendant, :hover) never matches.
function matchesOne(node, selector) {
    const m = /^([a-z][\w-]*)?((?:[.#][\w-]+)*)$/i.exec(selector.trim());
    if (!m) return false;
    if (m[1] && node.tagName !== m[1].toUpperCase()) return false;
    return (m[2].match(/[.#][\w-]+/g) || []).every((p) => (p[0] === "#" ? node.id === p.slice(1) : node.classList.contains(p.slice(1))));
}

let doc = null;

class Element {
    constructor(tag = "div", id = null) {
        this.tagName = tag.toUpperCase();
        this.id = id;
        this.children = [];
        this.parent = null;
        this.listeners = new Map();
        this.attributes = new Map();
        this.className = "";
        this.textContent = "";
        this.hidden = false;
        this.disabled = false;
        this.inert = false;
        this.dataset = {};
        this.clientWidth = 0;
        this.clientHeight = 0;
        const styles = {};
        this.style = { setProperty: (k, v) => { styles[k] = v; } };
        this.classList = {
            add: (n) => { if (!this.classList.contains(n)) this.className = (this.className + " " + n).trim(); },
            remove: (n) => { this.className = this.className.split(" ").filter((c) => c && c !== n).join(" "); },
            toggle: (n, on = !this.classList.contains(n)) => { (on ? this.classList.add : this.classList.remove)(n); return on; },
            contains: (n) => this.className.split(" ").includes(n)
        };
    }
    get isConnected() {
        let n = this;
        while (n.parent) n = n.parent;
        return n === doc.body || n.rooted === true;
    }
    get firstChild() { return this.children[0] || null; }
    get firstElementChild() { return this.children[0] || null; }
    // A tiny, flat parser: every opening tag becomes a child of this node, keeping its class and id
    set innerHTML(html) {
        this.children.forEach((c) => { c.parent = null; });
        this.children = [];
        for (const [, tag, attrs] of String(html).matchAll(/<([a-zA-Z][\w-]*)([^>]*)>/g)) {
            const child = doc.createElement(tag);
            const cls = /class="([^"]*)"/.exec(attrs);
            if (cls) child.className = cls[1];
            const id = /id="([^"]*)"/.exec(attrs);
            if (id) child.id = id[1];
            this.appendChild(child);
        }
    }
    appendChild(child) { child.remove(); child.parent = this; this.children.push(child); return child; }
    prepend(child) { child.remove(); child.parent = this; this.children.unshift(child); return child; }
    append(...nodes) { nodes.forEach((n) => (typeof n === "string" ? (this.textContent += n) : this.appendChild(n))); }
    remove() { if (this.parent) { this.parent.children = this.parent.children.filter((c) => c !== this); this.parent = null; } }
    contains(other) { for (let n = other; n; n = n.parent) if (n === this) return true; return false; }
    matches(selector) { return selector.split(",").some((s) => matchesOne(this, s)); }
    closest(selector) { for (let n = this; n; n = n.parent) if (n.matches(selector)) return n; return null; }
    querySelectorAll(selector) {
        const out = [];
        const walk = (n) => n.children.forEach((c) => { if (c.matches(selector)) out.push(c); walk(c); });
        walk(this);
        return out;
    }
    // Missing parts of the static page are not under test: hand back a detached stand-in instead of null
    querySelector(selector) { return this.querySelectorAll(selector)[0] || doc.createElement("span"); }
    setAttribute(name, value) { this.attributes.set(name, String(value)); }
    getAttribute(name) { return this.attributes.get(name) ?? null; }
    removeAttribute(name) { this.attributes.delete(name); }
    addEventListener(type, fn) { this.listeners.set(type, [...(this.listeners.get(type) || []), fn]); }
    dispatch(type, init = {}) {
        const e = { target: this, key: "", preventDefault() { this.defaultPrevented = true; }, stopPropagation() { this.stopped = true; }, ...init };
        (this.listeners.get(type) || []).forEach((fn) => fn(e));
        return e;
    }
    click() { return this.disabled ? null : this.dispatch("click"); }
    // As in a browser: hiding an element does not move the focus away from it, and blur() hands it back to <body>
    focus() { doc.activeElement = this; }
    blur() { if (doc.activeElement === this) doc.activeElement = doc.body; }
    scrollIntoView() {}
    getBoundingClientRect() { return { left: 0, top: 0, width: 100, height: 100, right: 100, bottom: 100 }; }
    getAnimations() { return []; }
}

class FakeImage {
    set src(url) { this._src = url; setImmediate(() => { this.naturalWidth = 600; this.naturalHeight = 800; this.onload?.(); }); }
    get src() { return this._src; }
}

const STATIC_IDS = ["masthead", "wall", "wall-status", "wall-status-text", "wall-retry", "wall-more", "empty", "empty-import",
    "no-match", "no-match-clear", "count-all", "q", "toast", "theme-toggle", "help-open", "shortcuts", "shortcuts-close",
    "viewer", "sv-canvas", "sv-img", "sv-notice", "sv-prev", "sv-next", "sv-back", "sv-zoom", "sv-info-toggle", "sv-edit-toggle",
    "sv-more", "sv-menu", "sv-original", "sv-source", "sv-fit", "sv-actual", "sv-delete", "sv-title", "sv-by", "sv-pages",
    "sv-num", "sv-pos", "sv-info",
    "delete-confirm", "del-dialog", "del-title", "del-cover", "del-thumb", "del-name", "del-meta", "del-text", "del-draft",
    "del-status", "del-ok", "del-cancel"];

const settle = async (n = 4) => { for (let i = 0; i < n; i++) await new Promise((r) => setImmediate(r)); };

function setup(responses, { search = "" } = {}) {
    const ids = {};
    doc = {
        activeElement: null,
        listeners: new Map(),
        createElement: (tag) => new Element(tag),
        getElementById: (id) => ids[id] || null,
        querySelectorAll: (selector) => doc.body.querySelectorAll(selector),
        addEventListener(type, fn) { this.listeners.set(type, [...(this.listeners.get(type) || []), fn]); },
        documentElement: Object.assign(new Element("html"), { dataset: { mode: "light" } })
    };
    doc.body = new Element("body");
    doc.body.rooted = true;
    doc.activeElement = doc.body;
    STATIC_IDS.forEach((id) => { ids[id] = doc.body.appendChild(new Element(id.match(/-(ok|cancel|clear|import|more|delete)$/) ? "button" : "div", id)); });
    ["shortcuts", "viewer", "sv-menu", "delete-confirm", "empty", "no-match"].forEach((id) => { ids[id].hidden = true; });
    // Keep the dialog's real nesting, which the focus hand-off depends on
    ids["delete-confirm"].appendChild(ids["del-dialog"]);
    ["del-title", "del-cover", "del-name", "del-meta", "del-text", "del-draft", "del-status", "del-ok", "del-cancel"]
        .forEach((id) => ids["del-dialog"].appendChild(ids[id]));
    ids["no-match"].appendChild(ids["no-match-clear"]);
    ids["empty"].appendChild(ids["empty-import"]);
    ids["sv-menu"].appendChild(ids["sv-delete"]);

    const requests = [];
    const toasts = [];
    const timer = (fn, ms) => { const t = setTimeout(fn, ms); t.unref?.(); return t; };
    const window = { addEventListener() {}, scrollY: 0, scrollTo() {}, innerWidth: 1400, innerHeight: 900, CTheme: { set() {} } };
    const context = {
        window, document: doc, console, URLSearchParams, AbortController, Image: FakeImage, Element,
        location: { search },
        setTimeout: timer, clearTimeout,
        requestAnimationFrame: (fn) => timer(fn, 0),
        IntersectionObserver: class { observe() {} },
        ResizeObserver: class { observe() {} },
        fetch: async (url, init = {}) => {
            const key = init.method && init.method !== "GET" ? `${init.method} ${url}` : url;
            requests.push(key);
            let reply = responses[key];
            if (typeof reply === "function") reply = reply();
            if (reply === 204) return { ok: true, status: 204, json: async () => { throw new SyntaxError("no body"); } };
            if (typeof reply === "number") return { ok: false, status: reply, json: async () => ({}) };
            if (!reply) return { ok: false, status: 404, json: async () => ({}) };
            return { ok: true, status: 200, json: async () => reply };
        }
    };
    vm.createContext(context);
    vm.runInContext(sources[0], context);
    window.CFilters = { renderSummary() {}, cancelPendingSearch() {}, closePicker() {}, isPickerOpen: () => false };
    window.CEditor = { isOpen: () => false, isConfirming: () => false, isSaving: () => false, isDirty: () => false,
        editingId: () => null, requestLeave: async () => true, close() {}, open() {} };
    vm.runInContext(sources[1], context);
    window.GalleryApi.toast = (t) => toasts.push(t);
    vm.runInContext(sources[2], context);
    const key = (target, k) => (doc.listeners.get("keydown") || []).forEach((fn) => fn({
        key: k, target, ctrlKey: false, metaKey: false, preventDefault() {}, stopPropagation() {}
    }));
    return { ids, requests, toasts, doc, key, Archive: window.Archive, Gallery: window.GalleryApi };
}

const item = (id) => ({ id, title: `Work ${id}`, author: null, coverAssetId: id * 10, coverMimeType: "image/png", assetCount: 1,
    createdAt: "2026-10-28T10:00:00", assets: [{ id: id * 10, mimeType: "image/png", sortOrder: 0 }] });

/** A server whose list (optionally filtered by ?q=) shrinks when a DELETE succeeds. */
function library(ids, { match = () => true } = {}) {
    const rows = ids.map(item);
    const page = (filter) => () => {
        const hits = rows.filter(filter);
        return { page: 0, size: 30, totalElements: hits.length, totalPages: 1, items: hits };
    };
    const responses = {
        "/api/illustrations?page=0&size=30": page(() => true),
        "/api/illustrations?page=0&size=30&q=keep": page(match),
        "/api/illustrations?page=0&size=1": () => ({ page: 0, size: 1, totalElements: rows.length, totalPages: 1, items: [] })
    };
    ids.forEach((id) => {
        responses[`DELETE /api/illustrations/${id}`] = () => { rows.splice(rows.findIndex((r) => r.id === id), 1); return 204; };
    });
    return responses;
}

const card = (env, id) => env.Gallery.pieces.get(id);
const cardButton = (env, id) => card(env, id).querySelector(".piece-open");

async function deleteFromWall(env, id) {
    const button = cardButton(env, id);
    button.focus();
    env.key(button, "Delete");
    assert.equal(env.ids["delete-confirm"].hidden, false, "Delete on a focused card opens the confirmation");
    assert.equal(env.doc.activeElement, env.ids["del-cancel"]);
    env.ids["del-ok"].click();
    assert.equal(env.ids["del-ok"].disabled, true, "locked while deleting");
    await settle();
    assert.equal(env.ids["delete-confirm"].hidden, true);
}

test("deleting a focused card from the wall moves the focus to the next card, or the previous one at the end", async () => {
    const env = setup(library([3, 2, 1]));
    await settle();
    assert.equal(env.Archive.works.length, 3);

    await deleteFromWall(env, 2);
    assert.equal(env.Archive.listed(2), false);
    assert.equal(env.doc.activeElement, cardButton(env, 1), "the next card takes the deleted card's place and the focus");
    assert.equal(env.Gallery.state.focusId, 1);

    await deleteFromWall(env, 1);
    assert.equal(env.doc.activeElement, cardButton(env, 3), "no next card: the previous one gets the focus");
    assert.equal(env.requests.filter((r) => r.startsWith("DELETE")).length, 2);
});

test("deleting the only match of a search moves the focus to Clear all conditions", async () => {
    const env = setup(library([3, 2, 1], { match: (r) => r.id === 2 }), { search: "?q=keep" });
    await settle();
    assert.equal(env.Archive.works.length, 1);
    await deleteFromWall(env, 2);
    await settle();
    assert.equal(env.ids["no-match"].hidden, false);
    assert.equal(env.doc.activeElement, env.ids["no-match-clear"]);
    assert.equal(env.Archive.query().q, "keep", "the search is kept");
});

test("deleting the last work in the library moves the focus to the empty gallery's import button", async () => {
    const env = setup(library([1]));
    await settle();
    await deleteFromWall(env, 1);
    assert.equal(env.ids["empty"].hidden, false);
    assert.equal(env.doc.activeElement, env.ids["empty-import"]);
});

test("cancelling from the wall sends nothing and returns the focus to the same card", async () => {
    const env = setup(library([3, 2, 1]));
    await settle();
    const button = cardButton(env, 2);
    button.focus();
    env.key(button, "Delete");
    env.ids["del-cancel"].click();
    assert.equal(env.ids["delete-confirm"].hidden, true);
    assert.equal(env.doc.activeElement, button);
    assert.equal(env.Archive.listed(2), true);
    assert.equal(env.requests.filter((r) => r.startsWith("DELETE")).length, 0);
});

test("deleting from the viewer closes it and focuses the card that took the work's place", async () => {
    const env = setup(library([3, 2, 1]));
    await settle();
    cardButton(env, 3).click();
    assert.equal(env.ids["viewer"].hidden, false);
    env.key(env.ids["viewer"], "Delete");
    assert.equal(env.ids["delete-confirm"].hidden, false);
    assert.equal(env.ids["viewer"].inert, true);
    env.ids["del-ok"].click();
    await settle();
    assert.equal(env.ids["viewer"].hidden, true, "back in the gallery");
    assert.equal(env.Gallery.isViewerOpen(), false);
    assert.equal(env.doc.activeElement, cardButton(env, 2));
});
