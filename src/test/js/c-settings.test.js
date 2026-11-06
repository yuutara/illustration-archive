const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const read = (name) => readFileSync(path.resolve(__dirname, "../../main/resources/static/c", name), "utf8");
const source = read("c-settings.js");
const modelSource = read("c-x-model.js");
const html = read("settings.html");

/** Any element the page asks for. */
class Node {
    constructor(tag = "div") {
        this.tag = tag;
        this.listeners = new Map();
        this.children = [];
        this.dataset = {};
        this.hidden = false;
        this.checked = false;
        this.disabled = false;
        this.open = false;
        this.textContent = "";
        this.value = "";
        this.placeholder = "";
    }
    addEventListener(type, fn) { this.listeners.set(type, fn); }
    setAttribute() { }
    querySelector() { return new Node(); }
    replaceChildren(...children) { this.children = children; }
    appendChild(child) { this.children.push(child); return child; }
    allText() { return [this.textContent, ...this.children.map((child) => child.allText())].join("\n"); }
    button(text) { return this.children.find((child) => child.textContent === text); }
    click(text) { return this.button(text).listeners.get("click")({}); }
}

const settle = async () => { for (let i = 0; i < 6; i++) await new Promise((resolve) => setImmediate(resolve)); };

const connectionOf = (state, savedLogin = "NONE", { status, ...rest } = {}) => ({
    status: { state, savedLogin, failure: null, authorizeUrl: null, browserOpened: false, ...status },
    clientId: "", callbackUrl: "http://127.0.0.1:47392/x/callback", scanBudget: 100, verifiedAt: null, verifiedAccount: null, ...rest
});

async function setup({ host = true, saved = "TRAY", accept = true, connection = connectionOf("DISCONNECTED"), keySaved = false,
    verifiedAt = null, search = "", answer } = {}) {
    const nodes = new Map();
    const element = (id) => { if (!nodes.has(id)) nodes.set(id, new Node()); return nodes.get(id); };
    element("window").hidden = true;
    const choices = ["ASK", "TRAY", "EXIT"].map((value) => Object.assign(new Node(), { value }));
    const sent = [];
    const app = { nodes, choices, sent, element, connection, timers: [], location: { search, href: "" }, reads: [] };
    const reply = (status, body) => ({ ok: status < 400, status, json: async () => body });
    const fetch = async (url, options = {}) => {
        app.reads.push(url);
        if (url === "/api/x-auth/status") return reply(200, app.connection);
        if (url === "/api/desktop/settings") return reply(200, { dataDirectory: "D:\\data", version: "1", proxy: "", geminiKeySaved: keySaved, geminiVerifiedAt: verifiedAt, closeAction: saved });
        sent.push([url, options]);
        if (answer) return reply(...await answer(url, options));
        return accept ? reply(204) : reply(500, { message: "disk full" });
    };
    const glass = [];
    const window = { CTheme: { get: () => "light", set() { }, glass: () => 50, GLASS_DEFAULT: 50,
        setGlass: (level, save = true) => glass.push([String(level), save]) } };
    app.glass = glass;
    if (host) window.iaDesktop = () => { };
    const context = {
        window, fetch, clearTimeout, URLSearchParams, Promise, Date, String,
        setTimeout: (fn, millis) => { if (millis === 1500) app.timers.push(fn); return 0; },
        location: app.location,
        navigator: { clipboard: { writeText: async () => { } } },
        document: {
            getElementById: element,
            createElement: (tag) => new Node(tag),
            querySelectorAll: () => choices
        }
    };
    vm.createContext(context);
    vm.runInContext(modelSource, context);
    vm.runInContext(source, context);
    await settle();
    return Object.assign(app, { checked: () => choices.find((choice) => choice.checked)?.value });
}

test("the saved close choice is shown only where there is a window to close", async () => {
    const app = await setup();
    assert.equal(app.element("window").hidden, false);
    assert.equal(app.checked(), "TRAY");
    assert.equal((await setup({ host: false })).element("window").hidden, true);
});

test("the glass slider changes the page while dragging and saves only when let go, and can go back to the default", async () => {
    const app = await setup();
    const slider = app.element("glass");
    assert.equal(slider.value, "50");
    slider.value = "80";
    slider.listeners.get("input")();
    assert.deepEqual(app.glass, [["80", false]]);
    slider.listeners.get("change")();
    assert.deepEqual(app.glass.at(-1), ["80", true]);
    app.element("glass-reset").listeners.get("click")();
    assert.deepEqual(app.glass.at(-1), ["50", true]);
    assert.match(html, /id="glass" type="range" min="0" max="100"/);
});

