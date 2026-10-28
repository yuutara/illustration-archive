const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const dir = path.resolve(__dirname, "../../main/resources/static/c");
const archiveSource = readFileSync(path.join(dir, "archive-client.js"), "utf8");
const filtersSource = readFileSync(path.join(dir, "c-filters.js"), "utf8");

/**
 * Just enough DOM for the search box, the active-filter chips and the picker. A `.class` lookup inside markup set
 * through innerHTML creates that element on first use; a list is 280px tall with 32px rows.
 */
class Node {
    constructor(tag = "div", id = null) {
        this.tagName = tag;
        this.id = id;
        this.children = [];
        this.listeners = new Map();
        this.attributes = new Map();
        this.className = "";
        this.textContent = "";
        this.value = "";
        this.hidden = false;
        this.style = {};
        this.scrollTop = 0;
        this.focused = 0;
        this.firstChild = { textContent: "" };
        this.classList = {
            add: (n) => { if (!this.className.split(" ").includes(n)) this.className = (this.className + " " + n).trim(); },
            remove: (n) => { this.className = this.className.split(" ").filter((c) => c !== n).join(" "); },
            toggle: (n, on) => { (on ? this.classList.add : this.classList.remove)(n); return on; },
            contains: (n) => this.className.split(" ").includes(n)
        };
    }
    set innerHTML(html) { this.children = []; this.html = html; }
    appendChild(child) { child.parent = this; this.children.push(child); return child; }
    remove() { this.removed = true; }
    setAttribute(name, value) { this.attributes.set(name, String(value)); }
    getAttribute(name) { return this.attributes.get(name) ?? null; }
    addEventListener(type, fn) { this.listeners.set(type, [...(this.listeners.get(type) || []), fn]); }
    removeEventListener() {}
    dispatch(type, event = {}) {
        const e = {
            key: "", isComposing: false, keyCode: 0, target: this,
            preventDefault() { this.defaultPrevented = true; }, stopPropagation() { this.stopped = true; }, ...event
        };
        (this.listeners.get(type) || []).forEach((fn) => fn(e));
        return e;
    }
    all(cls) { return this.children.flatMap((c) => [...(c.classList.contains(cls) ? [c] : []), ...c.all(cls)]); }
    querySelector(selector) {
        const cls = selector.slice(1);
        const found = this.all(cls)[0];
        if (found) return found;
        const tag = (this.html || "").match(new RegExp(`<(\\w+) class="${cls}"`));
        const node = new Node(tag ? tag[1] : "span");
        node.className = cls;
        return this.appendChild(node);
    }
    querySelectorAll(selector) { return this.all(selector.slice(1)); }
    focus() { this.focused++; }
    blur() {}
    select() {}
    scrollIntoView() {}
    contains(node) { for (let n = node; n; n = n.parent) if (n === this) return true; return false; }
    getBoundingClientRect() { return { left: 0, top: 0, bottom: 0, right: 0 }; }
    get offsetWidth() { return 280; }
    get offsetHeight() { return 300; }
    get clientHeight() { return 280; }
    get scrollHeight() { return this.children.length * 32; }
    get text() { return [this.textContent, ...this.children.map((c) => c.text)].join(""); }
}

const authorRows = (from, count) => Array.from({ length: count }, (_, i) => ({ id: from + i, displayName: `画师 ${from + i}`, xUsername: null }));
// 45 isolated fake authors: one full candidate page of 30, then 15 more
const AUTHORS = authorRows(1, 45);

function setup({ search = "" } = {}) {
    const ids = {};
    for (const id of ["q", "q-clear", "omnibox", "active-filters", "author-filter", "tag-filter"]) ids[id] = new Node("div", id);
    const timers = [];
    const requests = [];
    const location = { pathname: "/c/", search, hash: "" };
    const urls = [];
    const document = {
        activeElement: null,
        body: new Node("body"),
        getElementById: (id) => ids[id],
        createElement: (tag) => new Node(tag),
        addEventListener() {},
        removeEventListener() {}
    };
    const window = { addEventListener() {}, innerWidth: 1200, innerHeight: 800 };
    const context = {
        window, document, location, console, URLSearchParams, AbortController,
        history: { state: null, replaceState(_, __, url) { urls.push(url); location.search = url.slice(location.pathname.length); } },
        fetch: async (url) => {
            requests.push(url);
            if (url.startsWith("/api/authors?")) {
                const p = new URL(url, "http://localhost").searchParams;
                const offset = Number(p.get("offset")), limit = Number(p.get("limit"));
                const rows = AUTHORS.filter((a) => a.displayName.includes(p.get("keyword") || "")).slice(offset, offset + limit);
                return { ok: true, status: 200, json: async () => rows };
            }
            const filters = { authors: [{ id: 1, displayName: "雨宫ゆき", xUsername: "amamiya" }, { id: 2, displayName: "千秋" }], tags: [] };
            return { ok: true, status: 200, json: async () => ({ page: 0, size: 30, totalElements: 0, totalPages: 0, items: [], filters }) };
        },
        Image: class {},
        setTimeout: (fn, ms) => { timers.push({ fn, ms }); return timers.length; },
        clearTimeout: (id) => { if (timers[id - 1]) timers[id - 1].fn = null; }
    };
    vm.createContext(context);
    vm.runInContext(archiveSource, context);
    window.Archive.setQuery(window.Archive.queryFromSearch(location.search));
    vm.runInContext(filtersSource, context);
    const runTimers = () => { const due = timers.splice(0); due.forEach((t) => t.fn && t.fn()); return due.filter((t) => t.fn).map((t) => t.ms); };
    const settle = () => new Promise((resolve) => setImmediate(resolve));
    return { ids, requests, urls, runTimers, settle, document, window, Archive: window.Archive, CFilters: window.CFilters };
}

