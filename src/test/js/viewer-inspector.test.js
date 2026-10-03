const assert = require("node:assert/strict");
const test = require("node:test");
const { browser, settle } = require("./helpers/browser");

const config = (metadataProvider, id = 7) => ({ groupKey: `illustration:${id}`, metadataProvider,
    items: [11, 22].map(key => ({ key: String(key), fullUrl: `/api/assets/${key}/content` })),
    detailHref: key => `/detail.html?id=${id}&asset=${key}&ctx=origin` });
const metadata = { author: { displayName: "Artist", xUsername: "@artist" }, tags: [{ name: "Landscape" }],
    sourceUrl: "https://x.com/artist/status/1", note: "Saved note\nSecond line" };
function image(env) {
    const node = env.find("image-viewer-image");
    node.naturalWidth = 1000; node.naturalHeight = 1000; node.dispatch("load"); return node;
}
const info = env => env.viewerButton("作品信息");

test("lazy metadata is shared across Assets and toggles, Esc closes only Inspector first", async () => {
    const env = browser(); let calls = 0;
    env.window.ImageViewer.open(config(() => { calls++; return metadata; })); image(env);
    assert.equal(calls, 0); const history = env.entries().length;
    info(env).dispatch("pointerenter"); assert.equal(env.find("image-viewer-inspector").hidden, true);
    info(env).dispatch("click"); await settle();
    assert.equal(env.find("image-viewer-author").textContent, "Artist");
    assert.equal(env.find("image-viewer-handle").textContent, "@artist");
    assert.equal(env.find("image-viewer-note").textContent, metadata.note);
    env.viewerButton("下一张图片").dispatch("click");
    const full = env.find("image-viewer-inspector").children.at(-1);
    assert.equal(new URL(full.href).searchParams.get("asset"), "22");
    assert.equal(new URL(full.href).searchParams.get("ctx"), "origin");
    const dialog = env.find("image-viewer");
    assert.equal(dialog.dispatch("keydown", { key: "Escape" }).defaultPrevented, true);
    assert.equal(dialog.open, true); assert.equal(env.document.activeElement, info(env));
    info(env).dispatch("click"); await settle();
    env.viewerButton("收起作品信息").dispatch("click");
    info(env).dispatch("click"); info(env).dispatch("click");
    assert.equal(calls, 1); assert.equal(env.entries().length, history);
    info(env).dispatch("click"); dialog.dispatch("cancel");
    assert.equal(dialog.open, true); dialog.dispatch("cancel"); assert.equal(dialog.open, false);
});

test("pending, late success and late failure cannot pollute another work or a reopened Viewer", async () => {
    for (const fail of [false, true]) {
        const env = browser(); let resolve, reject;
        env.window.ImageViewer.open(config(() => new Promise((a, b) => { resolve = a; reject = b; })));
        info(env).dispatch("click"); info(env).dispatch("click"); info(env).dispatch("click");
        env.window.ImageViewer.open(config(() => ({ author: { displayName: "New" } }), 8));
        assert.equal(env.find("image-viewer-metadata").children.length, 0);
        info(env).dispatch("click"); await settle();
        if (fail) reject(new Error("old failure")); else resolve(metadata);
        await settle(); assert.equal(env.find("image-viewer-author").textContent, "New");
        assert.equal(env.find("image-viewer-metadata-status").textContent, "");
        assert.equal(env.find("image-viewer-note"), undefined);
    }
    const env = browser(); let complete;
    env.window.ImageViewer.open(config(() => new Promise(resolve => { complete = resolve; })));
    info(env).dispatch("click"); env.window.ImageViewer.close(); complete(metadata); await settle();
    assert.equal(env.find("image-viewer-metadata").children.length, 0);
    let calls = 0;
    env.window.ImageViewer.open(config(() => { calls++; return metadata; })); info(env).dispatch("click");
    await settle(); assert.equal(calls, 1);
});

