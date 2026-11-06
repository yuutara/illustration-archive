const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const source = readFileSync(path.resolve(__dirname, "../../main/resources/static/c/c-image-menu.js"), "utf8");
const BASE = "http://127.0.0.1:47391/c/";

class Element {
    constructor({ tagName = "DIV", closest = {}, inside = {} } = {}) {
        this.tagName = tagName;
        this.found = closest;
        this.inside = inside;
    }
    closest(selector) { return this.found[selector] || null; }
    querySelector(selector) { return this.inside[selector] || null; }
}

// 浏览器里（没有 iaDesktop）只载入纯函数，不接管右键
function load() {
    const window = {};
    vm.runInNewContext(source, { window, Element, URL });
    return window.CImageMenu._internal;
}

test("only the app's own work files count, thumbnail or original, and the menu always means the original", () => {
    const { assetIdFrom } = load();
    assert.equal(assetIdFrom("/api/assets/42/thumbnail", BASE), 42);
    assert.equal(assetIdFrom("http://127.0.0.1:47391/api/assets/7/content", BASE), 7);
    assert.equal(assetIdFrom("https://pbs.twimg.com/media/a.jpg", BASE), null, "an X photo is not in the library");
    assert.equal(assetIdFrom("http://127.0.0.1:1/api/assets/7/content", BASE), null, "another origin is not this app");
    assert.equal(assetIdFrom("data:image/svg+xml;charset=utf-8,%3Csvg%3E", BASE), null, "a placeholder is no file");
    assert.equal(assetIdFrom("/c/app-icon.svg", BASE), null);
    assert.equal(assetIdFrom("/api/assets/7/content/extra", BASE), null);
    assert.equal(assetIdFrom("", BASE), null);
});

test("a saved file keeps its imported name, and an unknown name is made from the id and format", () => {
    const { fileNameFor } = load();
    assert.equal(fileNameFor(7, "夏の海.png", "image/png"), "夏の海.png");
    assert.equal(fileNameFor(7, null, "image/jpeg"), "插画-7.jpg");
    assert.equal(fileNameFor(8, "  ", "video/mp4"), "插画-8.mp4");
    assert.equal(fileNameFor(9, undefined, undefined), "插画-9.bin");
});

test("on the wall the card's current page counts wherever the card is clicked; elsewhere the clicked image", () => {
    const { mediaAt } = load();
    const front = new Element({ tagName: "IMG" });
    const piece = new Element({ inside: { ".piece-open img": front } });
    const onBadge = new Element({ closest: { ".piece": piece } });
    assert.equal(mediaAt(onBadge), front, "a page badge or a deck layer under the pointer still means the front page");

    const video = new Element({ tagName: "VIDEO" });
    const videoPiece = new Element({ inside: { ".piece-video": video, ".piece-open img": front } });
    assert.equal(mediaAt(new Element({ closest: { ".piece": videoPiece } })), video, "a video work saves the video, not its placeholder");

    const viewerImage = new Element({ tagName: "IMG" });
    assert.equal(mediaAt(new Element({ closest: { "img, video": viewerImage } })), viewerImage);
    assert.equal(mediaAt(new Element()), null);
    assert.equal(mediaAt(null), null);
});
