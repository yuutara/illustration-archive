const assert = require("node:assert/strict");
const test = require("node:test");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const Deck = require("../../main/resources/static/c/c-deck-model.js");

const readStatic = (name) => readFileSync(path.resolve(__dirname, "../../main/resources/static/c", name), "utf8");
const fxSource = readStatic("fx.js");

function createFxEnv({ selecting = false, reducedMotion = false } = {}) {
    let now = 100;
    let nextRafId = 1;
    let rafQueue = new Map();
    let timerQueue = new Map();
    let nextTimerId = 1;
    let scrollXVal = 12;
    let scrollYVal = 500;
    const scrollCalls = [];

    class Node {
        constructor(tagName = "div") {
            this.tagName = tagName;
            this.className = "";
            this.children = [];
            this.parentNode = null;
            this.listeners = new Map();
            this.style = {
                getPropertyValue: (k) => this.style[k] || "",
                setProperty: (k, v) => { this.style[k] = v; },
                removeProperty: (k) => { delete this.style[k]; }
            };
            this.dataset = {};
            this.attrs = {};
            this.hidden = false;
            this.complete = true;
            this.offsetLeft = 0;
            this.offsetWidth = 80;
            this.innerHTML = "";
            this._rect = { left: 0, top: 100, width: 240, height: 320 };
            this.classList = {
                contains: (c) => this.className.split(/\s+/).includes(c),
                add: (...cs) => { this.className = [...new Set([...this.className.split(/\s+/), ...cs])].filter(Boolean).join(" "); },
                remove: (...cs) => { this.className = this.className.split(/\s+/).filter((n) => !cs.includes(n)).join(" "); },
                toggle: (c, force) => {
                    const on = force === undefined ? !this.classList.contains(c) : !!force;
                    if (on) this.classList.add(c); else this.classList.remove(c);
                    return on;
                }
            };
        }
        get childNodes() { return [...this.children]; }
        get src() { return this.attrs.src ?? ""; }
        set src(v) { this.attrs.src = String(v); }
        appendChild(child) {
            if (child.parentNode) child.parentNode.children = child.parentNode.children.filter((n) => n !== child);
            this.children.push(child);
            child.parentNode = this;
            return child;
        }
        append(...kids) { kids.forEach((k) => this.appendChild(k)); }
        prepend(...kids) { [...kids].reverse().forEach((k) => this.insertBefore(k, this.children[0] || null)); }
        animate() { return { cancel() {}, finished: Promise.resolve(), onfinish: null }; }
        insertBefore(child, ref) {
            const i = this.children.indexOf(ref);
            this.children.splice(i < 0 ? this.children.length : i, 0, child);
            child.parentNode = this;
            return child;
        }
        setAttribute(k, v) { this.attrs[k] = String(v); }
        getAttribute(k) { return this.attrs[k] ?? null; }
        contains(node) { return node === this || this.children.some((c) => c.contains(node)); }
        matches(sel) {
            if (sel === ":hover") return false;
            if (sel.startsWith(".")) return this.classList.contains(sel.slice(1));
            return false;
        }
        getBoundingClientRect() { return this._rect; }
        setPointerCapture(id) { this.capturedPointerId = id; }
        hasPointerCapture(id) { return this.capturedPointerId === id; }
        releasePointerCapture(id) { if (this.capturedPointerId === id) this.capturedPointerId = null; }
        addEventListener(type, fn, opts) {
            if (!this.listeners.has(type)) this.listeners.set(type, []);
            this.listeners.get(type).push({ fn, capture: opts === true || !!(opts && opts.capture) });
        }
        querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
        querySelectorAll(sel) {
            const out = [];
            const match = (n) => {
                if (sel === ".piece-open img") return n.tagName === "img" && n.parentNode && n.parentNode.classList.contains("piece-open");
                if (sel === ".piece-marks .mark") return n.classList.contains("mark");
                if (sel === ".fx-layers img") return n.tagName === "img" && n.parentNode && n.parentNode.classList.contains("fx-layers");
                if (sel === ".fx-layers img[data-depth]") return n.tagName === "img" && n.dataset.depth && n.parentNode && n.parentNode.classList.contains("fx-layers");
                if (sel === ":scope > .fx-card") return this.children.includes(n) && n.classList.contains("fx-card");
                if (sel.startsWith(".")) {
                    const cls = sel.slice(1).split(".");
                    return cls.every((c) => n.classList.contains(c));
                }
                return false;
            };
            const walk = (n) => {
                for (const c of n.children) {
                    if (match(c)) out.push(c);
                    walk(c);
                }
            };
            walk(this);
            return out;
        }
        closest(sel) {
            if (sel.startsWith(".") && this.classList.contains(sel.slice(1))) return this;
            return this.parentNode ? this.parentNode.closest(sel) : null;
        }
    }

    const html = new Node("html");
    const body = new Node("body");
    html.appendChild(body);
    const masthead = new Node("header"); masthead.className = "masthead";
    const scopes = new Node("div"); scopes.className = "scopes";
    const scopeCur = new Node("button"); scopeCur.setAttribute("aria-current", "true"); scopes.appendChild(scopeCur);
    const density = new Node("div"); density.className = "density";
    const densityCur = new Node("button"); densityCur.setAttribute("aria-pressed", "true"); density.appendChild(densityCur);
    const wallWrap = new Node("div"); wallWrap.className = "wall-wrap";
    const wall = new Node("div"); wall.id = "wall"; wallWrap.appendChild(wall);
    const batchbar = new Node("div"); batchbar.id = "batchbar"; batchbar.hidden = true;
    const bbCount = new Node("span"); bbCount.id = "bb-count";
    const toast = new Node("div"); toast.id = "toast"; toast.hidden = true;
    const svTopRight = new Node("div"); svTopRight.className = "sv-top-right";
    body.append(masthead, scopes, density, wallWrap, batchbar, bbCount, toast, svTopRight);

    const byId = { wall, batchbar, "bb-count": bbCount, toast };
    const docListeners = new Map();
    const winListeners = new Map();
    const document = {
        documentElement: html,
        body,
        createElement: (tag) => new Node(tag),
        createElementNS: (_ns, tag) => new Node(tag),
        getElementById: (id) => byId[id] || null,
        querySelector: (sel) => body.querySelector(sel),
        querySelectorAll: (sel) => body.querySelectorAll(sel),
        addEventListener: (type, fn, opts) => {
            if (!docListeners.has(type)) docListeners.set(type, []);
            docListeners.get(type).push({ fn, capture: opts === true || !!(opts && opts.capture) });
        }
    };

    const works = new Map();
    const pieces = new Map();
    function addPiece(id, pagesCount) {
        const pages = Array.from({ length: pagesCount }, (_, i) => ({
            id: id * 100 + i,
            src: `/thumb/${id}/${i}.jpg`,
            thumbUrl: `/thumb/${id}/${i}.jpg`,
            url: `/full/${id}/${i}.jpg`,
            tone: "#887766",
            width: 800,
            height: 1000
        }));
        works.set(id, { id, pages });
        const fig = new Node("figure");
        fig.className = "piece";
        fig.dataset.id = String(id);
        const media = new Node("div"); media.className = "piece-media";
        const open = new Node("button"); open.className = "piece-open";
        const front = new Node("img");
        front.src = pages[0].src;
        open.appendChild(front);
        const marks = new Node("div"); marks.className = "piece-marks";
        const mark = new Node("span"); mark.className = "mark";
        if (pagesCount > 1) marks.appendChild(mark);
        media.append(open, marks);
        fig.appendChild(media);
        wall.appendChild(fig);
        pieces.set(id, fig);
        return { fig, open, front, mark, get card() { return fig.querySelector(".fx-card"); } };
    }

    const single = addPiece(1, 1);
    const multi = addPiece(2, 3);

    const state = { selecting, viewer: false };
    const ctx = {
        window: null,
        document,
        Image: function () { return new Node("img"); },
        Map,
        Set,
        Math,
        Number,
        Array,
        Object,
        structuredClone,
        JSON,
        performance: { now: () => now },
        getComputedStyle: () => ({ getPropertyValue: () => "" }),
        getSelection: () => ({ removeAllRanges() {} }),
        matchMedia: () => ({ matches: reducedMotion }),
        MutationObserver: class { observe() {} },
        ResizeObserver: class { observe() {} },
        requestAnimationFrame: (fn) => {
            const id = nextRafId++;
            rafQueue.set(id, fn);
            return id;
        },
        cancelAnimationFrame: (id) => { rafQueue.delete(id); },
        setTimeout: (fn, ms) => {
            const id = nextTimerId++;
            timerQueue.set(id, { fn, ms });
            return id;
        },
        clearTimeout: (id) => { timerQueue.delete(id); },
        scrollTo: (x, y) => { scrollXVal = x; scrollYVal = y; scrollCalls.push([x, y]); },
        scrollBy: (dx, dy) => { scrollXVal += dx; scrollYVal += dy; scrollCalls.push([scrollXVal, scrollYVal]); },
        get scrollX() { return scrollXVal; },
        get scrollY() { return scrollYVal; },
        addEventListener: (type, fn, opts) => {
            if (!winListeners.has(type)) winListeners.set(type, []);
            winListeners.get(type).push({ fn, capture: opts === true || !!(opts && opts.capture) });
        },
        Archive: { get: (id) => works.get(Number(id)) },
        DeckModel: Deck,
        GalleryApi: {
            state,
            pieces,
            workOf: (id) => works.get(id),
            works: () => [...works.values()],
            thumbSrc: (p) => p.thumbUrl,
            coverSrc: (p) => p.url,
            prefersReducedMotion: () => reducedMotion
        }
    };
    ctx.window = ctx;
    vm.runInNewContext(fxSource, ctx);

    const dragShield = body.children.find((c) => c.classList.contains("fx-drag-shield"));

    const makeEvent = (target, extra = {}) => ({
        target,
        pointerId: 1,
        pointerType: "mouse",
        button: 0,
        clientX: 0,
        clientY: 0,
        ctrlKey: false,
        shiftKey: false,
        metaKey: false,
        altKey: false,
        defaultPrevented: false,
        propagationStopped: false,
        preventDefault() { this.defaultPrevented = true; },
        stopPropagation() { this.propagationStopped = true; },
        ...extra
    });

    const fireWin = (type, event, { captureOnly = false } = {}) => {
        for (const h of [...(winListeners.get(type) || [])]) {
            if (captureOnly && !h.capture) continue;
            h.fn(event);
        }
        return event;
    };

    const fireNode = (node, type, event) => {
        for (const h of [...(node.listeners.get(type) || [])]) h.fn(event);
        return event;
    };

    const flushRaf = (stepMs = 16) => {
        now += stepMs;
        const batch = [...rafQueue.entries()];
        rafQueue.clear();
        for (const [, fn] of batch) fn(now);
    };

    const flushTimers = () => {
        const batch = [...timerQueue.entries()];
        timerQueue.clear();
        for (const [, entry] of batch) entry.fn();
    };

    return {
        body,
        wallWrap,
        dragShield,
        single,
        multi,
        state,
        scrollCalls,
        getScroll: () => [scrollXVal, scrollYVal],
        getPendingRafCount: () => rafQueue.size,
        advanceNow: (ms) => { now += ms; },
        pointerDown: (target, x, y) => {
            const ev = makeEvent(target, { clientX: x, clientY: y });
            fireWin("pointerdown", ev, { captureOnly: true });
            fireNode(wallWrap, "pointerdown", ev);
        },
        pointerMove: (x, y) => fireWin("pointermove", makeEvent(dragShield, { clientX: x, clientY: y })),
        pointerUp: (x, y) => {
            const ev = makeEvent(dragShield, { clientX: x, clientY: y });
            fireWin("pointerup", ev);
            return ev;
        },
        click: (target) => fireWin("click", makeEvent(target)),
        keyDown: (key) => fireWin("keydown", makeEvent(body, { key })),
        flushRaf,
        flushTimers
    };
}

