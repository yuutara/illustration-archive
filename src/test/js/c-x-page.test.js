const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

/* X 收藏页的恢复入口和逐件预览：操作对准哪一件、列表刷新后怎样、视频何时释放。文字判断在 c-x-model.test.js。 */
const read = (name) => readFileSync(path.resolve(__dirname, "../../main/resources/static/c", name), "utf8");
const page = read("c-x.js");
const modelSource = read("c-x-model.js");
const html = read("x.html");

class Node {
    constructor(id, tag = "div") {
        this.id = id;
        this.tag = tag;
        this.parent = null;
        this.attributes = new Map();
        this.listeners = new Map();
        this.classList = { toggle() { }, add() { }, remove() { } };
        this.children = [];
        this.dataset = {};
        this.style = {};
        this.hidden = html.includes(`id="${id}"`) && new RegExp(`id="${id}"[^>]*\\shidden`).test(html);
        this.disabled = false;
        this.textContent = "";
        this.onclick = null;
    }
    addEventListener(type, fn) { this.listeners.set(type, fn); }
    get src() { return this.attributes.get("src"); }
    set src(url) { this.attributes.set("src", url); }
    setAttribute(name, value) { this.attributes.set(name, value); }
    removeAttribute(name) { this.attributes.delete(name); }
    querySelector(selector) { return selector === ".x-problem" ? null : new Node(); }
    querySelectorAll(tag) {
        return this.children.flatMap((child) => [...(child.tag === tag ? [child] : []), ...child.querySelectorAll(tag)]);
    }
    replaceChildren(...children) { this.children.forEach((child) => { child.parent = null; }); this.children = []; children.forEach((child) => this.appendChild(child)); }
    appendChild(child) { child.parent = this; this.children.push(child); return child; }
    insertBefore(child, before) {
        if (child.parent) child.remove();
        child.parent = this;
        this.children.splice(before ? this.children.indexOf(before) : this.children.length, 0, child);
        return child;
    }
    remove() { this.parent.children = this.parent.children.filter((child) => child !== this); this.parent = null; }
    pause() { this.paused = true; }
    load() { this.loads = (this.loads || 0) + 1; }
    focus() { }
    click() { if (this.onclick) this.onclick(); else (this.listeners.get("click") || (() => { }))({}); }
    get isConnected() { return true; }
}

const settle = async () => { for (let i = 0; i < 6; i++) await new Promise((resolve) => setImmediate(resolve)); };
const progress = (overrides) => ({ recentStatus: "ACTIVE", historyStatus: "COMPLETE", unscannedIntervals: true, accountStatus: "OK", currentAccount: null, ...overrides });
const invalidRecent = progress({ recentStatus: "INVALID" });
const healthy = progress();

