const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const staticDir = path.resolve(__dirname, "../../main/resources/static");

const { browser, settle } = require("./helpers/browser");

test("metadata drafts use author IDs and toggle tags; cancel resets both, save keeps PATCH ownership", async () => {
    const env = browser({ url: "http://localhost/detail.html?id=7" });
    const node = id => env.document.getElementById(`detail-${id}`);
    let detail = { id: 7, title: "Work", assets: [], author: { id: 1, displayName: "Same", xUsername: "one" }, tags: [{ id: 90, name: "Pinned" }] };
    const calls = [];
    env.sandbox.fetchImpl = async (url, options = {}) => {
        calls.push({ url, options });
        if (options.method === "PATCH") {
            const payload = JSON.parse(options.body);
            detail = { ...detail, author: { id: payload.authorId, displayName: "Same", xUsername: "two" },
                tags: payload.tagIds.map(id => ({ id, name: "Fresh" })) };
            return { ok: true };
        }
        return { ok: true, json: async () => url.startsWith("/api/authors")
            ? [{ id: 1, displayName: "Same", xUsername: "one" }, { id: 2, displayName: "Same", xUsername: "two" }]
            : url.startsWith("/api/tags") ? [{ id: 3, name: "Fresh" }] : detail };
    };
    env.run("detail.js"); await settle();
    node("edit-button").dispatch("click");
    await node("author-toggle").dispatch("click");
    const authors = node("author-search-results").children.map(li => li.children[0]);
    assert.equal(authors[0]["aria-pressed"], "true");
    authors[1].dispatch("click");
    assert.match(node("author-selection").textContent, /@two/);
    assert.equal(node("author-search-results").children[0].children[0].dataset.metadataId, "2");
    await node("tag-toggle").dispatch("click");
    const tags = node("tag-search-results").children.map(li => li.children[0]);
    assert.equal(tags[0].textContent, "✓ Pinned");
    tags[0].dispatch("click"); tags[1].dispatch("click");
    assert.equal(node("tag-selection").children[0].textContent, "Fresh ×");
    node("cancel-button").dispatch("click");
    assert.equal(node("author-panel").hidden, true);
    assert.equal(node("tag-panel").hidden, true);
    assert.equal(calls.some(call => call.options.method === "PATCH"), false);
    node("edit-button").dispatch("click");
    assert.match(node("author-selection").textContent, /@one/);
    assert.equal(node("tag-selection").children[0].textContent, "Pinned ×");
    await node("author-toggle").dispatch("click");
    node("author-search-results").children[1].children[0].dispatch("click");
    await node("tag-toggle").dispatch("click");
    node("tag-search-results").children[0].children[0].dispatch("click");
    node("tag-search-results").children[0].children[0].dispatch("click");
    await node("edit-form").dispatch("submit");
    const payload = JSON.parse(calls.find(call => call.options.method === "PATCH").options.body);
    assert.equal(payload.authorId, 2);
    assert.deepEqual(payload.tagIds, [3]);
    assert.equal(node("edit-form").hidden, true);
    assert.equal(node("author-name").children[0].href, "/?authorId=2&page=0");
});

test("new author and tag refresh open pickers and become drafts; failed save preserves editable selection", async () => {
    const env = browser({ url: "http://localhost/detail.html?id=7" });
    const node = id => env.document.getElementById(`detail-${id}`);
    const requests = [];
    const newAuthor = { id: 2, displayName: "New", xUsername: "new" }, newTag = { id: 3, name: "New tag" };
    env.sandbox.fetchImpl = async (url, options = {}) => {
        requests.push({ url, options });
        if (options.method === "PATCH") return { ok: false, status: 500 };
        return { ok: true, json: async () => options.method === "POST" ? (url === "/api/authors" ? newAuthor : newTag)
            : url.startsWith("/api/authors") || url.startsWith("/api/tags") ? [] : { id: 7, assets: [], tags: [] } };
    };
    env.run("detail.js"); await settle(); node("edit-button").dispatch("click");
    await node("author-toggle").dispatch("click");
    node("author-create-button").dispatch("click"); node("author-create-display-name").value = "New";
    await node("author-create-submit").dispatch("click"); await settle();
    assert.equal(requests.filter(call => call.url.startsWith("/api/authors?")).length, 2);
    assert.match(node("author-selection").textContent, /New/);
    await node("tag-toggle").dispatch("click");
    node("tag-create-button").dispatch("click"); node("tag-create-name").value = "New tag";
    await node("tag-create-submit").dispatch("click"); await settle();
    assert.equal(requests.filter(call => call.url.startsWith("/api/tags?")).length, 2);
    assert.equal(node("tag-search-results").children[0].children[0].textContent, "✓ New tag");
    await node("edit-form").dispatch("submit");
    assert.equal(node("edit-form").hidden, false);
    assert.equal(node("tag-toggle").disabled, false);
    assert.equal(node("tag-selection").children[0].textContent, "New tag ×");
});

