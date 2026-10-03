const assert = require("node:assert/strict");
const test = require("node:test");
const { browser, settle } = require("./helpers/browser");

const multi = { id: 7, title: "Three", author: { displayName: "Artist" }, coverAssetId: 11,
    assets: [11, 22, 33].map((id, sortOrder) => ({ id, sortOrder, mimeType: "image/png" })) };
async function setup(items = [multi], options = {}) {
    const env = browser(options);
    env.sandbox.fetchImpl = async () => ({ ok: true, json: async () => ({page: 0, totalPages: 2, totalElements: 25, items}) });
    env.run("app.js"); await settle(); env.flushFrames();
    return env;
}
function link(env, index = 0) { return env.elements.get("gallery").children[index].children[0].children[0]; }

test("one Illustration is one image entry; only multiple assets get preview arrows and a total", async () => {
    const env = await setup([multi, { id: 8, coverAssetId: 40, coverMimeType: "image/gif" }]);
    assert.equal(env.elements.get("gallery").children.length, 2);
    assert.equal(link(env).children.find(node => node.className === "card-asset-position").textContent, "3");
    assert.equal(env.elements.get("gallery").children[0].querySelectorAll(".card-asset-button").length, 2);
    assert.equal(env.elements.get("gallery").children[1].querySelectorAll(".card-asset-button").length, 0);
    assert.equal(link(env, 1).children.length, 2);
    assert.equal(link(env, 1).children[0].src, "/api/assets/40/content");
    assert.equal(link(env, 1)["aria-label"], "放大查看作品 8");
    assert.equal(env.elements.get("page-info").textContent, "第 1 / 2 页");
});

test("preview arrows wrap in sorted Asset order, remember the current Asset and never open Viewer", async () => {
    const env = await setup([{...multi, assets:multi.assets.slice().reverse()}]);
    const card = env.elements.get("gallery").children[0]; const initial = {...card.style};
    const initialEntries = env.entries().length;
    env.sandbox.fetchImpl = () => { throw new Error("Preview cycling must not fetch Illustration data"); };
    const previous = env.find("card-asset-previous"), next = env.find("card-asset-next");
    for (const [button, asset] of [[previous,33],[next,11],[next,22],[next,33],[next,11]]) {
        const event = button.dispatch("click");
        assert.equal(event.defaultPrevented,true);
        assert.equal(event.propagationStopped,true);
        assert.equal(link(env).children[0].src, `/api/assets/${asset}/thumbnail`);
        assert.equal(env.window.history.state.iaBrowse.assets["illustration:7"],String(asset));
        assert.equal(env.window.history.state.iaBrowse.viewer,null);
        assert.equal(new URL(link(env).href,"http://localhost").searchParams.get("asset"),String(asset));
        assert.deepEqual({...card.style},initial);
        assert.equal(env.find("image-viewer"),undefined);
        assert.equal(env.entries().length,initialEntries);
    }
    assert.equal(link(env).children.find(node=>node.className==="card-asset-position").textContent,"3");
});

test("Viewer starts at arrow-selected Asset; close and refresh preserve that preview and fixed cover frame", async () => {
    const env = browser();
    env.sandbox.imageSizeImpl = () => ({width:600,height:900});
    env.sandbox.fetchImpl = async () => ({ok:true,json:async()=>({page:0,totalPages:1,items:[multi]})});
    env.run("app.js"); await settle(); env.flushFrames();
    const card = env.elements.get("gallery").children[0], initial = {...card.style};
    env.window.scrollY = 160;
    env.find("card-asset-next").dispatch("click");
    assert.equal(link(env).children[0].src,"/api/assets/22/thumbnail");
    link(env).dispatch("click");
    assert.equal(env.find("image-viewer-image").src,"/api/assets/22/content");
    env.viewerButton("下一张图片").dispatch("click");
    env.viewerButton("关闭图片查看器").dispatch("click"); await settle(); env.flushFrames();
    assert.equal(env.window.scrollY,160);
    assert.equal(env.document.activeElement,link(env));
    assert.equal(link(env).children[0].src,"/api/assets/33/thumbnail");
    assert.deepEqual({...card.style},initial);
    const refreshed = browser({url:env.window.location.href,entries:env.entries(),storage:env.storage});
    refreshed.sandbox.imageSizeImpl = () => { throw new Error("Fixed cover ratio should come from cache"); };
    refreshed.sandbox.fetchImpl = async () => ({ok:true,json:async()=>({page:0,totalPages:1,items:[multi]})});
    refreshed.run("app.js"); await settle(); refreshed.flushFrames();
    assert.equal(link(refreshed).children[0].src,"/api/assets/33/thumbnail");
    assert.equal(refreshed.window.scrollY,160);
    assert.deepEqual({...refreshed.elements.get("gallery").children[0].style},initial);
});