function setup({ start = invalidRecent, answer = () => ({ status: 204 }), capabilities = {}, items = [],
    connection = { state: "CONNECTED", savedLogin: "USABLE" } } = {}) {
    const nodes = new Map();
    const element = (id) => { if (!nodes.has(id)) nodes.set(id, new Node(id)); return nodes.get(id); };
    const app = { progress: start, posts: [], bodies: [], syncs: 0, answer, items, listed: null, hold: {}, connection, statusReads: 0 };
    const reply = (status, body) => ({ ok: status < 400, status, json: async () => body });
    const fetch = async (url, options = {}) => {
        if (url === "/api/x-auth/status") { app.statusReads++; return reply(200, { status: app.connection, clientId: app.connection.clientId || "", scanBudget: 100 }); }
        if (url.startsWith("/api/x-import/inbox?")) {
            await app.hold.list;
            return reply(200, { items: app.items, totalItems: app.items.length, totalPages: app.items.length ? 1 : 0, syncProgress: app.progress });
        }
        if (url === "/api/x-import/sync") {
            app.syncs++;
            await app.hold.sync;
            return reply(200, app.items.length ? finishedSync : {});
        }
        app.posts.push(url);
        app.bodies.push(options.body);
        const answered = await app.answer();
        if (answered.status === 204) app.progress = healthy;
        if (answered.next) app.progress = answered.next;
        return reply(answered.status, answered.body);
    };
    const window = { CTheme: { get: () => "light", set() { } }, UiCapabilities: capabilities, scrollTo() { } };
    const keys = [];
    // 等待授权时页面每隔一会儿再读一次状态；测试里手动触发，不留真实计时器
    const timers = [];
    const context = {
        window, fetch, Number, Map, Set, Promise, console, Date,
        setTimeout: (fn, millis) => { if (millis === 1500) { timers.push(fn); return 0; } return setTimeout(fn, millis); },
        clearTimeout,
        Image: class { },
        Element: Node, localStorage: { getItem: () => "1", setItem() { } },
        document: {
            getElementById: element,
            createElement: (tag) => new Node(undefined, tag),
            querySelectorAll: () => [],
            addEventListener(type, fn) { if (type === "keydown") keys.push(fn); },
            activeElement: new Node(),
            body: { classList: { add() { }, remove() { } } }
        }
    };
    vm.createContext(context);
    vm.runInContext(modelSource, context);
    vm.runInContext(page, context);
    return Object.assign(app, {
        element,
        timers,
        click: (id) => element(id).listeners.get("click")({}),
        buttons: () => element("x-recovery-actions").children,
        press: (index = 0) => app.element("x-recovery-actions").children[index].listeners.get("click")({}),
        key: (key) => keys.forEach((fn) => fn({ key, target: new Node(), preventDefault() { } })),
        card: (id) => element("x-grid").children.find((card) => String(card.dataset.id) === id),
        open: (id) => app.card(id).listeners.get("dblclick")(),
        shown: () => element("x-side-handle").textContent,
        stageVideo: () => element("x-stage").querySelectorAll("video")[0]
    });
}

const post = (id, media = [photo(id)]) => ({ id, xPostId: String(id * 1000), authorUsername: `author${id}`, media });
const photo = (id) => ({ mediaKey: `m${id}`, mediaType: "photo", sortOrder: 0, sourceUrl: `https://pbs.twimg.com/media/${id}.jpg` });
const clip = (id) => ({ mediaKey: `v${id}`, mediaType: "animated_gif", sortOrder: 0 });

const quiet = { newCount: 0, existingCount: 0, unsupportedCount: 0, scannedCount: 0 };
const finishedSync = { recent: quiet, history: quiet, partial: false, historyStatus: "COMPLETE", recentStatus: "CAUGHT_UP" };

// 列表里的归档和跳过：服务端确实处理完就从待处理里去掉；ask 可以让某一件失败或拖住回执
function inbox(items, ask = () => ({ status: "SUCCESS" })) {
    const app = setup({ start: healthy, items });
    app.answer = async () => {
        const id = JSON.parse(app.bodies[app.bodies.length - 1]).itemIds[0];
        const result = await ask(id);
        if (result.status === "SUCCESS") app.items = app.items.filter((item) => item.id !== id);
        return { status: 200, body: { items: [{ status: result.status, reason: result.reason }], requestedCount: 1, skippedCount: result.status === "SUCCESS" ? 1 : 0 } };
    };
    app.acted = () => app.bodies.map((body) => JSON.parse(body).itemIds[0]);
    return app;
}

// 同步还在路上时打开预览，同步结束后列表刷新：这就是预览期间列表变化的真实路径
async function syncChangesListWhile(app, change) {
    let finish;
    app.hold.sync = new Promise((resolve) => { finish = resolve; });
    app.click("x-sync");
    await settle();
    await change();
    finish();
    await settle();
}

async function pressAndConfirm(app, index = 0) {
    app.press(index);
    await settle();
    app.element("x-confirm-ok").click();
    await settle();
}

test("a reconnect that is waiting or failed keeps Sync, because the saved login still works", async () => {
    for (const state of ["WAITING", "FAILED"]) {
        const app = setup({ start: healthy, connection: { state, savedLogin: "USABLE" } });
        await settle();
        assert.equal(app.element("x-sync").hidden, false, state);
        assert.equal(app.element("x-connect").hidden, true, state);
    }
});

