const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = readFileSync(path.resolve(__dirname, "../../main/resources/static/c/archive-client.js"), "utf8");

function item(id, assets) {
    return {
        id,
        title: `Work ${id}`,
        author: null,
        coverAssetId: assets[0].id,
        coverMimeType: assets[0].mimeType,
        assetCount: assets.length,
        createdAt: "2026-10-28T10:00:00",
        assets
    };
}

// Values created inside the vm context have their own Array prototype; compare as plain JSON.
const plain = (value) => JSON.parse(JSON.stringify(value));
const jpeg = (id, sortOrder = 0) => ({ id, mimeType: "image/jpeg", sortOrder });

const NO_CONTENT = Symbol("204 No Content");

/**
 * Loads archive-client.js with a scripted fetch and Image. `responses` maps a URL to a JSON body, a status
 * number, an Error to reject with, or a function returning a promise of one of those (to hold a reply back);
 * `brokenImages` lists image URLs that fail to load. Each request records its URL and abort signal.
 */
function setup({ responses = {}, brokenImages = [], ignoreAbort = false } = {}) {
    const requests = [];
    const calls = [];
    const imageLoads = [];
    class FakeImage {
        set src(url) {
            this._src = url;
            imageLoads.push(url);
            setImmediate(() => {
                if (brokenImages.includes(url)) this.onerror?.();
                else { this.naturalWidth = 600; this.naturalHeight = 900; this.onload?.(); }
            });
        }
        get src() { return this._src; }
    }
    const window = {};
    const context = {
        window,
        Image: FakeImage,
        document: { createElement() { return { getContext() { throw new Error("no canvas in tests"); } }; } },
        // GET replies are keyed by URL; other methods by "METHOD url". NO_CONTENT is a 204 whose body must not be read;
        // { status, body } is an error reply with a JSON body.
        fetch: async (url, init = {}) => {
            const method = init.method || "GET";
            const key = method === "GET" ? url : `${method} ${url}`;
            requests.push(key);
            calls.push({ url, method, signal: init.signal, body: typeof init.body === "string" ? JSON.parse(init.body) : init.body });
            let reply = responses[key];
            if (typeof reply === "function") reply = await reply();
            if (init.signal?.aborted && !ignoreAbort) throw new DOMException("aborted", "AbortError");
            if (reply instanceof Error) throw reply;
            if (reply === NO_CONTENT) return { ok: true, status: 204, json: async () => { throw new SyntaxError("Unexpected end of JSON input"); } };
            if (typeof reply === "number") return { ok: false, status: reply, json: async () => ({}) };
            if (reply && reply.status >= 400) return { ok: false, status: reply.status, json: async () => reply.body };
            if (!reply) return { ok: false, status: 404, json: async () => ({}) };
            return { ok: true, status: 200, json: async () => reply };
        },
        // Browser globals the page relies on; a vm context only has the JavaScript built-ins.
        URLSearchParams,
        AbortController,
        FormData,
        setImmediate,
        console
    };
    vm.runInNewContext(source, context);
    const events = [];
    window.Archive.subscribe((event) => events.push(event.type));
    return { Archive: window.Archive, requests, calls, imageLoads, events, responses };
}

const listUrl = (page, extra = "") => `/api/illustrations?page=${page}&size=30${extra}`;
const pageOf = (page, totalElements, items, filters = null) =>
    ({ page, size: 30, totalElements, totalPages: Math.ceil(totalElements / 30), items, filters });

