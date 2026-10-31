const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = readFileSync(path.resolve(__dirname, "../../main/resources/static/c/c-settings.js"), "utf8");

/** Any element the page asks for; only the close-action choices matter here. */
class Node {
    constructor() {
        this.listeners = new Map();
        this.hidden = false;
        this.checked = false;
        this.textContent = "";
        this.value = "";
    }
    addEventListener(type, fn) { this.listeners.set(type, fn); }
    setAttribute() { }
    querySelector() { return new Node(); }
    replaceChildren() { }
    appendChild() { }
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

async function setup({ host = true, saved = "TRAY", accept = true } = {}) {
    const nodes = new Map();
    const element = (id) => { if (!nodes.has(id)) nodes.set(id, new Node()); return nodes.get(id); };
    element("window").hidden = true;
    const choices = ["ASK", "TRAY", "EXIT"].map((value) => Object.assign(new Node(), { value }));
    const sent = [];
    const reply = (status, body) => ({ ok: status < 400, status, json: async () => body });
    const fetch = async (url, options) => {
        if (url === "/api/x-auth/status") return reply(200, { status: { state: "NONE" }, callbackUrl: "", clientId: "" });
        if (url === "/api/desktop/settings") return reply(200, { dataDirectory: "D:\\data", version: "1", proxy: "", geminiKeySaved: false, closeAction: saved });
        sent.push([url, options]);
        return accept ? reply(204) : reply(500, { message: "disk full" });
    };
    const window = { XInboxModel: { connectionProblem: () => "" }, CTheme: { get: () => "light", set() { } } };
    if (host) window.iaDesktop = () => { };
    vm.runInNewContext(source, {
        window, fetch, setTimeout, clearTimeout,
        document: {
            getElementById: element,
            createElement: () => new Node(),
            querySelectorAll: () => choices
        }
    });
    await settle();
    await settle();
    return { nodes, choices, sent, element, checked: () => choices.find((choice) => choice.checked)?.value };
}

test("the saved close choice is shown only where there is a window to close", async () => {
    const app = await setup();
    assert.equal(app.element("window").hidden, false);
    assert.equal(app.checked(), "TRAY");
    assert.equal((await setup({ host: false })).element("window").hidden, true);
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