test("with nothing connected the page says it is optional, sends people to Settings, and never syncs", async () => {
    const app = setup({ start: healthy, connection: { state: "DISCONNECTED", savedLogin: "NONE" } });
    await settle();
    assert.equal(app.element("x-sync").hidden, true);
    assert.equal(app.element("x-connect").hidden, false);
    assert.match(app.element("x-connect-text").textContent, /可选/);
    assert.equal(app.element("x-connect-link").textContent, "去设置");
    assert.match(html, /id="x-connect-link" href="\/c\/settings\.html\?from=x#x"/);
    assert.equal(app.syncs, 0);
});

test("a login finishing in the browser shows up here by itself, and does not sync", async () => {
    const app = setup({ start: healthy, connection: { state: "WAITING", savedLogin: "NONE" } });
    await settle();
    assert.equal(app.element("x-connect-title").textContent, "正在等待浏览器授权");
    assert.equal(app.element("x-sync").hidden, true);
    assert.equal(app.timers.length, 1);

    app.connection = { state: "CONNECTED", savedLogin: "USABLE" };
    app.timers.pop()();
    await settle();
    assert.equal(app.element("x-connect").hidden, true);
    assert.equal(app.element("x-sync").hidden, false);
    assert.equal(app.timers.length, 0, "nothing left to wait for");
    assert.equal(app.syncs, 0);
});

test("tokens the app cannot use are not offered a Sync button", async () => {
    const app = setup({ start: healthy, connection: { state: "DISCONNECTED", savedLogin: "UNUSABLE" } });
    await settle();
    assert.equal(app.element("x-sync").hidden, true);
    assert.equal(app.element("x-connect-link").textContent, "去重新授权");
});

test("a page with healthy progress offers nothing", async () => {
    const app = setup({ start: healthy });
    await settle();
    assert.equal(app.element("x-recovery").hidden, true);
});

test("invalid progress is offered as soon as the page opens, without any sync", async () => {
    const app = setup();
    await settle();
    assert.equal(app.element("x-recovery").hidden, false);
    assert.equal(app.element("x-recovery-title").textContent, "最新收藏的同步进度已失效");
    assert.deepEqual(app.posts, []);
    assert.equal(app.syncs, 0);
});

test("where X sync is off there is nothing to recover", async () => {
    const app = setup({ capabilities: { xImport: false } });
    await settle();
    assert.equal(app.element("x-recovery").hidden, true);
});

test("nothing is sent until the cost has been shown and accepted", async () => {
    const app = setup();
    await settle();
    app.press();
    await settle();
    assert.equal(app.element("x-confirm").hidden, false);
    assert.match(app.element("x-confirm-text").textContent, /同样计费/);
    assert.match(app.element("x-confirm-text").textContent, /100 条，约 US\$0\.10/);
    assert.deepEqual(app.posts, []);

    app.element("x-confirm-cancel").click();
    await settle();
    assert.equal(app.element("x-confirm").hidden, true);
    assert.deepEqual(app.posts, []);
    assert.equal(app.element("x-recovery").hidden, false);
});

test("confirming resets once, reads the real state back, and leaves syncing to the user", async () => {
    const app = setup();
    await settle();
    await pressAndConfirm(app);
    assert.deepEqual(app.posts, ["/api/x-import/sync/recent/reset"]);
    assert.equal(app.syncs, 0, "recovering never starts a sync by itself");
    assert.equal(app.element("x-recovery").hidden, true);
    assert.equal(app.element("x-result-head").textContent, "同步进度已重置");
    assert.match(app.element("x-result-detail").textContent, /没有读取 X/);
    assert.equal(app.element("x-sync").disabled, false);
});

test("progress with no owner names the account and offers both answers, each with its own confirmation", async () => {
    const unattributed = progress({ accountStatus: "UNATTRIBUTED", currentAccount: "@me" });
    const app = setup({ start: unattributed });
    await settle();
    assert.match(app.element("x-recovery-title").textContent, /@me/);
    assert.equal(app.buttons().length, 2);
    assert.match(app.buttons()[0].textContent, /@me/);
    assert.equal(app.buttons()[1].textContent, "不是，从头开始");

    app.press(1);
    await settle();
    assert.match(app.element("x-confirm-title").textContent, /从头开始/);
    app.element("x-confirm-cancel").click();
    await settle();
    assert.deepEqual(app.posts, [], "backing out clears nothing");

    await pressAndConfirm(app, 0);
    assert.deepEqual(app.posts, ["/api/x-import/sync/account/adopt"]);
    assert.equal(app.element("x-result-head").textContent, "已确认账号");
    assert.equal(app.syncs, 0);
});

