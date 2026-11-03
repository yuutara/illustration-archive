const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const read = (name) => readFileSync(path.resolve(__dirname, "../../main/resources/static/c", name), "utf8");
const source = read("c-backup.js");
const html = read("settings.html");

class Node {
    constructor(tag = "div") {
        this.tag = tag;
        this.listeners = new Map();
        this.children = [];
        this.dataset = {};
        this.style = {};
        this.hidden = false;
        this.disabled = false;
        this.inert = false;
        this.textContent = "";
        this.focused = 0;
    }
    addEventListener(type, fn) { this.listeners.set(type, fn); }
    replaceChildren(...children) { this.children = children; }
    focus() { this.focused++; this.document.activeElement = this; }
    fire(type, event = {}) { return this.listeners.get(type)({ target: this, preventDefault() { }, ...event }); }
}

const settle = async () => { for (let i = 0; i < 8; i++) await new Promise((resolve) => setImmediate(resolve)); };

const READY = {
    phase: "READY", id: "20261103-150000-ab12", stage: null, fraction: 1, message: null,
    preview: {
        fileName: "插画档案备份-20261103.zip", createdAt: "2026-11-03T15:00:00+08:00", appVersion: "0.6.0",
        schemaBefore: "7", schemaAfter: "8",
        summary: { works: 12, assets: 30, authors: 4, tags: 8, inbox: 20, files: 55, bytes: 3 * 1024 * 1024 }
    }
};

/** Runs the page against a fake server: {@code views} answers GET /api/desktop/restore in turn. */
async function setup({ views = [{ phase: "IDLE" }], answer = () => [200, {}] } = {}) {
    const nodes = new Map();
    const document = { activeElement: null };
    const element = (id) => {
        if (!nodes.has(id)) nodes.set(id, Object.assign(new Node(), { document }));
        return nodes.get(id);
    };
    const main = element("main");
    Object.assign(document, {
        getElementById: element,
        createElement: (tag) => Object.assign(new Node(tag), { document }),
        querySelector: () => main
    });
    element("restore-confirm").hidden = true;
    const sent = [];
    const timers = [];
    let read = 0;
    const reply = (status, body) => ({ ok: status < 400, status, json: async () => body });
    const fetch = async (url, options = {}) => {
        if (url === "/api/desktop/restore" && options.method === "GET") {
            return reply(200, views[Math.min(read++, views.length - 1)]);
        }
        sent.push([url, options.method, options.body]);
        return reply(...answer(url, options));
    };
    const context = {
        fetch, document, String, Number, Math, Date, Error, JSON, Promise,
        clearTimeout: () => { },
        setTimeout: (fn) => { timers.push(fn); return timers.length; }
    };
    vm.createContext(context);
    vm.runInContext(source, context);
    await settle();
    const tick = async () => { const due = timers.splice(0); due.forEach((fn) => fn()); await settle(); };
    return { element, sent, tick, document };
}

test("the page names what a backup holds and leaves out, and keeps the two steps of a restore apart", () => {
    assert.match(html, /不包含 X 登录、API Key 和应用设置/);
    assert.match(html, /备份之后新增或修改的作品、标签和整理进度会从图库中消失/);
    assert.match(html, /当前图库会先原样保留/);
    assert.match(html, /id="restore-cancel"[^>]*>取消</);
    assert.doesNotMatch(source, /path|filePath/i, "the page never names a file; the native dialog does");
});

test("creating a backup only asks the app to open its own save dialog", async () => {
    const app = await setup();
    app.element("backup-create").fire("click");
    await settle();
    assert.deepEqual(app.sent, [["/api/desktop/backup", "POST", undefined]]);
    assert.equal(app.element("backup-error").hidden, true);
});

test("a backup the app refuses says why and can be tried again", async () => {
    const app = await setup({ answer: () => [409, { message: "X 授权正在进行，请先完成或取消后再试。" }] });
    app.element("backup-create").fire("click");
    await settle();
    assert.match(app.element("backup-error").textContent, /X 授权正在进行/);
    assert.equal(app.element("backup-create").disabled, false);
});

