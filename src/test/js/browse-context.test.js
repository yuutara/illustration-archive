const assert = require("node:assert/strict");
const test = require("node:test");
const { browser, settle } = require("./helpers/browser");

function setup(env, { missing = false, reload = () => {} } = {}) {
    const opener = env.document.getElementById("opener");
    opener.dataset.browseAnchor = "illustration:7"; opener.rect.top = 900;
    let context;
    const config = { groupKey: "illustration:7", title: "作品", startKey: "22", opener,
        items: [11, 22, 33].map(id => ({ key: String(id), fullUrl: `/api/assets/${id}/content` })),
        detailHref: key => context.detailHref(7, key), onChange: key => { opener.currentKey = key; } };
    context = env.window.BrowseContext.create({ kind: "gallery", reload,
        resolveViewer: group => !missing && group === config.groupKey ? config : null });
    return { context, config, opener };
}

test("multi-select sets survive Viewer Detail return and normalize order without losing members", () => {
    const env = browser({ url: "http://localhost/?q=Work&authorId=2&authorId=1&authorId=2&tagId=5&tagId=3&page=2" });
    const { context, config, opener } = setup(env);
    const api = env.window.BrowseContext;
    assert.equal(api.sameQuery(context.initialQuery, { q: "Work", authorId: ["1", "2"], tagId: ["3", "5"] }), true);
    assert.equal(api.sameQuery(context.initialQuery, { q: "Work", authorId: ["1"], tagId: ["3", "5"] }), false);
    context.ready(); context.openViewer(config, opener);
    const detail = new URL(context.detailHref(7, "22"), "http://localhost");
    const detailEnv = browser({ url: detail.href, storage: env.storage });
    const detailContext = detailEnv.window.BrowseContext.create({ kind: "detail" });
    const back = detailEnv.document.getElementById("back");
    detailContext.bindReturnLink(back);
    const returned = new URL(back.href, "http://localhost");
    assert.deepEqual(returned.searchParams.getAll("authorId"), ["1", "2"]);
    assert.deepEqual(returned.searchParams.getAll("tagId"), ["3", "5"]);
    assert.equal(returned.searchParams.get("q"), "Work");
    assert.equal(returned.searchParams.get("page"), "2");
});

test("viewer adds one history entry; Back/Forward retain last asset, scroll and source focus", async () => {
    const env = browser({ url: "http://localhost/?page=2" }); const { context, config, opener } = setup(env);
    assert.equal(context.initialPage, 2); context.ready(); env.flushFrames();
    env.window.scrollY = 700; context.openViewer(config, opener);
    assert.equal(env.window.history.length, 2);
    env.viewerButton("下一张图片").dispatch("click");
    assert.equal(env.window.history.length, 2);
    env.window.history.back(); await settle(); env.flushFrames();
    assert.equal(env.find("image-viewer").open, false);
    assert.equal(context.imageKey(config.groupKey), "33");
    assert.equal(env.window.scrollY, 700);
    assert.equal(env.document.activeElement, opener);
    env.window.history.forward(); await settle();
    assert.equal(env.find("image-viewer-image").src, "/api/assets/33/content");
    env.viewerButton("关闭图片查看器").dispatch("click"); await settle();
    assert.equal(env.window.history.state.iaBrowse.viewer, null);
});

test("refresh restores viewer and current page without another push; missing Post/Asset falls back to list", async () => {
    const env = browser({ url: "http://localhost/?page=2" }); const { context, config, opener } = setup(env);
    context.openViewer(config, opener); env.viewerButton("下一张图片").dispatch("click");
    const refreshed = browser({ url: env.window.location.href, entries: env.entries(), storage: env.storage });
    const restored = setup(refreshed); restored.context.ready();
    assert.equal(restored.context.initialPage, 2);
    assert.equal(refreshed.find("image-viewer-image").src, "/api/assets/33/content");
    assert.equal(refreshed.window.history.length, 2);
    const stale = browser({ url: env.window.location.href, entries: env.entries(), storage: env.storage });
    setup(stale, { missing: true }).context.ready(); await settle();
    assert.equal(stale.window.history.state.iaBrowse.viewer, null);
    assert.equal(stale.find("image-viewer"), undefined);
});

