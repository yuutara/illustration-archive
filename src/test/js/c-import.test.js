const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const dir = path.resolve(__dirname, "../../main/resources/static/c");
const archiveSource = readFileSync(path.join(dir, "archive-client.js"), "utf8");
const importSource = readFileSync(path.join(dir, "c-import.js"), "utf8");

/** Just enough DOM for the import sheet. */
class Node {
    constructor(tag = "div", id = null) {
        this.tagName = tag.toUpperCase();
        this.id = id;
        this.children = [];
        this.listeners = new Map();
        this.className = "";
        this.textContent = "";
        this.hidden = false;
        this.disabled = false;
        this.isConnected = true;
        this.style = {};
        this.classList = {
            add: (n) => { if (!this.className.split(" ").includes(n)) this.className = (this.className + " " + n).trim(); },
            remove: (n) => { this.className = this.className.split(" ").filter((c) => c !== n).join(" "); },
            toggle: (n, on) => { (on ? this.classList.add : this.classList.remove)(n); return on; },
            contains: (n) => this.className.split(" ").includes(n)
        };
    }
    set innerHTML(html) { this.children = []; }
    get firstElementChild() { return this.children[0]; }
    appendChild(child) { this.children.push(child); return child; }
    append(...nodes) { nodes.forEach((n) => (typeof n === "string" ? this.appendChild(Object.assign(new Node("#text"), { textContent: n })) : this.appendChild(n))); }
    matches(selector) { return selector.startsWith(".") ? this.classList.contains(selector.slice(1)) : this.tagName === selector.toUpperCase(); }
    all(selector) { return this.children.flatMap((c) => [...(c.matches(selector) ? [c] : []), ...c.all(selector)]); }
    querySelector(selector) { return this.all(selector)[0] || null; }
    setAttribute() {}
    addEventListener(type, fn) { this.listeners.set(type, [...(this.listeners.get(type) || []), fn]); }
    dispatch(type, event = {}) {
        const e = { target: this, preventDefault() { this.defaultPrevented = true; }, stopPropagation() {}, ...event };
        (this.listeners.get(type) || []).forEach((fn) => fn(e));
        return e;
    }
    click() { return this.dispatch("click"); }
    focus() {}
    get text() { return [this.textContent, ...this.children.map((c) => c.text)].join(""); }
}

const IDS = ["import-sheet", "import-input", "dropzone", "import-list", "import-bar", "import-notice", "import-summary",
    "import-retry", "import-resume", "import-done", "import-close", "import-pick", "import-open", "empty-import", "shortcuts"];

function setup({ list, query = "", script }) {
    const ids = Object.fromEntries(IDS.map((id) => [id, new Node("div", id)]));
    ids["import-sheet"].hidden = true;
    ids["shortcuts"].hidden = true;
    ids["import-bar"].appendChild(new Node("span"));
    const documentListeners = new Map();
    const windowListeners = new Map();
    const document = {
        activeElement: null,
        getElementById: (id) => ids[id],
        createElement: (tag) => new Node(tag),
        addEventListener: (type, fn) => documentListeners.set(type, fn)
    };
    const requests = [];
    const calls = { toast: [], fresh: [], library: [], opened: [], inert: [], cleared: 0 };
    const gallery = {
        viewerOpen: false,
        isViewerOpen: () => gallery.viewerOpen,
        setBackgroundInert: (on) => calls.inert.push(on),
        toast: (t) => calls.toast.push(t),
        markFresh: (ids) => calls.fresh.push(...ids),
        setLibraryTotal: (n) => calls.library.push(n),
        openViewer: (id) => calls.opened.push(id)
    };
    const window = {
        addEventListener: (type, fn) => windowListeners.set(type, fn),
        GalleryApi: gallery,
        CFilters: { clearAll: () => { calls.cleared++; } },
        CEditor: { isConfirming: () => false }
    };
    let uploads = 0;
    const context = {
        window, document, console, URLSearchParams, AbortController, FormData, Image: class {},
        URL: { createObjectURL: () => "blob:preview", revokeObjectURL() {} },
        fetch: async (url, init = {}) => {
            requests.push(url);
            let reply;
            if (init.method === "POST") reply = await script(init.body.getAll("files")[0], uploads++);
            else reply = list(url);
            if (reply instanceof Error) throw reply;
            if (typeof reply === "number") return { ok: false, status: reply, json: async () => ({}) };
            return { ok: true, status: 200, json: async () => reply };
        }
    };
    vm.createContext(context);
    vm.runInContext(archiveSource, context);
    vm.runInContext(importSource, context);
    return { ids, requests, calls, gallery, window, Archive: window.Archive, Import: window.CImport, windowListeners, documentListeners, query };
}