test("the answer sent is bound to what was on the page when the button was drawn", async () => {
    const app = setup({ start: progress({ accountStatus: "UNATTRIBUTED", currentAccount: "@me", currentAccountId: "42", accountConfirmation: "null/42/t1" }) });
    await settle();
    app.press(0);
    await settle();
    app.progress = progress({ accountStatus: "UNATTRIBUTED", currentAccount: "@other", currentAccountId: "43", accountConfirmation: "null/43/t2" });
    app.element("x-confirm-ok").click();
    await settle();
    assert.deepEqual(JSON.parse(app.bodies[0]), { confirmation: "null/42/t1" });
});

test("an expired confirmation is reported, the page asks again about the new account, and nothing resyncs", async () => {
    const now = progress({ accountStatus: "UNATTRIBUTED", currentAccount: "@other", currentAccountId: "43", accountConfirmation: "null/43/t2" });
    const app = setup({
        start: progress({ accountStatus: "UNATTRIBUTED", currentAccount: "@me", currentAccountId: "42", accountConfirmation: "null/42/t1" }),
        answer: () => ({ status: 409, body: { code: "CONFIRMATION_EXPIRED" }, next: now })
    });
    await settle();
    await pressAndConfirm(app, 0);

    assert.equal(app.element("x-result-head").textContent, "账号状态已经变化");
    assert.match(app.element("x-recovery-title").textContent, /@other/);
    assert.match(app.buttons()[0].textContent, /@other/);
    assert.equal(app.buttons()[0].disabled, false);
    assert.equal(app.syncs, 0);

    app.answer = () => ({ status: 204 });
    await pressAndConfirm(app, 0);
    assert.deepEqual(JSON.parse(app.bodies[1]), { confirmation: "null/43/t2" }, "the new answer is about the new question");
    assert.equal(app.element("x-recovery").hidden, true);
});

test("the recovery panel lays out as one column so narrow windows keep the text readable", () => {
    const css = read("app.css");
    assert.match(html, /class="x-connect x-recovery" id="x-recovery"/);
    assert.match(css, /\.x-recovery \{ grid-template-columns: minmax\(0, 1fr\); \}/);
    assert.ok(css.indexOf(".x-recovery {") > css.indexOf(".x-connect {"), "it has to override the two-column layout");
    const buttons = /\.x-recovery-actions \.btn \{([^}]*)\}/.exec(css)[1];
    assert.match(buttons, /max-width: 100%/);
    assert.match(buttons, /height: auto/);
    assert.match(buttons, /white-space: normal/, "a long account name wraps instead of pushing the page sideways");
    assert.match(buttons, /overflow-wrap: anywhere/);
});

test("another account can only start over, and only after confirming", async () => {
    const app = setup({ start: progress({ accountStatus: "CHANGED", currentAccount: "@other", recentStatus: "INVALID" }) });
    await settle();
    assert.equal(app.buttons().length, 1, "invalid-progress resets wait until the account is settled");
    await pressAndConfirm(app);
    assert.deepEqual(app.posts, ["/api/x-import/sync/account/restart"]);
    assert.equal(app.element("x-result-head").textContent, "同步进度已清除");
    assert.equal(app.syncs, 0);
});

test("while an action is running a second click and a sync are both held back", async () => {
    let finish;
    const app = setup({ answer: () => new Promise((resolve) => { finish = () => resolve({ status: 204 }); }) });
    await settle();
    await pressAndConfirm(app);

    assert.equal(app.buttons()[0].disabled, true);
    assert.equal(app.buttons()[0].textContent, "正在处理…");
    assert.equal(app.element("x-sync").disabled, true);
    app.press();
    app.click("x-sync");
    await settle();
    assert.equal(app.posts.length, 1);
    assert.equal(app.syncs, 0);

    finish();
    await settle();
    assert.equal(app.element("x-sync").disabled, false);
    assert.equal(app.element("x-recovery").hidden, true);
});

