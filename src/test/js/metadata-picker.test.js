const assert = require("node:assert/strict");
const test = require("node:test");
const { browser, settle } = require("./helpers/browser");

function setup(selected = [], options = {}) {
    const env = browser(options);
    const picker = env.window.MetadataPicker.create({ prefix: "test", endpoint: "/api/tags",
        multiSelect: options.multiSelect,
        label: item => item.name, selected: () => selected,
        choose: item => { const index = selected.findIndex(tag => tag.id === item.id);
            if (index < 0) selected.push(item); else selected.splice(index, 1); } });
    return { env, picker, node: suffix => env.document.getElementById(`test-${suffix}`) };
}
const response = items => ({ ok: true, json: async () => items });
const page = (start, count) => Array.from({ length: count }, (_, i) => ({ id: start + i, name: `Tag ${start + i}` }));

test("open lists without typing; more uses server offset, deduplicates IDs and ends without a count", async () => {
    const { env, picker, node } = setup([{ id: 99, name: "Selected outside page" }]);
    const requests = [];
    env.sandbox.fetchImpl = async url => { requests.push(new URL(url, "http://localhost"));
        return response(requests.length === 1 ? page(1, 20) : page(21, 5)); };
    await picker.open();
    assert.equal(requests[0].searchParams.get("keyword"), "");
    assert.equal(requests[0].searchParams.get("limit"), "20");
    assert.equal(node("search-results").children.length, 21);
    assert.equal(node("search-results").children[0].children[0]["aria-pressed"], "true");
    await node("more").dispatch("click");
    assert.equal(requests[1].searchParams.get("offset"), "20");
    assert.equal(node("search-results").children.length, 26);
    assert.equal(node("more").hidden, true);
    await node("search-results").children[0].children[0].dispatch("click");
    assert.equal(node("search-results").children.some(li => li.children[0].dataset.metadataId === "99"), false);
});

test("old search and load-more responses cannot replace newer results or their loading state", async () => {
    const { env, picker, node } = setup();
    const pending = [];
    env.sandbox.fetchImpl = url => new Promise(resolve => pending.push({ url, resolve }));
    const first = picker.open(); pending[0].resolve(response(page(1, 20))); await first;
    const more = node("more").dispatch("click");
    node("search-input").value = "new";
    const search = node("search-button").dispatch("click");
    pending[2].resolve(response([{ id: 100, name: "new" }])); await search;
    pending[1].resolve(response(page(21, 20))); await more;
    assert.equal(node("search-results").children.length, 1);
    assert.equal(node("search-results").children[0].children[0].textContent, "new");
    assert.equal(node("more").hidden, true);
    const old = node("search-button").dispatch("click");
    node("search-input").value = "latest";
    const latest = node("search-button").dispatch("click");
    pending[3].resolve({ ok: false }); await old;
    assert.equal(node("search-status").textContent, "正在加载…");
    pending[4].resolve(response([{ id: 101, name: "latest" }])); await latest;
    assert.equal(node("search-results").children[0].children[0].textContent, "latest");
});

test("typing invalidates the old response before debounce; close ignores pending reads", async () => {
    const { env, picker, node } = setup([{ id: 99, name: "Pinned" }]);
    let resolve;
    env.sandbox.fetchImpl = () => new Promise(done => { resolve = done; });
    const pending = picker.open();
    node("search-input").value = "other";
    node("search-input").dispatch("input");
    resolve(response(page(1, 20))); await pending;
    assert.equal(node("search-results").children.length, 1);
    assert.equal(node("search-status").textContent, "正在搜索…");
    picker.close(); await settle();
    assert.equal(node("panel").hidden, true);
    const reopened = picker.open(); picker.close(); resolve(response(page(1, 20))); await reopened;
    assert.equal(node("panel").hidden, true);
    assert.equal(node("toggle")["aria-expanded"], "false");
});

test("failed more preserves items and retries the same offset; disabled options cannot mutate selection", async () => {
    const selected = [];
    const { env, picker, node } = setup(selected);
    let call = 0;
    const offsets = [];
    env.sandbox.fetchImpl = async url => {
        offsets.push(new URL(url, "http://localhost").searchParams.get("offset"));
        return ++call === 2 ? { ok: false } : response(call === 1 ? page(1, 20) : page(21, 2));
    };
    await picker.open(); await node("more").dispatch("click");
    assert.equal(node("search-results").children.length, 20);
    assert.equal(node("more").textContent, "重试");
    await node("more").dispatch("click");
    assert.deepEqual(offsets, ["0", "20", "20"]);
    picker.setDisabled(true);
    await node("search-results").children[0].children[0].dispatch("click");
    assert.equal(selected.length, 0);
});