const settle = async (n = 4) => { for (let i = 0; i < n; i++) await new Promise((r) => setImmediate(r)); };
const item = (id) => ({ id, title: `Work ${id}`, author: null, coverAssetId: id * 10, coverMimeType: "video/mp4", assetCount: 1,
    createdAt: "2026-10-28T10:00:00", assets: [{ id: id * 10, mimeType: "video/mp4", sortOrder: 0 }] });
const page = (items, total = items.length) => ({ page: 0, size: 30, totalElements: total, totalPages: 1, items });
const ok = (id) => ({ total: 1, items: [{ status: "SUCCESS", success: true, illustrationId: id }] });
const pic = (name) => new File([name], name, { type: "image/png" });
const rowsText = (ids) => ids["import-list"].children.map((li) => li.text);

test("picking files imports them one by one; afterwards the wall is re-read, new works highlighted and can be opened", async () => {
    let imported = 0;
    const env = setup({
        list: () => page([...Array.from({ length: imported }, (_, i) => item(100 + i)), item(1)]),
        script: async (file, n) => { imported++; return ok(100 + n); }
    });
    await env.Archive.setQuery({});
    env.ids["import-open"].click();
    assert.equal(env.ids["import-sheet"].hidden, false);
    assert.deepEqual(env.calls.inert, [true], "the gallery behind the sheet is made inert");
    env.ids["import-input"].files = [pic("a.png"), pic("b.png")];
    env.ids["import-input"].dispatch("change");
    assert.match(env.ids["import-summary"].textContent, /正在导入 1 \/ 2/);
    assert.equal(env.ids["import-close"].disabled, true);
    assert.equal(env.Import.close(), false, "cannot close mid-import");
    assert.match(env.calls.toast.at(-1), /正在导入/);
    await settle(8);
    assert.match(env.ids["import-summary"].textContent, /已导入 2/);
    assert.deepEqual(env.calls.fresh, [100, 101]);
    assert.equal(env.Archive.total(), 3, "the list was re-read from the server");
    assert.deepEqual(rowsText(env.ids).map((t) => /已导入/.test(t) && /打开/.test(t)), [true, true]);
    assert.equal(env.ids["import-notice"].hidden, true);

    const openFirst = env.ids["import-list"].children[0].querySelector(".imp-action").children[0];
    openFirst.click();
    assert.equal(env.ids["import-sheet"].hidden, true, "opening a work closes the sheet");
    assert.deepEqual(env.calls.opened, [100]);
    assert.deepEqual(env.calls.inert, [true, false]);
});

test("under a filter the query is kept; new works that do not match are explained, with a way to clear the conditions", async () => {
    let imported = false;
    const env = setup({
        list: (url) => (url.includes("size=1") ? page([], imported ? 9 : 8) : page([item(1)])),
        script: async () => { imported = true; return ok(200); }
    });
    await env.Archive.setQuery({ tagIds: [4] });
    env.Import.addFiles([pic("new.png")]);
    await settle(8);
    assert.equal(env.Archive.query().tagIds[0], 4, "the filter is kept");
    assert.equal(env.ids["import-notice"].hidden, false);
    assert.match(env.ids["import-notice"].text, /新导入的 1 件不符合当前的搜索或筛选条件/);
    assert.deepEqual(env.calls.library, [9], "the whole-library count is refreshed");
    assert.ok(!/打开/.test(rowsText(env.ids)[0]), "a work that is not on the wall offers no open button");
    const clear = env.ids["import-notice"].children[0].children.find((c) => c.tagName === "BUTTON");
    assert.equal(clear.textContent, "清除条件查看");
    clear.click();
    assert.equal(env.calls.cleared, 1);
    assert.equal(env.ids["import-sheet"].hidden, true);
});

test("a dropped connection is reported as unknown, not as “not imported”, and only those files are offered for retry", async () => {
    let online = false;
    const env = setup({
        list: () => page([item(1)]),
        script: async (file, n) => (n === 1 && !online ? new TypeError("Failed to fetch") : ok(300 + n))
    });
    await env.Archive.setQuery({});
    env.Import.addFiles([pic("1.png"), pic("2.png"), pic("3.png")]);
    await settle(8);
    assert.match(env.ids["import-summary"].textContent, /已暂停：连接中断/);
    assert.match(env.ids["import-summary"].textContent, /无法确认 1 · 未开始 1/);
    assert.match(env.ids["import-notice"].text, /无法确认是否已经导入/);
    assert.equal(env.ids["import-retry"].hidden, false);
    assert.equal(env.ids["import-retry"].textContent, "重试 1 张");
    assert.equal(env.ids["import-resume"].hidden, false);
    assert.equal(env.ids["import-resume"].textContent, "继续导入剩下的 1 张");
    online = true;
    env.ids["import-resume"].click();
    await settle(8);
    env.ids["import-retry"].click();
    await settle(8);
    assert.match(env.ids["import-summary"].textContent, /^已导入 3$/);
    assert.equal(env.requests.filter((u) => u === "/api/illustrations/import").length, 4, "three files, one retried once");
});

