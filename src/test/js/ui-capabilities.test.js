const assert = require("node:assert/strict");
const test = require("node:test");
const { browser, settle } = require("./helpers/browser");

const disabled = { xImport: false, aiAnalysis: false };
const pngDetail = { id: 7, title: "Work", tags: [], assets: [{ id: 11, mimeType: "image/png", sortOrder: 0 }] };

async function detailPage(capabilities, fetchImpl = async () => ({ ok: true, json: async () => pngDetail })) {
    const env = browser({ url: "http://localhost/detail.html?id=7" });
    if (capabilities) env.window.UiCapabilities = capabilities;
    env.sandbox.fetchImpl = fetchImpl;
    env.run("detail.js"); await settle(); env.flushFrames();
    return env;
}

test("Detail offers AI analysis on the normal server when capabilities are absent", async () => {
    const env = await detailPage(undefined);
    assert.equal(env.document.getElementById("detail-ai-open-viewer").hidden, false);
});

test("Detail hides every AI entry when the desktop build turns analysis off", async () => {
    const env = await detailPage(disabled);
    assert.equal(env.document.getElementById("detail-ai-open-viewer").hidden, true);
    env.find("image-open-button").dispatch("click"); await settle();
    assert.equal(env.viewerButton("AI 分析").hidden, true);
    assert.equal(env.find("image-viewer-info").textContent, "作品信息");
});

test("creating a tag whose name already exists explains the conflict and keeps the form open", async () => {
    const calls = [];
    const env = await detailPage(disabled, async (url, options = {}) => {
        calls.push({ url, options });
        if (url === "/api/tags" && options.method === "POST") return { ok: false, status: 409, json: async () => ({ code: "DUPLICATE_TAG" }) };
        return { ok: true, json: async () => url.startsWith("/api/") && url !== "/api/illustrations/7" ? [] : pngDetail };
    });
    const node = id => env.document.getElementById(`detail-${id}`);
    node("edit-button").dispatch("click");
    node("tag-create-button").dispatch("click");
    node("tag-create-name").value = "blue sky";
    await node("tag-create-submit").dispatch("click"); await settle();
    assert.match(node("tag-create-status").textContent, /已存在同名标签（不区分大小写）/);
    assert.equal(node("tag-create-panel").hidden, false);
    assert.equal(node("tag-selection").children.some(chip => /blue sky/.test(chip.textContent)), false);
    assert.equal(calls.filter(call => call.options.method === "POST").length, 1);
});

test("Gallery hides the X Inbox link only when X import is unavailable", async () => {
    for (const [capabilities, hidden] of [[undefined, false], [disabled, true]]) {
        const env = browser({ url: "http://localhost/" });
        if (capabilities) env.window.UiCapabilities = capabilities;
        env.sandbox.fetchImpl = async () => ({ ok: true, json: async () => ({ page: 0, totalPages: 0, totalElements: 0, items: [] }) });
        env.run("app.js"); await settle();
        assert.equal(env.document.getElementById("gallery-x-inbox-link").hidden, hidden);
    }
});

test("Inbox without X sync hides Sync and cursor resets but keeps the local list", async () => {
    const env = browser({ url: "http://localhost/x-import.html" });
    env.window.UiCapabilities = disabled;
    const calls = [];
    env.sandbox.fetchImpl = async url => {
        calls.push(url);
        return { ok: true, status: 200, json: async () => ({ items: [], page: 0, size: 24, totalItems: 0, totalPages: 0,
            syncProgress: { recentStatus: "INVALID", historyStatus: "INVALID", unscannedIntervals: true } }) };
    };
    env.run("x-import.js"); await settle();
    assert.equal(env.elements.get("sync-button").hidden, true);
    assert.equal(env.elements.get("reset-history-button").hidden, true);
    assert.equal(env.elements.get("reset-recent-button").hidden, true);
    assert.match(env.elements.get("sync-progress").textContent, /当前版本未启用 X 同步/);
    assert.deepEqual(calls, ["/api/x-import/inbox?page=0&size=24"]);
});
