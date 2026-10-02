const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const test = require("node:test");

const staticDir = path.resolve(__dirname, "../../main/resources/static");

const { browser } = require("./helpers/browser");

function inboxItem(id) {
    return { id, xPostId: `post-${id}`, authorDisplayName: `Artist ${id}`,
        authorUsername: `artist${id}`, postText: "", media: [] };
}

function inboxPage(items, page = 0, totalItems = items.length, size = 24) {
    return { items, page, size, totalItems, totalPages: Math.ceil(totalItems / size) };
}

async function page(responses) {
    const env = browser({ url: "http://localhost/x-import.html" });
    const elements = env.elements;
    const calls = [];
    const fetch = async (url, options) => {
        calls.push({ url, options });
        const response = responses.shift();
        assert.ok(response, `Unexpected request: ${url}`);
        assert.equal(url, response.url);
        return { ok: true, json: async () => response.body };
    };
    env.sandbox.fetchImpl = fetch;
    env.run("x-import.js");
    await new Promise(setImmediate);
    return { elements, calls, env };
}

function select(elements, ...ids) {
    const inputs = elements.get("inbox-list").querySelectorAll("input[type=checkbox]");
    inputs.forEach(input => { input.checked = ids.includes(Number(input.value)); });
    inputs[0]?.dispatch("change");
}

function resultRows(elements) {
    return elements.get("import-results-list").children;
}

test("page initialization only reads local Inbox; one success links to Detail after refresh", async () => {
    const { elements, calls } = await page([
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1)]) },
        { url: "/api/x-import/inbox/import", body: { total: 1, successCount: 1,
            duplicateCount: 0, failureCount: 0,
            items: [{ itemId: 1, status: "SUCCESS", illustrationId: 42, reason: null }] } },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([]) }
    ]);
    assert.deepEqual(calls.map(call => call.url), ["/api/x-import/inbox?page=0&size=24"]);
    select(elements, 1);
    await elements.get("import-button").dispatch("click");
    assert.deepEqual(calls.map(call => call.url), [
        "/api/x-import/inbox?page=0&size=24", "/api/x-import/inbox/import", "/api/x-import/inbox?page=0&size=24"
    ]);
    assert.deepEqual(JSON.parse(calls[1].options.body), { itemIds: [1] });
    assert.equal(elements.get("import-results").hidden, false);
    assert.match(elements.get("import-results-summary").textContent, /共 1 项：成功 1，重复 0，失败 0/);
    assert.match(resultRows(elements)[0].children[0].textContent, /post-1/);
    assert.equal(new URL(resultRows(elements)[0].children[2].href, "http://localhost").searchParams.get("id"), "42");
    assert.equal(elements.get("inbox-list").children.length, 0);
    assert.equal(resultRows(elements).length, 1);
});

test("Sync latest uses recent endpoint and explains that the next click restarts at latest", async () => {
    const { elements, calls } = await page([
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([]) },
        { url: "/api/x-import/sync/recent?maxResults=5&maxPages=3", body: {
            pagesFetched: 3, fetchedCount: 15, newCount: 2, existingCount: 13,
            pendingCount: 2, stoppedByMaxPages: true, stoppedByInvalidToken: false
        } },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([]) }
    ]);

    await elements.get("sync-button").dispatch("click");

    assert.equal(calls[1].options.method, "POST");
    assert.deepEqual(calls.map(call => call.url), [
        "/api/x-import/inbox?page=0&size=24", "/api/x-import/sync/recent?maxResults=5&maxPages=3", "/api/x-import/inbox?page=0&size=24"
    ]);
    assert.match(elements.get("inbox-message").textContent, /下次点击仍从最新 Likes 开始/);
});