test("Viewer changes current Asset and Detail href without refetching or changing tile geometry; refresh restores it", async () => {
    const env = await setup();
    const card = env.elements.get("gallery").children[0]; const initial = {...card.style};
    env.sandbox.fetchImpl = () => { throw new Error("Viewer must not fetch Detail"); };
    link(env).dispatch("click"); env.viewerButton("下一张图片").dispatch("click");
    assert.equal(link(env).children[0].src, "/api/assets/22/thumbnail");
    assert.equal(new URL(link(env).href, "http://localhost").searchParams.get("asset"), "22");
    assert.deepEqual({...card.style}, initial);
    env.viewerButton("关闭图片查看器").dispatch("click"); await settle(); env.flushFrames();
    assert.equal(env.document.activeElement, link(env));
    const refreshed = browser({ url: env.window.location.href, entries: env.entries(), storage: env.storage });
    refreshed.sandbox.fetchImpl = async () => ({ok:true, json:async()=>({page:0,totalPages:1,items:[multi]})});
    refreshed.run("app.js"); await settle(); refreshed.flushFrames();
    assert.equal(link(refreshed).children[0].src, "/api/assets/22/thumbnail");
});

test("modified clicks keep Detail navigation and multi-asset order stays sortOrder order", async () => {
    const env = await setup([{...multi, assets:multi.assets.slice().reverse()}]);
    assert.equal(link(env).dispatch("click", {ctrlKey:true}).defaultPrevented, false);
    assert.equal(env.find("image-viewer"), undefined);
    link(env).dispatch("click");
    assert.equal(env.find("image-viewer-image").src, "/api/assets/11/content");
    env.viewerButton("下一张图片").dispatch("click");
    assert.equal(env.find("image-viewer-image").src, "/api/assets/22/content");
});

test("cold Gallery refresh restores the latest pagehide position even when history carries an older snapshot", async () => {
    const env = await setup();
    const olderEntries = env.entries();
    env.window.scrollY = 217;
    env.window.dispatch("pagehide");
    const refreshed = await setup([multi], {url:env.window.location.href,entries:olderEntries,storage:env.storage});
    assert.equal(refreshed.window.scrollY,217);
    assert.equal(refreshed.window.history.state.iaBrowse.scrollY,217);
});

test("failed pagination preserves old page, DOM, scroll and URL; retry commits only after sizes settle", async () => {
    const env = await setup(); const card = env.elements.get("gallery").children[0];
    env.window.scrollY = 150;
    env.sandbox.fetchImpl = async () => ({ok:false,status:500});
    await env.elements.get("next-button").dispatch("click");
    await settle();
    assert.equal(env.elements.get("gallery").children[0], card);
    assert.equal(env.window.scrollY, 150);
    assert.equal(env.window.location.search, "?page=0");
    assert.equal(env.elements.get("retry-button").hidden, false);
    env.sandbox.fetchImpl = async () => ({ok:true,json:async()=>({page:1,totalPages:2,items:[{id:8,assets:[]} ]})});
    await env.elements.get("retry-button").dispatch("click"); await settle(); env.flushFrames();
    assert.equal(env.window.location.search, "?page=1");
    assert.equal(env.window.scrollY, 0);
    assert.equal(env.elements.get("gallery").inert, false);
});

test("dimension failure reserves a fixed frame; later image failure does not move neighbors", async () => {
    const env = browser(); env.sandbox.imageSizeImpl = () => null;
    env.sandbox.fetchImpl = async () => ({ok:true,json:async()=>({page:0,totalPages:1,items:[multi,{...multi,id:8}]})});
    env.run("app.js"); await settle(); env.flushFrames();
    const before = env.elements.get("gallery").children.map(card => ({...card.style}));
    link(env).children[0].dispatch("error");
    assert.equal(link(env).children[1].hidden, false);
    assert.deepEqual(env.elements.get("gallery").children.map(card => ({...card.style})), before);
});