const favoriteKey = kind => `illustration-archive.metadata-favorites.v1.${kind}`;
const favoritesList = env => env.document.querySelectorAll(".metadata-picker-favorites")[0];
const idsIn = list => Array.from(list.children, row => row.children[0].dataset.metadataId);

test("native multi-select checkboxes keep selection, stars and keyboard focus independent through sync", async () => {
    const selected = [];
    const { env, picker, node } = setup(selected, { multiSelect: true });
    env.sandbox.fetchImpl = async () => response(page(1, 2));
    await picker.open();
    const list = node("search-results");
    list.children[0].children[1].dispatch("click");
    assert.equal(selected.length, 0);
    assert.equal(list.children[0].children[0].children[0].type, "checkbox");
    list.children[0].children[0].children[0].dispatch("change");
    list.children[1].children[0].children[0].dispatch("change");
    assert.deepEqual(selected.map(item => item.id), [1, 2]);
    assert.equal(list.children[0].children[1].textContent, "★");
    picker.sync();
    assert.equal(env.document.activeElement.dataset.metadataId, "2");
    assert.equal(env.document.activeElement.type, "checkbox");
    list.children[0].children[0].children[0].dispatch("change");
    assert.deepEqual(selected.map(item => item.id), [2]);
    assert.equal(list.children.find(row => row.children[0].dataset.metadataId === "1").children[1].textContent, "★");
    node("search-input").focus(); picker.sync();
    assert.equal(env.document.activeElement, node("search-input"));
});

test("stars and selections are independent; favorites precede the unchanged full list and can be removed", async () => {
    const selected = [{ id: 1, name: "Tag 1" }], localStorage = new Map();
    const { env, picker, node } = setup(selected, { localStorage });
    env.sandbox.fetchImpl = async () => response(page(1, 2));
    await picker.open();
    const list = node("search-results"), favorites = favoritesList(env);
    await list.children[1].children[1].dispatch("click");
    assert.deepEqual(selected.map(item => item.id), [1]);
    assert.deepEqual(idsIn(favorites), ["2"]);
    assert.deepEqual(idsIn(list), ["1", "2"]);
    assert.equal(favorites.children[0].children[0]["aria-pressed"], "false");
    assert.equal(list.children[1].children[1].textContent, "★");
    assert.deepEqual(JSON.parse(localStorage.get(favoriteKey("tags"))), ["2"]);
    const parent = list.parentNode;
    assert.ok(parent.children.indexOf(favorites.parentNode) < parent.children.indexOf(list));
    assert.equal(favorites.parentNode.children[0].textContent, "收藏");
    await favorites.children[0].children[0].dispatch("click");
    assert.deepEqual(selected.map(item => item.id), [1, 2]);
    assert.equal(favorites.children[0].children[1].textContent, "★");
    await favorites.children[0].children[1].dispatch("click");
    assert.deepEqual(selected.map(item => item.id), [1, 2]);
    assert.equal(favorites.parentNode.hidden, true);
    assert.deepEqual(JSON.parse(localStorage.get(favoriteKey("tags"))), []);
});

test("favorites restore across pages, resolve beyond page 20, ignore stale IDs and survive search and more", async () => {
    const localStorage = new Map([[favoriteKey("tags"), JSON.stringify([25, 999, "bad", -1])]]);
    const { env, picker, node } = setup([], { localStorage });
    const requests = [];
    env.sandbox.fetchImpl = async url => {
        const query = new URL(url, "http://localhost").searchParams;
        requests.push(Object.fromEntries(query));
        if (query.get("limit") === "100") return response(page(1, 25));
        return response(query.get("offset") === "0" ? page(1, 20) : page(21, 5));
    };
    await picker.open();
    assert.deepEqual(idsIn(favoritesList(env)), ["25"]);
    assert.deepEqual(JSON.parse(localStorage.get(favoriteKey("tags"))), ["25"]);
    node("search-input").value = "Tag";
    await node("search-button").dispatch("click");
    await node("more").dispatch("click");
    assert.deepEqual(idsIn(favoritesList(env)), ["25"]);
    assert.equal(node("search-results").children[24].children[1].textContent, "★");
    assert.equal(requests.at(-1).offset, "20");
    picker.close();
    const restored = setup([], { localStorage });
    restored.env.sandbox.fetchImpl = async () => response(page(1, 25));
    await restored.picker.open();
    assert.deepEqual(idsIn(favoritesList(restored.env)), ["25"]);
    restored.picker.close();
    restored.env.sandbox.fetchImpl = async () => response(page(1, 2));
    await restored.picker.open();
    assert.equal(favoritesList(restored.env).parentNode.hidden, true);
    assert.deepEqual(JSON.parse(localStorage.get(favoriteKey("tags"))), []);
});