test("partial batch results stay paired with posts and failed items remain selectable", async () => {
    const { elements, calls } = await page([
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1), inboxItem(2), inboxItem(3)]) },
        { url: "/api/x-import/inbox/import", body: { total: 3, successCount: 1,
            duplicateCount: 1, failureCount: 1, items: [
                { itemId: 3, status: "FAILED", illustrationId: null, reason: "download failed" },
                { itemId: 1, status: "SUCCESS", illustrationId: 21, reason: null },
                { itemId: 2, status: "DUPLICATE", illustrationId: null, reason: "same photo" }
            ] } },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(2), inboxItem(3)]) },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(2), inboxItem(3)]) },
        { url: "/api/x-import/inbox/import", body: { total: 1, successCount: 1,
            duplicateCount: 0, failureCount: 0,
            items: [{ itemId: 3, status: "SUCCESS", illustrationId: 33, reason: null }] } },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(2)]) }
    ]);
    select(elements, 1, 2, 3);
    await elements.get("import-button").dispatch("click");
    const rows = resultRows(elements);
    assert.equal(rows.length, 3);
    assert.match(rows[0].children[0].textContent, /post-3/);
    assert.match(rows[0].children[1].textContent, /导入失败/);
    assert.equal(rows[0].children[2].textContent, "download failed");
    assert.match(rows[1].children[0].textContent, /post-1/);
    assert.equal(new URL(rows[1].children[2].href, "http://localhost").searchParams.get("id"), "21");
    assert.match(rows[2].children[0].textContent, /post-2/);
    assert.match(rows[2].children[1].textContent, /未导入/);
    assert.equal(rows[2].children[2].textContent, "same photo");
    assert.equal(elements.get("inbox-list").children.length, 2);
    select(elements, 3);
    assert.equal(elements.get("import-button").disabled, false);
    assert.equal(elements.get("skip-button").disabled, false);
    await elements.get("inbox-retry-button").dispatch("click");
    assert.equal(resultRows(elements).length, 3);
    assert.equal(elements.get("import-results").hidden, false);
    select(elements, 3);
    await elements.get("import-button").dispatch("click");
    assert.deepEqual(JSON.parse(calls[4].options.body), { itemIds: [3] });
    assert.equal(new URL(resultRows(elements)[0].children[2].href, "http://localhost").searchParams.get("id"), "33");
    assert.equal(elements.get("inbox-list").children.length, 1);
    assert.equal(calls.filter(call => call.url.includes("/sync/")).length, 0);
});

test("skip reports actual transition count and refreshes pending items", async () => {
    const { elements, calls } = await page([
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1), inboxItem(2)]) },
        { url: "/api/x-import/inbox/skip", body: { requestedCount: 2, skippedCount: 1 } },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(2)]) }
    ]);
    select(elements, 1, 2);
    await elements.get("skip-button").dispatch("click");
    assert.equal(calls[1].options.method, "PATCH");
    assert.deepEqual(JSON.parse(calls[1].options.body), { itemIds: [1, 2] });
    assert.match(elements.get("inbox-message").textContent, /实际跳过 1 \/ 2/);
    assert.match(elements.get("inbox-message").textContent, /其余项目已处理或不存在/);
    assert.equal(elements.get("inbox-list").children.length, 1);
    assert.equal(elements.get("inbox-list").querySelectorAll("input[type=checkbox]")[0].value, "2");
});

test("Previous and Next clear selection; Select Page submits only visible IDs", async () => {
    const { elements, calls } = await page([
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1), inboxItem(2)], 0, 26) },
        { url: "/api/x-import/inbox?page=1&size=24", body: inboxPage([inboxItem(25), inboxItem(26)], 1, 26) },
        { url: "/api/x-import/inbox/skip", body: { requestedCount: 2, skippedCount: 0 } },
        { url: "/api/x-import/inbox?page=1&size=24", body: inboxPage([inboxItem(25), inboxItem(26)], 1, 26) },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1), inboxItem(2)], 0, 26) }
    ]);
    assert.equal(elements.get("inbox-page-status").textContent, "Page 1 / 2");
    select(elements, 1);
    await elements.get("inbox-next").dispatch("click");
    assert.equal(elements.get("inbox-page-status").textContent, "Page 2 / 2");
    assert.equal(elements.get("import-button").disabled, true);
    assert.equal(elements.get("inbox-list").querySelectorAll("input[type=checkbox]:checked").length, 0);
    await elements.get("select-all-button").dispatch("click");
    await elements.get("skip-button").dispatch("click");
    assert.deepEqual(JSON.parse(calls[2].options.body), { itemIds: [25, 26] });
    await elements.get("inbox-previous").dispatch("click");
    assert.equal(elements.get("inbox-page-status").textContent, "Page 1 / 2");
    assert.equal(elements.get("skip-button").disabled, true);
});

