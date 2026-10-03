(function () {
    "use strict";

    function authorLabel(author) {
        const handle = (author.xUsername || "").trim().replace(/^@/, "");
        return author.displayName + (handle ? ` · @${handle}` : "");
    }

    const favoritesByEndpoint = new Map();
    function favoritesFor(endpoint) {
        if (favoritesByEndpoint.has(endpoint)) return favoritesByEndpoint.get(endpoint);
        const key = `illustration-archive.metadata-favorites.v1.${endpoint.split("/").pop()}`;
        function readIds() {
            try {
                const saved = JSON.parse(window.localStorage.getItem(key));
                return new Set((Array.isArray(saved) ? saved : []).filter(id =>
                    /^\d+$/.test(String(id)) && Number.isSafeInteger(Number(id)) && Number(id) > 0).map(id => String(Number(id))));
            } catch (_) { return null; }
        }
        const ids = readIds() || new Set();
        const listeners = new Set();
        function persist(item) {
            try { window.localStorage.setItem(key, JSON.stringify([...ids])); } catch (_) { /* Keep this session usable. */ }
            listeners.forEach(listener => listener(item));
        }
        const store = { ids, listeners,
            restore() {
                const restored = readIds();
                if (restored) { ids.clear(); restored.forEach(id => ids.add(id)); }
            },
            toggle(item) {
                const id = String(item.id);
                if (!ids.delete(id)) ids.add(id);
                persist(item);
            },
            discardMissing(snapshot, found) {
                let changed = false;
                snapshot.forEach(id => { if (!found.has(id) && ids.delete(id)) changed = true; });
                if (changed) persist();
            }
        };
        favoritesByEndpoint.set(endpoint, store);
        return store;
    }

    // List/search/favorites UI lives here. Callers own drafts and submissions.
    function create({ prefix, endpoint, label, selected, choose, multiSelect = false, blocked = () => false }) {
        const node = suffix => document.getElementById(`${prefix}-${suffix}`);
        const toggle = node("toggle"), panel = node("panel"), input = node("search-input");
        const search = node("search-button"), results = node("search-results");
        const status = node("search-status"), more = node("more");
        const favorites = favoritesFor(endpoint);
        const favoriteSection = document.createElement("section");
        const favoriteHeading = document.createElement("h4");
        favoriteHeading.textContent = "收藏";
        favoriteHeading.id = `${prefix}-favorites-heading`;
        favoriteSection.setAttribute("aria-labelledby", favoriteHeading.id);
        const favoriteResults = document.createElement("ul");
        favoriteResults.className = "metadata-picker-results metadata-picker-favorites";
        favoriteSection.append(favoriteHeading, favoriteResults);
        const listHeading = document.createElement("h4");
        listHeading.className = favoriteHeading.className = "metadata-picker-section-heading";
        results.parentNode.insertBefore(favoriteSection, results);
        results.parentNode.insertBefore(listHeading, results);
        const limit = 20;
        let items = [], offset = 0, hasMore = false, busy = false, failed = false;
        let generation = 0, timer = null, disabled = false;
        let favoritesGeneration = 0, favoriteItems = new Map();
        panel.hidden = true;

        function render() {
            const focused = document.activeElement;
            const focusId = focused?.dataset?.metadataPicker === prefix ? focused.dataset.metadataId : null;
            let focusOwner = focused;
            while (focusOwner && focusOwner !== results && focusOwner !== favoriteResults) focusOwner = focusOwner.parentNode;
            const scrollTop = results.scrollTop;
            const favoriteScrollTop = favoriteResults.scrollTop;
            results.replaceChildren();
            favoriteResults.replaceChildren();
            const picked = selected();
            const ids = new Set(picked.map(item => String(item.id)));
            const visible = new Map([...picked, ...items].map(item => [String(item.id), item]));
            function row(item, target) {
                const li = document.createElement("li");
                li.className = "metadata-picker-row";
                const button = document.createElement(multiSelect ? "label" : "button");
                if (!multiSelect) button.type = "button";
                button.className = "metadata-picker-option";
                button.dataset.metadataId = String(item.id);
                button.dataset.metadataPicker = prefix;
                button.dataset.metadataRole = "select";
                let checkbox;
                if (multiSelect) {
                    checkbox = document.createElement("input");
                    checkbox.type = "checkbox";
                    checkbox.dataset.metadataId = String(item.id);
                    checkbox.dataset.metadataPicker = prefix;
                    checkbox.dataset.metadataRole = "select";
                    checkbox.checked = ids.has(String(item.id));
                    checkbox.setAttribute("aria-label", label(item));
                    checkbox.disabled = disabled || blocked();
                    const text = document.createElement("span");
                    text.textContent = label(item);
                    button.append(checkbox, text);
                    button.dataset.selected = String(checkbox.checked);
                } else {
                    button.setAttribute("aria-pressed", String(ids.has(String(item.id))));
                    button.textContent = `${ids.has(String(item.id)) ? "✓ " : ""}${label(item)}`;
                }
                button.disabled = disabled || blocked();
                (checkbox || button).addEventListener(multiSelect ? "change" : "click", () => {
                    if (disabled || blocked()) return;
                    choose(item);
                    render();
                    if (!panel.hidden) {
                        const next = Array.from(target.children).map(li => li.children[0])
                            .find(button => button.dataset.metadataId === String(item.id));
                        (next ? multiSelect ? next.children[0] : next : input).focus({ preventScroll: true });
                    }
                });
                const star = document.createElement("button");
                star.type = "button";
                star.className = "metadata-picker-favorite";
                star.dataset.metadataId = String(item.id);
                star.dataset.metadataPicker = prefix;
                star.dataset.metadataRole = "favorite";
                const starred = favorites.ids.has(String(item.id));
                star.textContent = starred ? "★" : "☆";
                star.setAttribute("aria-label", `${starred ? "取消收藏" : "收藏"} ${label(item)}`);
                star.setAttribute("aria-pressed", String(starred));
                star.disabled = disabled || blocked();
                star.addEventListener("click", event => {
                    event.stopPropagation();
                    if (disabled || blocked()) return;
                    favorites.toggle(item);
                    const next = Array.from(target.children).map(li => li.children[1])
                        .find(star => star.dataset.metadataId === String(item.id));
                    (next || input).focus({ preventScroll: true });
                });
                li.append(button, star);
                target.appendChild(li);
            }
            favorites.ids.forEach(id => { if (favoriteItems.has(id)) row(favoriteItems.get(id), favoriteResults); });
            favoriteSection.hidden = favoriteResults.children.length === 0;
            listHeading.textContent = input.value.trim() ? "搜索结果" : "全部";
            visible.forEach(item => row(item, results));
            more.hidden = !failed && !hasMore;
            more.disabled = busy || disabled || blocked();
            more.textContent = failed ? "重试" : "加载更多";
            input.disabled = search.disabled = toggle.disabled = disabled || blocked();
            results.setAttribute("aria-busy", String(busy));
            results.scrollTop = scrollTop;
            favoriteResults.scrollTop = favoriteScrollTop;
            if (focusId && focusOwner) {
                const lists = focusOwner === favoriteResults ? [favoriteResults, results] : [results, favoriteResults];
                const controls = lists.flatMap(list => Array.from(list.children).map(row => focused.dataset.metadataRole === "favorite"
                    ? row.children[1] : multiSelect ? row.children[0].children[0] : row.children[0]));
                (controls.find(control => control.dataset.metadataId === focusId) || input).focus({ preventScroll: true });
            }
        }

        favorites.listeners.add(item => {
            if (item && favorites.ids.has(String(item.id))) favoriteItems.set(String(item.id), item);
            render();
        });

        async function resolveFavorites() {
            const ticket = ++favoritesGeneration;
            const snapshot = [...favorites.ids], found = new Set();
            let scanOffset = 0;
            try {
                while (found.size < snapshot.length) {
                    const query = new URLSearchParams({ keyword: "", limit: 100, offset: scanOffset });
                    const response = await fetch(`${endpoint}?${query}`, { cache: "no-store", headers: { Accept: "application/json" } });
                    if (!response.ok) return;
                    const page = await response.json();
                    if (ticket !== favoritesGeneration || panel.hidden || !Array.isArray(page)) return;
                    page.forEach(item => {
                        const id = String(item.id);
                        if (snapshot.includes(id)) { found.add(id); favoriteItems.set(id, item); }
                    });
                    render();
                    if (page.length < 100) break;
                    scanOffset += page.length;
                }
                if (ticket === favoritesGeneration && !panel.hidden) favorites.discardMissing(snapshot, found);
            } catch (_) { /* A failed read must not discard saved favorites. Retry on the next open. */ }
        }

        async function load(reset) {
            if (disabled || blocked() || (!reset && busy)) return;
            window.clearTimeout(timer);
            const ticket = ++generation;
            if (reset) { items = []; offset = 0; hasMore = false; results.scrollTop = 0; }
            busy = true;
            failed = false;
            status.textContent = "正在加载…";
            render();
            const query = new URLSearchParams({ keyword: input.value.trim(), limit, offset });
            try {
                const response = await fetch(`${endpoint}?${query}`, { cache: "no-store", headers: { Accept: "application/json" } });
                if (!response.ok) throw new Error("Metadata list failed");
                const page = await response.json();
                if (!Array.isArray(page)) throw new Error("Invalid metadata list");
                if (ticket !== generation || panel.hidden) return;
                items.push(...page);
                page.forEach(item => {
                    if (favorites.ids.has(String(item.id))) favoriteItems.set(String(item.id), item);
                });
                offset += page.length;
                hasMore = page.length === limit;
                status.textContent = page.length || items.length ? "" : "没有匹配项。";
            } catch (_) {
                if (ticket !== generation || panel.hidden) return;
                failed = true;
                status.textContent = "加载失败，请重试。";
            } finally {
                if (ticket === generation) { busy = false; render(); }
            }
        }

        function close() {
            ++generation;
            ++favoritesGeneration;
            window.clearTimeout(timer);
            busy = false;
            panel.hidden = true;
            toggle.setAttribute("aria-expanded", "false");
        }

        function open() {
            if (disabled || blocked()) return;
            // Re-read after returning from another document, including bfcache restores.
            favorites.restore();
            panel.hidden = false;
            toggle.setAttribute("aria-expanded", "true");
            input.value = "";
            favoriteItems = new Map();
            input.focus();
            return Promise.all([load(true), resolveFavorites()]);
        }

        toggle.addEventListener("click", () => panel.hidden ? open() : close());
        search.addEventListener("click", () => load(true));
        more.addEventListener("click", () => load(false));
        input.addEventListener("input", () => {
            // Invalidate immediately, including before the debounce timer fires.
            ++generation;
            window.clearTimeout(timer);
            busy = false;
            items = []; offset = 0; hasMore = failed = false;
            status.textContent = "正在搜索…";
            render();
            timer = window.setTimeout(() => load(true), 180);
        });
        input.addEventListener("keydown", event => {
            if (event.key === "Enter") { event.preventDefault(); load(true); }
        });
        panel.addEventListener("keydown", event => {
            if (event.key === "Escape") { event.preventDefault(); close(); toggle.focus(); }
        });
        render();
        return { open, close, refresh: () => panel.hidden ? undefined : Promise.all([load(true), resolveFavorites()]),
            sync: render, setDisabled(value) { disabled = value; render(); } };
    }

    window.MetadataPicker = { create, authorLabel };
})();