test("page correction retains existing last-valid-page behavior", async () => {
    const env = browser({url:"http://localhost/?page=7"}); const calls = [];
    env.sandbox.fetchImpl = async url => { calls.push(url); return {ok:true,json:async()=>({page:calls.length===1?7:1,totalPages:2,items:[]})}; };
    env.run("app.js"); await settle();
    assert.deepEqual(calls, ["/api/illustrations?page=7&size=24", "/api/illustrations?page=1&size=24"]);
});

test("import collapse keeps selected input and results; unavailable storage still permits layout", async () => {
    const env = await setup([multi], {storageDisabled:true});
    const toggle = env.elements.get("import-toggle"), panel = env.elements.get("import-panel");
    panel.hidden = true;
    env.elements.get("import-files").value = "selected";
    toggle.dispatch("click"); assert.equal(panel.hidden, false);
    toggle.dispatch("click"); assert.equal(panel.hidden, true);
    assert.equal(env.elements.get("import-files").value, "selected");
    assert.ok(parseFloat(env.elements.get("gallery").style.height) > 0);
});

test("out-of-order dimension loads wait before replacing a page or restoring its browse state", async () => {
    const env = await setup(); const oldCard = env.elements.get("gallery").children[0];
    env.window.setTimeout = setTimeout;
    let resolveSlow;
    env.sandbox.imageSizeImpl = url => url.includes("44") ? new Promise(resolve => { resolveSlow = resolve; }) : {width:400,height:800};
    env.sandbox.fetchImpl = async () => ({ok:true,json:async()=>({page:1,totalPages:2,items:[
        {id:9,coverAssetId:44,coverMimeType:"image/png"}, {id:10,coverAssetId:55,coverMimeType:"image/png"}
    ]})});
    env.elements.get("next-button").dispatch("click"); await settle();
    assert.equal(env.elements.get("gallery").children[0],oldCard);
    assert.equal(env.window.location.search,"?page=0");
    resolveSlow({width:1600,height:400}); await settle(); env.flushFrames();
    const cards = env.elements.get("gallery").children;
    assert.equal(cards[0].dataset.browseAnchor,"illustration:9");
    assert.equal(cards[1].dataset.browseAnchor,"illustration:10");
    assert.ok(parseFloat(cards[0].style.height) < parseFloat(cards[1].style.height));
    assert.equal(env.window.location.search,"?page=1");
});

test("timed-out dimensions commit fixed fallback frames and late loads do not reflow the page", async () => {
    const env = browser(); const timers = [], pendingSizes = [];
    env.window.setTimeout = (callback, delay) => { timers.push({callback, delay}); return 0; };
    env.sandbox.imageSizeImpl = () => new Promise(resolve => pendingSizes.push(resolve));
    env.sandbox.fetchImpl = async () => ({ok:true,json:async()=>({page:0,totalPages:1,items:[
        {id:1,coverAssetId:1,coverMimeType:"image/png"}, {id:2,coverAssetId:2,coverMimeType:"image/png"}
    ]})});
    env.run("app.js"); await settle();
    assert.equal(env.elements.get("gallery").children.length,0);
    assert.equal(timers.length,2);
    timers.forEach(timer => { assert.ok(timer.delay > 0 && timer.delay <= 6000); timer.callback(); });
    await settle(); env.flushFrames();
    const before = env.elements.get("gallery").children.map(card => ({...card.style}));
    assert.equal(before[0].width,before[0].height);
    assert.equal(env.elements.get("gallery")["aria-busy"],"false");
    pendingSizes.forEach(resolve => resolve({width:300,height:3000}));
    await settle(); env.flushFrames();
    assert.deepEqual(env.elements.get("gallery").children.map(card => ({...card.style})),before);
});