test("Detail navigation consumes viewer entry; trusted return goes straight to source list", async () => {
    const env = browser(); const { context, config, opener } = setup(env);
    context.ready();
    context.openViewer(config, opener);
    env.find("image-viewer-link").dispatch("click"); await settle();
    assert.equal(env.entries().length, 1);
    assert.equal(env.window.history.state.iaBrowse.viewer, null);
    const href = env.window.location.assigned;
    assert.equal(new URL(href, "http://localhost").searchParams.get("asset"), "22");
    const detail = browser({ url: new URL(href, "http://localhost").href, storage: env.storage,
        entries: [...env.entries(), { url: new URL(href, "http://localhost").href, state: null }] });
    const detailContext = detail.window.BrowseContext.create({ kind: "detail", resolveViewer: () => null });
    const back = detail.document.getElementById("back"); detailContext.bindReturnLink(back);
    detailContext.remember(config.groupKey, "33");
    back.dispatch("click"); await settle();
    assert.equal(detail.window.location.pathname, "/");
    assert.equal(detail.window.history.state.iaBrowse.viewer, null);
    const cold = browser({ url: env.window.location.href, entries: env.entries(), storage: env.storage });
    const coldContext = setup(cold).context;
    coldContext.ready();
    assert.equal(coldContext.imageKey(config.groupKey), "33");
    env.window.dispatch("pageshow", { persisted: true });
    assert.equal(opener.currentKey, "33");
});

test("direct Detail and modified clicks use safe links without consuming unrelated history", () => {
    const env = browser({ url: "http://localhost/detail.html?id=7&ctx=unknown" });
    const context = env.window.BrowseContext.create({ kind: "detail", resolveViewer: () => null });
    const back = env.document.getElementById("back"); context.bindReturnLink(back);
    assert.equal(back.href, "/");
    assert.equal(back.dispatch("click", { ctrlKey: true }).defaultPrevented, false);
    assert.equal(env.window.location.assigned, undefined);
    back.dispatch("click"); assert.equal(env.window.location.assigned, "/");
});

test("unavailable sessionStorage keeps history-based viewing and refresh usable", async () => {
    const env = browser({ storageDisabled: true }); const { context, config, opener } = setup(env);
    context.openViewer(config, opener); env.window.history.back(); await settle();
    assert.equal(env.find("image-viewer").open, false);
    const refreshed = browser({ url: env.window.location.href, entries: env.entries(), storageDisabled: true });
    assert.equal(setup(refreshed).context.imageKey(config.groupKey), "22");
});

test("changed source refreshes once on bfcache return; a page correction drops invalid viewer state", () => {
    const env = browser({ url: "http://localhost/?page=2" }); const calls = [];
    const { context, config, opener } = setup(env, { reload: page => calls.push(page) });
    context.openViewer(config, opener);
    const state = env.window.history.state.iaBrowse;
    env.storage.set("illustration-archive:browse:v1:" + state.id, JSON.stringify({ ...state, refresh: true, viewer: null }));
    env.window.dispatch("pageshow", { persisted: true });
    assert.deepEqual(calls, [2]);
    context.setPage(1); context.ready();
    assert.equal(env.window.location.search, "?page=1");
    assert.equal(env.window.history.state.iaBrowse.viewer, null);
    env.window.dispatch("pageshow", { persisted: true }); assert.deepEqual(calls, [2]);
});

test("anchor restoration tracks layout movement and stops correcting after user scroll", async () => {
    const env = browser(); const { context, opener } = setup(env);
    context.locate(opener); context.ready(); opener.rect.top = 1200;
    env.flushFrames(); assert.equal(env.window.scrollY, 1200);
    env.window.dispatch("wheel"); env.window.scrollY = 300;
    await settle(); env.flushFrames(); assert.equal(env.window.scrollY, 300);
});

test("invalid URL page and malformed history safely start on page zero", () => {
    for (const page of ["-1", "1.5", "garbage", "9007199254740992"]) {
        const env = browser({ url: "http://localhost/?page=" + page });
        assert.equal(setup(env).context.initialPage, 0);
    }
});

test("LAN HTTP UUID fallback works; unsupported dialog never adds a history entry", () => {
    const env = browser(); delete env.window.crypto.randomUUID;
    const { context, config, opener } = setup(env);
    assert.equal(context.openViewer(config, opener), true);
    const unsupported = browser(); const createElement = unsupported.document.createElement;
    unsupported.document.createElement = tag => {
        const node = createElement(tag); if (tag === "dialog") node.showModal = undefined; return node;
    };
    const fallback = setup(unsupported);
    assert.equal(fallback.context.openViewer(fallback.config, fallback.opener), false);
    assert.equal(unsupported.window.history.length, 1);
});

test("offset locate and pixel fallback replace the current entry without changing assets or other pages", () => {
    const env = browser(); const { context, config, opener } = setup(env);
    context.remember(config.groupKey, "22");
    context.locate(opener, 120, 300); context.ready(); env.flushFrames();
    assert.equal(env.window.scrollY, 780);
    assert.equal(context.imageKey(config.groupKey), "22");
    assert.equal(env.window.history.length, 1);
    context.locate(null, 0, 250); context.ready(); env.flushFrames();
    assert.equal(env.window.scrollY, 250);
    context.locate(opener); context.ready(); env.flushFrames();
    assert.equal(env.window.scrollY, 900);
});