test("CSS avoids global fx-dragging invalidation and scopes card glass blur/masks to visible states", () => {
    const fxCss = readStatic("fx.css");
    const galleryCss = readStatic("gallery.css");
    assert.doesNotMatch(fxCss, /body\.fx-dragging\s*,/);
    assert.doesNotMatch(fxCss, /body\.fx-dragging\s+\*/);
    assert.doesNotMatch(fxCss, /body\.fx-dragging\s+\.piece-open/);
    assert.match(fxCss, /\.fx-drag-shield\s*\{[^}]*position:\s*fixed;[^}]*cursor:\s*grabbing\s*!important;/s);
    assert.match(fxCss, /\.fx-drag-shield\[hidden\]\s*\{\s*display:\s*none\s*!important;\s*\}/);

    assert.doesNotMatch(fxCss, /\.fx-wall\.fx-mat-glass\s+:is\(\.piece-edit,\s*\.mark,\s*\.piece-check\)::before/);
    assert.match(fxCss, /\.fx-wall\.fx-mat-glass\s+:where\(\.piece:hover,\s*\.piece:focus-within\)\s+\.piece-edit/);
    assert.match(fxCss, /\.fx-wall\.fx-mat-glass\s+:where\(\.piece:hover,\s*\.is-selecting\)\s+\.piece-check/);

    assert.match(galleryCss, /\.piece\s*\{[^}]*content-visibility:\s*auto;\s*contain-intrinsic-block-size:\s*auto\s+280px;/s);
    assert.match(galleryCss, /\.piece:is\(:hover,\s*:focus-within,\s*\.fx-raised,\s*\.fx-pulled,\s*\.fx-decking,\s*\.is-fresh,\s*\.is-selected\)\s*\{\s*content-visibility:\s*visible;\s*\}/);
});