const searchRequests = (requests) => requests.map((u) => new URL(u, "http://localhost").searchParams.get("q"));

test("typing with a Chinese IME does not search until the word is chosen, then searches once after a pause", async () => {
    const env = setup();
    await env.settle();
    const q = env.ids.q;
    q.dispatch("compositionstart");
    for (const partial of ["h", "ha", "hai"]) {
        q.value = partial;
        q.dispatch("input", { isComposing: true });
    }
    const enter = q.dispatch("keydown", { key: "Enter", isComposing: true, keyCode: 229 });
    assert.equal(enter.defaultPrevented, undefined, "Enter that picks a candidate belongs to the IME");
    assert.deepEqual(env.runTimers(), [], "nothing is scheduled while composing");
    q.value = "海";
    q.dispatch("compositionend");
    q.dispatch("input");
    assert.deepEqual(env.runTimers(), [300], "one search after the pause");
    await env.settle();
    assert.deepEqual(searchRequests(env.requests), [null, "海"]);
    assert.equal(env.urls.at(-1), "/c/?q=%E6%B5%B7", "the query is kept in the page address");
    assert.equal(env.ids["q-clear"].hidden, false);
});

test("Enter searches at once; Esc clears the search; whitespace-only text is no search", async () => {
    const env = setup();
    await env.settle();
    const q = env.ids.q;
    q.value = "Moon";
    q.dispatch("input");
    q.dispatch("keydown", { key: "Enter" });
    await env.settle();
    assert.deepEqual(searchRequests(env.requests), [null, "Moon"]);
    env.runTimers(); // the pending debounce was cancelled by Enter and must not search again
    q.value = "   ";
    q.dispatch("keydown", { key: "Enter" });
    await env.settle();
    q.value = "Moon";
    q.dispatch("keydown", { key: "Escape" });
    await env.settle();
    assert.equal(q.value, "");
    assert.deepEqual(searchRequests(env.requests), [null, "Moon", null]);
    assert.equal(env.urls.at(-1), "/c/");
});

test("conditions from the address show as chips with server-provided names; removing one narrows the request", async () => {
    const env = setup({ search: "?q=%E6%B5%B7&authorId=2&authorId=1&tagId=9" });
    await env.settle();
    assert.equal(env.ids.q.value, "海", "the search box shows the restored text");
    const box = env.ids["active-filters"];
    const chips = box.children.filter((c) => c.className === "fchip");
    assert.deepEqual(chips.map((c) => c.children[1].textContent), ["“海”", "雨宫ゆき", "千秋", "已不存在的标签 #9"]);
    assert.ok(box.children.some((c) => c.className === "fjoin" && c.textContent === "或"), "authors are joined by “or”");
    assert.equal(env.ids["author-filter"].firstChild.textContent, "作者 · 2 ");
    assert.equal(box.children.at(-1).textContent, "找到 0 件");

    chips[1].children[2].dispatch("click");
    await env.settle();
    const last = new URL(env.requests.at(-1), "http://localhost").searchParams;
    assert.deepEqual([last.get("q"), last.getAll("authorId"), last.getAll("tagId"), last.get("page")], ["海", ["2"], ["9"], "0"]);

    box.children.find((c) => c.className === "clear-all").dispatch("click");
    await env.settle();
    assert.equal(env.requests.at(-1), "/api/illustrations?page=0&size=30");
    assert.equal(env.ids.q.value, "");
    assert.equal(box.children.length, 0);
});

