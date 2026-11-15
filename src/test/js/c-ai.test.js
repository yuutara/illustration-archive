const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

/* 看图器 AI 翻译分区读的字段要和 AiAnalysisResponse 一致；夹具就是这个 record 序列化后的样子。 */
const source = readFileSync(path.resolve(__dirname, "../../main/resources/static/c/c-ai.js"), "utf8");

class Node {
    constructor() { this.children = []; this.listeners = new Map(); this.attrs = new Map(); this.textContent = ""; this.className = ""; }
    appendChild(child) { this.children.push(child); return child; }
    addEventListener(type, fn) { this.listeners.set(type, fn); }
    setAttribute(name, value) { this.attrs.set(name, value); }
    allText() { return [this.textContent, ...this.children.map((child) => child.allText())].join("\n"); }
    find(tag, predicate) {
        if (this.tag === tag && (!predicate || predicate(this))) return this;
        return this.children.map((child) => child.find(tag, predicate)).find(Boolean);
    }
    findAll(tag, predicate) {
        const out = this.tag === tag && (!predicate || predicate(this)) ? [this] : [];
        for (const child of this.children) out.push(...child.findAll(tag, predicate));
        return out;
    }
}

const actionBtn = (box) => box.find("button", (b) => b.className === "btn");

const response = (skippedAssets = [], overrides = {}) => ({
    model: "gemini-4-hydrogen",
    pages: [{ index: 1, description: "人物面对面交谈", texts: [] }],
    analyzedAssets: [{ assetId: 11, position: 1, mimeType: "image/png" }],
    skippedAssets,
    ...overrides
});

function mount(fetchImpl) {
    const window = {};
    const context = {
        window, encodeURIComponent, Map,
        document: { createElement: (tag) => Object.assign(new Node(), { tag }) },
        fetch: fetchImpl
    };
    vm.createContext(context);
    vm.runInContext(source, context);
    return () => { const box = new Node(); window.CAi.attach(box, { id: 7 }, () => { }); return box; };
}

async function answered(status, body) {
    const urls = [];
    const render = mount(async (url) => {
        urls.push(url);
        return { ok: status < 400, status, json: async () => body };
    });
    actionBtn(render()).listeners.get("click")();
    await new Promise((resolve) => setImmediate(resolve));
    return { box: render(), urls };
}

test("a key Gemini refused leads back to Settings and can be retried", async () => {
    const { box } = await answered(502, { message: "Gemini 拒绝了这次请求。请到设置检查。（HTTP 400 INVALID_ARGUMENT，详情见 logs\\desktop.log）" });
    assert.equal(box.find("a").href, "/c/settings.html#ai");
    assert.equal(actionBtn(box).textContent, "重试");
    assert.match(box.allText(), /Gemini 拒绝了这次请求/);
});

test("a passing failure offers a retry, not a trip to Settings", async () => {
    const { box } = await answered(429, { message: "已达到 Gemini 的额度或速率限制（HTTP 429 RESOURCE_EXHAUSTED），请稍后再试。" });
    assert.equal(box.find("a"), undefined);
    assert.equal(actionBtn(box).textContent, "重试");
});

test("model unavailable (404/503 without 未启用) shows exact failure and retry without blaming missing key", async () => {
    const { box } = await answered(503, { message: "模型 gemini-4-hydrogen 在当前接口不可用（HTTP 404 NOT_FOUND），具体错误见 logs\\desktop.log。" });
    assert.equal(box.find("a"), undefined);
    assert.equal(actionBtn(box).textContent, "重试");
    assert.match(box.allText(), /gemini-4-hydrogen.*404 NOT_FOUND/);
    assert.doesNotMatch(box.allText(), /还没有填写 Gemini API Key/);
});

test("truncation (MAX_TOKENS) is surfaced with retry", async () => {
    const { box } = await answered(502, { message: "AI 返回内容超出长度限制被截断（MAX_TOKENS），请重试。" });
    assert.equal(actionBtn(box).textContent, "重试");
    assert.match(box.allText(), /MAX_TOKENS/);
});