test("vertical drag uses drag shield, coalesces pointermove to rAF, preserves scroll/cancel/click-swallow", () => {
    const env = createFxEnv();
    assert.ok(env.dragShield, "fx.js should mount .fx-drag-shield");
    assert.equal(env.dragShield.hidden, true);
    assert.equal(env.body.classList.contains("fx-dragging"), false);

    // 1. Plain click under SLOP never shows shield and never swallows click
    env.pointerDown(env.single.open, 100, 100);
    env.pointerMove(102, 103);
    assert.equal(env.dragShield.hidden, true);
    env.pointerUp(102, 103);
    assert.equal(env.click(env.single.open).defaultPrevented, false);

    // 2. Vertical drag past SLOP shows dragShield, captures pointer, coalesces multiple moves per rAF
    env.pointerDown(env.single.open, 200, 300);
    env.pointerMove(200, 260); // dy = -40 -> scroll to 500 - (-40) = 540
    assert.equal(env.dragShield.hidden, false);
    assert.equal(env.dragShield.capturedPointerId, 1);
    assert.equal(env.scrollCalls.length, 0, "scrollTo defers until rAF flush");

    env.pointerMove(200, 240);
    env.pointerMove(200, 220); // dy = -80 -> scroll to 580
    assert.equal(env.getPendingRafCount(), 1, "multiple pointermoves collapse into one rAF");
    env.flushRaf();
    assert.deepEqual(env.scrollCalls, [[12, 580]], "uses cached scrollX=12 and latest dy");

    // 3. Releasing flushes final pending move, hides shield, swallows trailing click
    env.advanceNow(10);
    env.pointerMove(200, 200); // dy = -100 -> scroll to 600 on release
    env.pointerUp(200, 200);
    assert.equal(env.dragShield.hidden, true);
    assert.deepEqual(env.getScroll(), [12, 600]);
    const swallowed = env.click(env.single.open);
    assert.equal(swallowed.defaultPrevented, true);
    assert.equal(swallowed.propagationStopped, true);

    // 4. Esc during vertical drag restores original scroll position and still swallows click on release
    env.pointerDown(env.single.open, 200, 300);
    env.pointerMove(200, 220);
    env.flushRaf();
    assert.deepEqual(env.getScroll(), [12, 680]);
    const esc = env.keyDown("Escape");
    assert.equal(esc.defaultPrevented, true);
    assert.equal(env.dragShield.hidden, true);
    assert.deepEqual(env.getScroll(), [12, 600]);
    env.pointerUp(200, 220);
    assert.equal(env.click(env.single.open).defaultPrevented, true);
    env.flushTimers();
});