test("a failed action says so and can be tried again", async () => {
    const app = setup({ answer: () => ({ status: 500, body: { message: "disk full" } }) });
    await settle();
    await pressAndConfirm(app);
    assert.equal(app.element("x-result-head").textContent, "这次操作没有完成");
    assert.match(app.element("x-result-detail").textContent, /disk full/);
    assert.equal(app.element("x-recovery").hidden, false);
    assert.equal(app.buttons()[0].disabled, false);
    assert.equal(app.buttons()[0].textContent, "重置进度");

    app.answer = () => ({ status: 204 });
    await pressAndConfirm(app);
    assert.equal(app.posts.length, 2);
    assert.equal(app.element("x-recovery").hidden, true);
});

test("archive and skip go to the item on screen after new likes pushed the list down", async () => {
    for (const button of ["x-side-archive", "x-side-skip"]) {
        const app = inbox([post(1), post(2), post(3)]);
        await settle();
        await syncChangesListWhile(app, async () => {
            app.open("2");
            assert.equal(app.shown(), "@author2");
            app.items = [post(9), ...app.items];
        });
        assert.equal(app.shown(), "@author2", "the refresh does not change what is on screen");

        app.click(button);
        await settle();
        assert.deepEqual(app.acted(), [2]);
        assert.equal(app.shown(), "@author3", "the next item in the list follows");
    }
});

test("the keyboard acts on the same item as the buttons", async () => {
    const app = inbox([post(1), post(2)]);
    await settle();
    await syncChangesListWhile(app, async () => { app.open("1"); app.items = [post(9), ...app.items]; });
    app.key("s");
    await settle();
    assert.deepEqual(app.acted(), [1]);
});

test("the position shown follows the item when the list moves under it", async () => {
    const app = inbox([post(1), post(2)]);
    await settle();
    await syncChangesListWhile(app, async () => { app.open("2"); app.items = [post(9), ...app.items]; });
    assert.match(app.element("x-side-meta").textContent, /第 3 \/ 3 件/);
});

test("an item that left the list stays on screen and is still what the keys act on", async () => {
    const app = inbox([post(1), post(2), post(3)]);
    await settle();
    await syncChangesListWhile(app, async () => { app.open("2"); app.items = [post(1), post(3)]; });
    assert.equal(app.shown(), "@author2", "no swap under the user's hand");
    assert.equal(app.element("x-side-meta").textContent, "", "it has no place in this list");

    app.click("x-side-archive");
    await settle();
    assert.deepEqual(app.acted(), [2]);
    assert.equal(app.shown(), "@author3", "its old position now holds the next item");
});

test("from an item that left the list, next and previous land on its old neighbours", async () => {
    const app = inbox([post(1), post(2), post(3)]);
    await settle();
    await syncChangesListWhile(app, async () => { app.open("2"); app.items = [post(1), post(3)]; });
    app.key("ArrowDown");
    assert.equal(app.shown(), "@author3");

    const back = inbox([post(1), post(2), post(3)]);
    await settle();
    await syncChangesListWhile(back, async () => { back.open("2"); back.items = [post(1), post(3)]; });
    back.key("ArrowUp");
    assert.equal(back.shown(), "@author1");
});

test("after archiving the last item the preview lands on the one before it, and closes when nothing is left", async () => {
    const app = inbox([post(1), post(2)]);
    await settle();
    app.open("2");
    app.click("x-side-archive");
    await settle();
    assert.equal(app.shown(), "@author1");
    assert.equal(app.element("x-preview").hidden, false);

    app.click("x-side-archive");
    await settle();
    assert.equal(app.element("x-preview").hidden, true);
    assert.deepEqual(app.acted(), [2, 1]);
});

test("a failed archive stays on the item and says why, even when the item has left the list", async () => {
    const app = inbox([post(1), post(2)], () => ({ status: "FAILED", reason: "download failed" }));
    await settle();
    app.open("1");
    app.click("x-side-archive");
    await settle();
    assert.equal(app.shown(), "@author1");
    assert.equal(app.element("x-side-problem").hidden, false);

    const gone = inbox([post(1), post(2)], () => ({ status: "FAILED", reason: "download failed" }));
    await settle();
    await syncChangesListWhile(gone, async () => { gone.open("1"); gone.items = [post(2)]; });
    gone.click("x-side-archive");
    await settle();
    assert.equal(gone.shown(), "@author1");
    assert.match(gone.element("x-side-problem").textContent, /下载失败/);
});

