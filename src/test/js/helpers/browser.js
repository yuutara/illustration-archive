const { readFileSync } = require("node:fs");
const { randomUUID } = require("node:crypto");
const path = require("node:path");
const vm = require("node:vm");

function browser({ url = "http://localhost/", entries, storage = new Map(), localStorage = new Map(), storageDisabled = false } = {}) {
    const windowListeners = new Map();
    const ids = new Map();
    const frames = [];
    const deferredTimers = new Map();
    let address = new URL(url);
    let historyEntries = entries ? structuredClone(entries) : [{ url, state: null }];
    let cursor = historyEntries.length - 1;
    const document = { activeElement: null };
    class Element {
        constructor(tagName = "div") {
            this.tagName = tagName;
            this.children = [];
            this.listeners = new Map();
            this.dataset = {};
            this.style = {};
            this.hidden = false;
            this.disabled = false;
            this.checked = false;
            this.value = "";
            this.complete = true;
            this.className = "";
            this.clientWidth = 800;
            this.clientHeight = 600;
            this.rect = { top: 0, height: 100 };
            this.classList = {
                contains: name => this.className.split(" ").includes(name),
                add: (...names) => { this.className = [...new Set([...this.className.split(" "), ...names])].filter(Boolean).join(" "); },
                remove: (...names) => { this.className = this.className.split(" ").filter(name => !names.includes(name)).join(" "); },
                toggle: (name, force) => {
                    const active = force === undefined ? !this.classList.contains(name) : force;
                    if (active) this.classList.add(name); else this.classList.remove(name);
                    return active;
                }
            };
        }
        get isConnected() { return this === document.body || Boolean(this.parentNode && this.parentNode.isConnected); }
        contains(node) { return node === this || this.children.some(child => child.contains(node)); }
        addEventListener(type, listener, options) {
            if (!this.listeners.has(type)) this.listeners.set(type, []);
            this.listeners.get(type).push({ listener, once: options && options.once });
        }
        append(...children) { children.forEach(child => this.appendChild(child)); }
        removeEventListener(type, listener) {
            this.listeners.set(type, (this.listeners.get(type) || []).filter(entry => entry.listener !== listener));
        }
        appendChild(child) {
            if (child.parentNode) child.parentNode.children = child.parentNode.children.filter(node => node !== child);
            this.children.push(child); child.parentNode = this; return child;
        }
        replaceChildren(...children) {
            this.children.forEach(child => { child.parentNode = null; });
            this.children = []; this.append(...children);
        }
        insertBefore(child, reference) {
            this.children.splice(this.children.indexOf(reference), 0, child);
            child.parentNode = this;
            return child;
        }
        setAttribute(name, value) { this[name] = value; }
        getAttribute(name) { return this[name] ?? null; }
        removeAttribute(name) { delete this[name]; }
        dispatch(type, properties = {}) {
            const event = { type, target: this, button: 0, isPrimary: true,
                defaultPrevented: false, propagationStopped: false,
                preventDefault() { this.defaultPrevented = true; }, stopPropagation() { this.propagationStopped = true; }, ...properties };
            if (this.disabled && type === "click") return event;
            let result;
            for (const { listener, once } of [...(this.listeners.get(type) || [])]) {
                result = listener(event);
                if (once) this.listeners.set(type, this.listeners.get(type).filter(entry => entry.listener !== listener));
            }
            return result === undefined ? event : result;
        }
        querySelectorAll(selector) {
            const nodes = [];
            const visit = node => {
                if (selector === "[data-browse-anchor]" && node.dataset.browseAnchor
                    || selector === "video" && node.tagName === "video"
                    || selector === "main img" && node.tagName === "img"
                    || selector.startsWith("input[type=checkbox]") && node.tagName === "input" && node.type === "checkbox" && (!selector.includes(":checked") || node.checked)
                    || selector[0] === "." && node.classList.contains(selector.slice(1))) nodes.push(node);
                node.children.forEach(visit);
            };
            this.children.forEach(visit); return nodes;
        }
        closest(selector) {
            if (selector === "[data-browse-anchor]" && this.dataset.browseAnchor) return this;
            return this.parentNode ? this.parentNode.closest(selector) : null;
        }
        focus() { document.activeElement = this; }
        getBoundingClientRect() {
            const top = (this.style.top ? parseFloat(this.style.top) : this.rect.top) - window.scrollY;
            const height = this.style.height ? parseFloat(this.style.height) : this.rect.height;
            return { top, bottom: top + height, height };
        }
        showModal() { this.open = true; }
        close() { this.open = false; }
        pause() { this.paused = true; this.pauseCount = (this.pauseCount || 0) + 1; }
        play() { this.paused = false; return Promise.resolve(); }
        load() { this.loadCount = (this.loadCount || 0) + 1; }
        setPointerCapture(id) { this.capturedPointer = id; }
    }
    document.body = new Element("body");
    document.documentElement = new Element("html");
    const main = new Element("main");
    document.body.appendChild(main);
    document.createElement = tag => {
        const node = new Element(tag);
        if (tag === "video") {
            Object.defineProperty(node, "src", {
                configurable: true,
                get() { return this.url; },
                set(url) {
                    this.url = url;
                    Promise.resolve(sandbox.videoSizeImpl(url)).then(size => {
                        node.videoWidth = size && size.width; node.videoHeight = size && size.height;
                        if (size) node.onloadedmetadata?.(); else node.onerror?.();
                    });
                }
            });
            const remove = node.removeAttribute.bind(node);
            node.removeAttribute = name => { if (name === "src") node.url = undefined; else remove(name); };
        }
        return node;
    };
    document.getElementById = id => {
        if (!ids.has(id)) {
            const node = new Element(); node.id = id; ids.set(id, node); main.appendChild(node);
        }
        return ids.get(id);
    };
    document.querySelectorAll = selector => document.body.querySelectorAll(selector);
    const location = {
        get href() { return address.href; }, get search() { return address.search; },
        get pathname() { return address.pathname; }, get origin() { return address.origin; },
        assign(href) { this.assigned = href; }, replace(href) { this.replaced = href; }
    };
    const window = {
        document, location, crypto: { randomUUID }, scrollY: 0, innerHeight: 800, innerWidth: 1280,
        sessionStorage: {
            getItem(key) { if (storageDisabled) throw new Error("Storage disabled"); return storage.get(key) || null; },
            setItem(key, value) { if (storageDisabled) throw new Error("Storage disabled"); storage.set(key, value); }
        },
        localStorage: {
            getItem(key) { if (storageDisabled) throw new Error("Storage disabled"); return localStorage.get(key) || null; },
            setItem(key, value) { if (storageDisabled) throw new Error("Storage disabled"); localStorage.set(key, value); }
        },
        addEventListener(type, listener) {
            if (!windowListeners.has(type)) windowListeners.set(type, []);
            windowListeners.get(type).push(listener);
        },
        dispatch(type, event = {}) { (windowListeners.get(type) || []).forEach(listener => listener(event)); },
        scrollTo(x, y) { window.scrollY = y; },
        requestAnimationFrame(action) { frames.push(action); },
        getSelection() { return { isCollapsed: true }; },
        setTimeout(action, delay) {
            if (delay < 1000) return setTimeout(action, 1);
            const token = {};
            deferredTimers.set(token, action);
            return token;
        },
        clearTimeout(token) { if (!deferredTimers.delete(token)) clearTimeout(token); },
        history: {
            get state() { return historyEntries[cursor].state; }, get length() { return historyEntries.length; },
            pushState(state, unused, url) {
                address = new URL(url, address);
                historyEntries = historyEntries.slice(0, cursor + 1);
                historyEntries.push({ state: structuredClone(state), url: address.href }); ++cursor;
            },
            replaceState(state, unused, url) { address = new URL(url, address); historyEntries[cursor] = { state: structuredClone(state), url: address.href }; },
            back() { traverse(-1); }, forward() { traverse(1); }
        }
    };
    function traverse(delta) {
        if (cursor + delta < 0 || cursor + delta >= historyEntries.length) return;
        queueMicrotask(() => {
            cursor += delta; address = new URL(historyEntries[cursor].url);
            window.dispatch("popstate", { state: structuredClone(window.history.state) });
        });
    }
    const sandbox = vm.createContext({ window, document, URL, URLSearchParams, structuredClone,
        encodeURIComponent, setTimeout, clearTimeout, FormData, fetch: (...args) => sandbox.fetchImpl(...args) });
    sandbox.imageSizeImpl = () => ({ width: 800, height: 600 });
    sandbox.videoSizeImpl = () => ({ width: 800, height: 600 });
    const intersections = [];
    sandbox.IntersectionObserver = class {
        constructor(callback) { this.callback = callback; this.targets = new Set(); intersections.push(this); }
        observe(node) { this.targets.add(node); }
        unobserve(node) { this.targets.delete(node); }
    };
    sandbox.Image = class extends Element {
        constructor() { super("img"); }
        set src(url) {
            this.url = url;
            Promise.resolve(sandbox.imageSizeImpl(url)).then(size => {
                this.naturalWidth = size && size.width; this.naturalHeight = size && size.height;
                if (size) this.onload?.(); else this.onerror?.();
            });
        }
    };
    const observers = [];
    sandbox.ResizeObserver = class {
        constructor(callback) { observers.push(callback); }
        observe() {}
    };
    function run(file) {
        vm.runInContext(readFileSync(path.resolve(__dirname, "../../../main/resources/static", file), "utf8"), sandbox);
    }
    function flushFrames() { while (frames.length) frames.shift()(); }
    run("image-viewer.js"); run("browse-context.js"); run("masonry-layout.js"); run("metadata-picker.js");
    return { window, document, elements: ids, storage, run, sandbox, Element, flushFrames,
        intersect(node, isIntersecting) { intersections.forEach(observer => {
            if (observer.targets.has(node)) observer.callback([{ target: node, isIntersecting }]);
        }); },
        flushTimers() { const actions = [...deferredTimers.values()]; deferredTimers.clear(); actions.forEach(action => action()); },
        resize: () => observers.forEach(callback => callback()),
        entries: () => structuredClone(historyEntries.slice(0, cursor + 1)),
        find: className => document.querySelectorAll("." + className)[0],
        viewerButton: label => document.querySelectorAll(".image-viewer-button")
            .find(node => node["aria-label"] === label) };
}

async function settle() { await new Promise(setImmediate); }
module.exports = { browser, settle };