async function loadPage(assets) {
    const env = browser({ url: "http://localhost/detail.html?id=7" });
    const elements = env.elements;
    const detail = { id: 7, title: "Test illustration", assets, tags: [] };
    env.sandbox.fetchImpl = async () => ({ ok: true, json: async () => detail });
    env.run("detail.js");
    await new Promise(setImmediate);
    assert.equal(elements.get("detail-status").textContent, "");
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

test("Detail keeps loading and errors, then clears status after successful retry", async () => {
    const env = browser({ url: "http://localhost/detail.html?id=7" });
    const elements = env.elements;
    let complete;
    env.sandbox.fetchImpl = () => new Promise(resolve => { complete = resolve; });
    env.run("detail.js");
    assert.equal(elements.get("detail-status").textContent, "正在加载…");
    assert.equal(elements.get("detail-content").hidden, true);
    complete({ ok: false, status: 500 });
    await new Promise(setImmediate);
    assert.equal(elements.get("detail-status").textContent, "加载失败");
    assert.equal(elements.get("detail-state-panel").hidden, false);
    assert.equal(elements.get("detail-retry-button").hidden, false);

    const retried = elements.get("detail-retry-button").dispatch("click");
    assert.equal(elements.get("detail-status").textContent, "正在加载…");
    complete({ ok: true, json: async () => ({ id: 7, title: "Test", assets: [], tags: [] }) });
    await retried;
    assert.equal(elements.get("detail-status").textContent, "");
    assert.equal(elements.get("detail-state-panel").hidden, true);
    assert.equal(elements.get("detail-content").hidden, false);
});

test("Detail cancel and successful save clear status without masking save or reload errors", async () => {
    const env = browser({ url: "http://localhost/detail.html?id=7" });
    const elements = env.elements;
    const success = async () => ({ ok: true,
        json: async () => ({ id: 7, title: "Test", assets: [], tags: [] }) });
    env.sandbox.fetchImpl = success;
    env.run("detail.js"); await new Promise(setImmediate);
    elements.get("detail-edit-button").dispatch("click");
    assert.equal(elements.get("detail-status").textContent, "正在编辑");
    elements.get("detail-cancel-button").dispatch("click");
    assert.equal(elements.get("detail-status").textContent, "");
    assert.equal(elements.get("detail-edit-form").hidden, true);

    elements.get("detail-edit-button").dispatch("click");
    elements.get("detail-title-input").value = "尚未保存";
    env.sandbox.fetchImpl = async () => ({ ok: false, status: 500 });
    await elements.get("detail-edit-form").dispatch("submit");
    assert.equal(elements.get("detail-status").textContent, "保存失败，请重试");
    assert.equal(elements.get("detail-edit-form").hidden, false);
    assert.equal(elements.get("detail-title-input").value, "尚未保存");

    env.sandbox.fetchImpl = async (url, request) => ({ ok: request.method === "PATCH", status: 500 });
    await elements.get("detail-edit-form").dispatch("submit");
    assert.equal(elements.get("detail-status").textContent, "加载失败");
    assert.equal(elements.get("detail-state-panel").hidden, false);

    env.sandbox.fetchImpl = success;
    await elements.get("detail-retry-button").dispatch("click");
    elements.get("detail-edit-button").dispatch("click");
    await elements.get("detail-edit-form").dispatch("submit");
    assert.equal(elements.get("detail-status").textContent, "");
    assert.equal(elements.get("detail-edit-form").hidden, true);
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
    env.viewerButton("关闭图片查看器").dispatch("click"); await new Promise(setImmediate);
    assert.equal(title.value, "尚未保存");
    assert.equal(env.elements.get("detail-edit-form").hidden, false);
});

test("reading metadata hides empty blocks, links tags to Gallery and keeps safe sources", async () => {
    const env = browser({url:"http://localhost/detail.html?id=7"});
    env.sandbox.fetchImpl = async () => ({ok:true,json:async()=>({id:7,title:null,author:null,note:"",sourceUrl:"javascript:alert(1)",assets:[],tags:[]})});
    env.run("detail.js"); await new Promise(setImmediate);
    assert.equal(env.elements.get("detail-title").hidden,true);
    assert.equal(env.elements.get("detail-author-name").textContent,"作者未填写");
    assert.equal(env.elements.get("detail-note-section").hidden,true);
    assert.equal(env.elements.get("detail-source-section").hidden,true);
    assert.equal(env.elements.get("detail-tags-section").hidden,true);
    env.sandbox.fetchImpl = async () => ({ok:true,json:async()=>({id:7,title:"Work",author:{displayName:"Artist",xUsername:"artist"},note:"A note",sourceUrl:"https://example.com/art/1",assets:[],tags:[{id:1,name:"收藏"}]})});
    await env.elements.get("detail-retry-button").dispatch("click");
    assert.equal(env.elements.get("detail-title").hidden,false);
    assert.equal(env.elements.get("detail-author-name").textContent,"Artist");
    assert.equal(env.elements.get("detail-tags").children[0].tagName,"a");
    assert.equal(env.elements.get("detail-tags").children[0].href,"/?tagId=1&page=0");
    assert.equal(env.elements.get("detail-source").children[0].href,"https://example.com/art/1");
    assert.equal(env.elements.get("detail-source").children[0].textContent,"example.com ↗");
    assert.equal(env.elements.get("detail-note-section").hidden,false);
});

test("Detail Author and Tag navigation use IDs with no inherited search or source context", async () => {
    const env=browser({url:"http://localhost/detail.html?id=7"});
    env.sandbox.fetchImpl=async()=>({ok:true,json:async()=>({id:7,author:{id:12,displayName:"Artist",xUsername:"artist"},tags:[{id:7,name:"风景"}],assets:[]})});
    env.run("detail.js"); await settle();
    const author=env.elements.get("detail-author-name").children[0];
    assert.equal(author.tagName,"a");
    assert.equal(author.href,"/?authorId=12&page=0");
    assert.equal(author.textContent,"Artist");
    assert.equal(env.elements.get("detail-tags").children[0].href,"/?tagId=7&page=0");
});
