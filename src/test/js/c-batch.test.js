const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

/* The batch panel (c-batch.js) on the real data layer: what the draft sends, and what a failure leaves behind. */
const dir = path.resolve(__dirname, "../../main/resources/static/c");
const archiveSource = readFileSync(path.join(dir, "archive-client.js"), "utf8");
const batchSource = readFileSync(path.join(dir, "c-batch.js"), "utf8");

class Node {
    constructor(tag = "div", id = null) {
        this.tagName = tag.toUpperCase();
        this.id = id;
        this.children = [];
        this.listeners = new Map();
        this.attributes = new Map();
        this.className = "";
        this.textContent = "";
        this.hidden = false;
        this.disabled = false;
        this.focused = 0;
        this.classList = {
            add: (n) => { if (!this.className.split(" ").includes(n)) this.className = (this.className + " " + n).trim(); },
            remove: (n) => { this.className = this.className.split(" ").filter((c) => c !== n).join(" "); },
            toggle: (n, on) => { (on ? this.classList.add : this.classList.remove)(n); return on; },
            contains: (n) => this.className.split(" ").includes(n)
        };
    }
    set innerHTML(html) { this.children = []; }
    appendChild(child) { child.parent = this; this.children.push(child); return child; }
    insertBefore(child, before) { child.parent = this; this.children.splice(this.children.indexOf(before), 0, child); return child; }
    remove() { if (this.parent) this.parent.children = this.parent.children.filter((c) => c !== this); }
    all(cls) { return this.children.flatMap((c) => [...(c.classList.contains(cls) ? [c] : []), ...c.all(cls)]); }
    querySelectorAll(selector) { return selector.split(",").flatMap((s) => this.all(s.trim().slice(1))); }
    setAttribute(name, value) { this.attributes.set(name, String(value)); }
    addEventListener(type, fn) { this.listeners.set(type, [...(this.listeners.get(type) || []), fn]); }
    dispatch(type, event = {}) {
        const e = { target: this, preventDefault() {}, ...event };
        (this.listeners.get(type) || []).forEach((fn) => fn(e));
        return e;
    }
    click() { return this.disabled ? null : this.dispatch("click"); }
    focus() { this.focused++; }
    get text() { return [this.textContent, ...this.children.map((c) => c.text)].join(""); }
}

const IDS = ["batch-sheet", "batch-author", "batch-author-reset", "batch-author-note", "batch-tags", "batch-error", "batch-apply",
    "batch-title", "batch-covers", "batch-summary", "batch-cancel", "batch-close", "bb-edit"];
const settle = async (n = 6) => { for (let i = 0; i < n; i++) await new Promise((r) => setImmediate(r)); };
const item = (id, author = null) => ({ id, title: `Work ${id}`, author, coverAssetId: id * 10, coverMimeType: "video/mp4", assetCount: 1,
    createdAt: "2026-10-28T10:00:00", assets: [{ id: id * 10, mimeType: "video/mp4", sortOrder: 0 }] });
const LUMEN = { id: 7, displayName: "Lumen", xUsername: "lumen" };

async function setup({ counts = [{ id: 1, name: "海", count: 3 }, { id: 2, name: "夏", count: 1 }], save = () => ({ status: 204 }) } = {}) {
    const ids = Object.fromEntries(IDS.map((id) => [id, new Node("div", id)]));
    ids["batch-sheet"].hidden = true;
    const calls = { toast: [], inert: [], ended: 0, posts: [], pickers: [] };
    const gallery = {
        toast: (t) => calls.toast.push(t), setBackgroundInert: (on) => calls.inert.push(on), endSelecting: () => { calls.ended++; }
    };
    const filters = {
        nameOf: (kind, id) => `${kind}-${id}`, closePicker() {}, openPicker: (kind, options) => calls.pickers.push({ kind, options })
    };
    let countReply = () => counts;
    const window = { GalleryApi: gallery, CFilters: filters };
    const context = {
        window, console, URLSearchParams, AbortController, FormData, Image: class {},
        document: { getElementById: (id) => ids[id], createElement: (tag) => new Node(tag) },
        fetch: async (url, init = {}) => {
            if (url === "/api/illustrations/batch/tags") {
                const reply = countReply();
                if (reply instanceof Error) throw reply;
                return { ok: true, status: 200, json: async () => reply };
            }
            if (url === "/api/illustrations/batch/metadata") {
                calls.posts.push(JSON.parse(init.body));
                const reply = await save(calls.posts.length);
                if (reply instanceof Error) throw reply;
                return { ok: reply.status < 400, status: reply.status, json: async () => reply.body || {} };
            }
            return { ok: true, status: 200, json: async () => ({ page: 0, size: 30, totalElements: 3, totalPages: 1, items: [item(1, LUMEN), item(2, LUMEN), item(3)] }) };
        }
    };
    vm.createContext(context);
    vm.runInContext(archiveSource, context);
    vm.runInContext(batchSource, context);
    await window.Archive.setQuery({});
    return { ids, calls, Batch: window.CBatch, Archive: window.Archive, setCounts: (fn) => { countReply = fn; } };
}

