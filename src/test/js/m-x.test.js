const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const readStatic = (...parts) => readFileSync(path.resolve(__dirname, "../../main/resources/static", ...parts), "utf8");
const modelSource = readStatic("c", "c-x-model.js");
const mobileXJs = readStatic("m", "x.js");
const mobileXHtml = readStatic("m", "x.html");
const mobileIndexHtml = readStatic("m", "index.html");
const mobileAppJs = readStatic("m", "app.js");
const mobileCss = readStatic("m", "app.css");

class Node {
    constructor(id, tag = "div", htmlSource = "") {
        this.id = id;
        this.tag = tag;
        this.parent = null;
        this.attributes = new Map();
        this.listeners = new Map();
        this.className = "";
        this.children = [];
        this.dataset = {};
        this.style = {};
        this.hidden = !!(id && htmlSource.includes(`id="${id}"`) && new RegExp(`id="${id}"[^>]*\\shidden`).test(htmlSource));
        this.disabled = false;
        this.text = "";
        this.classList = {
            toggle: (name, force) => {
                const parts = new Set(this.className.split(/\s+/).filter(Boolean));
                if (force === undefined ? !parts.has(name) : force) parts.add(name);
                else parts.delete(name);
                this.className = [...parts].join(" ");
            },
            add: (...names) => names.forEach((n) => this.classList.toggle(n, true)),
            remove: (...names) => names.forEach((n) => this.classList.toggle(n, false))
        };
    }
    get textContent() { return this.text; }
    set textContent(value) { this.text = value == null ? "" : String(value); this.children = []; }
    get parentElement() { return this.parent; }
    get firstChild() { return this.children[0] || null; }
    addEventListener(type, fn) { this.listeners.set(type, fn); }
    get src() { return this.attributes.get("src"); }
    set src(url) { this.attributes.set("src", url); }
    setAttribute(name, value) { this.attributes.set(name, String(value)); }
    getAttribute(name) { return this.attributes.get(name); }
    removeAttribute(name) { this.attributes.delete(name); }
    querySelector(selector) {
        if (selector.startsWith(".")) {
            const cls = selector.slice(1);
            for (const child of this.children) {
                if (child.className.split(/\s+/).includes(cls)) return child;
                const found = child.querySelector(selector);
                if (found) return found;
            }
            return null;
        }
        return this.querySelectorAll(selector)[0] || null;
    }
    querySelectorAll(tag) {
        return this.children.flatMap((child) => [...(child.tag === tag ? [child] : []), ...child.querySelectorAll(tag)]);
    }
    replaceChildren(...children) {
        this.children.forEach((c) => { c.parent = null; });
        this.children = [];
        children.forEach((c) => this.appendChild(c));
    }
    appendChild(child) { child.parent = this; this.children.push(child); return child; }
    pause() { this.paused = true; }
    load() { this.loads = (this.loads || 0) + 1; }
    focus() { }
    click() {
        const fn = this.listeners.get("click");
        if (fn) fn({ stopPropagation() { } });
    }
}

const settle = async () => { for (let i = 0; i < 6; i++) await new Promise((r) => setImmediate(r)); };

function itemOf(id, mediaType = "photo", extra = {}) {
    return {
        id,
        xPostId: String(1000 + id),
        authorUsername: "artist" + id,
        authorDisplayName: "画师 " + id,
        postText: "作品 " + id,
        media: [{ mediaKey: "m" + id, sortOrder: 0, mediaType, sourceUrl: mediaType === "animated_gif" ? "https://video.twimg.com/a.mp4" : "https://pbs.twimg.com/media/X" + id + ".png", width: 800, height: 600 }],
        ...extra
    };
}