/** A reply that waits until the test calls release(). */
function held(reply) {
    let release;
    const gate = new Promise((resolve) => { release = () => resolve(reply); });
    const fn = () => gate;
    fn.release = () => release();
    return fn;
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

test("loads pages in order until every work is on the wall, without duplicates", async () => {
    const first = Array.from({ length: 30 }, (_, i) => item(100 - i, [jpeg(1000 + i)]));
    const second = [item(70, [jpeg(1100)]), item(69, [jpeg(1101)]), item(100, [jpeg(1000)])];
    const { Archive, requests } = setup({ responses: {
        [listUrl(0)]: { page: 0, size: 30, totalElements: 32, totalPages: 2, items: first },
        [listUrl(1)]: { page: 1, size: 30, totalElements: 32, totalPages: 2, items: second }
    } });
    assert.equal(Archive.hasMore(), true);
    assert.equal(await Archive.loadMore(), true);
    assert.equal(Archive.works.length, 30);
    assert.equal(Archive.total(), 32);
    assert.equal(Archive.hasMore(), true);
    await Archive.loadMore();
    assert.deepEqual(requests, [listUrl(0), listUrl(1)]);
    assert.equal(Archive.works.length, 32, "a work repeated across pages is shown once");
    assert.equal(Archive.hasMore(), false);
    assert.equal(await Archive.loadMore(), false, "nothing left to request");
    assert.equal(requests.length, 2);
});

test("subscribers see isLoading() true on 'loading' and false once 'append' or 'error' arrives", async () => {
    const first = Array.from({ length: 30 }, (_, i) => item(40 - i, [jpeg(2000 + i)]));
    const env = setup({ responses: { [listUrl(0)]: pageOf(0, 31, first), [listUrl(1)]: 500 } });
    const seen = [];
    env.Archive.subscribe((event) => seen.push([event.type, env.Archive.isLoading(), env.Archive.lastError() != null]));
    await env.Archive.setQuery({});
    await env.Archive.loadMore();
    assert.deepEqual(seen, [
        ["reset", false, false],
        ["loading", true, false],
        ["append", false, false],
        ["loading", true, false],
        ["error", false, true]
    ]);
});

test("an empty library finishes loading with no works", async () => {
    const { Archive, events } = setup({ responses: {
        [listUrl(0)]: { page: 0, size: 30, totalElements: 0, totalPages: 0, items: [] }
    } });
    await Archive.loadMore();
    assert.equal(Archive.total(), 0);
    assert.equal(Archive.works.length, 0);
    assert.equal(Archive.hasMore(), false);
    assert.equal(Archive.lastError(), null);
    assert.deepEqual(events, ["loading", "append"]);
});

test("a failed request keeps loaded works and retries the same page", async () => {
    const env = setup({ responses: { [listUrl(0)]: new TypeError("connection refused") } });
    assert.equal(await env.Archive.loadMore(), false);
    assert.match(env.Archive.lastError().message, /无法连接/);
    assert.equal(env.Archive.works.length, 0);
    assert.ok(env.events.includes("error"));

    env.responses[listUrl(0)] = { page: 0, size: 30, totalElements: 1, totalPages: 1, items: [item(1, [jpeg(10)])] };
    assert.equal(await env.Archive.loadMore(), true);
    assert.equal(env.Archive.lastError(), null);
    assert.equal(env.Archive.works.length, 1);
    assert.deepEqual(env.requests, [listUrl(0), listUrl(0)]);
});

test("an expired launch session is reported as such", async () => {
    const { Archive } = setup({ responses: { [listUrl(0)]: 403 } });
    await Archive.loadMore();
    assert.equal(Archive.lastError().status, 403);
    assert.match(Archive.lastError().message, /会话已失效/);
});

test("covers fall back from thumbnail to original, then to a placeholder", async () => {
    const { Archive } = setup({
        responses: { [listUrl(0)]: { page: 0, size: 30, totalElements: 2, totalPages: 1, items: [
            item(1, [jpeg(11)]), item(2, [jpeg(22)])
        ] } },
        brokenImages: ["/api/assets/11/thumbnail", "/api/assets/22/thumbnail", "/api/assets/22/content"]
    });
    await Archive.loadMore();
    const [one, two] = Archive.works.map((w) => w.pages[0]);
    assert.equal(one.src, "/api/assets/11/content");
    assert.equal(one.broken, false);
    assert.equal(one.width, 600);
    assert.equal(two.broken, true);
    assert.match(two.src, /^data:image\/svg\+xml/);
    assert.ok(two.width > 0 && two.height > 0, "a broken cover still has a layout size");
});

test("multi-page works keep page order; GIF and video pages use their own sources", async () => {
    const { Archive } = setup({ responses: { [listUrl(0)]: { page: 0, size: 30, totalElements: 2, totalPages: 1, items: [
        item(1, [jpeg(13, 2), { id: 11, mimeType: "image/gif", sortOrder: 0 }, jpeg(12, 1)]),
        item(2, [{ id: 21, mimeType: "video/mp4", sortOrder: 0 }])
    ] } } });
    await Archive.loadMore();
    const multi = Archive.get(1);
    assert.deepEqual(plain(multi.pages.map((p) => p.assetId)), [11, 12, 13]);
    assert.equal(multi.pages[0].src, "/api/assets/11/content", "GIF has no thumbnail and stays animated");
    assert.equal(multi.pages[1].src, "/api/assets/12/thumbnail");
    assert.equal(multi.pages[1].full, "/api/assets/12/content");
    const video = Archive.get(2).pages[0];
    assert.equal(video.video, true);
    assert.equal(video.full, null);
    assert.match(video.src, /^data:image\/svg\+xml/);
});

test("detail adds tags, note, source and file names; a failed detail can be retried", async () => {
    const env = setup({ responses: {
        [listUrl(0)]: { page: 0, size: 30, totalElements: 1, totalPages: 1, items: [item(5, [jpeg(51), jpeg(52, 1)])] },
        "/api/illustrations/5": 500
    } });
    await env.Archive.loadMore();
    assert.equal(await env.Archive.loadDetail(5), null);
    assert.ok(env.Archive.get(5).detailError);
    env.responses["/api/illustrations/5"] = {
        id: 5, sourceUrl: "https://example.com/5", note: "两页", tags: [{ id: 1, name: "海" }],
        assets: [{ id: 51, originalFilename: "a.jpg", fileSize: 100 }, { id: 52, originalFilename: "b.jpg", fileSize: 200 }]
    };
    const detail = await env.Archive.loadDetail(5);
    assert.equal(detail.note, "两页");
    assert.deepEqual(plain(detail.tags.map((t) => t.name)), ["海"]);
    assert.deepEqual(plain(env.Archive.get(5).pages.map((p) => [p.originalFilename, p.size])), [["a.jpg", 100], ["b.jpg", 200]]);
    assert.equal(env.Archive.get(5).detailError, null);
});

/* ---------------- 搜索与筛选 ---------------- */

const works = (from, count, assetBase = 1000) =>
    Array.from({ length: count }, (_, i) => item(from - i, [jpeg(assetBase + from - i)]));

test("query URLs repeat authorId / tagId, encode Chinese text, and keep the unfiltered URL unchanged", () => {
    const { Archive } = setup();
    const { listUrl: build, normalizeQuery } = Archive._internal;
    assert.equal(build(normalizeQuery({}), 0), "/api/illustrations?page=0&size=30");
    const url = build(normalizeQuery({ q: "  海边 夏天 ", authorIds: [3, 1, 3, "2", 0, -4, "x"], tagIds: [5] }), 2);
    const params = new URL(url, "http://localhost").searchParams;
    assert.deepEqual([params.get("page"), params.get("size"), params.get("q")], ["2", "30", "海边 夏天"]);
    assert.deepEqual(params.getAll("authorId"), ["1", "2", "3"], "ids are deduplicated, sorted, and invalid ones dropped");
    assert.deepEqual(params.getAll("tagId"), ["5"]);

    const fromUrl = Archive.queryFromSearch("?q=%E6%B5%B7&authorId=7&authorId=2&tagId=9&other=1");
    assert.deepEqual(plain(fromUrl), { q: "海", authorIds: [2, 7], tagIds: [9] });
    assert.equal(Archive.searchOf(fromUrl), "?q=%E6%B5%B7&authorId=2&authorId=7&tagId=9");
    assert.equal(Archive.searchOf({ q: "   " }), "", "an empty query leaves the page URL bare");
});

test("changing the query resets results, page and total, then pages through the filtered set on the server", async () => {
    const filter = "&q=%E6%B5%B7&authorId=1&authorId=2&tagId=3";
    const env = setup({ responses: {
        [listUrl(0)]: pageOf(0, 64, works(100, 30)),
        [listUrl(0, filter)]: pageOf(0, 35, works(90, 30, 5000), { authors: [{ id: 1, displayName: "雨宫" }], tags: [] }),
        [listUrl(1, filter)]: pageOf(1, 35, works(60, 5, 5000))
    } });
    await env.Archive.setQuery({});
    assert.equal(env.Archive.works.length, 30);
    assert.equal(env.Archive.total(), 64);
    const sameArray = env.Archive.works;

    const pending = env.Archive.setQuery({ q: "海", authorIds: [2, 1], tagIds: [3] });
    assert.equal(env.Archive.works.length, 0, "old results are gone at once");
    assert.equal(env.Archive.total(), null);
    assert.equal(env.Archive.filters(), null);
    assert.equal(env.Archive.isFiltered(), true);
    await pending;
    assert.equal(env.Archive.works, sameArray, "the wall keeps one array reference");
    assert.equal(env.Archive.total(), 35);
    assert.equal(env.Archive.filters().authors[0].displayName, "雨宫");
    await env.Archive.loadMore();
    assert.equal(env.Archive.works.length, 35, "the second filtered page comes from the server, not from loaded works");
    assert.equal(env.Archive.hasMore(), false);
    assert.deepEqual(env.requests, [listUrl(0), listUrl(0, filter), listUrl(1, filter)]);
    assert.equal(env.events.filter((e) => e === "reset").length, 2);
    assert.equal(env.Archive.get(100), null, "works from the previous query are not reachable");
});

test("a query guard that says no leaves results, page, total and query untouched and reports 'blocked'", async () => {
    const env = setup({ responses: {
        [listUrl(0)]: pageOf(0, 31, Array.from({ length: 30 }, (_, i) => item(90 - i, [jpeg(6000 + i)]))),
        [listUrl(0, "&q=x")]: pageOf(0, 0, [])
    } });
    await env.Archive.setQuery({});
    let allow = false;
    env.Archive.setQueryGuard(() => allow);
    env.events.length = 0;
    assert.equal(await env.Archive.setQuery({ q: "x" }), false);
    assert.deepEqual(env.events, ["blocked"]);
    assert.equal(env.Archive.works.length, 30);
    assert.equal(env.Archive.total(), 31);
    assert.equal(env.Archive.query().q, "");
    assert.equal(env.requests.length, 1, "no request for the refused query");
    allow = true;
    await env.Archive.setQuery({ q: "x" });
    assert.equal(env.Archive.query().q, "x");
    assert.equal(env.Archive.total(), 0);
});

test("re-applying the same query (in any id order) does not reload", async () => {
    const env = setup({ responses: { [listUrl(0, "&tagId=1&tagId=2")]: pageOf(0, 1, works(5, 1)) } });
    await env.Archive.setQuery({ tagIds: [2, 1] });
    await env.Archive.setQuery({ tagIds: [1, 2, 2], q: " " });
    assert.equal(env.requests.length, 1);
});

test("a slower response for an earlier query is cancelled and never mixed into the newer results", async () => {
    const slow = held(pageOf(0, 1, works(10, 1)));
    const env = setup({ responses: {
        [listUrl(0, "&q=a")]: slow,
        [listUrl(0, "&q=ab")]: pageOf(0, 1, works(20, 1))
    } });
    const first = env.Archive.setQuery({ q: "a" });
    const second = env.Archive.setQuery({ q: "ab" });
    assert.equal(await second, true);
    slow.release();
    assert.equal(await first, false);
    assert.equal(env.calls[0].signal.aborted, true, "the superseded request is aborted");
    assert.deepEqual(plain(env.Archive.works.map((w) => w.id)), [20]);
    assert.equal(env.Archive.lastError(), null, "the aborted request is not reported as a failure");
    assert.equal(env.events.filter((e) => e === "append").length, 1);
});

test("a stale response that still arrives (no abort) is dropped, even while its covers are being measured", async () => {
    const slow = held(pageOf(0, 10, works(50, 10, 7000)));
    const env = setup({ ignoreAbort: true, responses: {
        [listUrl(0, "&tagId=1")]: slow,
        [listUrl(0, "&tagId=2")]: pageOf(0, 1, works(20, 1))
    } });
    const first = env.Archive.setQuery({ tagIds: [1] });
    slow.release();
    await settle(); // the old page has arrived and its first covers are loading
    const oldCovers = () => env.imageLoads.filter((u) => /\/api\/assets\/70\d\d\//.test(u)).length;
    assert.ok(oldCovers() > 0 && oldCovers() < 10, `old covers are mid-measurement (${oldCovers()})`);
    await env.Archive.setQuery({ tagIds: [2] });
    assert.equal(await first, false);
    await settle();
    assert.ok(oldCovers() <= 6, "no more old covers are started after the query changed");
    assert.deepEqual(plain(env.Archive.works.map((w) => w.id)), [20]);
    assert.equal(env.Archive.total(), 1);
});

test("a failed first page of a search can be retried with the same conditions; a later page failure keeps results", async () => {
    const filter = "&authorId=4";
    const env = setup({ responses: { [listUrl(0, filter)]: 500 } });
    await env.Archive.setQuery({ authorIds: [4] });
    assert.equal(env.Archive.lastError().status, 500);
    assert.equal(env.Archive.works.length, 0);
    env.responses[listUrl(0, filter)] = pageOf(0, 31, works(80, 30));
    env.responses[listUrl(1, filter)] = new TypeError("offline");
    assert.equal(await env.Archive.loadMore(), true);
    assert.equal(await env.Archive.loadMore(), false);
    assert.equal(env.Archive.works.length, 30, "loaded works stay when the next page fails");
    env.responses[listUrl(1, filter)] = pageOf(1, 31, works(50, 1));
    assert.equal(await env.Archive.loadMore(), true);
    assert.deepEqual(env.requests, [listUrl(0, filter), listUrl(0, filter), listUrl(1, filter), listUrl(1, filter)]);
    assert.equal(env.Archive.works.length, 31);
});

test("a cover measured under one query is reused when the same work appears under another", async () => {
    const env = setup({ responses: {
        [listUrl(0)]: pageOf(0, 2, [item(1, [jpeg(10)]), item(2, [jpeg(20)])]),
        [listUrl(0, "&q=one")]: pageOf(0, 1, [item(1, [jpeg(10)])])
    } });
    await env.Archive.setQuery({});
    await env.Archive.setQuery({ q: "one" });
    assert.equal(env.imageLoads.filter((u) => u === "/api/assets/10/thumbnail").length, 1);
    assert.equal(env.Archive.get(1).pages[0].width, 600);
});

const facetUrl = (base, offset, keyword) =>
    `${base}?limit=30&offset=${offset}` + (keyword ? "&" + new URLSearchParams({ keyword }) : "");
const authors = (from, count) => Array.from({ length: count }, (_, i) => ({ id: from + i, displayName: `作者 ${from + i}`, xUsername: null }));

test("author candidates page past the first results until the server runs out", async () => {
    const env = setup({ responses: {
        [facetUrl("/api/authors", 0)]: authors(1, 30),
        [facetUrl("/api/authors", 30)]: [...authors(30, 1), ...authors(31, 4)]
    } });
    const facet = env.Archive.facetSearch("author");
    await facet.search("");
    assert.equal(facet.state.items.length, 30);
    assert.equal(facet.state.done, false);
    await facet.more();
    assert.equal(facet.state.items.length, 34, "a row repeated across pages is listed once");
    assert.equal(facet.state.done, true);
    assert.equal(await facet.more(), false);
    assert.deepEqual(env.requests, [facetUrl("/api/authors", 0), facetUrl("/api/authors", 30)]);
});

test("tag candidates search by keyword, drop a stale keyword's reply, and retry a failed page", async () => {
    const slow = held([{ id: 1, name: "海" }]);
    const env = setup({ responses: {
        [facetUrl("/api/tags", 0, "海")]: slow,
        [facetUrl("/api/tags", 0, "海边")]: 500
    } });
    const facet = env.Archive.facetSearch("tag");
    const first = facet.search(" 海 ");
    const second = facet.search("海边");
    assert.equal(await second, false);
    assert.equal(facet.state.error.status, 500);
    slow.release();
    assert.equal(await first, false);
    assert.deepEqual(plain(facet.state.items), [], "the earlier keyword's reply is ignored");
    assert.equal(facet.state.error.status, 500, "and does not clear the current error");
    env.responses[facetUrl("/api/tags", 0, "海边")] = [{ id: 2, name: "海边" }];
    assert.equal(await facet.more(), true);
    assert.deepEqual(plain(facet.state.items.map((t) => t.name)), ["海边"]);
    assert.equal(facet.state.error, null);
    assert.equal(facet.state.done, true);
});

/* ---------------- 单件编辑 ---------------- */

const detailOf = (id, extra = {}) => ({
    id, title: `Work ${id}`, author: { id: 7, displayName: "雨宫ゆき", xUsername: "amamiya" },
    sourceUrl: "https://example.com/" + id, note: "原备注", tags: [{ id: 1, name: "海" }, { id: 2, name: "夜空" }],
    assets: [], updatedAt: "2026-10-28T10:00:00", ...extra
});
const detailUrl = (id) => `/api/illustrations/${id}`;
const patchKey = (id) => `PATCH /api/illustrations/${id}`;

test("the PATCH body carries only changed fields: trimmed text, blank as null, cleared author null, cleared tags []", () => {
    const { Archive } = setup();
    const { buildPatch, editableOf } = Archive._internal;
    const original = editableOf(detailOf(5));
    assert.deepEqual(plain(original), { title: "Work 5", authorId: 7, tagIds: [1, 2], sourceUrl: "https://example.com/5", note: "原备注" });
    assert.deepEqual(plain(buildPatch(original, { ...original, title: "  Work 5 ", tagIds: [2, 1, 2] })), {},
        "surrounding spaces and tag order are not changes");
    assert.deepEqual(plain(buildPatch(original, { ...original, title: " 新标题 ", note: "   ", authorId: null, tagIds: [] })),
        { title: "新标题", note: null, authorId: null, tagIds: [] });
    assert.deepEqual(plain(buildPatch(original, { ...original, sourceUrl: "", tagIds: [2, 3, 3] })), { sourceUrl: null, tagIds: [2, 3] });
    const blank = editableOf({ title: null, author: null, sourceUrl: null, note: null, tags: [] });
    assert.deepEqual(plain(buildPatch(blank, { ...blank, authorId: 9 })), { authorId: 9 });
});

async function editEnv(responses = {}, items = [item(5, [jpeg(50)])]) {
    const env = setup({ responses: {
        [listUrl(0)]: pageOf(0, items.length, items),
        [detailUrl(5)]: detailOf(5),
        ...responses
    } });
    await env.Archive.setQuery({});
    env.events.length = 0;
    return env;
}

test("nothing can be edited or saved until the full detail has loaded; a failed load cannot be saved", async () => {
    const slow = held(detailOf(5));
    const env = await editEnv({ [detailUrl(5)]: slow });
    const session = env.Archive.editSession(5);
    const loading = session.load();
    assert.equal(session.state.phase, "loading");
    session.set("title", "抢先修改");
    assert.equal(session.state.draft, null, "no draft before the detail arrives");
    assert.equal(await session.save(), null);
    slow.release();
    assert.equal(await loading, true);
    assert.equal(session.state.draft.title, "Work 5");
    assert.equal(session.dirty(), false);

    env.responses[detailUrl(5)] = 500;
    const failing = env.Archive.editSession(5);
    assert.equal(await failing.load(), false);
    assert.equal(failing.state.phase, "error");
    assert.match(failing.state.error, /500/);
    assert.equal(await failing.save(), null);
    assert.equal(env.requests.filter((r) => r.startsWith("PATCH")).length, 0);
});

test("saving sends one PATCH, treats 204 as success without reading a body, and refreshes card and detail", async () => {
    let saved = false;
    const env = await editEnv({
        [patchKey(5)]: () => { saved = true; return NO_CONTENT; },
        [detailUrl(5)]: () => (saved ? detailOf(5, { title: "新标题", author: null, tags: [] }) : detailOf(5))
    });
    const session = env.Archive.editSession(5);
    await session.load();
    session.set("title", "新标题 ");
    session.set("authorId", null);
    session.set("tagIds", []);
    assert.equal(session.dirty(), true);
    const first = session.save();
    assert.equal(session.state.phase, "saving");
    assert.equal(await session.save(), null, "a second submit while saving is ignored");
    const result = await first;
    assert.deepEqual(plain(result), { listed: true, detailError: null, refreshError: null });
    const patches = env.calls.filter((c) => c.method === "PATCH");
    assert.equal(patches.length, 1);
    assert.deepEqual(patches[0].body, { title: "新标题", authorId: null, tagIds: [] });
    assert.equal(session.state.phase, "saved");
    const work = env.Archive.get(5);
    assert.equal(work.title, "新标题", "the card reads the saved title");
    assert.equal(work.author, null);
    assert.deepEqual(plain(work.detail.tags), []);
    assert.ok(env.events.includes("updated"));
    assert.equal(env.requests.filter((r) => r.startsWith("/api/illustrations?")).length, 1, "no list reload without filters");
});

test("a failed save keeps the draft and the reason, and the same draft can be retried", async () => {
    const env = await editEnv({ [patchKey(5)]: { status: 400, body: { message: "Title must not exceed 255 characters." } } });
    const session = env.Archive.editSession(5);
    await session.load();
    session.set("note", "新的备注");
    assert.equal(await session.save(), null);
    assert.equal(session.state.phase, "ready");
    assert.match(session.state.error, /内容不符合要求：Title must not exceed 255/);
    assert.equal(session.state.draft.note, "新的备注", "the draft is kept");
    assert.equal(session.dirty(), true);

    env.responses[patchKey(5)] = 500;
    assert.equal(await session.save(), null);
    assert.match(session.state.error, /服务没有保存这次修改（500）/);
    env.responses[patchKey(5)] = 404;
    assert.equal(await session.save(), null);
    assert.match(session.state.error, /已不存在/);
    env.responses[patchKey(5)] = new TypeError("offline");
    assert.equal(await session.save(), null);
    assert.match(session.state.error, /无法连接/);

    env.responses[patchKey(5)] = NO_CONTENT;
    assert.ok(await session.save());
    assert.equal(session.state.error, null);
    assert.deepEqual(env.calls.filter((c) => c.method === "PATCH").map((c) => c.body), Array(5).fill({ note: "新的备注" }));
});

test("cancelling (disposing) writes nothing, and an unchanged draft saves without a request", async () => {
    const env = await editEnv();
    const session = env.Archive.editSession(5);
    await session.load();
    assert.deepEqual(plain(await session.save()), { unchanged: true });
    session.set("title", "不要了");
    session.dispose();
    assert.equal(env.requests.filter((r) => r.startsWith("PATCH")).length, 0);
    assert.equal(env.Archive.get(5).title, "Work 5");
});

test("after an edit under a filter, loaded pages are re-read from the server: the work leaves, the count drops, no page repeats", async () => {
    const filter = "&tagId=1";
    const before = Array.from({ length: 35 }, (_, i) => item(100 - i, [jpeg(3000 + i)]));
    const after = before.filter((w) => w.id !== 98);
    let saved = false;
    const env = setup({ responses: {
        [listUrl(0, filter)]: () => pageOf(0, saved ? 34 : 35, (saved ? after : before).slice(0, 30)),
        [listUrl(1, filter)]: () => pageOf(1, saved ? 34 : 35, (saved ? after : before).slice(30)),
        [detailUrl(98)]: () => detailOf(98, { tags: saved ? [] : [{ id: 1, name: "海" }] }),
        [patchKey(98)]: () => { saved = true; return NO_CONTENT; }
    } });
    const removed = [];
    env.Archive.subscribe((e) => { if (e.type === "refresh") removed.push(...e.removed); });
    await env.Archive.setQuery({ tagIds: [1] });
    await env.Archive.loadMore();
    assert.equal(env.Archive.works.length, 35);
    const kept = env.Archive.get(100);

    const session = env.Archive.editSession(98);
    await session.load();
    session.set("tagIds", []);
    const result = await session.save();
    assert.equal(result.listed, false, "the work no longer matches the filter");
    assert.equal(env.Archive.total(), 34);
    assert.equal(env.Archive.works.length, 34);
    assert.equal(new Set(env.Archive.works.map((w) => w.id)).size, 34, "no work twice");
    assert.equal(env.Archive.listed(98), false);
    assert.equal(env.Archive.get(98).id, 98, "the viewer can still read the work it is showing");
    assert.deepEqual(plain(removed), [{ id: 98, index: 2 }]);
    assert.equal(env.Archive.get(100), kept, "works that stay keep their objects (measured covers, details)");
    assert.equal(env.Archive.hasMore(), false);
    assert.equal(env.Archive.query().tagIds[0], 1, "the filter is kept");
    assert.deepEqual(env.requests.filter((r) => r.startsWith("/api/illustrations?")),
        [listUrl(0, filter), listUrl(1, filter), listUrl(0, filter), listUrl(1, filter)]);
});

test("a page still loading when the list is re-read after a save is dropped, so nothing is appended twice", async () => {
    const all = Array.from({ length: 65 }, (_, i) => item(200 - i, [jpeg(4000 + i)]));
    const slowPage2 = held(pageOf(2, 65, all.slice(60)));
    const env = setup({ responses: {
        [listUrl(0, "&q=a")]: pageOf(0, 65, all.slice(0, 30)),
        [listUrl(1, "&q=a")]: pageOf(1, 65, all.slice(30, 60)),
        [listUrl(2, "&q=a")]: slowPage2,
        [detailUrl(200)]: detailOf(200),
        [patchKey(200)]: NO_CONTENT
    } });
    await env.Archive.setQuery({ q: "a" });
    await env.Archive.loadMore();
    const pending = env.Archive.loadMore();
    const session = env.Archive.editSession(200);
    await session.load();
    session.set("note", "x");
    const saving = session.save();
    slowPage2.release();
    assert.equal(await pending, false, "the page that was in flight is discarded");
    await saving;
    assert.equal(env.Archive.works.length, 60);
    assert.equal(new Set(env.Archive.works.map((w) => w.id)).size, 60);
    env.responses[listUrl(2, "&q=a")] = pageOf(2, 65, all.slice(60));
    await env.Archive.loadMore();
    assert.equal(env.Archive.works.length, 65, "loading continues from the next page");
    assert.equal(new Set(env.Archive.works.map((w) => w.id)).size, 65);
});

test("of two detail reads for one work, the later one wins even if the earlier answers last", async () => {
    const older = held(detailOf(5, { title: "旧" }));
    const env = await editEnv({ [detailUrl(5)]: older });
    const first = env.Archive.loadDetail(5);
    env.responses[detailUrl(5)] = detailOf(5, { title: "新" });
    const second = env.Archive.loadDetail(5, { force: true });
    assert.equal((await second).title, "新");
    older.release();
    assert.equal((await first).title, "新", "the superseded read resolves to the latest detail");
    assert.equal(env.Archive.get(5).title, "新");
    assert.equal(env.Archive.get(5).detail.title, "新");
});

/* ---------------- 本地导入 ---------------- */

const IMPORT = "POST /api/illustrations/import";
const pic = (name, body = name, type = "image/png") => new File([body], name, { type });
const itemResult = (status, extra = {}) => ({ total: 1, items: [{ filename: "x", status, success: status === "SUCCESS", illustrationId: null, ...extra }] });

/**
 * Scripts the import endpoint: `script(file, index)` returns the reply for the file in each request (a body, a status
 * number, an Error, NO_CONTENT …). Records which file each request carried and how many requests overlapped.
 */
function importEnv(script, responses = {}) {
    const sent = [];
    let inflight = 0;
    let maxInflight = 0;
    const env = setup({ responses: {
        ...responses,
        [IMPORT]: async () => {
            const call = env.calls.filter((c) => c.method === "POST").at(-1);
            const files = call.body.getAll("files");
            sent.push(files);
            inflight++;
            maxInflight = Math.max(maxInflight, inflight);
            await new Promise((r) => setImmediate(r));
            inflight--;
            return script(files[0], sent.length - 1);
        }
    } });
    return { env, sent, maxInflight: () => maxInflight };
}

test("each file goes in its own request, one at a time; results map to files by request, not by name", async () => {
    const a1 = pic("a.png", "red");
    const b = pic("b.png", "blue");
    const a2 = pic("a.png", "green");
    const fake = pic("photo.jpg", "not really a jpeg", "image/jpeg");
    // The reply depends on what was uploaded (FormData hands back copies of the File objects)
    const replies = new Map([
        ["red", itemResult("SUCCESS", { illustrationId: 11 })],
        ["blue", itemResult("DUPLICATE", { illustrationId: 3, errorCode: "DUPLICATE_ILLUSTRATION", message: "Duplicate illustration: 3" })],
        ["green", itemResult("SUCCESS", { illustrationId: 12 })],
        ["not really a jpeg", itemResult("FAILED", { errorCode: "INVALID_FILE", message: "Filename extension does not match the image content." })]
    ]);
    const { env, sent, maxInflight } = importEnv(async (file) => replies.get(await file.text()));
    let changes = 0;
    const queue = env.Archive.importQueue({ onChange: () => changes++ });
    queue.add([a1, b, a2, fake]);
    queue.run(); queue.run(); // repeated triggers join the same loop
    await queue.run();
    assert.equal(maxInflight(), 1, "never two uploads at once");
    assert.deepEqual(sent.map((files) => files.length), [1, 1, 1, 1], "one file per request");
    const uploaded = await Promise.all(sent.map(async (files) => [files[0].name, await files[0].text()]));
    assert.deepEqual(uploaded, [["a.png", "red"], ["b.png", "blue"], ["a.png", "green"], ["photo.jpg", "not really a jpeg"]],
        "in the order they were added, each with its own content");
    assert.deepEqual(plain(queue.items.map((i) => [i.name, i.status, i.illustrationId, i.message])), [
        ["a.png", "success", 11, ""],
        ["b.png", "duplicate", 3, "图库中已存在"],
        ["a.png", "success", 12, ""],
        ["photo.jpg", "failed", null, "扩展名与图片内容不符"]
    ], "two files with the same name are both imported");
    assert.deepEqual(plain(queue.summary()), { total: 4, queued: 0, uploading: 0, success: 2, duplicate: 1, failed: 1, unknown: 0 });
    assert.deepEqual(plain(queue.importedIds()), [11, 12]);
    assert.equal(queue.retryable(), 0, "an invalid file is not offered for retry");
    assert.ok(changes > 4);
});

test("folders and files over 50 MB are rejected locally without a request and are not retried", async () => {
    const { env, sent } = importEnv(() => itemResult("SUCCESS", { illustrationId: 1 }));
    const queue = env.Archive.importQueue();
    const big = { name: "huge.png", size: 50 * 1024 * 1024 + 1, type: "image/png" };
    queue.add([{ file: pic("folder", ""), directory: true }, big, pic("ok.png")]);
    await queue.run();
    assert.deepEqual(plain(queue.items.map((i) => [i.status, i.message])), [
        ["failed", "不支持导入文件夹，请选择其中的图片"], ["failed", "超过 50 MB 上限"], ["success", ""]
    ]);
    assert.equal(sent.length, 1);
    await queue.retry();
    assert.equal(sent.length, 1, "local rejections are not retried");
});

test("when every file is rejected locally the queue goes idle and still takes new files", async () => {
    const { env, sent } = importEnv(() => itemResult("SUCCESS", { illustrationId: 7 }));
    const queue = env.Archive.importQueue();
    queue.add([{ file: pic("folder", ""), directory: true }, { name: "huge.png", size: 51 * 1024 * 1024, type: "image/png" }]);
    await queue.run();
    assert.equal(queue.isRunning(), false, "nothing to send: the queue is idle, not stuck in a run");
    assert.equal(sent.length, 0);
    queue.add([pic("ok.png")]);
    await queue.run();
    assert.equal(queue.isRunning(), false);
    assert.deepEqual(plain(queue.items.map((i) => i.status)), ["failed", "failed", "success"]);
    assert.equal(sent.length, 1);
});

test("files added while paused wait without being sent; resume sends every unsent file, retry only the unknown", async () => {
    let online = false;
    const { env, sent } = importEnv((file) => (file.name === "1.png" && !online
        ? new TypeError("Failed to fetch")
        : itemResult("SUCCESS", { illustrationId: Number(file.name[0]) })));
    const queue = env.Archive.importQueue();
    queue.add([pic("1.png"), pic("2.png")]);
    await queue.run();
    assert.equal(queue.pausedReason(), "network");
    queue.add([pic("3.png")]);
    await queue.run();
    assert.equal(queue.isRunning(), false, "adding while paused does not hang the queue");
    assert.deepEqual(plain(queue.items.map((i) => i.status)), ["unknown", "queued", "queued"]);
    assert.equal(sent.length, 1, "nothing new was sent while paused");

    await queue.resume();
    assert.deepEqual(sent.map((f) => f[0].name), ["1.png", "2.png", "3.png"], "resume sends what was never sent, not the unknown one");
    assert.deepEqual(plain(queue.items.map((i) => i.status)), ["unknown", "success", "success"]);
    online = true;
    await queue.retry();
    assert.deepEqual(sent.map((f) => f[0].name), ["1.png", "2.png", "3.png", "1.png"]);
    assert.equal(queue.isRunning(), false);
});

test("a file whose reply was lost but which the server had stored comes back as a duplicate that is new to the gallery", async () => {
    let attempt = 0;
    const { env } = importEnv(() => (attempt++ === 0
        ? new TypeError("connection reset")
        : itemResult("DUPLICATE", { illustrationId: 77, errorCode: "DUPLICATE_ILLUSTRATION" })));
    const queue = env.Archive.importQueue();
    queue.add([pic("lost.png")]);
    await queue.run();
    assert.equal(queue.items[0].status, "unknown");
    assert.deepEqual(plain(queue.newIds()), []);
    await queue.retry();
    const it = queue.items[0];
    assert.equal(it.status, "duplicate");
    assert.equal(it.recovered, true);
    assert.match(it.message, /图库中已存在（之前那次其实已经导入）/);
    assert.deepEqual(plain(queue.newIds()), [77], "the list must pick it up");
    assert.deepEqual(plain(queue.importedIds()), [], "it is not counted as imported in this run");
    assert.equal(queue.summary().duplicate, 1);

    // An ordinary duplicate (never unknown) is not new to the gallery
    const plainDup = importEnv(() => itemResult("DUPLICATE", { illustrationId: 5 }));
    const q2 = plainDup.env.Archive.importQueue();
    q2.add([pic("old.png")]);
    await q2.run();
    assert.deepEqual(plain(q2.newIds()), []);
});

test("a dropped connection marks that file unknown and pauses; the rest stay unsent until resumed", async () => {
    const files = [pic("1.png"), pic("2.png"), pic("3.png"), pic("4.png")];
    let offline = true;
    const { env, sent } = importEnv((file, n) => {
        if (file.name === "2.png" && offline) return new TypeError("Failed to fetch");
        if (file.name === "2.png") return itemResult("DUPLICATE", { illustrationId: 22, errorCode: "DUPLICATE_ILLUSTRATION" });
        return itemResult("SUCCESS", { illustrationId: 20 + n });
    });
    const queue = env.Archive.importQueue();
    queue.add(files);
    await queue.run();
    assert.deepEqual(plain(queue.items.map((i) => i.status)), ["success", "unknown", "queued", "queued"]);
    assert.match(queue.items[1].message, /无法确认/);
    assert.equal(queue.pausedReason(), "network");
    assert.equal(sent.length, 2, "nothing after the failure was sent");

    await queue.resume();
    assert.deepEqual(plain(queue.items.map((i) => i.status)), ["success", "unknown", "success", "success"]);
    assert.deepEqual(sent.map((f) => f[0].name), ["1.png", "2.png", "3.png", "4.png"], "resume only sends what was never sent");

    offline = false;
    assert.equal(queue.retryable(), 1);
    await queue.retry();
    assert.deepEqual(sent.map((f) => f[0].name), ["1.png", "2.png", "3.png", "4.png", "2.png"], "retry resends only the unknown one");
    assert.equal(queue.items[1].status, "duplicate", "it had in fact been stored: the server reports it as already there");
});

test("request-level failures: 403 pauses as an expired session, 413 and 500 fail that file, an unreadable reply is unknown", async () => {
    const files = [pic("a.png"), pic("b.png"), pic("c.png"), pic("d.png")];
    const replies = [413, 500, { notTheBatchShape: true }];
    const { env, sent } = importEnv((file, n) => replies[n]);
    const queue = env.Archive.importQueue();
    queue.add(files.slice(0, 3));
    await queue.run();
    assert.deepEqual(plain(queue.items.map((i) => [i.status, i.message])), [
        ["failed", "文件太大，超出上传上限"],
        ["failed", "服务返回 500，没有导入"],
        ["unknown", "服务的回应无法读取，无法确认这张是否已导入"]
    ]);
    assert.equal(queue.pausedReason(), "network");

    const session = importEnv(() => 403);
    const q2 = session.env.Archive.importQueue();
    q2.add([files[3], pic("e.png")]);
    await q2.run();
    assert.deepEqual(plain(q2.items.map((i) => i.status)), ["failed", "queued"]);
    assert.match(q2.items[0].message, /会话已失效/);
    assert.equal(q2.pausedReason(), "session");
    assert.equal(session.sent.length, 1);
    assert.equal(sent.length, 3);
});

test("files added while a run is going join the same queue in order; success and duplicates are never resent", async () => {
    const { env, sent, maxInflight } = importEnv((file, n) => (n === 1
        ? itemResult("FAILED", { errorCode: "STORAGE_FAILED", message: "Failed to store file." })
        : itemResult(n === 2 ? "DUPLICATE" : "SUCCESS", { illustrationId: 30 + n })));
    const queue = env.Archive.importQueue();
    queue.add([pic("1.png"), pic("2.png")]);
    queue.add([pic("3.png")]);
    await queue.run();
    assert.equal(maxInflight(), 1);
    assert.deepEqual(plain(queue.items.map((i) => [i.name, i.status])), [["1.png", "success"], ["2.png", "failed"], ["3.png", "duplicate"]]);
    assert.equal(queue.items[1].message, "保存文件失败");
    assert.equal(queue.retryable(), 1, "a storage failure can be retried");
    await queue.retry();
    assert.deepEqual(sent.map((f) => f[0].name), ["1.png", "2.png", "3.png", "2.png"]);
});

test("after an import the loaded list is re-read: new works appear unfiltered, and under a filter they can be told apart", async () => {
    let imported = false;
    const old = Array.from({ length: 3 }, (_, i) => item(10 - i, [jpeg(800 + i)]));
    const fresh = item(50, [jpeg(850)]);
    const { env } = importEnv(() => { imported = true; return itemResult("SUCCESS", { illustrationId: 50 }); }, {
        [listUrl(0)]: () => pageOf(0, imported ? 4 : 3, imported ? [fresh, ...old] : old),
        [listUrl(0, "&tagId=1")]: pageOf(0, 2, old.slice(0, 2)),
        "/api/illustrations?page=0&size=1": () => pageOf(0, imported ? 4 : 3, [])
    });
    await env.Archive.setQuery({});
    const queue = env.Archive.importQueue();
    queue.add([pic("new.png")]);
    await queue.run();
    await env.Archive.refreshLoaded();
    assert.equal(env.Archive.total(), 4);
    assert.equal(env.Archive.works[0].id, 50, "the newest work leads the wall");
    assert.equal(env.Archive.listed(50), true);

    await env.Archive.setQuery({ tagIds: [1] });
    await env.Archive.refreshLoaded();
    assert.equal(env.Archive.listed(50), false, "the new work does not match the tag filter");
    assert.equal(env.Archive.total(), 2);
    assert.equal(await env.Archive.libraryCount(), 4, "the whole library count is read separately");
});

/* ---------------- single-work delete ---------------- */

/** A server-side list that shrinks when a DELETE succeeds, so later page reads see the shifted offsets. */
function deletableLibrary(count) {
    const rows = Array.from({ length: count }, (_, i) => item(count - i, [jpeg(5000 + i)]));
    const responses = {};
    for (let p = 0; p < 3; p++) responses[listUrl(p)] = () => pageOf(p, rows.length, rows.slice(p * 30, p * 30 + 30));
    rows.forEach((row) => {
        responses[`DELETE /api/illustrations/${row.id}`] = () => {
            rows.splice(rows.indexOf(row), 1);
            return NO_CONTENT;
        };
        responses[`/api/illustrations/${row.id}`] = () => (rows.includes(row) ? { id: row.id, title: row.title, assets: [] } : 404);
    });
    return { rows, responses };
}

test("204 deletes without reading a body; removing it keeps paging complete with no work skipped or repeated", async () => {
    const lib = deletableLibrary(31);
    const env = setup({ responses: lib.responses });
    await env.Archive.setQuery({});
    assert.equal(env.Archive.works.length, 30);
    assert.equal(env.Archive.total(), 31);
    const seen = [];
    env.Archive.subscribe((e) => { if (e.type === "removed") seen.push(plain(e)); });

    const result = await env.Archive.deleteWork(25);
    assert.deepEqual(plain(result), { status: "deleted", verified: false });
    assert.equal(env.requests.filter((r) => r === "/api/illustrations/25").length, 0, "a 204 needs no re-read");
    assert.equal(env.Archive.listed(25), true, "deleteWork alone does not touch the list");
    await env.Archive.removeWork(25);
    assert.deepEqual(seen, [{ type: "removed", id: 25, index: 6 }]);
    assert.equal(env.Archive.listed(25), false);
    assert.equal(env.Archive.get(25), null);
    assert.equal(env.Archive.total(), 30);
    assert.equal(env.Archive.hasMore(), true, "work #1 moved up into page 0 on the server and is still to load");

    await env.Archive.loadMore();
    const ids = env.Archive.works.map((w) => w.id);
    assert.equal(ids.length, 30);
    assert.equal(new Set(ids).size, 30, "no work twice");
    assert.deepEqual(plain(ids), lib.rows.map((r) => r.id), "the wall matches the server order exactly");
    assert.equal(env.Archive.hasMore(), false);
});

test("clicking delete twice while the first request is in flight sends one DELETE", async () => {
    const gate = held(NO_CONTENT);
    const env = setup({ responses: { [listUrl(0)]: pageOf(0, 1, [item(7, [jpeg(70)])]), "DELETE /api/illustrations/7": gate } });
    await env.Archive.setQuery({});
    const a = env.Archive.deleteWork(7);
    const b = env.Archive.deleteWork(7);
    assert.equal(env.Archive.isDeleting(7), true);
    gate.release();
    assert.equal(await a, await b, "both callers get the same result");
    assert.equal(env.requests.filter((r) => r === "DELETE /api/illustrations/7").length, 1);
    assert.equal(env.Archive.isDeleting(7), false);
});

test("deleting the last work leaves an empty, fully loaded library", async () => {
    const lib = deletableLibrary(1);
    const env = setup({ responses: lib.responses });
    await env.Archive.setQuery({});
    assert.equal((await env.Archive.deleteWork(1)).status, "deleted");
    await env.Archive.removeWork(1);
    assert.equal(env.Archive.total(), 0);
    assert.equal(env.Archive.works.length, 0);
    assert.equal(env.Archive.hasMore(), false);
});

test("404 means the work was already gone; 403 means the access guard refused it and nothing was deleted", async () => {
    const env = setup({ responses: {
        "DELETE /api/illustrations/3": 404,
        "DELETE /api/illustrations/4": 403
    } });
    assert.equal((await env.Archive.deleteWork(3)).status, "gone");
    const refused = await env.Archive.deleteWork(4);
    assert.equal(refused.status, "kept");
    assert.match(refused.message, /会话已失效/);
    assert.deepEqual(env.requests, ["DELETE /api/illustrations/3", "DELETE /api/illustrations/4"], "neither case re-reads");
});

test("a lost reply is never reported as kept or deleted until a re-read says which", async () => {
    const env = setup({ responses: {
        "DELETE /api/illustrations/1": new TypeError("Failed to fetch"),
        "/api/illustrations/1": 404,
        "DELETE /api/illustrations/2": new TypeError("Failed to fetch"),
        "/api/illustrations/2": { id: 2, assets: [] },
        "DELETE /api/illustrations/3": new TypeError("Failed to fetch"),
        "/api/illustrations/3": new TypeError("Failed to fetch"),
        "DELETE /api/illustrations/4": new TypeError("Failed to fetch"),
        "/api/illustrations/4": 500
    } });
    assert.deepEqual(plain(await env.Archive.deleteWork(1)),
        { status: "deleted", verified: true, reason: "连接中断，没有收到删除结果" }, "re-read 404: it was deleted");
    const kept = await env.Archive.deleteWork(2);
    assert.equal(kept.status, "kept");
    assert.match(kept.message, /仍在图库中，没有删除/);
    const unknown = await env.Archive.deleteWork(3);
    assert.equal(unknown.status, "unknown");
    assert.match(unknown.message, /无法确认/);
    assert.doesNotMatch(unknown.message, /没有删除/);
    assert.equal((await env.Archive.deleteWork(4)).status, "unknown", "a failing re-read is not taken as an answer");

    // Re-checking later only reads; it never sends another DELETE
    env.responses["/api/illustrations/3"] = 404;
    const before = env.requests.filter((r) => r.startsWith("DELETE")).length;
    assert.equal((await env.Archive.checkDeleted(3)).status, "deleted");
    assert.equal(env.requests.filter((r) => r.startsWith("DELETE")).length, before);
});

test("a server error is checked by re-reading: still there is kept with the reason, gone is deleted", async () => {
    const env = setup({ responses: {
        "DELETE /api/illustrations/5": 500,
        "/api/illustrations/5": { id: 5, assets: [] },
        "DELETE /api/illustrations/6": 503,
        "/api/illustrations/6": 404
    } });
    const kept = await env.Archive.deleteWork(5);
    assert.equal(kept.status, "kept");
    assert.match(kept.message, /服务返回 500/);
    assert.deepEqual(plain(await env.Archive.deleteWork(6)), { status: "deleted", verified: true, reason: "服务返回 503" });
});

test("removal waits for a page that is still loading, so a late page cannot bring the deleted work back", async () => {
    const firstPage = Array.from({ length: 30 }, (_, i) => item(60 - i, [jpeg(6000 + i)]));
    const late = held(pageOf(1, 32, [item(30, [jpeg(6100)]), item(29, [jpeg(6101)])]));
    const env = setup({ responses: { [listUrl(0)]: pageOf(0, 32, firstPage), [listUrl(1)]: late } });
    await env.Archive.setQuery({});
    const loading = env.Archive.loadMore();
    const removing = env.Archive.removeWork(29);
    await settle();
    assert.equal(env.Archive.works.length, 30, "nothing is removed while the page is still on its way");
    late.release();
    await loading;
    await removing;
    assert.equal(env.Archive.listed(29), false, "the work in the late page is removed after it lands");
    assert.equal(env.Archive.works.length, 31);
    assert.equal(env.Archive.total(), 31);
});

test("a work that an edit had already moved out of the filtered results is removed without changing the count", async () => {
    const env = setup({ responses: {
        [listUrl(0, "&tagId=1")]: pageOf(0, 1, [item(8, [jpeg(80)])])
    } });
    await env.Archive.setQuery({ tagIds: [1] });
    env.responses[listUrl(0, "&tagId=1")] = pageOf(0, 0, []);
    await env.Archive.refreshLoaded();
    assert.notEqual(env.Archive.get(8), null, "still readable for the open viewer");
    const result = await env.Archive.removeWork(8);
    assert.equal(result.index, -1);
    assert.equal(env.Archive.get(8), null);
    assert.equal(env.Archive.total(), 0);
});
