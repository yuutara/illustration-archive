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
        if (response.wait) await response.wait;
        if (response.error) throw response.error;
        return { ok: response.ok !== false, status: response.status || 200, json: async () => response.body };
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
    assert.doesNotMatch(elements.get("inbox-message").textContent, /已处理或不存在|项目 2.*失败/);
    assert.equal(resultRows(elements).length, 1);
    assert.match(resultRows(elements)[0].textContent, /仅返回实际处理数量/);
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
    env.viewerButton("关闭图片查看器").dispatch("click"); await new Promise(setImmediate);
    assert.equal(elements.get("inbox-list").querySelectorAll("input[type=checkbox]:checked").length, 1);
    assert.equal(elements.get("import-button").disabled, false);
    assert.deepEqual(calls.map(call => call.url), ["/api/x-import/inbox?page=0&size=24"]);
});

function deferred() {
    let resolve;
    const promise = new Promise(done => { resolve = done; });
    return { promise, resolve };
}

function archiveResult(...outcomes) {
    const items = outcomes.map(([itemId, status, reason]) => ({ itemId, status, reason,
        illustrationId: status === "SUCCESS" ? 100 + itemId : null }));
    return { items, total: items.length, successCount: items.filter(item => item.status === "SUCCESS").length,
        duplicateCount: items.filter(item => item.status === "DUPLICATE").length,
        failureCount: items.filter(item => item.status === "FAILED").length };
}

test("card background toggles whole-card selection; interactive targets and selected text do not", async () => {
    const { elements, env } = await page([{ url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1)]) }]);
    const card = elements.get("inbox-list").children[0];
    const content = card.children[1];
    card.dispatch("click", { target: content });
    assert.equal(card.classList.contains("is-selected"), true);
    assert.equal(elements.get("inbox-selected-count").textContent, "本页已选 1 项");
    for (const tag of ["a", "button", "input", "label", "select", "textarea", "summary", "details", "p", "time"]) {
        const target = new env.Element(tag); content.appendChild(target);
        card.dispatch("click", { target });
        assert.equal(card.classList.contains("is-selected"), true, tag);
    }
    env.window.getSelection = () => ({ isCollapsed: false });
    card.dispatch("click", { target: content });
    assert.equal(card.classList.contains("is-selected"), true);
    env.window.getSelection = () => ({ isCollapsed: true });
    card.dispatch("click", { target: content });
    assert.equal(card.classList.contains("is-selected"), false);
    await elements.get("select-all-button").dispatch("click");
    assert.equal(card.classList.contains("is-selected"), true);
    await elements.get("clear-button").dispatch("click");
    assert.equal(card.classList.contains("is-selected"), false);
});

test("touch scroll, pointer cancellation, drag and a text-selection gesture never toggle a card", async () => {
    const { elements, env } = await page([{ url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1)]) }]);
    const card = elements.get("inbox-list").children[0];
    card.dispatch("pointerdown", { clientX: 20, clientY: 40, pointerType: "touch" });
    env.window.scrollY = 200;
    card.dispatch("click");
    assert.equal(card.classList.contains("is-selected"), false);
    card.dispatch("pointerdown", { clientX: 20, clientY: 40 });
    card.dispatch("pointermove", { clientX: 20, clientY: 60 });
    card.dispatch("click");
    assert.equal(card.classList.contains("is-selected"), false);
    card.dispatch("pointerdown", { clientX: 20, clientY: 40 });
    card.dispatch("pointercancel"); card.dispatch("click");
    assert.equal(card.classList.contains("is-selected"), false);
    env.window.getSelection = () => ({ isCollapsed: false });
    card.dispatch("pointerdown", { clientX: 20, clientY: 40 });
    env.window.getSelection = () => ({ isCollapsed: true });
    card.dispatch("click");
    assert.equal(card.classList.contains("is-selected"), false);
    card.dispatch("pointerdown", { clientX: 20, clientY: 40 }); card.dispatch("click");
    assert.equal(card.classList.contains("is-selected"), true);
});