function setupMobileX({ items = [], skipped = [], answer = () => [200, {}] } = {}) {
    const nodes = new Map();
    const element = (id) => {
        if (!nodes.has(id)) nodes.set(id, new Node(id, "div", mobileXHtml));
        return nodes.get(id);
    };
    const app = { items, skipped, requests: [], redirects: [], storage: new Map(), timers: [] };
    const reply = (status, body) => ({ ok: status >= 200 && status < 400, status, json: async () => body });
    const fetch = async (url, options = {}) => {
        app.requests.push({ url, method: options.method || "GET", body: options.body ? JSON.parse(options.body) : null });
        if (url.startsWith("/api/x-import/inbox/skipped?")) {
            return reply(200, { items: app.skipped, totalItems: app.skipped.length, totalPages: app.skipped.length ? 1 : 0 });
        }
        if (url.startsWith("/api/x-import/inbox?")) {
            return reply(200, { items: app.items, totalItems: app.items.length, totalPages: app.items.length ? 1 : 0 });
        }
        const [status, body] = await answer(url, options);
        return reply(status, body);
    };
    const window = {
        localStorage: {
            getItem: (k) => app.storage.get(k) || null,
            setItem: (k, v) => app.storage.set(k, String(v))
        },
        addEventListener() { }
    };
    const document = {
        getElementById: element,
        createElement: (tag) => new Node(null, tag, mobileXHtml),
        createDocumentFragment: () => new Node(null, "fragment", mobileXHtml),
        addEventListener() { }
    };
    const context = {
        window,
        document,
        fetch,
        URLSearchParams,
        Date,
        Map,
        Set,
        location: { replace: (url) => app.redirects.push(url) },
        setTimeout: (fn, ms) => { const h = { fn, ms }; app.timers.push(h); return h; },
        clearTimeout: () => { }
    };
    vm.runInNewContext(modelSource, context);
    context.XInboxModel = context.window.XInboxModel;
    vm.runInNewContext(mobileXJs, context);
    app.element = element;
    app.gridCards = () => element("mx-grid").children;
    return app;
}

test("mobile X page never sets no-referrer, never hits twimg directly, and keeps WeChat long-press img behavior", () => {
    assert.doesNotMatch(mobileXHtml, /no-referrer/i);
    assert.doesNotMatch(mobileXJs, /pbs\.twimg\.com|video\.twimg\.com/);
    assert.match(mobileCss, /\.mx-stage img[\s\S]*?-webkit-touch-callout:\s*default/);
    assert.doesNotMatch(mobileCss, /\.mx-stage img[\s\S]*?-webkit-touch-callout:\s*none/);
});

test("mobile gallery index shows X inbox nav only when desktop capability is enabled", async () => {
    for (const enabled of [false, true]) {
        const nodes = new Map();
        const el = (id) => { if (!nodes.has(id)) nodes.set(id, new Node(id, "div", mobileIndexHtml)); return nodes.get(id); };
        const context = {
            window: { addEventListener() { } },
            document: { getElementById: el, createElement: (t) => new Node(null, t), createDocumentFragment: () => new Node(null, "f"), addEventListener() { } },
            fetch: async (url) => url === "/api/m/capabilities"
                ? { ok: true, status: 200, json: async () => ({ xInboxEnabled: enabled }) }
                : { ok: true, status: 200, json: async () => ({ items: [], totalElements: 0, totalPages: 0 }) },
            URLSearchParams,
            location: { replace() { } },
            setTimeout,
            clearTimeout
        };
        vm.runInNewContext(mobileAppJs, context);
        await settle();
        assert.equal(el("m-nav").hidden, !enabled);
    }
});

