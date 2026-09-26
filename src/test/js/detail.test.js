const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const staticDir = path.resolve(__dirname, "../../main/resources/static");
const detailScript = readFileSync(path.join(staticDir, "detail.js"), "utf8");

class Element {
    constructor(tagName = "div") {
        this.tagName = tagName;
        this.children = [];
        this.listeners = new Map();
        this.hidden = false;
        this.value = "";
    }

    addEventListener(type, listener) {
        this.listeners.set(type, listener);
    }

    appendChild(child) {
        this.children.push(child);
    }

    replaceChildren(...children) {
        this.children = children;
    }

    querySelectorAll() {
        return [];
    }

    dispatch(type) {
        this.listeners.get(type)?.();
    }
}

async function loadPage(assets) {
    const elements = new Map();
    const document = {
        getElementById(id) {
            if (!elements.has(id)) {
                elements.set(id, new Element());
            }
            return elements.get(id);
        },
        createElement(tagName) {
            return new Element(tagName);
        }
    };
    const detail = { id: 7, title: "Test illustration", assets, tags: [] };
    vm.runInNewContext(detailScript, {
        document,
        window: { location: { search: "?id=7" } },
        URL,
        URLSearchParams,
        fetch: async () => ({ ok: true, json: async () => detail })
    });
    await new Promise(setImmediate);
    assert.equal(elements.get("detail-status").textContent, "详情已加载");
    return elements.get("detail-images").children;
}

test("single image keeps one original in the main image stage", async () => {
    const stages = await loadPage([{ id: 11, sortOrder: 0 }]);
    assert.equal(stages.length, 1);
    assert.equal(stages[0].className, "detail-image");
    assert.equal(stages[0].children[0].src, "/api/assets/11/content");
    assert.equal(stages[0].children[0].alt, "Test illustration");
    assert.equal(stages[0].children[0].loading, "eager");
});

test("three images render vertically in sortOrder order with later originals lazy", async () => {
    const stages = await loadPage([
        { id: 33, sortOrder: 2 },
        { id: 11, sortOrder: 0 },
        { id: 22, sortOrder: 1 }
    ]);
    assert.deepEqual(stages.map(stage => stage.children[0].src), [
        "/api/assets/11/content",
        "/api/assets/22/content",
        "/api/assets/33/content"
    ]);
    assert.deepEqual(stages.map(stage => stage.children[0].loading), ["eager", "lazy", "lazy"]);
    assert.deepEqual(stages.map(stage => stage.children[0].alt), [
        "Test illustration · 第 1 张", "Test illustration · 第 2 张", "Test illustration · 第 3 张"
    ]);
    stages[1].children[0].dispatch("error");
    assert.equal(stages[1].children[0].hidden, true);
    assert.equal(stages[1].children[1].hidden, false);
    assert.equal(stages[0].children[0].hidden, false);
});

test("empty assets show a fallback without crashing", async () => {
    const stages = await loadPage([]);
    assert.equal(stages.length, 1);
    assert.equal(stages[0].children[0].textContent, "暂无图片");
});

test("detail page provides the gallery element", () => {
    const html = readFileSync(path.join(staticDir, "detail.html"), "utf8");
    assert.match(html, /id="detail-images"/);
});