test("viewer closes on its last media, preserving selection, position and one modal history entry", async () => {
    const item = { ...inboxItem(1), media: [1, 2, 3].map(number => ({ mediaKey: `m${number}`, sortOrder: number,
        photoUrl: `/photo-${number}.jpg` })) };
    const { elements, env } = await page([{ url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([item]) }]);
    env.flushFrames();
    select(elements, 1);
    const buttons = env.document.querySelectorAll(".image-open-button");
    env.window.scrollY = 300;
    buttons[1].dispatch("click"); env.viewerButton("下一张图片").dispatch("click");
    assert.equal(env.window.history.length, 2);
    env.viewerButton("关闭图片查看器").dispatch("click"); await new Promise(setImmediate); env.flushFrames();
    assert.equal(env.document.activeElement, buttons[2]);
    assert.equal(buttons[2].getAttribute("aria-current"), "true");
    assert.equal(buttons[1].getAttribute("aria-current"), null);
    assert.equal(env.window.scrollY, 300);
    assert.equal(elements.get("inbox-selected-count").textContent, "本页已选 1 项");
});

test("old cards stay connected during the write and GET; new page IDs start unchecked and summary expires", async () => {
    const write = deferred(); const read = deferred();
    const { elements, calls, env } = await page([
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1), inboxItem(2)]) },
        { url: "/api/x-import/inbox/import", body: archiveResult([1, "SUCCESS"]), wait: write.promise },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(2), inboxItem(3)]), wait: read.promise }
    ]);
    const old = elements.get("inbox-list").children[0];
    select(elements, 1);
    const operation = elements.get("import-button").dispatch("click");
    assert.equal(old.isConnected, true);
    assert.equal(elements.get("import-button").disabled, true);
    await elements.get("skip-button").dispatch("click");
    assert.equal(calls.length, 2);
    write.resolve(); await new Promise(setImmediate);
    assert.equal(old.isConnected, true);
    assert.equal(elements.get("inbox-list").querySelectorAll("input[type=checkbox]:checked").length, 1);
    read.resolve(); await operation;
    assert.equal(old.isConnected, false);
    assert.equal(elements.get("inbox-list").querySelectorAll("input[type=checkbox]:checked").length, 0);
    assert.equal(elements.get("import-results").open, false);
    assert.match(elements.get("inbox-message").textContent, /已归档 1 项/);
    env.flushTimers();
    assert.equal(elements.get("inbox-message").textContent, "");
    assert.equal(resultRows(elements).length, 1);
});

test("a confirmed Archive with a failed GET retains old cards and result; only a successful read unlocks writes", async () => {
    const { elements, calls } = await page([
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1), inboxItem(2)]) },
        { url: "/api/x-import/inbox/import", body: archiveResult([1, "SUCCESS"]) },
        { url: "/api/x-import/inbox?page=0&size=24", ok: false, status: 503 },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(2)]) }
    ]);
    const old = elements.get("inbox-list").children[0]; select(elements, 1);
    await elements.get("import-button").dispatch("click");
    assert.equal(old.isConnected, true);
    assert.match(elements.get("inbox-message").textContent, /已归档 1 项.*列表更新失败/);
    assert.equal(resultRows(elements).length, 1);
    assert.equal(elements.get("import-button").disabled, true);
    await elements.get("import-button").dispatch("click"); assert.equal(calls.length, 3);
    await elements.get("inbox-retry-button").dispatch("click");
    assert.equal(old.isConnected, false);
    select(elements, 2); assert.equal(elements.get("import-button").disabled, false);
    assert.equal(calls.filter(call => call.options?.method === "POST").length, 1);
});