test("the author picker pages past 30 candidates and keeps arrow keys, Space and Esc to itself", async () => {
    const env = setup();
    await env.settle();
    env.ids["author-filter"].dispatch("click");
    assert.equal(env.CFilters.isPickerOpen(), true);
    const root = env.document.body.children.at(-1);
    const input = root.querySelector(".pk-input");
    const list = root.querySelector(".pk-list");
    await env.settle();
    const options = () => list.querySelectorAll(".pk-item");
    assert.equal(options().length, 30, "the first candidate page");
    assert.ok(list.text.includes("载入更多"));

    list.scrollTop = list.scrollHeight - list.clientHeight;
    list.dispatch("scroll");
    await env.settle();
    assert.equal(options().length, 45, "scrolling to the end loads the rest");
    assert.ok(options().some((li) => li.querySelector(".pk-label").textContent === "画师 45"), "beyond the first 30");
    assert.ok(!list.text.includes("载入更多"), "nothing left to load");
    assert.deepEqual(env.requests.filter((u) => u.startsWith("/api/authors")),
        ["/api/authors?limit=30&offset=0", "/api/authors?limit=30&offset=30"]);

    // Keys typed in the picker never bubble to the wall's shortcuts (arrow focus moves, Enter opens the viewer)
    for (const key of ["ArrowDown", "ArrowDown", "ArrowUp", "ArrowDown", " "]) {
        assert.equal(input.dispatch("keydown", { key }).stopped, true, `${key} stays in the picker`);
    }
    assert.equal(input.dispatch("keydown", { key: " " }).defaultPrevented, undefined, "Space types into the search field");
    assert.equal(options()[2].classList.contains("is-active"), true, "arrows move the picker's own highlight");
    assert.equal(input.dispatch("keydown", { key: "Enter" }).stopped, true);
    await env.settle();
    assert.equal(env.requests.at(-1), "/api/illustrations?page=0&size=30&authorId=3", "Enter toggles the highlighted author");

    // Clicking blank picker space keeps focus in its field instead of handing keys to the page
    assert.equal(root.dispatch("mousedown", { target: root.querySelector(".pk-hint") }).defaultPrevented, true);
    assert.equal(root.dispatch("mousedown", { target: input }).defaultPrevented, undefined, "the field itself still takes clicks");

    const anchorFocus = env.ids["author-filter"].focused;
    assert.equal(input.dispatch("keydown", { key: "Escape" }).stopped, true);
    assert.equal(env.CFilters.isPickerOpen(), false, "Esc closes the picker, not the page's layers");
    assert.equal(root.removed, true);
    assert.equal(env.ids["author-filter"].focused, anchorFocus + 1, "focus returns to the 作者 button");
});

test("a search still waiting for its pause is dropped when the viewer opens, and the box shows the applied query", async () => {
    const env = setup({ search: "?q=sea" });
    await env.settle();
    const q = env.ids.q;
    q.value = "sea 海";
    q.dispatch("input");
    env.CFilters.cancelPendingSearch(); // what openViewer does before showing the viewer / editor
    assert.deepEqual(env.runTimers(), [], "the queued search never fires");
    await env.settle();
    assert.deepEqual(searchRequests(env.requests), ["sea"], "no new list request");
    assert.equal(q.value, "sea");
});

test("when the gallery refuses to leave (继续编辑 or saving), search and chip changes do not touch the query", async () => {
    const env = setup({ search: "?q=sea&authorId=1" });
    await env.settle();
    const asked = [];
    let answer = false;
    env.window.GalleryApi = { prepareQueryChange: async () => { asked.push(true); return answer; }, toast() {} };
    const q = env.ids.q;
    const before = env.requests.length;

    q.value = "moon";
    q.dispatch("keydown", { key: "Enter" });
    await env.settle();
    const chip = env.ids["active-filters"].children.find((c) => c.className === "fchip" && c.children[1].textContent === "雨宫ゆき");
    chip.children[2].dispatch("click");
    await env.settle();
    assert.equal(asked.length, 2, "each change asks first");
    assert.equal(env.requests.length, before, "nothing was requested");
    assert.deepEqual(JSON.parse(JSON.stringify(env.Archive.query())), { q: "sea", authorIds: [1], tagIds: [] });
    assert.equal(q.value, "sea", "the search box returns to the applied query");

    answer = true;
    q.value = "moon";
    q.dispatch("keydown", { key: "Enter" });
    await env.settle();
    assert.equal(env.Archive.query().q, "moon", "once leaving is allowed the search applies");
});

test("“only this author” replaces the author group and keeps the other conditions", async () => {
    const env = setup({ search: "?q=sea&authorId=5&tagId=3" });
    await env.settle();
    env.CFilters.only("author", { id: 8, displayName: "Lumen Kiln", xUsername: "lumenkiln" });
    await env.settle();
    assert.equal(env.requests.at(-1), "/api/illustrations?page=0&size=30&q=sea&authorId=8&tagId=3");
});