test("author and tag stores are isolated while same-kind pickers share favorites", async () => {
    const localStorage = new Map();
    const { env, picker, node } = setup([], { localStorage });
    const other = env.window.MetadataPicker.create({ prefix: "other", endpoint: "/api/tags",
        label: item => item.name, selected: () => [], choose: () => assert.fail("star selected a tag") });
    const author = env.window.MetadataPicker.create({ prefix: "author", endpoint: "/api/authors",
        label: env.window.MetadataPicker.authorLabel, selected: () => [], choose: () => assert.fail("star selected an author") });
    env.sandbox.fetchImpl = async url => response(url.startsWith("/api/authors")
        ? [{ id: 1, displayName: "Same ID", xUsername: "author" }] : page(1, 2));
    await picker.open(); await other.open(); await author.open();
    await node("search-results").children[0].children[1].dispatch("click");
    const favoriteLists = env.document.querySelectorAll(".metadata-picker-favorites");
    assert.deepEqual(idsIn(favoriteLists[0]), ["1"]);
    assert.deepEqual(idsIn(favoriteLists[1]), ["1"]);
    assert.deepEqual(idsIn(favoriteLists[2]), []);
    await env.document.getElementById("author-search-results").children[0].children[1].dispatch("click");
    await favoriteLists[1].children[0].children[1].dispatch("click");
    assert.deepEqual(JSON.parse(localStorage.get(favoriteKey("tags"))), []);
    assert.deepEqual(JSON.parse(localStorage.get(favoriteKey("authors"))), ["1"]);
});

test("favorite validation scans all pages; failed or closed scans never discard saved IDs", async () => {
    const localStorage = new Map([[favoriteKey("tags"), '["105","999"]']]);
    const { env, picker } = setup([], { localStorage });
    const offsets = [];
    env.sandbox.fetchImpl = async url => {
        const query = new URL(url, "http://localhost").searchParams;
        if (query.get("limit") === "20") return response(page(1, 20));
        offsets.push(query.get("offset"));
        return response(query.get("offset") === "0" ? page(1, 100) : page(101, 5));
    };
    await picker.open();
    assert.deepEqual(offsets, ["0", "100"]);
    assert.deepEqual(idsIn(favoritesList(env)), ["105"]);
    picker.close();
    env.sandbox.fetchImpl = async url => url.includes("limit=100") ? { ok: false } : response(page(1, 20));
    await picker.open();
    assert.deepEqual(JSON.parse(localStorage.get(favoriteKey("tags"))), ["105"]);
    picker.close();
    let resolve;
    env.sandbox.fetchImpl = url => url.includes("limit=100") ? new Promise(done => { resolve = done; }) : Promise.resolve(response(page(1, 20)));
    const opening = picker.open(); picker.close(); resolve(response([])); await opening;
    assert.deepEqual(JSON.parse(localStorage.get(favoriteKey("tags"))), ["105"]);
});

test("unavailable or malformed localStorage preserves usable session favorites", async () => {
    for (const options of [{ storageDisabled: true }, { localStorage: new Map([[favoriteKey("tags"), "not json"]]) }]) {
        const { env, picker, node } = setup([], options);
        env.sandbox.fetchImpl = async () => response(page(1, 1));
        await picker.open();
        await node("search-results").children[0].children[1].dispatch("click");
        assert.deepEqual(idsIn(favoritesList(env)), ["1"]);
    }
});

test("a cached page re-reads favorites changed by another document when reopened", async () => {
    const localStorage = new Map();
    const first = setup([], { localStorage });
    first.env.sandbox.fetchImpl = async () => response(page(1, 2));
    await first.picker.open(); first.picker.close();
    const second = setup([], { localStorage });
    second.env.sandbox.fetchImpl = async () => response(page(1, 2));
    await second.picker.open();
    await second.node("search-results").children[1].children[1].dispatch("click");
    await first.picker.open();
    assert.deepEqual(idsIn(favoritesList(first.env)), ["2"]);
    first.picker.close();
    await favoritesList(second.env).children[0].children[1].dispatch("click");
    await first.picker.open();
    assert.deepEqual(idsIn(favoritesList(first.env)), []);
});