test("a check in progress shows its stage and can be cancelled; a checked backup asks before anything changes", async () => {
    const checking = { phase: "CHECKING", stage: "正在解包并校验备份…", fraction: 0.4 };
    const app = await setup({ views: [{ phase: "IDLE" }, checking, READY], answer: () => [200, checking] });
    app.element("restore-choose").fire("click");
    await settle();
    assert.deepEqual(app.sent[0], ["/api/desktop/restore/check", "POST", undefined]);
    assert.equal(app.element("restore-check").hidden, false);
    assert.equal(app.element("restore-stage").textContent, "正在解包并校验备份…");
    assert.equal(app.element("restore-fill").style.width, "40%");
    assert.equal(app.element("backup-create").disabled, true, "one operation at a time");

    await app.tick();
    await app.tick();
    assert.equal(app.element("restore-check").hidden, true);
    assert.equal(app.element("restore-confirm").hidden, false);
    assert.equal(app.document.activeElement, app.element("restore-cancel"), "the focus starts on 取消");
    assert.equal(app.element("main").inert, true);
    const facts = app.element("restore-facts").children.map((node) => node.textContent).join("|");
    assert.match(facts, /插画档案备份-20261103\.zip/);
    assert.match(facts, /12 件作品、30 张图片或视频/);
    assert.match(facts, /备份时为 V7，恢复时升级到 V8/);
    assert.match(facts, /3\.0 MB/);
    assert.equal(app.sent.length, 1, "nothing is restored until the user confirms");
});

test("confirming sends only the id of the backup that was checked", async () => {
    const app = await setup({ views: [READY], answer: () => [202, null] });
    app.element("restore-ok").fire("click");
    await settle();
    assert.deepEqual(app.sent, [["/api/desktop/restore/apply", "POST", '{"id":"20261103-150000-ab12"}']]);
    assert.equal(app.element("restore-ok").disabled, true, "no second click while the app takes over");
});

test("Esc, the backdrop and 取消 all drop the checked backup", async () => {
    for (const how of ["escape", "backdrop", "button"]) {
        const app = await setup({ views: [READY], answer: () => [200, { phase: "IDLE" }] });
        const box = app.element("restore-confirm");
        if (how === "escape") box.fire("keydown", { key: "Escape" });
        if (how === "backdrop") box.fire("click", { target: box });
        if (how === "button") app.element("restore-cancel").fire("click");
        await settle();
        assert.deepEqual(app.sent, [["/api/desktop/restore/cancel", "POST", '{"id":"20261103-150000-ab12"}']], how);
        assert.equal(box.hidden, true, how);
        assert.equal(app.element("main").inert, false, how);
    }
});

test("a refused backup is explained once", async () => {
    const app = await setup({ views: [{ phase: "FAILED", message: "这个备份来自更新版本的插画档案，请先升级本应用再恢复。" }] });
    assert.match(app.element("backup-error").textContent, /没有恢复：这个备份来自更新版本/);
    assert.equal(app.sent[0][0], "/api/desktop/restore/cancel");
    assert.equal(app.element("restore-choose").disabled, false);
});

test("the server's own mode has no backup and hides the section", async () => {
    const nodes = new Map();
    const element = (id) => { if (!nodes.has(id)) nodes.set(id, new Node()); return nodes.get(id); };
    const context = {
        document: { getElementById: element, querySelector: () => element("main"), createElement: () => new Node() },
        fetch: async () => ({ ok: false, status: 404, json: async () => ({}) }),
        setTimeout: () => 0, clearTimeout: () => { }, String, Number, Math, Date, Error, JSON, Promise
    };
    vm.createContext(context);
    vm.runInContext(source, context);
    await settle();
    assert.equal(element("backup").hidden, true);
});