const chips = (env) => env.ids["batch-tags"].all("ed-tag");
const chip = (env, name) => chips(env).find((c) => c.children[0].textContent === name);
const action = (node, text) => node.children.find((c) => c.textContent === text);
const apply = async (env) => { env.ids["batch-apply"].click(); await settle(); };

test("opening shows the selection, the tags already on it and a disabled apply until something changes", async () => {
    const env = await setup();
    env.Batch.open([1, 2, 3]);
    assert.equal(env.ids["batch-sheet"].hidden, false);
    assert.equal(env.calls.inert[0], true);
    assert.match(env.ids["batch-tags"].text, /正在读取已有标签/);
    await settle();
    assert.equal(env.ids["batch-title"].textContent, "整理 3 件作品");
    assert.equal(env.ids["batch-author-note"].textContent, "目前有 1 位作者，1 件没有作者");
    assert.equal(chips(env).length, 2);
    assert.equal(action(chip(env, "海"), "补齐"), undefined, "a tag on every work needs no filling in");
    assert.equal(chip(env, "夏").text.includes("1/3"), true);
    assert.equal(env.ids["batch-apply"].disabled, true);
    assert.equal(env.ids["batch-summary"].textContent, "还没有改动");
});

test("only the changes made are sent, in one request: an untouched author and untouched tags stay out", async () => {
    const env = await setup();
    env.Batch.open([1, 2, 3]);
    await settle();
    action(chip(env, "海"), "×").click();
    action(chip(env, "夏"), "补齐").click();
    assert.equal(env.ids["batch-summary"].textContent, "添加 1 个标签，移除 1 个标签");
    assert.equal(env.ids["batch-apply"].textContent, "应用到 3 件");
    await apply(env);
    assert.equal(env.calls.posts.length, 1);
    assert.deepEqual(env.calls.posts[0], { ids: [1, 2, 3], addTagIds: [2], removeTagIds: [1] });
    assert.equal("authorId" in env.calls.posts[0], false, "no author field means authors stay as they are");
    assert.equal(env.calls.ended, 1, "selection ends after a successful save");
    assert.equal(env.calls.toast.at(-1), "已更新 3 件作品");
    assert.equal(env.ids["batch-sheet"].hidden, true);
    assert.equal(env.Batch.isOpen(), false);
});

test("the shared author is named from the loaded work, without waiting for the filter's name cache", async () => {
    const env = await setup();
    env.Batch.open([1, 2]);
    await settle();
    assert.equal(env.ids["batch-author-note"].textContent, "目前都是 Lumen");
});

test("Esc on a draft warns once and keeps it; a second Esc discards, and an empty panel just closes", async () => {
    const env = await setup();
    env.Batch.open([1, 2, 3]);
    await settle();
    env.Batch.requestClose();
    assert.equal(env.ids["batch-sheet"].hidden, true, "nothing to lose");
    env.Batch.open([1, 2, 3]);
    await settle();
    action(chip(env, "夏"), "补齐").click();
    env.Batch.requestClose();
    assert.equal(env.ids["batch-sheet"].hidden, false);
    assert.match(env.ids["batch-error"].textContent, /还没有应用/);
    assert.equal(env.ids["batch-summary"].textContent, "添加 1 个标签");
    env.Batch.requestClose();
    assert.equal(env.ids["batch-sheet"].hidden, true);
});

test("the author can be set or cleared, and either can be undone back to keeping it", async () => {
    const env = await setup();
    env.Batch.open([1, 2]);
    await settle();
    env.ids["batch-author"].click();
    const picker = env.calls.pickers.at(-1);
    assert.equal(picker.kind, "author");
    assert.equal(picker.options.single, true);
    picker.options.onToggle(9);
    assert.equal(env.ids["batch-summary"].textContent, "作者改为 author-9");
    assert.equal(env.ids["batch-author-reset"].textContent, "撤销");
    env.ids["batch-author-reset"].click();
    assert.equal(env.ids["batch-summary"].textContent, "还没有改动");
    assert.equal(env.ids["batch-author-reset"].textContent, "清空");
    env.ids["batch-author-reset"].click();
    assert.equal(env.ids["batch-summary"].textContent, "清空作者");
    await apply(env);
    assert.equal(env.calls.posts[0].authorId, null);
    assert.equal("authorId" in env.calls.posts[0], true, "an explicit null clears the author");
});

