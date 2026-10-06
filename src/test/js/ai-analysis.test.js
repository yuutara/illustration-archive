const test = require("node:test");
const assert = require("node:assert/strict");
const { browser, settle } = require("./helpers/browser");

const detail = { id: 7, title: "Comic", assets: [{ id: 11, mimeType: "image/jpeg", sortOrder: 0 }], tags: [] };
const result = {
    summary: "<script>摘要</script>", pages: [{ index: 1, description: "看不清对白", texts: [
        { source: "<img src=x onerror=evil()>Hello", translation: "<script>你好</script>", note: "右上对话框 <b>注释</b>" }
    ] }],
    characters: [{ name: "黑发角色", description: "身份不确定" }], relationships: [], suggestedTags: ["<img onerror=evil()>"],
    model: "free-model", analyzedAssets: [{ assetId: 11, position: 2, mimeType: "image/jpeg" }],
    skippedAssets: [{ assetId: 12, position: 1, mimeType: "video/mp4" }]
};
async function setup(post, loaded = detail) {
    const env = browser({ url: "http://localhost/detail.html?id=7" });
    env.window.AbortController = AbortController;
    env.sandbox.fetchImpl = (url, options = {}) => options.method === "POST" ? post(url, options) : Promise.resolve({ ok: true, json: async () => loaded });
    env.run("detail.js"); await settle();
    env.document.getElementById("detail-ai-open-viewer").dispatch("click");
    env.node = suffix => env.find(`image-viewer-ai-${suffix === "button" ? "action" : suffix}`);
    return env;
}

test("Viewer owns analysis, locks duplicate clicks and safely renders the current page with a collapsed overview", async () => {
    let finish, calls = 0;
    const env = await setup((url, options) => {
        ++calls; assert.equal(url, "/api/illustrations/7/ai-analysis");
        assert.equal(options.method, "POST"); assert.equal(options.body, undefined);
        assert.equal(options.headers.Authorization, undefined);
        return new Promise(resolve => { finish = resolve; });
    });
    assert.equal(env.node("status").textContent, "未分析");
    const pending = env.node("button").dispatch("click");
    assert.equal(env.node("status").textContent, "分析中…");
    assert.equal(env.node("button").disabled, true);
    await env.node("button").dispatch("click"); assert.equal(calls, 1);
    finish({ ok: true, json: async () => result }); await pending;
    assert.equal(env.node("status").textContent, "分析完成"); assert.equal(env.node("button").hidden, true);
    const children = env.node("result").children;
    assert.equal(children[0].textContent, "当前图片 · 第 2 张");
    assert.ok(children.some(node => node.textContent === "看不清对白"));
    assert.ok(children.some(node => node.tagName === "h4" && node.textContent === "画面文字 / 翻译"));
    assert.ok(children.some(node => node.textContent === `原文：${result.pages[0].texts[0].source}`));
    assert.ok(children.some(node => node.textContent === `简体中文：${result.pages[0].texts[0].translation}`));
    assert.ok(children.some(node => node.textContent === `位置 / 类型：${result.pages[0].texts[0].note}`));
    const overview = env.find("image-viewer-ai-overview");
    assert.equal(overview.tagName, "details"); assert.equal(overview.open, false);
    assert.ok(overview.children.some(node => node.textContent === result.summary));
    assert.ok(overview.children.some(node => node.textContent === result.suggestedTags[0]));
    assert.ok(overview.children.some(node => /第 1 张.*video\/mp4/.test(node.textContent)));
    assert.ok([...children, ...overview.children].every(node => node.innerHTML === undefined));
    assert.equal(env.document.getElementById("detail-title").textContent, "Comic");
});