test("moving on while an action is running stays where the user went", async () => {
    let finish;
    const pages = [photo(3), { ...photo(33), sortOrder: 1 }];
    const app = inbox([post(1), post(2), post(3, pages)], () => new Promise((resolve) => { finish = () => resolve({ status: "SUCCESS" }); }));
    await settle();
    app.open("1");
    app.click("x-side-archive");
    await settle();
    app.key("ArrowDown");
    app.key("ArrowDown");
    app.key("ArrowRight");
    assert.equal(app.shown(), "@author3");
    const counter = () => app.element("x-stage").children.at(-1).textContent;
    assert.equal(counter(), "2 / 2");

    finish();
    await settle();
    assert.equal(app.shown(), "@author3");
    assert.equal(counter(), "2 / 2", "not thrown back to the first page of the post");
    assert.deepEqual(app.acted(), [1]);
    assert.match(app.element("x-side-meta").textContent, /第 2 \/ 2 件/);
});

test("a second press while the list is still refreshing does not act twice", async () => {
    const app = inbox([post(1), post(2)]);
    await settle();
    app.open("1");
    let release;
    app.hold.list = new Promise((resolve) => { release = resolve; });
    app.click("x-side-archive");
    await settle();
    assert.deepEqual(app.acted(), [1]);

    app.key("a");
    app.key("a");
    await settle();
    assert.deepEqual(app.acted(), [1], "the refresh has not told the page what is next yet");

    app.hold.list = null;
    release();
    await settle();
    assert.equal(app.shown(), "@author2");
    assert.equal(app.element("x-archive").disabled, false, "and the page takes actions again afterwards");
});

test("a preview video is released when the next item replaces it and when the preview closes", async () => {
    const app = inbox([post(1, [clip(1)]), post(2, [clip(2)])]);
    await settle();
    app.open("1");
    const first = app.stageVideo();
    assert.equal(first.src, "/api/x-import/inbox/1/media/v1/content");

    app.key("ArrowDown");
    assert.equal(first.paused, true);
    assert.equal(first.attributes.has("src"), false);
    assert.equal(first.loads, 1);
    assert.equal(app.element("x-stage").querySelectorAll("video").length, 1, "only the current one is on stage");

    const second = app.stageVideo();
    app.key("Escape");
    assert.equal(second.paused, true, "stops at once");
    assert.equal(second.src, "/api/x-import/inbox/2/media/v2/content", "the frame stays for the fade");
    await new Promise((resolve) => setTimeout(resolve, 200));
    assert.equal(second.attributes.has("src"), false);
    assert.equal(second.loads, 1);
    assert.equal(app.element("x-stage").children.length, 0);
});

test("a list refresh does not restart the video on screen", async () => {
    const app = inbox([post(1, [clip(1)]), post(2)]);
    await settle();
    let playing;
    await syncChangesListWhile(app, async () => {
        app.open("1");
        playing = app.stageVideo();
        app.items = [post(9), ...app.items];
    });
    assert.equal(app.stageVideo(), playing, "the same element, still on stage");
    assert.equal(playing.paused, undefined);
    assert.equal(playing.loads, undefined);
});

test("a card that leaves the list releases its video; the others keep theirs", async () => {
    const app = inbox([post(1, [clip(1)]), post(2, [clip(2)])]);
    await settle();
    const leaving = app.card("1").querySelectorAll("video")[0];
    const staying = app.card("2").querySelectorAll("video")[0];
    app.card("1").listeners.get("click")({ target: { closest: () => null } });
    app.click("x-archive");
    await settle();
    assert.equal(app.card("1"), undefined);
    assert.equal(leaving.attributes.has("src"), false);
    assert.equal(leaving.loads, 1);
    assert.equal(staying.loads, undefined);
    assert.ok(staying.src);
});