test("optional close callback receives the last media once after close, never on forward reopening", async () => {
    const env = browser(); const calls = [];
    const opener = env.document.getElementById("opener"); opener.dataset.browseAnchor = "inbox:7";
    const config = { groupKey: "inbox:7", items: ["a", "b"].map(key => ({ key, fullUrl: `/${key}.jpg` })), opener };
    const context = env.window.BrowseContext.create({ kind: "inbox", resolveViewer: () => config,
        onViewerClose: (group, key) => calls.push([group, key]) });
    context.ready(); context.openViewer({ ...config, startKey: "a" }, opener);
    env.viewerButton("下一张图片").dispatch("click");
    env.window.history.back(); await settle();
    assert.deepEqual(calls, [["inbox:7", "b"]]);
    env.window.history.forward(); await settle();
    assert.equal(calls.length, 1);
    assert.equal(env.find("image-viewer-image").src, "/b.jpg");
});

test("Gallery return consumes saved metadata invalidation before popstate can overwrite it", () => {
    const env = browser({ url: "http://localhost/?q=Work&authorId=1&tagId=2&page=0" });
    const calls = [];
    const { context, config } = setup(env, { reload: (...args) => calls.push(args) });
    context.ready();
    const sourceState = structuredClone(env.window.history.state);
    const detail = browser({ url: new URL(context.detailHref(7, "11"), "http://localhost").href, storage: env.storage });
    const detailContext = detail.window.BrowseContext.create({ kind: "detail", resolveViewer: () => null });
    detailContext.invalidateSource();
    env.window.dispatch("popstate", { state: sourceState });
    assert.equal(calls.length, 1);
    assert.deepEqual(Array.from(calls[0][1].authorId), ["1"]);
    assert.deepEqual(Array.from(calls[0][1].tagId), ["2"]);
    assert.equal(calls[0][1].q, "Work");
    assert.equal(calls[0][2].history, true);
    env.window.dispatch("pageshow", { persisted: true });
    assert.equal(calls.length, 1);
});

test("Gallery source links preserve all query conditions, page and ctx without a second return parameter", () => {
    const env=browser({url:"http://localhost/?q=%E5%A4%8F%E6%97%A5&authorId=12&tagId=7&page=2"});
    const {context,config}=setup(env); context.ready(); context.remember(config.groupKey,"33");
    const detail=browser({url:new URL(context.detailHref(7,"33"),"http://localhost").href,storage:env.storage});
    const current=detail.window.BrowseContext.create({kind:"detail",resolveViewer:()=>null});
    const back=detail.document.getElementById("back"); current.bindReturnLink(back);
    const query=new URL(back.href,"http://localhost").searchParams;
    assert.equal(query.get("q"),"夏日"); assert.equal(query.get("authorId"),"12");
    assert.equal(query.get("tagId"),"7"); assert.equal(query.get("page"),"2");
    assert.equal(query.get("returnTo"),null);
    const returned=browser({url:new URL(back.href,"http://localhost").href,storage:env.storage});
    assert.equal(setup(returned).context.imageKey(config.groupKey),"33");
});

test("ctx and history from a different Gallery query cannot restore its assets, anchor or Viewer", () => {
    const env=browser({url:"http://localhost/?q=old&page=0"});
    const {context,config,opener}=setup(env); context.remember(config.groupKey,"33"); context.openViewer(config,opener);
    const old=env.window.history.state.iaBrowse;
    const changed=browser({url:"http://localhost/?q=new&page=0&ctx="+old.id,storage:env.storage,
        entries:[{url:"http://localhost/?q=new&page=0&ctx="+old.id,state:{iaBrowse:old}}]});
    const newContext=setup(changed).context;
    assert.equal(newContext.imageKey(config.groupKey),undefined);
    assert.equal(changed.window.history.state.iaBrowse.viewer,null);
    assert.equal(changed.window.history.state.iaBrowse.anchor,null);
    assert.notEqual(changed.window.history.state.iaBrowse.id,old.id);
});

test("successful Gallery pages have separate snapshots; Back restores the original Asset and position", async () => {
    const env=browser(); let reload;
    const {context,config,opener}=setup(env,{reload:(page,query,options)=>{reload={page,query,options};}});
    context.ready(); env.window.scrollY=300; context.remember(config.groupKey,"33");
    context.setGallery({q:"new",authorId:"",tagId:""},0,true);
    assert.equal(context.imageKey(config.groupKey),undefined);
    env.window.history.back(); await settle();
    assert.equal(reload.page,0); assert.equal(reload.options.history,true);
    assert.equal(context.imageKey(config.groupKey),"33");
    context.ready(); env.flushFrames(); assert.equal(env.window.scrollY,300);
    assert.equal(opener.currentKey,"33");
});