test("cards proxy small previews, viewer proxies plain full-resolution img, and retries failed proxy image", async () => {
    const app = setupMobileX({ items: [itemOf(10, "photo", {
        media: [
            { mediaKey: "m10b", sortOrder: 1, mediaType: "photo", sourceUrl: "https://pbs.twimg.com/media/B.jpg" },
            { mediaKey: "m10a", sortOrder: 0, mediaType: "photo", sourceUrl: "https://pbs.twimg.com/media/A.png" }
        ]
    })] });
    await settle();
    const card = app.gridCards()[0];
    const cardImg = card.querySelector("img");
    assert.equal(cardImg.src, "/api/x-import/inbox/10/media/m10a/content?size=small");

    // Tap card opens viewer with plain <img src="...?size=4096x4096"> (never wrapped in <a>)
    card.querySelector(".mx-card-media-btn").click();
    assert.equal(app.element("mx-preview").hidden, false);
    assert.equal(app.element("mx-side-open").href, "https://x.com/artist10/status/1010");
    const stageImg = app.element("mx-stage").querySelector("img");
    assert.equal(stageImg.src, "/api/x-import/inbox/10/media/m10a/content?size=4096x4096");
    assert.equal(stageImg.parent.tag, "figure");
    assert.equal(app.element("mx-media-switcher").hidden, false);

    // Simulate proxy image error -> shows retry button -> click retries with fresh query param
    stageImg.listeners.get("error")();
    const failBox = app.element("mx-stage").querySelector(".mx-img-fail");
    assert.ok(failBox);
    failBox.querySelector("button").click();
    const retriedImg = app.element("mx-stage").querySelector("img");
    assert.match(retriedImg.src, /^\/api\/x-import\/inbox\/10\/media\/m10a\/content\?size=4096x4096&retry=/);

    // Switch to second image cleanly
    app.element("mx-media-next").click();
    assert.equal(app.element("mx-stage").querySelector("img").src, "/api/x-import/inbox/10/media/m10b/content?size=4096x4096");
});

test("viewer skip advances, archive failure keeps item for retry, and animated video is released", async () => {
    let failArchive = true;
    const app = setupMobileX({
        items: [itemOf(1, "animated_gif"), itemOf(2, "photo")],
        answer: (url, options) => {
            if (url === "/api/x-import/inbox/import") {
                const { itemIds } = JSON.parse(options.body);
                if (failArchive) {
                    return [200, { total: 1, successCount: 0, duplicateCount: 0, failureCount: 1,
                        items: [{ itemId: itemIds[0], status: "FAILED", reason: "CDN request failed with HTTP 500" }] }];
                }
                app.items = app.items.filter((i) => i.id !== itemIds[0]);
                return [200, { total: 1, successCount: 1, duplicateCount: 0, failureCount: 0,
                    items: [{ itemId: itemIds[0], status: "SUCCESS", illustrationId: 99 }] }];
            }
            if (url === "/api/x-import/inbox/skip") {
                const { itemIds } = JSON.parse(options.body);
                app.items = app.items.filter((i) => !itemIds.includes(i.id));
                return [200, { requestedCount: itemIds.length, skippedCount: itemIds.length, skippedIds: itemIds, skipToken: "tok1" }];
            }
            if (url === "/api/x-import/inbox/unskip") {
                return [200, { requestedCount: 1, restoredCount: 1 }];
            }
            return [200, {}];
        }
    });
    await settle();

    app.gridCards()[0].querySelector(".mx-card-media-btn").click();
    const video = app.element("mx-stage").querySelector("video");
    assert.equal(video.src, "/api/x-import/inbox/1/media/m1/content");

    // Failed archive keeps viewer open on item 1 with retry label
    app.element("mx-side-archive").click();
    await settle();
    assert.equal(app.element("mx-preview").hidden, false);
    assert.equal(app.element("mx-side-archive").textContent, "重试归档");
    assert.equal(app.element("mx-side-problem").hidden, false);

    // Retry succeeds -> advances to item 2 and releases video
    failArchive = false;
    app.element("mx-side-archive").click();
    await settle();
    assert.equal(video.paused, true);
    assert.equal(app.element("mx-side-handle").textContent, "@artist2");

    // Skip item 2 -> toast offers 10s undo -> undo sends skipToken
    app.element("mx-side-skip").click();
    await settle();
    assert.equal(app.element("mx-preview").hidden, true);
    const toastBar = app.element("mx-toast");
    assert.equal(toastBar.hidden, false);
    const undoBtn = toastBar.querySelector("button");
    assert.ok(undoBtn);
    assert.equal(app.timers.at(-1).ms, 10000);
    undoBtn.click();
    await settle();
    const unskipReq = app.requests.find((r) => r.url === "/api/x-import/inbox/unskip");
    assert.deepEqual(unskipReq.body, { itemIds: [2], skipToken: "tok1" });
});