test("unknown write outcome stays visible, requires a read and is never automatically replayed", async () => {
    const { elements, calls, env } = await page([
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1)]) },
        { url: "/api/x-import/inbox/skip", error: new Error("connection lost") },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([]) }
    ]);
    select(elements, 1); await elements.get("skip-button").dispatch("click");
    assert.equal(elements.get("inbox-list").children.length, 1);
    assert.equal(elements.get("skip-button").disabled, true);
    env.flushTimers(); assert.match(elements.get("inbox-message").textContent, /未能确认结果/);
    assert.equal(calls.length, 2);
    await elements.get("inbox-retry-button").dispatch("click");
    assert.equal(calls.filter(call => call.options?.method === "PATCH").length, 1);
});

test("Archive issues stay on their cards across batches; selecting failures excludes Duplicate and newly loaded IDs", async () => {
    const { elements, calls, env } = await page([
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1), inboxItem(2), inboxItem(3)]) },
        { url: "/api/x-import/inbox/import", body: archiveResult([2, "DUPLICATE", "same photo"], [1, "FAILED", "download failed"], [3, "SUCCESS"]) },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1), inboxItem(2), inboxItem(4)]) },
        { url: "/api/x-import/inbox/import", body: archiveResult([4, "SUCCESS"]) },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1), inboxItem(2), inboxItem(5)]) },
        { url: "/api/x-import/inbox/import", body: archiveResult([1, "SUCCESS"]) },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(2), inboxItem(5)]) }
    ]);
    select(elements, 1, 2, 3); await elements.get("import-button").dispatch("click");
    assert.deepEqual(env.document.querySelectorAll(".inbox-item-feedback").map(node => node.children[1].children[1].textContent), ["download failed", "same photo"]);
    await elements.get("select-failed-button").dispatch("click");
    assert.deepEqual(elements.get("inbox-list").querySelectorAll("input[type=checkbox]:checked").map(input => input.value), ["1"]);
    select(elements, 4); await elements.get("import-button").dispatch("click");
    assert.equal(env.document.querySelectorAll(".inbox-item-feedback").length, 2);
    env.document.querySelectorAll(".inbox-item-feedback")[0].children[2].dispatch("click");
    await elements.get("import-button").dispatch("click");
    assert.deepEqual(JSON.parse(calls[5].options.body), { itemIds: [1] });
    assert.equal(env.document.querySelectorAll(".inbox-item-feedback").length, 1);
    assert.equal(elements.get("select-failed-button").hidden, true);
});

test("partial Skip creates no per-ID outcomes and keeps the last genuine Archive reason", async () => {
    const { elements, env } = await page([
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1), inboxItem(2)]) },
        { url: "/api/x-import/inbox/import", body: archiveResult([1, "FAILED", "real Archive reason"]) },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1), inboxItem(2)]) },
        { url: "/api/x-import/inbox/skip", body: { requestedCount: 2, skippedCount: 1 } },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1)]) }
    ]);
    select(elements, 1); await elements.get("import-button").dispatch("click");
    select(elements, 1, 2); await elements.get("skip-button").dispatch("click");
    assert.equal(env.document.querySelectorAll(".inbox-item-feedback")[0].children[1].children[1].textContent, "real Archive reason");
    assert.match(resultRows(elements)[0].textContent, /仅返回实际处理数量/);
    assert.equal(resultRows(elements).length, 1);
});

test("same-page reload restores the current ID and media at its viewport offset", async () => {
    const wait = deferred();
    const { elements, env } = await page([
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1), inboxItem(2), inboxItem(3)]) },
        { url: "/api/x-import/inbox/skip", body: { requestedCount: 1, skippedCount: 1 } },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(2), inboxItem(3), inboxItem(4)]), wait: wait.promise }
    ]);
    env.flushFrames();
    const old = elements.get("inbox-list").children;
    old.forEach((card, index) => { card.rect = { top: 300 + index * 250, height: 200 }; });
    env.window.scrollY = 500; elements.get("inbox-toolbar").rect = { top: 500, height: 44 };
    select(elements, 1); const operation = elements.get("skip-button").dispatch("click");
    await new Promise(setImmediate);
    // The user scrolls during the GET. Capture the current visible ID at commit, not the old pixel offset.
    env.window.scrollY = 550; elements.get("inbox-toolbar").rect.top = 550;
    wait.resolve(); await operation;
    const state = env.window.history.state.iaBrowse;
    assert.equal(state.anchor.key, "inbox:2"); assert.equal(state.anchor.top, 0);
    elements.get("inbox-list").children[0].rect.top = 400; env.flushFrames();
    assert.equal(env.window.scrollY, 400);
    assert.equal(env.window.history.length, 1);
});