test("Viewer keeps page text order, switches pages, and keeps the overview folded state", async () => {
    const response = { ...result, pages: [
        { index: 1, description: "第一幅交谈", texts: [
            { source: "こんにちは！", translation: "你好！", note: "右上对话框" },
            { source: "[无法辨认]", translation: "[无法辨认]", note: "左侧旁白" }
        ] },
        { index: 2, description: "第二幅无文字", texts: [] }
    ], analyzedAssets: [{ assetId: 11, position: 1, mimeType: "image/jpeg" },
        { assetId: 13, position: 2, mimeType: "image/png" }], skippedAssets: [] };
    const loaded = { ...detail, assets: [...detail.assets, { id: 13, mimeType: "image/png", sortOrder: 1 }] };
    const env = await setup(async () => ({ ok: true, json: async () => response }), loaded);
    await env.node("button").dispatch("click");
    assert.equal(env.node("status").textContent, "分析完成");
    const lines = env.node("result").children.map(node => node.textContent);
    assert.deepEqual(lines.slice(0, 8), [
        "当前图片 · 第 1 张", "第一幅交谈", "画面文字 / 翻译",
        "原文：こんにちは！", "简体中文：你好！", "位置 / 类型：右上对话框",
        "原文：[无法辨认]", "简体中文：[无法辨认]"
    ]);
    env.find("image-viewer-ai-overview").open = true;
    env.viewerButton("下一张图片").dispatch("click");
    assert.deepEqual(env.node("result").children.slice(0, 4).map(node => node.textContent),
        ["当前图片 · 第 2 张", "第二幅无文字", "画面文字 / 翻译", "暂无可读文字"]);
    assert.equal(env.find("image-viewer-ai-overview").open, true);
});

test("Viewer analysis failures are visible and can be retried manually", async () => {
    let calls = 0;
    const env = await setup(async () => ++calls === 1 ? { ok: false, json: async () => ({ message: "免费 AI 分析暂不可用。" }) }
        : { ok: true, json: async () => result });
    await env.node("button").dispatch("click");
    assert.equal(env.node("status").textContent, "失败：免费 AI 分析暂不可用。");
    assert.equal(env.node("result").children.length, 0); assert.equal(env.node("button").disabled, false);
    await env.node("button").dispatch("click"); assert.equal(env.node("status").textContent, "分析完成");
    assert.equal(calls, 2);
});

test("Leaving Detail aborts Viewer analysis and ignores a late response", async () => {
    let finish, signal;
    const env = await setup((url, options) => { signal = options.signal; return new Promise(resolve => { finish = resolve; }); });
    const pending = env.node("button").dispatch("click");
    env.window.dispatch("pagehide"); assert.equal(signal.aborted, true);
    finish({ ok: true, json: async () => result }); await pending;
    assert.equal(env.node("status").textContent, "未分析"); assert.equal(env.node("button").disabled, false);
    assert.equal(env.node("result").children.length, 0);
});

test("Browser timeout and invalid response JSON show failure without automatic retry", async () => {
    let calls = 0;
    const env = await setup((url, options) => {
        ++calls;
        if (calls > 1) return Promise.resolve({ ok: true, json: async () => { throw new Error("Invalid JSON"); } });
        return new Promise((resolve, reject) => options.signal.addEventListener("abort", () => reject(new Error("aborted"))));
    });
    const pending = env.node("button").dispatch("click"); env.flushTimers(); await pending;
    assert.equal(env.node("status").textContent, "失败：AI 分析超时，请稍后再试。"); assert.equal(calls, 1);
    await env.node("button").dispatch("click"); assert.match(env.node("status").textContent, /^失败：/);
    assert.equal(env.node("button").disabled, false);
});

const comicDetail = { id: 7, title: "Comic", tags: [], assets: [
    { id: 11, mimeType: "image/jpeg", sortOrder: 0 },
    { id: 12, mimeType: "video/mp4", sortOrder: 1 }
] };
const comicResult = { ...result,
    analyzedAssets: [{ assetId: 11, position: 1, mimeType: "image/jpeg" }],
    skippedAssets: [{ assetId: 12, position: 2, mimeType: "video/mp4" }]
};