test("Sync returns to first page and shows newly discovered item", async () => {
    const { elements } = await page([
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1)], 0, 25) },
        { url: "/api/x-import/inbox?page=1&size=24", body: inboxPage([inboxItem(25)], 1, 25) },
        { url: "/api/x-import/sync/recent?maxResults=5&maxPages=3", body: {
            pagesFetched: 1, fetchedCount: 1, newCount: 1, existingCount: 0,
            pendingCount: 1, stoppedByMaxPages: false, stoppedByInvalidToken: false
        } },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(99), inboxItem(1)], 0, 26) }
    ]);
    await elements.get("inbox-next").dispatch("click");
    select(elements, 25);
    await elements.get("sync-button").dispatch("click");
    assert.equal(elements.get("inbox-page-status").textContent, "Page 1 / 2");
    assert.equal(elements.get("inbox-list").querySelectorAll("input[type=checkbox]")[0].value, "99");
    assert.equal(elements.get("import-button").disabled, true);
});

test("Import falls back from an emptied last page while preserving results", async () => {
    const { elements, calls } = await page([
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1)], 0, 25) },
        { url: "/api/x-import/inbox?page=1&size=24", body: inboxPage([inboxItem(25)], 1, 25) },
        { url: "/api/x-import/inbox/import", body: { total: 1, successCount: 1,
            duplicateCount: 0, failureCount: 0, items: [
                { itemId: 25, status: "SUCCESS", illustrationId: 42, reason: null }
            ] } },
        { url: "/api/x-import/inbox?page=1&size=24", body: inboxPage([], 1, 24) },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1)], 0, 24) }
    ]);
    await elements.get("inbox-next").dispatch("click");
    select(elements, 25);
    await elements.get("import-button").dispatch("click");
    assert.equal(elements.get("inbox-page-status").textContent, "Page 1 / 1");
    assert.equal(new URL(resultRows(elements)[0].children[2].href, "http://localhost").searchParams.get("id"), "42");
    assert.equal(calls[4].url, "/api/x-import/inbox?page=0&size=24");
});

test("Skip falls back from an emptied last page", async () => {
    const { elements } = await page([
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1)], 0, 25) },
        { url: "/api/x-import/inbox?page=1&size=24", body: inboxPage([inboxItem(26)], 1, 25) },
        { url: "/api/x-import/inbox/skip", body: { requestedCount: 1, skippedCount: 1 } },
        { url: "/api/x-import/inbox?page=1&size=24", body: inboxPage([], 1, 24) },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1)], 0, 24) }
    ]);
    await elements.get("inbox-next").dispatch("click");
    select(elements, 26);
    await elements.get("skip-button").dispatch("click");
    assert.equal(elements.get("inbox-page-status").textContent, "Page 1 / 1");
});

test("result region and Gallery link are present in the page", () => {
    const html = readFileSync(path.join(staticDir, "x-import.html"), "utf8");
    assert.match(html, /id="import-results"/);
    assert.match(html, /id="import-results-list"/);
    assert.match(html, /href="\/">返回 Gallery/);
});

test("Inbox clicked media opens in order, retains visible selection and never sends write requests", async () => {
    const item = { ...inboxItem(1), media: [3, 1, 2].map(number => ({ mediaKey: `media-${number}`, sortOrder: number,
        photoUrl: `https://pbs.twimg.com/media/test-${number}.jpg` })) };
    const { elements, calls, env } = await page([
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([item]) }
    ]);
    select(elements, 1);
    const buttons = env.document.querySelectorAll(".image-open-button");
    buttons[1].dispatch("click");
    assert.equal(env.find("image-viewer-image").src, "https://pbs.twimg.com/media/test-2.jpg");
    assert.equal(env.find("image-viewer-position").textContent, "2 / 3");
    env.find("image-viewer-header").children[2].dispatch("click"); await new Promise(setImmediate);
    assert.equal(elements.get("inbox-list").querySelectorAll("input[type=checkbox]:checked").length, 1);
    assert.equal(elements.get("import-button").disabled, false);
    assert.deepEqual(calls.map(call => call.url), ["/api/x-import/inbox?page=0&size=24"]);
});
