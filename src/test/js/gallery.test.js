const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const script = readFileSync(path.resolve(__dirname, "../../main/resources/static/app.js"), "utf8");

class Element {
    constructor(tagName = "div") {
        this.tagName = tagName;
        this.children = [];
        this.listeners = new Map();
        this.hidden = false;
        this.disabled = false;
        this.classList = { toggle() {} };
    }

    addEventListener(type, listener) { this.listeners.set(type, listener); }
    append(...children) { this.children.push(...children); }
    appendChild(child) { this.children.push(child); }
    replaceChildren(...children) { this.children = children; }
    setAttribute(name, value) { this[name] = value; }
    dispatch(type) {
        const event = {
            defaultPrevented: false,
            propagationStopped: false,
            preventDefault() { this.defaultPrevented = true; },
            stopPropagation() { this.propagationStopped = true; }
        };
        if (!this.disabled) this.listeners.get(type)?.(event);
        return event;
    }
}

async function cardFor(item) {
    const elements = new Map();
    const document = {
        getElementById(id) {
            if (!elements.has(id)) elements.set(id, new Element());
            return elements.get(id);
        },
        createElement(tagName) { return new Element(tagName); }
    };
    vm.runInNewContext(script, {
        document,
        encodeURIComponent,
        fetch: async () => ({ ok: true, json: async () => ({
            page: 0, totalPages: 1, totalElements: 1, items: [item]
        }) })
    });
    await new Promise(setImmediate);
    return elements.get("gallery").children[0];
}

test("single asset uses thumbnail and has no preview controls or position", async () => {
    const card = await cardFor({ id: 7, title: "One", coverAssetId: 11, coverMimeType: "image/jpeg",
        assets: [{ id: 11, mimeType: "image/jpeg", sortOrder: 0 }] });
    const imageContainer = card.children[0];
    assert.equal(imageContainer.children.length, 1);
    assert.equal(imageContainer.children[0].href, "/detail.html?id=7");
    assert.equal(imageContainer.children[0].children[0].src, "/api/assets/11/thumbnail");
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
    assert.equal(imageLink.href, "/detail.html?id=7");
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