test("picking a choice saves it", async () => {
    const { choices, sent, checked, element } = await setup();
    const exit = choices[2];
    exit.checked = true;
    exit.listeners.get("change")();
    await settle();
    assert.equal(sent[0][0], "/api/desktop/settings/close-action");
    assert.equal(sent[0][1].method, "PUT");
    assert.equal(sent[0][1].body, '{"action":"EXIT"}');
    assert.equal(checked(), "EXIT");
    assert.equal(element("close-error").hidden, true);
});

test("a choice that could not be saved goes back to what is really saved, and says so", async () => {
    const { choices, checked, element } = await setup({ accept: false });
    const ask = choices[0];
    choices[1].checked = false;
    ask.checked = true;
    ask.listeners.get("change")();
    await settle();
    assert.equal(checked(), "TRAY");
    assert.equal(element("close-error").hidden, false);
    assert.match(element("close-error").textContent, /disk full/);
});

test("the developer app guide stays folded unless someone arrives from X 收藏 with nothing set up", async () => {
    assert.equal((await setup()).element("x-guide").open, false);
    assert.equal((await setup({ search: "?from=x" })).element("x-guide").open, true);
    const configured = connectionOf("DISCONNECTED", "NONE", { clientId: "client-id-123" });
    assert.equal((await setup({ search: "?from=x", connection: configured })).element("x-guide").open, false);
});

test("the page asks for no Client Secret and promises neither free use nor a connection test", () => {
    assert.doesNotMatch(html, /id="[^"]*secret/i);
    assert.doesNotMatch(html, /测试连接/);
    assert.match(html, /不能保证一定免费/);
});

test("nothing is read from X or Gemini by opening the page", async () => {
    const { reads, sent } = await setup({ connection: connectionOf("CONNECTED", "USABLE", { clientId: "client-id-123" }) });
    assert.deepEqual([...new Set(reads)].sort(), ["/api/desktop/settings", "/api/x-auth/status"]);
    assert.equal(sent.length, 0);
});

test("a Client ID is needed before anything is sent", async () => {
    const app = await setup();
    app.element("x-actions").click("在浏览器中授权");
    await settle();
    assert.equal(app.sent.length, 0);
    assert.match(app.element("x-error").textContent, /Client ID/);
});

test("authorizing twice in a row only starts one login", async () => {
    let finish;
    const app = await setup({ answer: () => new Promise((resolve) => { finish = () => resolve([200, connectionOf("WAITING")]); }) });
    app.element("x-client-id").value = "client-id-123";
    const start = app.element("x-actions").button("在浏览器中授权").listeners.get("click");
    start({});
    start({});
    await settle();
    assert.equal(app.sent.length, 1);
    assert.equal(JSON.parse(app.sent[0][1].body).clientId, "client-id-123");
    finish();
    await settle();
});

test("a browser that did not open is said plainly, with the link to copy", async () => {
    const waiting = connectionOf("WAITING", "NONE", { status: { authorizeUrl: "https://x.test/a", browserOpened: false } });
    const app = await setup({ connection: waiting });
    assert.match(app.element("x-state").allText(), /没能自动打开浏览器/);
    assert.ok(app.element("x-actions").button("复制授权链接"));
    assert.equal(app.element("x-setup").hidden, true);
});

test("finishing in the browser is picked up, says nothing has been read, and names no account", async () => {
    const waiting = connectionOf("WAITING", "NONE", { clientId: "client-id-123", status: { authorizeUrl: "https://x.test/a", browserOpened: true } });
    const app = await setup({ connection: waiting });
    assert.match(app.element("x-state").allText(), /在打开的浏览器页面中完成授权/);
    app.connection = connectionOf("CONNECTED", "USABLE", { clientId: "client-id-123" });
    app.timers.pop()();
    await settle();
    assert.match(app.element("toast").textContent, /还没有读取任何 X 数据/);
    assert.match(app.element("x-steps").allText(), /尚未验证/);
    assert.doesNotMatch(app.element("x-steps").allText(), /@/);
    assert.equal(app.timers.length, 0);
    assert.ok(app.element("x-actions").button("回到 X 收藏"));
    assert.equal(app.sent.length, 0, "no sync and no analysis");
});

test("going back to X 收藏 is one click once connected", async () => {
    const app = await setup({ connection: connectionOf("CONNECTED", "USABLE", { clientId: "client-id-123" }) });
    app.element("x-actions").click("回到 X 收藏");
    assert.equal(app.location.href, "/c/x.html");
    assert.ok(app.element("x-actions").button("重新授权"), "a login X stopped accepting can be replaced without disconnecting");
});

test("a failed reconnect keeps saying the existing login is fine", async () => {
    const app = await setup({ connection: connectionOf("FAILED", "USABLE", { clientId: "client-id-123", status: { failure: "DENIED" } }) });
    assert.match(app.element("x-error").textContent, /原来的连接没有变化/);
    assert.match(app.element("x-steps").allText(), /已连接，这次重新授权没有完成/);
    assert.ok(app.element("x-actions").button("断开连接"));
});

