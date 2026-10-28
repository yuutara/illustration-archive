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
        fetch: async (url, init = {}) => {
            requests.push(url);
            calls.push({ url, signal: init.signal });
            let reply = responses[url];
            if (typeof reply === "function") reply = await reply();
            if (init.signal?.aborted && !ignoreAbort) throw new DOMException("aborted", "AbortError");
            if (reply instanceof Error) throw reply;
            if (typeof reply === "number") return { ok: false, status: reply, json: async () => ({}) };
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
