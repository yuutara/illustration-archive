const assert = require("node:assert/strict");
const test = require("node:test");
const { browser } = require("./helpers/browser");

const config = () => ({ groupKey: "illustration:7", title: "作品", startKey: "22",
    items: [11, 22, 33].map(id => ({ key: String(id), fullUrl: `/api/assets/${id}/content`,
        previewUrl: `/api/assets/${id}/thumbnail`, alt: `图片 ${id}` })) });
function control(env, text) {
    const labels = { "←": "上一张图片", "→": "下一张图片", "−": "缩小图片",
        "+": "放大图片", "适应窗口": "适应窗口", "1:1": "原始尺寸" };
    return env.viewerButton(labels[text]);
}
function load(env, width = 1600, height = 2400) {
    const image = env.find("image-viewer-image");
    image.naturalWidth = width; image.naturalHeight = height; image.dispatch("load");
    return image;
}

test("empty viewer is rejected; single image stops at both ends and invalid key falls back", () => {
    const env = browser();
    assert.equal(env.window.ImageViewer.open({ items: [] }), false);
    assert.equal(env.find("image-viewer"), undefined);
    assert.equal(env.window.ImageViewer.open({ ...config(), startKey: "missing", items: [config().items[0]] }), true);
    assert.equal(env.find("image-viewer-position").textContent, "1 / 1");
    assert.equal(env.find("image-viewer-pagination").hidden, true);
    assert.equal(control(env, "←").disabled, true);
    assert.equal(control(env, "→").disabled, true);
    load(env, 200, 100);
    assert.equal(env.find("image-viewer-zoom").textContent, "100%");
});

test("clicked image opens directly; navigation stops and stale load/error cannot replace current image", () => {
    const env = browser(); const changes = [];
    env.window.ImageViewer.open({ ...config(), onChange: key => changes.push(key) });
    const old = env.find("image-viewer-image");
    assert.equal(old.src, "/api/assets/22/content");
    assert.equal(env.find("image-viewer-preview").src, "/api/assets/22/thumbnail");
    control(env, "→").dispatch("click");
    old.naturalWidth = 100; old.naturalHeight = 100;
    old.dispatch("load"); old.dispatch("error");
    assert.equal(env.find("image-viewer-zoom").textContent, "");
    assert.equal(env.find("image-viewer-status").textContent, "正在加载图片…");
    assert.equal(load(env).src, "/api/assets/33/content");
    assert.equal(env.find("image-viewer-preview"), undefined);
    control(env, "→").dispatch("click");
    assert.equal(env.find("image-viewer-position").textContent, "3 / 3");
    assert.deepEqual(changes, ["22", "33"]);
});

test("failed original can retry while navigation and close remain usable", () => {
    const env = browser(); env.window.ImageViewer.open(config());
    const failed = env.find("image-viewer-image"); failed.dispatch("error");
    assert.match(env.find("image-viewer-status").textContent, /加载失败/);
    const retry = env.find("image-viewer-feedback").children[1];
    assert.equal(retry.hidden, false); retry.dispatch("click");
    assert.notEqual(env.find("image-viewer-image"), failed);
    const current = load(env);
    env.window.ImageViewer.close(); current.dispatch("error");
    assert.equal(env.find("image-viewer").open, false);
    assert.equal(env.find("image-viewer-stage").children.length, 0);
});

test("zoom uses original dimensions, clamps pan and scale, and resets on image change/resize", () => {
    const env = browser(); env.window.ImageViewer.open(config());
    const image = load(env, 1000, 1000);
    assert.equal(env.find("image-viewer-zoom").textContent, "60%");
    control(env, "1:1").dispatch("click");
    assert.equal(env.find("image-viewer-more").open, false);
    assert.equal(env.document.activeElement["aria-label"], "更多查看操作");
    const stage = env.find("image-viewer-stage");
    stage.dispatch("pointerdown", { pointerId: 1, clientX: 0, clientY: 0 });
    stage.dispatch("pointermove", { pointerId: 1, clientX: 10000, clientY: 10000 });
    assert.ok(image.style.transform.includes("translate(100px, 200px)"));
    stage.dispatch("pointercancel", { pointerId: 1 });
    for (let i = 0; i < 20; i++) control(env, "+").dispatch("click");
    assert.equal(env.find("image-viewer-zoom").textContent, "400%");
    control(env, "适应窗口").dispatch("click");
    stage.clientHeight = 300; env.window.dispatch("resize");
    assert.equal(env.find("image-viewer-zoom").textContent, "30%");
    control(env, "→").dispatch("click"); load(env, 1000, 1000);
    assert.equal(env.find("image-viewer-zoom").textContent, "30%");
});

test("dialog owns shortcuts, preserves GIF URL, restores focus/scroll lock, and rejects unsafe links", () => {
    const env = browser(); const opener = env.document.getElementById("opener"); opener.focus();
    env.document.documentElement.style.overflow = "auto";
    env.window.ImageViewer.open({ ...config(), sourceHref: "javascript:alert(1)",
        items: [{ key: "gif", fullUrl: "/api/assets/33/content" }] });
    load(env); const dialog = env.find("image-viewer");
    assert.equal(env.document.documentElement.style.overflow, "hidden");
    assert.equal(env.find("image-viewer-links").children[1].hidden, true);
    assert.equal(env.find("image-viewer-image").src, "/api/assets/33/content");
    dialog.dispatch("keydown", { key: "1" });
    assert.equal(env.find("image-viewer-zoom").textContent, "100%");
    dialog.dispatch("keydown", { key: "0", ctrlKey: true });
    assert.equal(env.find("image-viewer-zoom").textContent, "100%");
    assert.equal(dialog.dispatch("cancel").defaultPrevented, true);
    assert.equal(env.document.activeElement, opener);
    assert.equal(env.document.documentElement.style.overflow, "auto");
    dialog.dispatch("keydown", { key: "+" }); assert.equal(dialog.open, false);
});
