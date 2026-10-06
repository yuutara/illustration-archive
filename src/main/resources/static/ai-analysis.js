(function () {
    "use strict";

    const sessions = new Map();
    const handoffPrefix = "illustration-archive:ai-handoff:v1:";

    function createSession(id) {
        let status = "idle";
        let result = null;
        let error = "";
        let request = null;
        const listeners = new Set();
        function snapshot() { return { status, result, error }; }
        function notify() { listeners.forEach(listener => listener(snapshot())); }
        function abort() {
            if (!request) return;
            const previous = request;
            request = null;
            previous.abort();
            status = "idle";
            error = "";
            notify();
        }
        async function start() {
            if (status === "loading" || status === "ready") return;
            const current = new window.AbortController();
            request = current;
            status = "loading";
            error = "";
            notify();
            let timedOut = false;
            const timer = window.setTimeout(() => { timedOut = true; current.abort(); }, 120000);
            try {
                const response = await fetch(`/api/illustrations/${encodeURIComponent(String(id))}/ai-analysis`, {
                    method: "POST", headers: { Accept: "application/json" }, signal: current.signal, cache: "no-store"
                });
                if (request !== current) return;
                const body = await response.json();
                if (request !== current) return;
                if (!response.ok) throw new Error(body.message || "AI 分析暂不可用，请稍后再试。");
                result = body;
                status = "ready";
                notify();
            } catch (failure) {
                if (request !== current) return;
                status = "error";
                error = timedOut ? "AI 分析超时，请稍后再试。" : failure.message || "AI 分析请求失败。";
                notify();
            } finally {
                window.clearTimeout(timer);
                if (request === current) request = null;
            }
        }
        return {
            snapshot, start, abort,
            subscribe(listener) { listeners.add(listener); listener(snapshot()); return () => listeners.delete(listener); },
            accept(value) { abort(); result = value; status = "ready"; error = ""; notify(); }
        };
    }

    function forIllustration(id) {
        const key = String(id);
        if (!sessions.has(key)) sessions.set(key, createSession(key));
        return sessions.get(key);
    }

    function paragraph(parent, value, className) {
        const node = document.createElement("p");
        if (className) node.className = className;
        node.textContent = value;
        parent.appendChild(node);
        return node;
    }

    function heading(parent, value, tag = "h3") {
        const node = document.createElement(tag);
        node.textContent = value;
        parent.appendChild(node);
    }

    function section(parent, title, lines) {
        heading(parent, title);
        (lines.length ? lines : ["暂无候选"]).forEach(line => paragraph(parent, line));
    }

    function renderCurrent(parent, result, assetKey) {
        const overviewOpen = parent.querySelectorAll(".image-viewer-ai-overview")[0]?.open || false;
        parent.replaceChildren();
        const mediaIndex = result.analyzedAssets.findIndex(media => String(media.assetId) === String(assetKey));
        const skipped = result.skippedAssets.find(media => String(media.assetId) === String(assetKey));
        if (mediaIndex >= 0) {
            const media = result.analyzedAssets[mediaIndex];
            const page = result.pages.find(item => item.index === mediaIndex + 1);
            heading(parent, `当前图片 · 第 ${media.position} 张`);
            paragraph(parent, page.description);
            heading(parent, "画面文字 / 翻译", "h4");
            if (!page.texts.length) paragraph(parent, "暂无可读文字");
            page.texts.forEach(text => {
                paragraph(parent, `原文：${text.source}`, "image-viewer-ai-source");
                paragraph(parent, `简体中文：${text.translation}`, "image-viewer-ai-translation");
                if (text.note) paragraph(parent, `位置 / 类型：${text.note}`, "image-viewer-ai-note");
            });
        } else if (skipped) {
            heading(parent, `当前图片 · 第 ${skipped.position} 张`);
            paragraph(parent, `此媒体（${skipped.mimeType}）未参与分析。`);
        } else {
            heading(parent, "当前图片");
            paragraph(parent, "这张图片没有对应的分析结果。");
        }
        const overview = document.createElement("details");
        overview.className = "image-viewer-ai-overview";
        overview.open = overviewOpen;
        const trigger = document.createElement("summary");
        trigger.textContent = "全篇摘要、角色与标签";
        overview.appendChild(trigger);
        section(overview, "全篇摘要", [result.summary]);
        section(overview, "角色候选", result.characters.map(character => `${character.name || "身份未确认的角色"}：${character.description}`));
        section(overview, "关系候选", result.relationships);
        section(overview, "Tag 建议", result.suggestedTags);
        if (result.skippedAssets.length) section(overview, "已跳过的媒体", result.skippedAssets.map(media =>
            `作品第 ${media.position} 张（${media.mimeType}）未分析。`));
        paragraph(overview, `本次模型：${result.model}`, "image-viewer-ai-notice");
        parent.appendChild(overview);
    }

    function prepareHandoff(token, id, assetKey, result) {
        if (!result || !token) return false;
        try {
            window.sessionStorage.setItem(handoffPrefix + token, JSON.stringify({
                id: String(id), assetKey: String(assetKey),
                expiresAt: Date.now() + 5 * 60 * 1000, result
            }));
            return true;
        } catch (error) { return false; }
    }

    function validMedia(result, assets) {
        if (!result || typeof result !== "object" || typeof result.summary !== "string" || typeof result.model !== "string"
            || !Array.isArray(result.pages) || !Array.isArray(result.analyzedAssets)
            || !Array.isArray(result.skippedAssets) || !Array.isArray(result.characters)
            || !Array.isArray(result.relationships) || !Array.isArray(result.suggestedTags)
            || result.pages.length !== result.analyzedAssets.length) return false;
        const media = [...result.analyzedAssets, ...result.skippedAssets];
        if (media.length !== assets.length || new Set(media.map(item => String(item.assetId))).size !== assets.length) return false;
        if (!assets.every((asset, index) => media.some(item => String(item.assetId) === String(asset.id)
            && item.position === index + 1 && item.mimeType === asset.mimeType))) return false;
        return result.pages.every((page, index) => page.index === index + 1 && typeof page.description === "string"
            && Array.isArray(page.texts) && page.texts.every(text => typeof text.source === "string"
                && typeof text.translation === "string" && (text.note == null || typeof text.note === "string")))
            && result.characters.every(item => item && typeof item.description === "string")
            && result.relationships.every(item => typeof item === "string")
            && result.suggestedTags.every(item => typeof item === "string");
    }

    function takeHandoff(token, id, assets) {
        if (!token) return { status: "none" };
        const key = handoffPrefix + token;
        let raw;
        try {
            raw = window.sessionStorage.getItem(key);
            if (!raw) return { status: "none" };
            window.sessionStorage.removeItem(key);
            const value = JSON.parse(raw);
            if (value.expiresAt < Date.now() || String(value.id) !== String(id)
                || !assets.some(asset => String(asset.id) === value.assetKey)
                || !validMedia(value.result, assets)) return { status: "invalid" };
            return { status: "ready", ...value };
        } catch (error) { return { status: raw ? "invalid" : "none" }; }
    }

    window.addEventListener("pagehide", () => sessions.forEach(session => session.abort()));
    window.AiAnalysis = { forIllustration, renderCurrent, prepareHandoff, takeHandoff };
})();