test("horizontal drag triggers tether on single-page card, deck swipe on multi-page card, and reduced-motion flip", () => {
    const env = createFxEnv();

    // Single-page card horizontal drag -> tether
    env.pointerDown(env.single.open, 100, 100);
    env.pointerMove(140, 102);
    assert.equal(env.dragShield.hidden, false);
    assert.equal(env.single.fig.classList.contains("fx-pulled"), true);
    assert.equal(env.single.fig.classList.contains("fx-raised"), true);
    env.flushRaf();
    assert.ok(env.single.card.style.transform, "tether transform applied on rAF flush");
    env.pointerUp(140, 102);
    assert.equal(env.dragShield.hidden, true);
    env.flushTimers();

    // Multi-page card horizontal drag -> swipe
    env.pointerDown(env.multi.open, 100, 100);
    env.pointerMove(20, 102);
    assert.equal(env.dragShield.hidden, false);
    assert.equal(env.multi.fig.classList.contains("fx-decking"), true);
    assert.equal(env.multi.fig.classList.contains("fx-raised"), true);
    env.flushRaf();
    assert.ok(env.multi.fig.querySelectorAll(".fx-layers img").some((img) => img.style.transform), "deck poses updated on rAF");
    env.pointerUp(20, 102);
    assert.equal(env.dragShield.hidden, true);
    env.flushTimers();

    // Selection mode blocks drag/tether while still swallowing drag-release click
    const selEnv = createFxEnv({ selecting: true });
    selEnv.pointerDown(selEnv.single.open, 100, 100);
    selEnv.pointerMove(100, 200);
    selEnv.flushRaf();
    assert.equal(selEnv.scrollCalls.length, 0);
    assert.equal(selEnv.single.fig.classList.contains("fx-pulled"), false);
    selEnv.pointerUp(100, 200);
    assert.equal(selEnv.click(selEnv.single.open).defaultPrevented, true);
    selEnv.flushTimers();

    // Reduced motion multi-page swipe past 40px cycles page without animations
    const redEnv = createFxEnv({ reducedMotion: true });
    redEnv.pointerDown(redEnv.multi.open, 200, 100);
    redEnv.pointerMove(140, 100); // dx = -60 -> next page (2 / 3)
    assert.equal(redEnv.dragShield.hidden, false);
    redEnv.pointerUp(140, 100);
    assert.equal(redEnv.dragShield.hidden, true);
    assert.equal(redEnv.multi.mark.textContent, "2 / 3");
});
