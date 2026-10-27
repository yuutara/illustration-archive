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
 * number, or an Error to reject with; `brokenImages` lists image URLs that fail to load.
 */
function setup({ responses = {}, brokenImages = [] } = {}) {
    const requests = [];
    class FakeImage {
        set src(url) {
            this._src = url;
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
        fetch: async (url) => {
            requests.push(url);
            const reply = responses[url];
            if (reply instanceof Error) throw reply;
            if (typeof reply === "number") return { ok: false, status: reply, json: async () => ({}) };
            if (!reply) return { ok: false, status: 404, json: async () => ({}) };
            return { ok: true, status: 200, json: async () => reply };
        },
        setImmediate,
        console
    };
    vm.runInNewContext(source, context);
    const events = [];
    window.Archive.subscribe((event) => events.push(event.type));
    return { Archive: window.Archive, requests, events, responses };
}

const listUrl = (page) => `/api/illustrations?page=${page}&size=30`;

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