test("failure has a local retry and never affects image state; missing fields and unsafe sources disappear", async () => {
    const env = browser(); let calls = 0;
    env.window.ImageViewer.open(config(() => { if (++calls === 1) throw new Error("offline"); return { sourceUrl: "javascript:alert(1)", author: {}, tags: [{ name: " " }] }; }));
    const node = image(env), transform = node.style.transform;
    info(env).dispatch("click"); await settle();
    assert.match(env.find("image-viewer-metadata-status").textContent, /失败/);
    assert.equal(node.style.transform, transform); assert.equal(env.find("image-viewer-status").textContent, "");
    await env.viewerButton("重新加载作品信息").dispatch("click");
    assert.equal(calls, 2); assert.equal(env.find("image-viewer-metadata").children.length, 0);
    assert.equal(env.viewerButton("重新加载作品信息").hidden, true);
});

test("Inspector events are local; stage resize refits or preserves manual zoom and clamps pan", async () => {
    const env = browser(); env.window.ImageViewer.open(config(() => metadata)); const node = image(env);
    const stage = env.find("image-viewer-stage"), panel = env.find("image-viewer-inspector");
    stage.clientWidth = 400; info(env).dispatch("click"); await settle();
    assert.equal(env.find("image-viewer-zoom").textContent, "40%");
    stage.clientWidth = 800; info(env).dispatch("click");
    assert.equal(env.find("image-viewer-zoom").textContent, "60%");
    env.viewerButton("原始尺寸").dispatch("click");
    stage.dispatch("pointerdown", { pointerId: 1, clientX: 0, clientY: 0 });
    stage.dispatch("pointermove", { pointerId: 1, clientX: 1000, clientY: 1000 });
    stage.dispatch("pointerup", { pointerId: 1 });
    stage.clientWidth = 1200; stage.clientHeight = 900;
    info(env).dispatch("click");
    assert.equal(env.find("image-viewer-zoom").textContent, "100%");
    assert.match(node.style.transform, /translate\(0px, 50px\)/);
    const before = node.style.transform;
    for (const type of ["wheel", "pointerdown", "click"]) assert.equal(panel.dispatch(type).propagationStopped, true);
    env.find("image-viewer").dispatch("keydown", { key: "+", target: panel });
    assert.equal(node.style.transform, before);
    stage.clientHeight = 1200; env.resize(); assert.equal(env.find("image-viewer-zoom").textContent, "100%");
});