test("URL query is sent to the library API; chips clear one condition and reset pagination", async () => {
    const env = browser({url:"http://localhost/?q=%E5%A4%8F%E6%97%A5&authorId=12&tagId=7&page=1"});
    const calls = [];
    env.sandbox.fetchImpl = async url => {
        calls.push(url); const p = new URL(url, "http://localhost").searchParams;
        return {ok:true,json:async()=>({page:Number(p.get("page")),totalPages:2,totalElements:26,items:[multi],
            filters:{author:{id:12,displayName:"Artist"},tag:{id:7,name:"风景"}}})};
    };
    env.run("app.js"); await settle(); env.flushFrames();
    assert.equal(new URL(calls[0], "http://localhost").searchParams.get("q"), "夏日");
    assert.equal(env.elements.get("total-count").textContent, "匹配 26 件作品");
    assert.equal(env.elements.get("gallery-search-input").value, "夏日");
    const chips = env.elements.get("gallery-filters").children;
    assert.ok(chips[1].textContent.includes("Artist"));
    chips[2].dispatch("click"); await settle(); env.flushFrames();
    const address = new URL(env.window.location.href);
    assert.equal(address.searchParams.get("tagId"), null);
    assert.equal(address.searchParams.get("authorId"), "12");
    assert.equal(address.searchParams.get("page"), "0");
    assert.equal(env.window.history.length, 2);
});

test("search submission preserves exact filters; Back/Forward restore different queries on the same page", async () => {
    const env = await setup([multi], {url:"http://localhost/?authorId=12"});
    const calls = [];
    env.sandbox.fetchImpl = async url => {
        calls.push(url); const q = new URL(url,"http://localhost").searchParams.get("q");
        return {ok:true,json:async()=>({page:0,totalPages:1,totalElements:1,items:[{...multi,id:q?8:7}]})};
    };
    env.window.scrollY = 123;
    env.elements.get("gallery-search-input").value = "  summer  ";
    env.elements.get("gallery-search-form").dispatch("submit"); await settle(); env.flushFrames();
    assert.equal(env.window.history.length, 2);
    assert.equal(new URL(calls[0],"http://localhost").searchParams.get("authorId"), "12");
    assert.equal(env.window.history.state.iaBrowse.anchor, null);
    env.window.history.back(); await settle(); env.flushFrames();
    assert.equal(env.elements.get("gallery-search-input").value, "");
    assert.equal(env.elements.get("gallery").children[0].dataset.browseAnchor,"illustration:7");
    assert.equal(env.window.scrollY,123);
    env.window.history.forward(); await settle(); env.flushFrames();
    assert.equal(env.elements.get("gallery-search-input").value,"summer");
    assert.equal(env.elements.get("gallery").children[0].dataset.browseAnchor,"illustration:8");
    assert.equal(env.window.history.length,2);
    env.elements.get("gallery-search-form").dispatch("submit"); await settle();
    assert.equal(env.window.history.length,2);
});

test("failed search keeps applied query, cards and URL; retry applies the original draft", async () => {
    const env = await setup(); const card = env.elements.get("gallery").children[0];
    env.sandbox.fetchImpl = async () => ({ok:false,status:503});
    env.elements.get("gallery-search-input").value = "failure";
    env.elements.get("gallery-search-form").dispatch("submit"); await settle();
    assert.equal(env.window.location.search,"?page=0");
    assert.equal(env.elements.get("gallery").children[0],card);
    assert.equal(env.elements.get("gallery-filters").hidden,true);
    const calls=[];
    env.sandbox.fetchImpl = async url => { calls.push(url); return {ok:true,json:async()=>({page:0,totalPages:0,totalElements:0,items:[]})}; };
    env.elements.get("retry-button").dispatch("click"); await settle();
    assert.equal(new URL(calls[0],"http://localhost").searchParams.get("q"),"failure");
    assert.equal(env.elements.get("state-title").textContent,"没有匹配作品");
    assert.equal(env.elements.get("total-count").textContent,"匹配 0 件作品");
    env.elements.get("gallery-filters").children.at(-1).dispatch("click"); await settle();
    assert.equal(env.window.location.search,"?page=0");
    assert.equal(env.elements.get("gallery-filters").hidden,true);
});