test("select page, archive selected and skip rest, recently skipped restore, and 403 disabled/revoked handling", async () => {
    const app = setupMobileX({
        items: [itemOf(1), itemOf(2), itemOf(3)],
        skipped: [itemOf(9)],
        answer: (url, options) => {
            if (url === "/api/x-import/inbox/skip") {
                const { itemIds } = JSON.parse(options.body);
                app.items = app.items.filter((i) => !itemIds.includes(i.id));
                return [200, { requestedCount: itemIds.length, skippedCount: itemIds.length, skippedIds: itemIds, skipToken: "tok2" }];
            }
            if (url === "/api/x-import/inbox/import") {
                const { itemIds } = JSON.parse(options.body);
                app.items = app.items.filter((i) => !itemIds.includes(i.id));
                return [200, { total: 1, successCount: 1, duplicateCount: 0, failureCount: 0,
                    items: [{ itemId: itemIds[0], status: "SUCCESS", illustrationId: 88 }] }];
            }
            if (url === "/api/x-import/inbox/unskip") {
                app.skipped = [];
                return [200, { requestedCount: 1, restoredCount: 1 }];
            }
            return [200, {}];
        }
    });
    await settle();

    // Select all then clear, then select item 1 only -> archive selected and skip rest (2, 3)
    app.element("mx-select-page").click();
    assert.equal(app.element("mx-selected").textContent, "已选 3 件");
    assert.equal(app.element("mx-archive-rest").hidden, true);
    app.element("mx-clear").click();
    app.gridCards()[0].querySelector(".mx-check").click();
    assert.equal(app.element("mx-archive-rest").textContent, "归档并跳过其余 2");

    app.element("mx-archive-rest").click();
    await settle();
    assert.equal(app.element("mx-confirm").hidden, false);
    app.element("mx-confirm-ok").click();
    await settle();

    const skipCall = app.requests.find((r) => r.url === "/api/x-import/inbox/skip");
    const importCall = app.requests.find((r) => r.url === "/api/x-import/inbox/import");
    assert.deepEqual(skipCall.body, { itemIds: [2, 3] });
    assert.deepEqual(importCall.body, { itemIds: [1] });

    // Switch to skipped view and restore item 9
    app.element("mx-view-skipped").click();
    await settle();
    app.gridCards()[0].querySelector(".mx-check").click();
    app.element("mx-restore").click();
    await settle();
    assert.deepEqual(app.requests.at(-2).body, { itemIds: [9] }); // unskip then reload skipped

    // MOBILE_X_DISABLED shows disabled panel without redirecting; revoked redirects to pairing
    const disabledApp = setupMobileX({ items: [itemOf(1)], answer: () => [403, { code: "MOBILE_X_DISABLED", message: "off" }] });
    await settle();
    disabledApp.gridCards()[0].querySelector(".mx-check").click();
    disabledApp.element("mx-skip").click();
    await settle();
    assert.equal(disabledApp.element("mx-disabled").hidden, false);
    assert.equal(disabledApp.redirects.length, 0);

    const revokedApp = setupMobileX({ items: [itemOf(1)], answer: () => [403, { code: "MOBILE_FORBIDDEN", message: "revoked" }] });
    await settle();
    revokedApp.gridCards()[0].querySelector(".mx-check").click();
    revokedApp.element("mx-skip").click();
    await settle();
    assert.deepEqual(revokedApp.redirects, ["/m/pair.html?e=3"]);
});