test("a failed first login can simply be tried again with the same Client ID", async () => {
    const app = await setup({ connection: connectionOf("FAILED", "NONE", { clientId: "client-id-123", status: { failure: "TIMED_OUT" } }), answer: () => [200, connectionOf("WAITING")] });
    assert.equal(app.element("x-client-id").value, "client-id-123");
    assert.match(app.element("x-error").textContent, /超时/);
    app.element("x-actions").click("在浏览器中授权");
    await settle();
    assert.equal(app.sent[0][0], "/api/x-auth/start");
    assert.equal(app.element("x-error").hidden, true);
});

test("a Client ID that could not be saved stays in the box", async () => {
    const app = await setup({ answer: () => [500, { message: "写入数据文件夹失败" }] });
    app.element("x-client-id").value = "client-id-123";
    app.element("x-actions").click("在浏览器中授权");
    await settle();
    assert.equal(app.element("x-client-id").value, "client-id-123");
    assert.match(app.element("x-error").textContent, /写入数据文件夹失败.*再试一次/);
});

test("tokens the app cannot use are said to be unusable and can be removed", async () => {
    const app = await setup({ connection: connectionOf("DISCONNECTED", "UNUSABLE"), answer: () => [200, { revoked: false }] });
    assert.match(app.element("x-steps").allText(), /不能使用/);
    assert.doesNotMatch(app.element("x-steps").allText(), /已连接/);
    app.connection = connectionOf("DISCONNECTED");
    app.element("x-actions").click("移除这份登录");
    await settle();
    assert.equal(app.sent[0][0], "/api/x-auth/disconnect");
    assert.match(app.element("toast").textContent, /本机的登录已移除/);
});

test("saving a key says it is only saved, and clears it from the page", async () => {
    const app = await setup({ answer: () => [204] });
    assert.equal(app.element("ai-save").textContent, "保存");
    app.element("ai-key").value = " fake-key ";
    app.element("ai-save").listeners.get("click")({});
    await settle();
    assert.equal(JSON.parse(app.sent[0][1].body).apiKey, "fake-key");
    assert.equal(app.element("ai-key").value, "");
    assert.match(app.element("toast").textContent, /已保存在本机，尚未验证/);
    assert.match(app.element("ai-steps").allText(), /尚未验证/);
    assert.doesNotMatch(app.element("ai-steps").allText() + app.element("toast").textContent, /连接成功|验证成功|fake-key/);
    assert.equal(app.element("ai-save").textContent, "替换");
    assert.equal(app.element("ai-clear").hidden, false);
});

test("replacing a key drops the proof that belonged to the old one", async () => {
    const app = await setup({ keySaved: true, verifiedAt: "2026-11-02T06:03:00Z", answer: () => [204] });
    assert.match(app.element("ai-steps").allText(), /已验证/);
    app.element("ai-key").value = "fake-key-2";
    app.element("ai-save").listeners.get("click")({});
    await settle();
    assert.match(app.element("toast").textContent, /已替换/);
    assert.match(app.element("ai-steps").allText(), /尚未验证/);
});

test("a key that could not be saved stays in the box, keeps the old one, and can be retried", async () => {
    let attempts = 0;
    const app = await setup({ keySaved: true, answer: () => (++attempts === 1 ? [500, { message: "写入数据文件夹失败" }] : [204]) });
    app.element("ai-key").value = "fake-key-2";
    app.element("ai-save").listeners.get("click")({});
    await settle();
    assert.equal(app.element("ai-key").value, "fake-key-2");
    assert.match(app.element("ai-error").textContent, /没有保存.*原来的 Key 没有变化/);
    assert.equal(app.element("ai-save").disabled, false);
    app.element("ai-save").listeners.get("click")({});
    await settle();
    assert.equal(app.element("ai-error").hidden, true);
    assert.equal(app.element("ai-key").value, "");
});

test("a badly formed key is told apart from a failed save", async () => {
    const app = await setup({ answer: () => [400, { message: "API key format is not valid." }] });
    app.element("ai-key").value = "fake key";
    app.element("ai-save").listeners.get("click")({});
    await settle();
    assert.match(app.element("ai-error").textContent, /格式不对/);
});

test("removing a key says so, and a failed removal keeps offering it", async () => {
    let attempts = 0;
    const app = await setup({ keySaved: true, answer: () => (++attempts === 1 ? [500, { message: "写入数据文件夹失败" }] : [204]) });
    app.element("ai-clear").listeners.get("click")({});
    await settle();
    assert.match(app.element("ai-error").textContent, /没有移除/);
    assert.equal(app.element("ai-clear").hidden, false);
    app.element("ai-clear").listeners.get("click")({});
    await settle();
    assert.match(app.element("toast").textContent, /已移除/);
    assert.equal(app.element("ai-clear").hidden, true);
    assert.equal(app.element("ai-save").textContent, "保存");
});
