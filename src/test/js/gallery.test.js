const assert = require("node:assert/strict");
const test = require("node:test");


const { browser } = require("./helpers/browser");

async function cardFor(item, includeEnvironment = false) {
    const env = browser({ url: "http://localhost/" });
    const elements = env.elements;
    env.sandbox.fetchImpl = async () => ({ ok: true, json: async () => ({
        page: 0, totalPages: 1, totalElements: 1, items: [item]
    }) });
    env.run("app.js");
    await new Promise(setImmediate);
    const card = elements.get("gallery").children[0];
    return includeEnvironment ? { card, env } : card;
}

test("single asset uses thumbnail and has no preview controls or position", async () => {
    const card = await cardFor({ id: 7, title: "One", coverAssetId: 11, coverMimeType: "image/jpeg",
        assets: [{ id: 11, mimeType: "image/jpeg", sortOrder: 0 }] });
    const imageContainer = card.children[0];
    assert.equal(imageContainer.children.length, 1);
    assert.equal(new URL(imageContainer.children[0].href, "http://localhost").searchParams.get("asset"), "11");
    assert.equal(imageContainer.children[0].children[0].src, "/api/assets/11/thumbnail");
});

test("Gallery opens current Asset and viewer change updates card without another API request", async () => {
    const item = { id: 7, title: "Three", assets: [11, 22, 33].map((id, sortOrder) => ({ id, sortOrder, mimeType: "image/png" })) };
    const { card, env } = await cardFor(item, true);
    env.sandbox.fetchImpl = () => { throw new Error("Viewer must not fetch Detail"); };
    const [imageLink, , next, position] = card.children[0].children;
    next.dispatch("click"); assert.equal(imageLink.dispatch("click").defaultPrevented, true);
    assert.equal(env.find("image-viewer-image").src, "/api/assets/22/content");
    env.find("image-viewer-toolbar").children[1].dispatch("click");
    assert.equal(position.textContent, "3 / 3");
    assert.equal(imageLink.children[0].src, "/api/assets/33/thumbnail");
    assert.equal(new URL(card.children[1].href, "http://localhost").searchParams.get("asset"), "33");
    env.find("image-viewer-header").children[2].dispatch("click"); await new Promise(setImmediate);
    assert.equal(position.textContent, "3 / 3");
    assert.equal(env.document.activeElement, imageLink);
    const refreshed = browser({ url: env.window.location.href, entries: env.entries(), storage: env.storage });
    refreshed.sandbox.fetchImpl = async () => ({ ok: true, json: async () => ({ page: 0, totalPages: 1, items: [item] }) });
    refreshed.run("app.js"); await new Promise(setImmediate);
    assert.equal(refreshed.elements.get("gallery").children[0].children[0].children[3].textContent, "3 / 3");
});

test("Gallery modified image click retains Detail link; out-of-range page reloads last valid page", async () => {
    const { card, env } = await cardFor({ id: 7, assets: [{ id: 11, mimeType: "image/gif", sortOrder: 0 }] }, true);
    assert.equal(card.children[0].children[0].dispatch("click", { ctrlKey: true }).defaultPrevented, false);
    assert.equal(env.find("image-viewer"), undefined);
    const stale = browser({ url: "http://localhost/?page=7" }); const calls = [];
    stale.sandbox.fetchImpl = async url => {
        calls.push(url); const page = calls.length === 1 ? 7 : 1;
        return { ok: true, json: async () => ({ page, totalPages: 2, totalElements: 25, items: [] }) };
    };
    stale.run("app.js"); await new Promise(setImmediate);
    assert.deepEqual(calls, ["/api/illustrations?page=7&size=24", "/api/illustrations?page=1&size=24"]);
    assert.equal(stale.elements.get("page-info").textContent, "第 2 / 2 页");
});

test("three assets sort by sortOrder and wrap in both directions without navigation", async () => {
    const card = await cardFor({ id: 7, title: "Three", coverAssetId: 11, coverMimeType: "image/jpeg",
        assets: [
            { id: 33, mimeType: "image/gif", sortOrder: 2 },
            { id: 11, mimeType: "image/jpeg", sortOrder: 0 },
            { id: 22, mimeType: "image/png", sortOrder: 1 }
        ] });
    const [imageLink, previous, next, position] = card.children[0].children;
    const image = imageLink.children[0];
    assert.equal(image.src, "/api/assets/11/thumbnail");
    assert.equal(position.textContent, "1 / 3");
    assert.equal(previous.disabled, false);
    assert.equal(next.disabled, false);

    const click = next.dispatch("click");
    assert.equal(click.defaultPrevented, true);
    assert.equal(click.propagationStopped, true);
    assert.equal(new URL(imageLink.href, "http://localhost").searchParams.get("asset"), "22");
    assert.equal(image.src, "/api/assets/22/thumbnail");
    assert.equal(position.textContent, "2 / 3");
    next.dispatch("click");
    assert.equal(image.src, "/api/assets/33/content");
    assert.equal(position.textContent, "3 / 3");
    assert.equal(next.disabled, false);
    next.dispatch("click");
    assert.equal(image.src, "/api/assets/11/thumbnail");
    assert.equal(position.textContent, "1 / 3");
    const previousClick = previous.dispatch("click");
    assert.equal(previousClick.defaultPrevented, true);
    assert.equal(previousClick.propagationStopped, true);
    assert.equal(image.src, "/api/assets/33/content");
    assert.equal(position.textContent, "3 / 3");
    previous.dispatch("click");
    assert.equal(image.src, "/api/assets/22/thumbnail");
    assert.equal(position.textContent, "2 / 3");
    previous.dispatch("click");
    assert.equal(image.src, "/api/assets/11/thumbnail");
    assert.equal(position.textContent, "1 / 3");
    assert.equal(previous.disabled, false);
    assert.equal(next.disabled, false);
});
