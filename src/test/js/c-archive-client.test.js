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
            calls.push({ url, method, signal: init.signal, body: init.body ? JSON.parse(init.body) : undefined });
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