test("when every file is rejected locally the sheet can be closed and new files still import", async () => {
    const env = setup({ list: () => page([item(1)]), script: async () => ok(600) });
    await env.Archive.setQuery({});
    env.Import.addFiles([{ file: pic("folder"), directory: true }, { file: { name: "huge.png", size: 51 * 1024 * 1024, type: "image/png" } }]);
    await settle();
    assert.equal(env.Import.isRunning(), false);
    assert.equal(env.ids["import-close"].disabled, false);
    assert.match(env.ids["import-summary"].textContent, /失败 2/);
    assert.equal(env.Import.close(), true, "the sheet closes");
    assert.equal(env.ids["import-sheet"].hidden, true);
    env.Import.addFiles([pic("ok.png")]);
    await settle(8);
    assert.match(env.ids["import-summary"].textContent, /^已导入 1$/);
    assert.equal(env.requests.filter((u) => u === "/api/illustrations/import").length, 1);
});

test("a lost reply retried into “already there” refreshes the wall and total and offers 打开, still counted as 已存在", async () => {
    let stored = false;
    let attempt = 0;
    const env = setup({
        list: () => page(stored ? [item(700), item(1)] : [item(1)]),
        script: async () => {
            stored = true; // the first attempt reached the server, only the reply was lost
            return attempt++ === 0 ? new TypeError("connection reset")
                : { total: 1, items: [{ status: "DUPLICATE", success: false, illustrationId: 700, errorCode: "DUPLICATE_ILLUSTRATION" }] };
        }
    });
    await env.Archive.setQuery({});
    env.Import.addFiles([pic("lost.png")]);
    await settle(8);
    assert.match(env.ids["import-summary"].textContent, /无法确认 1/);
    assert.equal(env.Archive.total(), 1, "nothing refreshed yet: it is not known to be stored");
    env.ids["import-retry"].click();
    await settle(10);
    assert.match(env.ids["import-summary"].textContent, /^已导入 0 · 已存在 1$/);
    assert.equal(env.Archive.total(), 2, "the wall and total now include the work");
    assert.equal(env.Archive.listed(700), true);
    assert.deepEqual(env.calls.fresh, [700]);
    const row = rowsText(env.ids)[0];
    assert.match(row, /之前那次其实已经导入/);
    assert.match(row, /打开/);
});

test("only external file drags import: internal drags are ignored, and over the viewer a drop is blocked without opening the file", async () => {
    const env = setup({ list: () => page([item(1)]), script: async () => ok(400) });
    await env.Archive.setQuery({});
    const event =(type, types) => { const e = { dataTransfer: { types, dropEffect: "", items: [], files: [pic("d.png")] }, preventDefault() { e.defaultPrevented = true; } }; env.windowListeners.get(type)(e); return e; };

    // A drag started on the page (link, text, image) carries the internal marker
    const started = { dataTransfer: { types: [], setData(type) { this.types.push(type); } } };
    env.documentListeners.get("dragstart")(started);
    const internal = event("dragover", ["Files", ...started.dataTransfer.types]);
    assert.equal(internal.defaultPrevented, undefined, "internal drags keep their default behaviour");
    assert.equal(event("drop", ["text/plain"]).defaultPrevented, undefined, "non-file drags are ignored");

    env.gallery.viewerOpen = true;
    event("dragenter", ["Files"]);
    assert.equal(env.ids["import-sheet"].hidden, true, "no sheet over the viewer");
    const over = event("dragover", ["Files"]);
    assert.equal(over.defaultPrevented, true, "the browser must not open the file and replace the page");
    assert.equal(over.dataTransfer.dropEffect, "none");
    const blocked = event("drop", ["Files"]);
    assert.equal(blocked.defaultPrevented, true);
    assert.match(env.calls.toast.at(-1), /看图或编辑时不能导入/);
    await settle();
    assert.equal(env.requests.filter((u) => u === "/api/illustrations/import").length, 0);

    env.gallery.viewerOpen = false;
    event("dragenter", ["Files"]);
    assert.equal(env.ids["import-sheet"].hidden, false, "dragging files over the gallery opens the sheet");
    assert.equal(env.ids["dropzone"].classList.contains("is-over"), true);
    event("dragleave", ["Files"]);
    assert.equal(env.ids["import-sheet"].hidden, true, "leaving without dropping folds the empty sheet away");
    event("dragenter", ["Files"]);
    assert.equal(event("dragover", ["Files"]).dataTransfer.dropEffect, "copy");
    event("drop", ["Files"]);
    await settle(8);
    assert.equal(env.requests.filter((u) => u === "/api/illustrations/import").length, 1);
});