test("removed anchor chooses the next old surviving ID, then the previous ID, then the first replacement", async () => {
    for (const [remaining, expected] of [[[1, 3, 4], 3], [[1, 4], 1], [[4, 5], 4]]) {
        const { elements, env } = await page([
            { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1), inboxItem(2), inboxItem(3)]) },
            { url: "/api/x-import/inbox/skip", body: { requestedCount: 1, skippedCount: 1 } },
            { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage(remaining.map(inboxItem)) }
        ]);
        env.flushFrames();
        elements.get("inbox-list").children.forEach((card, index) => { card.rect = { top: 300 + index * 250, height: 200 }; });
        env.window.scrollY = 500; elements.get("inbox-toolbar").rect = { top: 500, height: 44 };
        select(elements, 2); await elements.get("skip-button").dispatch("click");
        assert.equal(env.window.history.state.iaBrowse.anchor.key, `inbox:${expected}`);
        assert.ok(env.window.history.state.iaBrowse.anchor.top >= 44);
    }
});

test("failed page navigation keeps old URL, page, DOM and checked IDs until a successful read", async () => {
    const { elements, env } = await page([
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1)], 0, 25) },
        { url: "/api/x-import/inbox?page=1&size=24", ok: false, status: 503 },
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1)], 0, 25) }
    ]);
    const old = elements.get("inbox-list").children[0]; select(elements, 1);
    await elements.get("inbox-next").dispatch("click");
    assert.equal(old.isConnected, true); assert.equal(env.window.location.search, "?page=0");
    assert.equal(elements.get("inbox-page-status").textContent, "Page 1 / 2");
    assert.equal(elements.get("inbox-list").querySelectorAll("input[type=checkbox]:checked").length, 1);
    await elements.get("inbox-retry-button").dispatch("click");
    assert.equal(elements.get("inbox-list").querySelectorAll("input[type=checkbox]:checked").length, 0);
});

test("a late page response cannot overwrite a newer history restoration", async () => {
    const late = deferred();
    const { elements, env } = await page([
        { url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1)], 0, 49) },
        { url: "/api/x-import/inbox?page=1&size=24", body: inboxPage([inboxItem(25)], 1, 49), wait: late.promise },
        { url: "/api/x-import/inbox?page=2&size=24", body: inboxPage([inboxItem(49)], 2, 49) }
    ]);
    const navigation = elements.get("inbox-next").dispatch("click");
    env.window.dispatch("popstate", { state: { iaBrowse: { ...env.window.history.state.iaBrowse, page: 2 } } });
    await new Promise(setImmediate); late.resolve(); await navigation;
    assert.equal(elements.get("inbox-page-status").textContent, "Page 3 / 3");
    assert.equal(elements.get("inbox-list").querySelectorAll("input[type=checkbox]")[0].value, "49");
    assert.equal(env.window.location.search, "?page=2");
});

test("IDs not belonging to the loaded page cannot become a batch request", async () => {
    const { elements, calls } = await page([{ url: "/api/x-import/inbox?page=0&size=24", body: inboxPage([inboxItem(1)]) }]);
    const input = elements.get("inbox-list").querySelectorAll("input[type=checkbox]")[0];
    input.value = "999"; input.checked = true; input.dispatch("change");
    assert.equal(elements.get("inbox-selected-count").textContent, "本页已选 0 项");
    await elements.get("import-button").dispatch("click"); assert.equal(calls.length, 1);
});
