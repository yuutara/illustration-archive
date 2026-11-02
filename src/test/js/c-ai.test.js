const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

/* 看图器 AI 分区读的字段要和 AiAnalysisResponse 一致；夹具就是这个 record 序列化后的样子。 */
const source = readFileSync(path.resolve(__dirname, "../../main/resources/static/c/c-ai.js"), "utf8");

class Node {
    constructor() { this.children = []; this.listeners = new Map(); this.textContent = ""; this.className = ""; }
    appendChild(child) { this.children.push(child); return child; }
    addEventListener(type, fn) { this.listeners.set(type, fn); }
    allText() { return [this.textContent, ...this.children.map((child) => child.allText())].join("\n"); }
    find(tag) { return this.tag === tag ? this : this.children.map((child) => child.find(tag)).find(Boolean); }
}

const response = (skippedAssets) => ({
    summary: "两页漫画", model: "gemini-3.1-flash-lite", relationships: [], suggestedTags: [], characters: [],
    pages: [{ index: 1, description: "第一页", texts: [] }],
    analyzedAssets: [{ assetId: 11, position: 1, mimeType: "image/png" }],
    skippedAssets
});

async function answered(status, body) {
    const window = {};
    const context = {
        window, encodeURIComponent, Map,
        document: { createElement: (tag) => Object.assign(new Node(), { tag }) },
        fetch: async () => ({ ok: status < 400, status, json: async () => body })
    };
    vm.createContext(context);
    vm.runInContext(source, context);
    const render = () => { const box = new Node(); window.CAi.attach(box, { id: 7 }, () => { }); return box; };
    render().find("button").listeners.get("click")();
    await new Promise((resolve) => setImmediate(resolve));
    return render();
}

const analyzed = async (body) => (await answered(200, body)).allText();

test("a key Gemini refused leads back to Settings and can be retried", async () => {
    const box = await answered(502, { message: "Gemini 拒绝了这次请求。请到设置检查。" });
    assert.equal(box.find("a").href, "/c/settings.html#ai");
    assert.equal(box.find("button").textContent, "重试");
    assert.match(box.allText(), /Gemini 拒绝了这次请求/);
});

test("a passing failure offers a retry, not a trip to Settings", async () => {
    const box = await answered(429, { message: "已达到 Gemini 的额度或速率限制，请稍后再试。" });
    assert.equal(box.find("a"), undefined);
    assert.equal(box.find("button").textContent, "重试");
});

test("the missing key is the one failure with nothing to retry", async () => {
    const box = await answered(503, { message: "AI 分析未启用或后端配置未完成。" });
    assert.match(box.allText(), /还没有填写 Gemini API Key/);
    assert.equal(box.find("a").href, "/c/settings.html#ai");
});

test("before anything is sent the page says where the image goes and does not promise it is free", () => {
    const window = {};
    const context = { window, Map, encodeURIComponent, document: { createElement: (tag) => Object.assign(new Node(), { tag }) } };
    vm.createContext(context);
    vm.runInContext(source, context);
    const box = new Node();
    window.CAi.attach(box, { id: 7 }, () => { });
    assert.match(box.allText(), /上传到 Google Gemini/);
    assert.doesNotMatch(box.allText(), /免费/);
});

test("the note counts the media the server actually left out", async () => {
    const text = await analyzed(response([
        { assetId: 12, position: 2, mimeType: "video/mp4" },
        { assetId: 13, position: 3, mimeType: "image/gif" }
    ]));
    assert.match(text, /有 2 个动图或视频没有参与分析。/);
});

test("nothing is said when every page was analyzed", async () => {
    assert.doesNotMatch(await analyzed(response([])), /没有参与分析/);
});