async function galleryComic(options = {}) {
    const env = browser({ url: "http://localhost/?q=comic&page=0", ...options });
    env.window.AbortController = AbortController;
    let posts = 0;
    env.sandbox.fetchImpl = async (url, request = {}) => {
        if (request.method === "POST") { posts++; return { ok: true, json: async () => comicResult }; }
        return { ok: true, json: async () => ({ page: 0, totalPages: 1, items: [comicDetail] }) };
    };
    env.run("app.js"); await settle(); env.flushFrames();
    const card = env.document.querySelectorAll(".card-image-link")[0];
    card.dispatch("click");
    return { env, card, posts: () => posts };
}

async function transferredDetail(url, storage, loaded = comicDetail) {
    const env = browser({ url: `http://localhost${url}`, storage });
    env.window.AbortController = AbortController;
    let posts = 0;
    env.sandbox.fetchImpl = async (path, request = {}) => {
        if (request.method === "POST") { posts++; return { ok: true, json: async () => comicResult }; }
        return { ok: true, json: async () => loaded };
    };
    env.run("detail.js"); await settle(); env.flushFrames();
    return { env, posts: () => posts };
}

test("Gallery Viewer analyzes only on click, follows the current Asset and reuses its result after close", async () => {
    const { env, card, posts } = await galleryComic();
    assert.equal(posts(), 0);
    env.find("image-viewer-info").dispatch("click");
    assert.equal(env.viewerButton("AI 分析").hidden, false);
    env.find("image-viewer-inspector-tabs").dispatch("keydown", { key: "ArrowRight", target: env.viewerButton("作品信息") });
    assert.equal(env.document.activeElement, env.viewerButton("AI 分析"));
    assert.equal(env.viewerButton("AI 分析").getAttribute("aria-selected"), "true");
    assert.equal(posts(), 0);
    await env.viewerButton("开始 AI 分析").dispatch("click");
    assert.equal(posts(), 1);
    assert.match(env.find("image-viewer-ai-result").textContent || env.find("image-viewer-ai-result").children.map(node => node.textContent).join(" "), /看不清对白/);
    env.viewerButton("下一张图片").dispatch("click");
    assert.equal(env.viewerButton("AI 分析").getAttribute("aria-selected"), "true");
    assert.match(env.find("image-viewer-ai-result").children.map(node => node.textContent).join(" "), /未参与分析/);
    assert.equal(posts(), 1);
    env.find("image-viewer").dispatch("cancel"); await settle();
    card.dispatch("click");
    env.find("image-viewer-info").dispatch("click");
    env.viewerButton("AI 分析").dispatch("click");
    assert.equal(env.viewerButton("开始 AI 分析").hidden, true);
    assert.equal(posts(), 1);
});

test("the single Viewer Detail link transfers analysis and current Asset without reopening Viewer", async () => {
    for (const advancePage of [false, true]) {
        const storage = new Map();
        const { env, posts } = await galleryComic({ storage });
        env.find("image-viewer-info").dispatch("click");
        env.viewerButton("AI 分析").dispatch("click");
        await env.viewerButton("开始 AI 分析").dispatch("click");
        assert.equal(posts(), 1);
        if (advancePage) env.viewerButton("下一张图片").dispatch("click");
        assert.equal(env.find("image-viewer-inspector").children.length, 2);
        const target = env.find("image-viewer-links").children[0];
        target.dispatch("click"); await settle();
        const destination = env.window.location.assigned;
        assert.ok(destination);
        assert.equal(new URL(destination, "http://localhost").searchParams.get("asset"), advancePage ? "12" : "11");
        assert.equal(storage.has(`illustration-archive:ai-handoff:v1:${new URL(destination, "http://localhost").searchParams.get("nav")}`), true);
        const { env: detailPage, posts: detailPosts } = await transferredDetail(destination, storage);
        assert.equal(detailPage.window.AiAnalysis.forIllustration(7).snapshot().status, "ready");
        assert.equal(detailPage.document.getElementById("detail-ai-handoff-status").hidden, false);
        assert.equal(Boolean(detailPage.find("image-viewer")?.open), false);
        detailPage.document.getElementById("detail-ai-open-viewer").dispatch("click");
        if (advancePage) {
            assert.match(detailPage.find("image-viewer-ai-result").children.map(node => node.textContent).join(" "), /未参与分析/);
            assert.equal(detailPage.window.history.state.iaBrowse.assets["illustration:7"], "12");
        }
        assert.equal(detailPage.find("image-viewer-links").children[0].hidden, true);
        assert.equal(detailPage.find("image-viewer-inspector").children.length, 2);
        assert.equal(detailPosts(), 0);
        assert.equal([...storage.keys()].some(key => key.startsWith("illustration-archive:ai-handoff:v1:")), false);
        const { env: refreshed } = await transferredDetail(destination, storage);
        assert.equal(refreshed.window.AiAnalysis.forIllustration(7).snapshot().status, "idle");
        assert.equal(refreshed.find("image-viewer"), undefined);
    }
});

