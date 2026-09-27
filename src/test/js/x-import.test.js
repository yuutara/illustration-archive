const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const staticDir = path.resolve(__dirname, "../../main/resources/static");
const script = readFileSync(path.join(staticDir, "x-import.js"), "utf8");

class Element {
    constructor(tagName = "div") {
        this.tagName = tagName;
        this.children = [];
        this.listeners = new Map();
        this.hidden = false;
        this.disabled = false;
        this.checked = false;
        this.classList = { toggle() {} };
    }

    addEventListener(type, listener) { this.listeners.set(type, listener); }
    append(...children) { this.children.push(...children); }
    appendChild(child) { this.children.push(child); }
    replaceChildren(...children) { this.children = children; }
    setAttribute(name, value) { this[name] = value; }
    dispatch(type) { return this.listeners.get(type)?.(); }
    querySelectorAll(selector) {
        const matches = [];
        const visit = element => {
            if (element.tagName === "input" && element.type === "checkbox"
                    && (selector === "input[type=checkbox]" || element.checked)) {
                matches.push(element);
            }
            element.children.forEach(visit);
        };
        this.children.forEach(visit);
        return matches;
    }
}

function inboxItem(id) {
    return { id, xPostId: `post-${id}`, authorDisplayName: `Artist ${id}`,
        authorUsername: `artist${id}`, postText: "", media: [] };
}

async function page(responses) {
    const elements = new Map();
    const calls = [];
    const document = {
        getElementById(id) {
            if (!elements.has(id)) elements.set(id, new Element());
            return elements.get(id);
        },
        createElement(tagName) { return new Element(tagName); }
    };
    const fetch = async (url, options) => {
        calls.push({ url, options });
        const response = responses.shift();
        assert.ok(response, `Unexpected request: ${url}`);
        assert.equal(url, response.url);
        return { ok: true, json: async () => response.body };
    };
    vm.runInNewContext(script, { document, fetch, encodeURIComponent });
    await new Promise(setImmediate);
    return { elements, calls };
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
        { url: "/api/x-import/inbox", body: [inboxItem(1)] },
        { url: "/api/x-import/inbox/import", body: { total: 1, successCount: 1,
            duplicateCount: 0, failureCount: 0,
            items: [{ itemId: 1, status: "SUCCESS", illustrationId: 42, reason: null }] } },
        { url: "/api/x-import/inbox", body: [] }
    ]);
    assert.deepEqual(calls.map(call => call.url), ["/api/x-import/inbox"]);
    select(elements, 1);
    await elements.get("import-button").dispatch("click");
    assert.deepEqual(calls.map(call => call.url), [
        "/api/x-import/inbox", "/api/x-import/inbox/import", "/api/x-import/inbox"
    ]);
    assert.deepEqual(JSON.parse(calls[1].options.body), { itemIds: [1] });
    assert.equal(elements.get("import-results").hidden, false);
    assert.match(elements.get("import-results-summary").textContent, /共 1 项：成功 1，重复 0，失败 0/);
    assert.match(resultRows(elements)[0].children[0].textContent, /post-1/);
    assert.equal(resultRows(elements)[0].children[2].href, "/detail.html?id=42");
    assert.equal(elements.get("inbox-list").children.length, 0);
    assert.equal(resultRows(elements).length, 1);
});

test("Sync latest uses recent endpoint and explains that the next click restarts at latest", async () => {
    const { elements, calls } = await page([
        { url: "/api/x-import/inbox", body: [] },
        { url: "/api/x-import/sync/recent?maxResults=5&maxPages=3", body: {
            pagesFetched: 3, fetchedCount: 15, newCount: 2, existingCount: 13,
            pendingCount: 2, stoppedByMaxPages: true, stoppedByInvalidToken: false
        } },
        { url: "/api/x-import/inbox", body: [] }
    ]);

    await elements.get("sync-button").dispatch("click");

    assert.equal(calls[1].options.method, "POST");
    assert.deepEqual(calls.map(call => call.url), [
        "/api/x-import/inbox", "/api/x-import/sync/recent?maxResults=5&maxPages=3", "/api/x-import/inbox"
    ]);
    assert.match(elements.get("inbox-message").textContent, /下次点击仍从最新 Likes 开始/);
});

test("partial batch results stay paired with posts and failed items remain selectable", async () => {
    const { elements, calls } = await page([
        { url: "/api/x-import/inbox", body: [inboxItem(1), inboxItem(2), inboxItem(3)] },
        { url: "/api/x-import/inbox/import", body: { total: 3, successCount: 1,
            duplicateCount: 1, failureCount: 1, items: [
                { itemId: 3, status: "FAILED", illustrationId: null, reason: "download failed" },
                { itemId: 1, status: "SUCCESS", illustrationId: 21, reason: null },
                { itemId: 2, status: "DUPLICATE", illustrationId: null, reason: "same photo" }
            ] } },
        { url: "/api/x-import/inbox", body: [inboxItem(2), inboxItem(3)] },
        { url: "/api/x-import/inbox", body: [inboxItem(2), inboxItem(3)] },
        { url: "/api/x-import/inbox/import", body: { total: 1, successCount: 1,
            duplicateCount: 0, failureCount: 0,
            items: [{ itemId: 3, status: "SUCCESS", illustrationId: 33, reason: null }] } },
        { url: "/api/x-import/inbox", body: [inboxItem(2)] }
    ]);
    select(elements, 1, 2, 3);
    await elements.get("import-button").dispatch("click");
    const rows = resultRows(elements);
    assert.equal(rows.length, 3);
    assert.match(rows[0].children[0].textContent, /post-3/);
    assert.match(rows[0].children[1].textContent, /导入失败/);
    assert.equal(rows[0].children[2].textContent, "download failed");
    assert.match(rows[1].children[0].textContent, /post-1/);
    assert.equal(rows[1].children[2].href, "/detail.html?id=21");
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
    assert.equal(resultRows(elements)[0].children[2].href, "/detail.html?id=33");
    assert.equal(elements.get("inbox-list").children.length, 1);
    assert.equal(calls.filter(call => call.url.includes("/sync/")).length, 0);
});

test("skip reports actual transition count and refreshes pending items", async () => {
    const { elements, calls } = await page([
        { url: "/api/x-import/inbox", body: [inboxItem(1), inboxItem(2)] },
        { url: "/api/x-import/inbox/skip", body: { requestedCount: 2, skippedCount: 1 } },
        { url: "/api/x-import/inbox", body: [inboxItem(2)] }
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

test("result region and Gallery link are present in the page", () => {
    const html = readFileSync(path.join(staticDir, "x-import.html"), "utf8");
    assert.match(html, /id="import-results"/);
    assert.match(html, /id="import-results-list"/);
    assert.match(html, /href="\/">返回 Gallery/);
});