test("the tag picker adds new tags, and unticking a tag every work has removes it", async () => {
    const env = await setup();
    env.Batch.open([1, 2, 3]);
    await settle();
    const addButton = env.ids["batch-tags"].all("ed-tag-add")[0];
    addButton.click();
    const { options } = env.calls.pickers.at(-1);
    assert.deepEqual([...options.selected()], [1], "tags on every work start ticked");
    options.onToggle(5);
    assert.ok(chip(env, "tag-5").text.includes("将添加"));
    options.onToggle(1);
    assert.ok(chip(env, "海").classList.contains("is-removing"));
    assert.deepEqual([...options.selected()], [5]);
    options.onToggle(1);
    assert.ok(chip(env, "海").classList.contains("is-kept"), "ticking it again undoes the removal");
    action(chip(env, "tag-5"), "×").click();
    assert.equal(env.ids["batch-apply"].disabled, true);
});

test("a refused save keeps the panel, the draft and the selection; retrying sends the same change", async () => {
    let attempt = 0;
    const env = await setup({ save: () => (++attempt === 1 ? { status: 404 } : { status: 204 }) });
    env.Batch.open([1, 2, 3]);
    await settle();
    action(chip(env, "海"), "×").click();
    await apply(env);
    assert.equal(env.ids["batch-sheet"].hidden, false);
    assert.equal(env.calls.ended, 0);
    assert.equal(env.ids["batch-error"].hidden, false);
    assert.match(env.ids["batch-error"].textContent, /所选作品都没有改动/);
    assert.ok(chip(env, "海").classList.contains("is-removing"), "the draft survives");
    await apply(env);
    assert.deepEqual(env.calls.posts[1], env.calls.posts[0]);
    assert.equal(env.ids["batch-sheet"].hidden, true);
});

test("a lost connection says the result is unknown and offers the same retry", async () => {
    const env = await setup({ save: (n) => (n === 1 ? new TypeError("network") : { status: 204 }) });
    env.Batch.open([1]);
    await settle();
    env.ids["batch-author-reset"].click();
    await apply(env);
    assert.match(env.ids["batch-error"].textContent, /无法确认/);
    assert.equal(env.ids["batch-apply"].disabled, false);
    await apply(env);
    assert.equal(env.calls.ended, 1);
});

test("while saving nothing can close the panel or submit twice", async () => {
    let finish;
    const env = await setup({ save: () => new Promise((resolve) => { finish = resolve; }) });
    env.Batch.open([1, 2]);
    await settle();
    env.ids["batch-author-reset"].click();
    env.ids["batch-apply"].click();
    await settle();
    assert.equal(env.ids["batch-apply"].textContent, "正在应用…");
    assert.equal(env.ids["batch-apply"].disabled, true);
    assert.equal(env.Batch.close(), false);
    env.ids["batch-sheet"].dispatch("mousedown", { target: env.ids["batch-sheet"] });
    assert.equal(env.ids["batch-sheet"].hidden, false);
    assert.equal(env.calls.posts.length, 1);
    finish({ status: 204 });
    await settle();
    assert.equal(env.ids["batch-sheet"].hidden, true);
});

test("tags that could not be read do not block adding, and can be retried", async () => {
    const env = await setup();
    env.setCounts(() => new TypeError("network"));
    env.Batch.open([1, 2]);
    await settle();
    assert.match(env.ids["batch-tags"].text, /已有标签没有读到/);
    env.ids["batch-tags"].all("ed-tag-add")[0].click();
    env.calls.pickers.at(-1).options.onToggle(5);
    assert.equal(env.ids["batch-apply"].disabled, false);
    env.setCounts(() => [{ id: 1, name: "海", count: 2 }]);
    env.ids["batch-tags"].all("link")[0].click();
    await settle();
    assert.ok(chip(env, "海"));
    assert.ok(chip(env, "tag-5"), "the draft stays while the counts reload");
});

test("selections over the limit are refused before anything is sent", async () => {
    const env = await setup();
    env.Batch.open(Array.from({ length: env.Archive.BATCH_MAX + 1 }, (_, i) => i + 1));
    assert.equal(env.ids["batch-sheet"].hidden, true);
    assert.match(env.calls.toast.at(-1), /一次最多整理 500 件/);
});