test("the missing key is the one failure with nothing to retry", async () => {
    const { box } = await answered(503, { message: "AI 分析未启用或后端配置未完成。" });
    assert.match(box.allText(), /还没有填写 Gemini API Key/);
    assert.equal(box.find("a").href, "/c/settings.html#ai");
});

test("before sending, entry says 翻译画面文字, omits model switcher, discloses upload and avoids free promise", () => {
    const render = mount();
    const box = render();
    assert.equal(actionBtn(box).textContent, "翻译画面文字");
    assert.equal(box.findAll("button").length, 1);
    assert.match(box.allText(), /上传到 Google Gemini/);
    assert.doesNotMatch(box.allText(), /免费|模型对照|3\.1 Flash-Lite/);
});

test("single-page cards show note -> translation -> source, deduplicate identical text, keep newlines, and place description after cards", async () => {
    const { box, urls } = await answered(200, response([], {
        pages: [{
            index: 1,
            description: "少女手持账单皱眉对话",
            texts: [
                { source: "こんにちは！\n元気？", translation: "你好！\n最近怎么样？", note: "右上对白" },
                { source: "[无法辨认]", translation: "[无法辨认]", note: "左下角落" },
                { source: "<b>¥169231</b>", translation: "¥169231", note: "" }
            ]
        }]
    }));
    assert.deepEqual(urls, ["/api/illustrations/7/ai-analysis"]);
    assert.equal(box.findAll("h4", (n) => n.className === "ai-page-title").length, 0);
    assert.equal(box.find("div", (n) => n.className === "ai-meta"), undefined);

    const cards = box.findAll("div", (n) => n.className === "ai-card");
    assert.equal(cards.length, 3);

    assert.deepEqual(cards[0].children.map((c) => [c.className, c.textContent]), [
        ["ai-card-note", "右上对白"],
        ["ai-card-tr", "你好！\n最近怎么样？"],
        ["ai-card-src", "こんにちは！\n元気？"]
    ]);
    assert.deepEqual(cards[1].children.map((c) => [c.className, c.textContent]), [
        ["ai-card-note", "左下角落"],
        ["ai-card-tr", "[无法辨认]"]
    ]);
    assert.deepEqual(cards[2].children.map((c) => [c.className, c.textContent]), [
        ["ai-card-tr", "¥169231"],
        ["ai-card-src", "<b>¥169231</b>"]
    ]);

    const group = box.find("div", (n) => n.className === "ai-group");
    assert.equal(group.children.at(-1).className, "ai-desc");
    assert.equal(group.children.at(-1).textContent, "少女手持账单皱眉对话");
    assert.ok(cards.every((c) => c.innerHTML === undefined));
});

test("empty page displays 未发现可翻译文字 and one description sentence", async () => {
    const { box } = await answered(200, response([]));
    const text = box.allText();
    assert.match(text, /未发现可翻译文字/);
    assert.match(text, /人物面对面交谈/);
});

test("multi-page and mixed media group by archive position order", async () => {
    const { box } = await answered(200, {
        model: "gemini-4-hydrogen",
        pages: [
            { index: 1, description: "第二张有字漫画", texts: [{ source: "えっ", translation: "哎", note: "旁白" }] },
            { index: 2, description: "第三张无字插画", texts: [] }
        ],
        analyzedAssets: [
            { assetId: 22, position: 2, mimeType: "image/png" },
            { assetId: 33, position: 3, mimeType: "image/jpeg" }
        ],
        skippedAssets: [
            { assetId: 11, position: 1, mimeType: "image/gif" },
            { assetId: 44, position: 4, mimeType: "video/mp4" }
        ]
    });
    const groups = box.findAll("div", (n) => n.className === "ai-group");
    assert.equal(groups.length, 4);
    assert.deepEqual(groups.map((g) => g.find("h4").textContent), ["第 1 页", "第 2 页", "第 3 页", "第 4 页"]);
    assert.match(groups[0].allText(), /动图或视频未参与翻译/);
    assert.match(groups[1].allText(), /哎/);
    assert.match(groups[1].allText(), /第二张有字漫画/);
    assert.match(groups[2].allText(), /未发现可翻译文字/);
    assert.match(groups[2].allText(), /第三张无字插画/);
    assert.match(groups[3].allText(), /动图或视频未参与翻译/);
});
