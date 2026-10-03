(function () {
    "use strict";

    const prefix = "illustration-archive:browse:v1:";
    const paths = { gallery: "/", inbox: "/x-import.html", detail: "/detail.html" };

    function read(key) {
        try { return JSON.parse(window.sessionStorage.getItem(prefix + key)); }
        catch (error) { return null; }
    }

    function store(key, value) {
        try { window.sessionStorage.setItem(prefix + key, JSON.stringify(value)); }
        catch (error) { /* Browsing still works when session storage is unavailable. */ }
    }

    function id() {
        // These identify UI snapshots, not credentials. LAN HTTP may not expose randomUUID.
        return window.crypto && window.crypto.randomUUID ? window.crypto.randomUUID()
            : Date.now().toString(36) + "-" + Math.random().toString(36).slice(2);
    }

    function pageNumber(value) {
        const page = Number(value);
        return Number.isSafeInteger(page) && page >= 0 ? page : 0;
    }

    function valid(state, kind) {
        return state && state.kind === kind && typeof state.id === "string" && state.assets
            && typeof state.assets === "object" && state.route === route(kind);
    }

    function route(kind) {
        return kind === "detail" ? paths.detail + "?id=" + new URLSearchParams(window.location.search).get("id") : paths[kind];
    }

    function create(adapter) {
        const params = new URLSearchParams(window.location.search);
        const initial = window.history.state && window.history.state.iaBrowse;
        const saved = read(params.get("ctx"));
        let state = valid(initial, adapter.kind) ? initial
            : valid(saved, adapter.kind) ? { ...saved, viewer: null } : {
                id: id(), kind: adapter.kind, route: route(adapter.kind), page: 0,
                assets: {}, viewer: null, scrollY: 0, anchor: null
            };
        if (adapter.kind !== "detail" && params.has("page")) state.page = pageNumber(params.get("page"));
        const currentSaved = read(state.id);
        if (valid(currentSaved, adapter.kind) && currentSaved.page === state.page) {
            state = { ...state, assets: currentSaved.assets, refresh: currentSaved.refresh };
        }
        let ready = false;
        let closing = false;
        let afterClose = null;
        let restoreGeneration = 0;
        let restoreCleanup = function () {};
        const origin = saved && ["gallery", "inbox"].includes(saved.kind) && typeof saved.id === "string" ? saved : null;
        const navigation = read("navigation:" + params.get("nav"));
        const canGoBack = origin && navigation && navigation.from === origin.id
            && navigation.to === window.location.pathname + window.location.search
            && window.history.length > 1;

        function url() {
            const url = new URL(window.location.href);
            if (adapter.kind !== "detail") url.searchParams.set("page", String(state.page));
            return url.pathname + url.search + url.hash;
        }

        function write(push) {
            store(state.id, state);
            const entry = { ...(window.history.state || {}), iaBrowse: structuredClone(state) };
            if (push) window.history.pushState(entry, "", url());
            else window.history.replaceState(entry, "", url());
        }

        function capture(node) {
            // The modal locks scrolling; its saved background position must stay intact.
            if (state.viewer) return;
            state.scrollY = window.scrollY;
            const anchor = node && node.closest("[data-browse-anchor]")
                || Array.from(document.querySelectorAll("[data-browse-anchor]"))
                    .find(element => element.getBoundingClientRect().bottom > 0);
            state.anchor = anchor ? { key: anchor.dataset.browseAnchor, top: anchor.getBoundingClientRect().top } : null;
        }

        function restoreScroll() {
            restoreCleanup();
            const ticket = ++restoreGeneration;
            const scrollY = state.scrollY;
            const anchor = state.anchor;
            const adjust = () => {
                if (ticket !== restoreGeneration) return;
                const node = anchor && Array.from(document.querySelectorAll("[data-browse-anchor]"))
                    .find(element => element.dataset.browseAnchor === anchor.key);
                const top = node ? window.scrollY + node.getBoundingClientRect().top - anchor.top : scrollY;
                window.scrollTo(0, Math.max(0, top || 0));
            };
            window.requestAnimationFrame(() => {
                adjust();
                // Correct once after visible lazy images settle; remove listeners on completion/cancellation.
                const pending = Array.from(document.querySelectorAll("main img"))
                    .filter(image => !image.complete && image.getBoundingClientRect().top <= window.innerHeight);
                const cleanups = [];
                const cleanup = () => cleanups.forEach(remove => remove());
                restoreCleanup = cleanup;
                const settled = pending.map(image => new Promise(resolve => {
                    const finish = () => {
                        image.removeEventListener("load", finish);
                        image.removeEventListener("error", finish);
                        resolve();
                    };
                    cleanups.push(finish);
                    image.addEventListener("load", finish);
                    image.addEventListener("error", finish);
                }));
                Promise.race([Promise.all(settled), new Promise(resolve => window.setTimeout(resolve, 800))])
                    .then(() => { cleanup(); window.requestAnimationFrame(adjust); });
            });
        }

        function remember(group, key) {
            state.assets[group] = key;
            if (state.viewer && state.viewer.groupKey === group) state.viewer.key = key;
            write(false);
            if (adapter.kind === "detail" && origin && origin.kind === "gallery") {
                const source = read(origin.id) || origin;
                store(origin.id, { ...source, assets: { ...source.assets, [group]: key } });
            }
        }

        function showViewer(config, key) {
            return window.ImageViewer.open({ ...config, startKey: key,
                onChange: current => {
                    remember(config.groupKey, current);
                    if (config.onChange) config.onChange(current);
                }, onRequestClose: requestClose, onNavigateDetail: navigateDetail });
        }

        function openViewer(config, opener) {
            if (!config || !config.items.length || closing || !window.ImageViewer.supported()) return false;
            capture(opener);
            write(false);
            state.viewer = { groupKey: config.groupKey, key: config.startKey };
            write(true);
            if (!showViewer({ ...config, opener }, config.startKey)) {
                requestClose();
                return false;
            }
            return true;
        }

        function requestClose(action) {
            if (closing) return;
            const current = window.history.state && window.history.state.iaBrowse;
            if (current && current.id === state.id && current.viewer) {
                closing = true;
                afterClose = typeof action === "function" ? action : null;
                window.history.back();
            } else {
                const closedViewer = state.viewer;
                window.ImageViewer.close();
                state.viewer = null;
                write(false);
                if (closedViewer && adapter.onViewerClose) adapter.onViewerClose(closedViewer.groupKey, closedViewer.key);
                if (typeof action === "function") action();
            }
        }

        function detailHref(illustrationId, assetKey) {
            const query = new URLSearchParams({ id: String(illustrationId), ctx: state.id });
            if (assetKey) query.set("asset", String(assetKey));
            return paths.detail + "?" + query;
        }

        function navigateDetail(href) {
            const navigate = () => {
                capture();
                write(false);
                const target = new URL(href, window.location.href);
                if (target.origin !== window.location.origin || target.pathname !== paths.detail) return;
                const token = id();
                target.searchParams.set("ctx", state.id);
                target.searchParams.set("nav", token);
                const to = target.pathname + target.search;
                store("navigation:" + token, { from: state.id, to });
                window.location.assign(to);
            };
            if (state.viewer) requestClose(navigate);
            else navigate();
        }

        function bindDetailLink(link, illustrationId, assetKey) {
            link.href = detailHref(illustrationId, assetKey);
            link.addEventListener("click", event => {
                if (!window.ImageViewer.plainClick(event)) return;
                event.preventDefault();
                navigateDetail(link.href);
            });
        }

        function sourceUrl() {
            return origin ? paths[origin.kind] + "?" + new URLSearchParams({ page: String(pageNumber(origin.page)), ctx: origin.id }) : "/";
        }

        function invalidateSource() {
            if (!origin) return;
            const latest = read(origin.id) || origin;
            store(origin.id, { ...latest, refresh: true, viewer: null });
        }

        function returnToSource(replace) {
            if (canGoBack) window.history.back();
            else if (replace) window.location.replace(sourceUrl());
            else window.location.assign(sourceUrl());
        }

        function bindReturnLink(link) {
            link.href = sourceUrl();
            if (origin && origin.kind === "inbox") link.textContent = "← 返回 Inbox";
            link.addEventListener("click", event => {
                if (!window.ImageViewer.plainClick(event)) return;
                event.preventDefault();
                returnToSource(false);
            });
        }

        function restore() {
            if (!ready) return;
            restoreScroll();
            Object.entries(state.assets).forEach(([group, key]) => {
                const config = adapter.resolveViewer(group);
                if (config && config.onChange) config.onChange(key);
            });
            if (state.viewer) {
                const config = adapter.resolveViewer(state.viewer.groupKey);
                if (config && config.items.some(item => item.key === state.viewer.key)) {
                    showViewer(config, state.viewer.key);
                } else requestClose();
            }
        }

        window.addEventListener("popstate", event => {
            const next = event.state && event.state.iaBrowse;
            if (!valid(next, adapter.kind)) return;
            const previousPage = state.page;
            const closedViewer = state.viewer;
            const latestAssets = next.id === state.id ? state.assets : next.assets;
            window.ImageViewer.close();
            state = { ...next, assets: latestAssets };
            closing = false;
            write(false);
            const action = afterClose;
            afterClose = null;
            if (action) action();
            else if (next.page !== previousPage) {
                ready = false;
                adapter.reload(next.page);
            } else {
                restore();
                if (closedViewer && !state.viewer && adapter.onViewerClose) {
                    adapter.onViewerClose(closedViewer.groupKey, closedViewer.key);
                }
            }
        });
        function cancelRestore() { ++restoreGeneration; restoreCleanup(); }
        window.addEventListener("pagehide", () => { cancelRestore(); capture(); write(false); });
        window.addEventListener("pageshow", event => {
            if (!event.persisted) return;
            const latest = read(state.id);
            if (latest && latest.refresh) {
                window.ImageViewer.close();
                state = { ...latest, refresh: false, viewer: null };
                ready = false;
                write(false);
                adapter.reload(state.page);
            } else {
                if (latest && latest.page === state.page) {
                    state.assets = latest.assets;
                    write(false);
                }
                restore();
            }
        });
        window.addEventListener("wheel", cancelRestore, { passive: true });
        window.addEventListener("pointerdown", cancelRestore, { passive: true });
        window.addEventListener("keydown", cancelRestore);
        write(false);

        return {
            initialPage: state.page,
            imageKey: group => state.assets[group], remember, openViewer, detailHref, bindDetailLink,
            bindReturnLink, invalidateSource, returnToSource,
            locate(node, top = 0, scrollY = 0) {
                cancelRestore();
                state.anchor = node ? { key: node.dataset.browseAnchor, top } : null;
                state.scrollY = scrollY;
                write(false);
            },
            setPage(page) {
                if (page !== state.page) {
                    state.assets = {};
                    state.viewer = null;
                    state.anchor = null;
                    state.scrollY = 0;
                }
                state.page = page;
                write(false);
            },
            ready() { ready = true; state.refresh = false; write(false); restore(); }
        };
    }

    window.BrowseContext = { create };
})();