test("invalid or unavailable handoff shows a recovery path without adding another Detail action", async () => {
    const storage = new Map();
    const source = browser({ storage });
    assert.equal(source.window.AiAnalysis.prepareHandoff("bad", 7, "11", comicResult), true);
    const changed = { ...comicDetail, assets: [{ id: 99, mimeType: "image/jpeg", sortOrder: 0 }] };
    const { env: rejected } = await transferredDetail("/detail.html?id=7&nav=bad", storage, changed);
    assert.equal(rejected.window.AiAnalysis.forIllustration(7).snapshot().status, "idle");
    assert.match(rejected.document.getElementById("detail-ai-handoff-status").textContent, /未能带入/);
    assert.equal([...storage.keys()].some(key => key.startsWith("illustration-archive:ai-handoff:v1:")), false);

    const { env } = await galleryComic({ storageDisabled: true });
    env.find("image-viewer-info").dispatch("click");
    env.viewerButton("AI 分析").dispatch("click");
    await env.viewerButton("开始 AI 分析").dispatch("click");
    env.find("image-viewer-links").children[0].dispatch("click"); await settle();
    assert.match(env.window.location.assigned, /aiTransfer=failed/);
});

test("Detail top action opens the Viewer AI tab and reuses its result after closing", async () => {
    const env = browser({ url: "http://localhost/detail.html?id=7" });
    env.window.AbortController = AbortController;
    let posts = 0;
    env.sandbox.fetchImpl = async (url, request = {}) => request.method === "POST"
        ? (posts++, { ok: true, json: async () => comicResult })
        : { ok: true, json: async () => comicDetail };
    env.run("detail.js"); await settle();
    env.document.getElementById("detail-ai-open-viewer").dispatch("click");
    assert.equal(env.find("image-viewer").open, true);
    assert.equal(env.find("image-viewer-inspector").hidden, false);
    assert.equal(posts, 0);
    await env.viewerButton("开始 AI 分析").dispatch("click");
    assert.equal(posts, 1);
    env.find("image-viewer").dispatch("cancel"); await settle();
    assert.equal(env.document.getElementById("detail-ai-open-viewer").textContent, "边看图边读 AI");
    env.document.getElementById("detail-ai-open-viewer").dispatch("click");
    assert.equal(env.viewerButton("开始 AI 分析").hidden, true);
    assert.equal(posts, 1);
});

test("pure video has no analysis entry in Gallery or Detail", async () => {
    const video = { ...comicDetail, assets: [{ id: 12, mimeType: "video/mp4", sortOrder: 0 }] };
    const env = browser();
    env.sandbox.fetchImpl = async () => ({ ok: true, json: async () => ({ page: 0, totalPages: 1, items: [video] }) });
    env.run("app.js"); await settle(); env.flushFrames();
    env.document.querySelectorAll(".card-image-link")[0].dispatch("click");
    assert.equal(env.find("image-viewer-info").hidden, false);
    env.find("image-viewer-info").dispatch("click");
    assert.equal(env.viewerButton("AI 分析").hidden, true);
    const { env: detailPage } = await transferredDetail("/detail.html?id=7", new Map(), video);
    assert.equal(detailPage.document.getElementById("detail-ai-open-viewer").hidden, true);
});