test("Gallery requests only on expansion, keeps BrowseContext and focus, and loads the next work", async () => {
    const env = browser({ url: "http://localhost/?q=art&authorId=2&tagId=3" }); const calls = [];
    env.sandbox.fetchImpl = async url => {
        calls.push(url);
        if (url.startsWith("/api/illustrations/")) return { ok: true, json: async () => ({ author: { displayName: url.endsWith("7") ? "One" : "Two" } }) };
        return { ok: true, json: async () => ({ page: 0, totalPages: 1, items: [7, 8].map(id => ({ id, title: "art", assets: [11, 22].map(id => ({ id, sortOrder: id, mimeType: "image/png" })) })) }) };
    };
    env.run("app.js"); await settle(); env.flushFrames();
    const links = env.document.querySelectorAll(".card-image-link"); env.window.scrollY = 170;
    links[0].dispatch("click"); assert.equal(calls.filter(url => /illustrations\//.test(url)).length, 0);
    const count = env.entries().length;
    info(env).dispatch("click"); await settle(); env.viewerButton("下一张图片").dispatch("click");
    assert.equal(calls.filter(url => /illustrations\//.test(url)).length, 1);
    assert.equal(env.entries().length, count);
    const href = new URL(env.find("image-viewer-inspector").children.at(-1).href);
    assert.equal(href.searchParams.get("asset"), "22"); assert.ok(href.searchParams.get("ctx"));
    env.find("image-viewer").dispatch("cancel"); assert.equal(env.find("image-viewer").open, true);
    env.find("image-viewer").dispatch("cancel"); await settle(); env.flushFrames();
    assert.equal(env.window.scrollY, 170); assert.equal(env.document.activeElement, links[0]);
    assert.equal(env.window.history.state.iaBrowse.query.q, "art");
    links[1].dispatch("click"); assert.equal(env.find("image-viewer-metadata").children.length, 0);
    info(env).dispatch("click"); await settle(); assert.equal(env.find("image-viewer-author").textContent, "Two");
});

test("Detail supplies saved metadata while edit inputs and picker selections remain drafts", async () => {
    const env = browser({ url: "http://localhost/detail.html?id=7" }); let calls = 0;
    env.sandbox.fetchImpl = async () => { calls++; return { ok: true, json: async () => ({ id: 7, ...metadata, assets: [{ id: 11, sortOrder: 0 }] }) }; };
    env.run("detail.js"); await settle(); env.flushFrames();
    env.document.getElementById("detail-edit-button").dispatch("click");
    env.document.getElementById("detail-note-input").value = "Unsaved note";
    env.document.getElementById("detail-source-input").value = "https://example.org/draft";
    env.find("image-open-button").dispatch("click"); info(env).dispatch("click"); await settle();
    assert.equal(calls, 1); assert.equal(env.find("image-viewer-note").textContent, metadata.note);
    assert.equal(env.find("image-viewer-author").textContent, "Artist");
    assert.equal(env.find("image-viewer-links").children[0].hidden, true);
    assert.equal(env.find("image-viewer-inspector").children.at(-1).hidden, false);
    env.find("image-viewer-inspector").children.at(-1).dispatch("click"); await settle(); env.flushFrames();
    assert.equal(env.find("image-viewer").open, false);
    assert.equal(env.window.location.assigned, undefined);
    assert.equal(env.document.getElementById("detail-note-input").value, "Unsaved note");
    assert.equal(env.window.history.state.iaBrowse.assets["illustration:7"], "11");
});

test("Inbox configuration without a provider has no Inspector and keeps single Esc close", () => {
    const env = browser(); const opener = env.document.getElementById("opener"); opener.focus();
    env.window.ImageViewer.open(config(undefined)); image(env);
    assert.equal(info(env).hidden, true); info(env).dispatch("click");
    assert.equal(env.find("image-viewer-inspector").hidden, true);
    env.find("image-viewer").dispatch("cancel"); assert.equal(env.find("image-viewer").open, false);
    assert.equal(env.document.activeElement, opener);
});

test("explicit 1:1 survives a smaller stage even when original fit was 100%", () => {
    const env = browser(); env.window.ImageViewer.open(config(() => metadata));
    const node = env.find("image-viewer-image"); node.naturalWidth = 200; node.naturalHeight = 200; node.dispatch("load");
    env.viewerButton("原始尺寸").dispatch("click");
    env.find("image-viewer-stage").clientWidth = 100; info(env).dispatch("click");
    assert.equal(env.find("image-viewer-zoom").textContent, "100%");
    env.viewerButton("适应窗口").dispatch("click"); assert.equal(env.find("image-viewer-zoom").textContent, "50%");
});

test("Detail author and Tag drafts stay out of Inspector metadata", async () => {
    const env = browser({ url: "http://localhost/detail.html?id=7" }); let detailReads = 0;
    env.sandbox.fetchImpl = async url => ({ ok: true, json: async () => {
        if (url.startsWith("/api/authors")) return [{ id: 2, displayName: "Draft artist", xUsername: "draft" }];
        if (url.startsWith("/api/tags")) return [{ id: 3, name: "Draft tag" }];
        detailReads++;
        return { id: 7, ...metadata, author: { id: 1, ...metadata.author }, tags: [{ id: 90, name: "Saved tag" }], assets: [{ id: 11 }] };
    } });
    env.run("detail.js"); await settle(); env.document.getElementById("detail-edit-button").dispatch("click");
    await env.document.getElementById("detail-author-toggle").dispatch("click");
    env.document.getElementById("detail-author-search-results").children[1].children[0].dispatch("click");
    await env.document.getElementById("detail-tag-toggle").dispatch("click");
    env.document.getElementById("detail-tag-search-results").children[1].children[0].dispatch("click");
    env.find("image-open-button").dispatch("click"); info(env).dispatch("click"); await settle();
    assert.equal(detailReads, 1);
    assert.equal(env.find("image-viewer-author").textContent, "Artist");
    assert.equal(env.find("image-viewer-tags").children.length, 1);
    assert.equal(env.find("image-viewer-tags").children[0].textContent, "Saved tag");
});