test("obsolete search and dimension responses cannot overwrite a newer query or history entry", async () => {
    const env = await setup(); let release;
    env.sandbox.fetchImpl = url => new URL(url,"http://localhost").searchParams.get("q") === "slow"
        ? new Promise(resolve => {release=resolve;})
        : Promise.resolve({ok:true,json:async()=>({page:0,totalPages:1,totalElements:1,items:[{...multi,id:9}]})});
    env.elements.get("gallery-search-input").value="slow";
    env.elements.get("gallery-search-form").dispatch("submit"); await settle();
    env.elements.get("gallery-search-input").value="fast";
    env.elements.get("gallery-search-form").dispatch("submit"); await settle();
    release({ok:true,json:async()=>({page:0,totalPages:1,items:[{...multi,id:8}]})}); await settle();
    assert.equal(env.elements.get("gallery").children[0].dataset.browseAnchor,"illustration:9");
    assert.equal(new URL(env.window.location.href).searchParams.get("q"),"fast");
    assert.equal(env.window.history.length,2);
});

test("history load failure hides obsolete results and retry reads the target URL", async () => {
    const env = await setup();
    env.elements.get("gallery-search-input").value="new";
    env.elements.get("gallery-search-form").dispatch("submit"); await settle();
    env.sandbox.fetchImpl=async()=>({ok:false,status:503});
    env.window.history.back(); await settle();
    assert.equal(env.window.location.search,"?page=0");
    assert.equal(env.elements.get("gallery").hidden,true);
    assert.equal(env.elements.get("total-count").textContent,"");
    assert.equal(env.elements.get("previous-button").disabled,true);
    assert.equal(env.elements.get("next-button").disabled,true);
    env.sandbox.fetchImpl=async()=>({ok:true,json:async()=>({page:0,totalPages:1,totalElements:1,items:[multi]})});
    env.elements.get("retry-button").dispatch("click"); await settle();
    assert.equal(env.elements.get("gallery").hidden,false);
    assert.equal(env.window.history.length,2);
});

test("invalid exact-filter URL never silently queries the entire library", async () => {
    const env=browser({url:"http://localhost/?authorId=wrong"}); let calls=0;
    env.sandbox.fetchImpl=async()=>{calls++;};
    env.run("app.js"); await settle();
    assert.equal(calls,0);
    assert.equal(env.elements.get("state-title").textContent,"查询条件无效");
    assert.equal(env.elements.get("gallery-filters").hidden,false);
});

test("typing a new draft during a pending read is preserved when that read completes", async () => {
    const env=await setup(); let release;
    env.sandbox.fetchImpl=()=>new Promise(resolve=>{release=resolve;});
    env.elements.get("gallery-search-input").value="first";
    env.elements.get("gallery-search-form").dispatch("submit"); await settle();
    env.elements.get("gallery-search-input").value="next draft";
    release({ok:true,json:async()=>({page:0,totalPages:1,totalElements:1,items:[multi]})}); await settle();
    assert.equal(env.elements.get("gallery-search-input").value,"next draft");
    assert.equal(new URL(env.window.location.href).searchParams.get("q"),"first");
    assert.ok(env.elements.get("gallery-filters").children[0].textContent.includes("first"));
});

test("a superseded dimension preparation cannot replace newer search cards", async () => {
    const env=await setup(); let release;
    env.sandbox.imageSizeImpl=url=>url.includes("441")?new Promise(resolve=>{release=resolve;}):{width:800,height:600};
    env.sandbox.fetchImpl=async url=>({ok:true,json:async()=>({page:0,totalPages:1,totalElements:1,
        items:[{id:new URL(url,"http://localhost").searchParams.get("q")==="slow"?44:55,
            coverAssetId:new URL(url,"http://localhost").searchParams.get("q")==="slow"?441:551,coverMimeType:"image/png"}]})});
    env.elements.get("gallery-search-input").value="slow";
    env.elements.get("gallery-search-form").dispatch("submit"); await settle();
    env.elements.get("gallery-search-input").value="fast";
    env.elements.get("gallery-search-form").dispatch("submit"); await settle();
    release({width:400,height:900}); await settle();
    assert.equal(env.elements.get("gallery").children[0].dataset.browseAnchor,"illustration:55");
    assert.equal(new URL(env.window.location.href).searchParams.get("q"),"fast");
});
