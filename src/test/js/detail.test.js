const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const staticDir = path.resolve(__dirname, "../../main/resources/static");

const { browser } = require("./helpers/browser");

async function loadPage(assets) {
    const env = browser({ url: "http://localhost/detail.html?id=7" });
    const elements = env.elements;
    const detail = { id: 7, title: "Test illustration", assets, tags: [] };
    env.sandbox.fetchImpl = async () => ({ ok: true, json: async () => detail });
    env.run("detail.js");
    await new Promise(setImmediate);
    assert.equal(elements.get("detail-status").textContent, "详情已加载");
    return elements.get("detail-images").children;
}

test("single image keeps one original in the main image stage", async () => {
    const stages = await loadPage([{ id: 11, sortOrder: 0 }]);
    assert.equal(stages.length, 1);
    assert.equal(stages[0].className, "detail-image image-open-button");
    assert.equal(stages[0].children[0].src, "/api/assets/11/content");
    assert.equal(stages[0].children[0].alt, "Test illustration");
    assert.equal(stages[0].children[0].loading, "eager");
});

test("three images render vertically in sortOrder order with later originals lazy", async () => {
    const stages = await loadPage([
        { id: 33, sortOrder: 2 },
        { id: 11, sortOrder: 0 },
        { id: 22, sortOrder: 1 }
    ]);
    assert.deepEqual(stages.map(stage => stage.children[0].src), [
        "/api/assets/11/content",
        "/api/assets/22/content",
        "/api/assets/33/content"
    ]);
    assert.deepEqual(stages.map(stage => stage.children[0].loading), ["eager", "lazy", "lazy"]);
    assert.deepEqual(stages.map(stage => stage.children[0].alt), [
        "Test illustration · 第 1 张", "Test illustration · 第 2 张", "Test illustration · 第 3 张"
    ]);
    stages[1].children[0].dispatch("error");
    assert.equal(stages[1].children[0].hidden, true);
    assert.equal(stages[1].children[1].hidden, false);
    assert.equal(stages[0].children[0].hidden, false);
});

test("empty assets show a fallback without crashing", async () => {
    const stages = await loadPage([]);
    assert.equal(stages.length, 1);
    assert.equal(stages[0].children[0].textContent, "暂无图片");
});

test("detail page provides the gallery element", () => {
    const html = readFileSync(path.join(staticDir, "detail.html"), "utf8");
    assert.match(html, /id="detail-images"/);
});

test("Detail URL Asset locates original; viewer does not discard unsaved editor values", async () => {
    const env = browser({ url: "http://localhost/detail.html?id=7&asset=22" });
    env.sandbox.fetchImpl = async () => ({ ok: true, json: async () => ({ id: 7, title: "Test",
        assets: [{ id: 11, sortOrder: 0 }, { id: 22, sortOrder: 1 }], tags: [] }) });
    env.run("detail.js"); await new Promise(setImmediate); env.flushFrames();
    assert.equal(env.window.history.state.iaBrowse.anchor.key, "asset:22");
    env.elements.get("detail-edit-button").dispatch("click");
    const title = env.elements.get("detail-title-input"); title.value = "尚未保存";
    env.elements.get("detail-images").children[1].dispatch("click");
    assert.equal(env.find("image-viewer-image").src, "/api/assets/22/content");
    env.find("image-viewer-header").children[2].dispatch("click"); await new Promise(setImmediate);
    assert.equal(title.value, "尚未保存");
    assert.equal(env.elements.get("detail-edit-form").hidden, false);
});
