(function () {
    "use strict";

    const list = document.getElementById("inbox-list");
    const count = document.getElementById("inbox-count");
    const selectedCount = document.getElementById("inbox-selected-count");
    const toolbar = document.getElementById("inbox-toolbar");
    const message = document.getElementById("inbox-message");
    const successLinks = document.getElementById("inbox-success-links");
    const statePanel = document.getElementById("inbox-state");
    const stateTitle = document.getElementById("inbox-state-title");
    const stateMessage = document.getElementById("inbox-state-message");
    const retryButton = document.getElementById("inbox-retry-button");
    const syncButton = document.getElementById("sync-button");
    const previousButton = document.getElementById("inbox-previous");
    const nextButton = document.getElementById("inbox-next");
    const pageStatus = document.getElementById("inbox-page-status");
    const selectAllButton = document.getElementById("select-all-button");
    const clearButton = document.getElementById("clear-button");
    const selectFailedButton = document.getElementById("select-failed-button");
    const skipButton = document.getElementById("skip-button");
    const importButton = document.getElementById("import-button");
    const importResults = document.getElementById("import-results");
    const importResultsSummary = document.getElementById("import-results-summary");
    const importResultsList = document.getElementById("import-results-list");
    let items = [];
    let busy = false;
    let loading = false;
    let page = 0;
    let totalPages = 0;
    let refreshRequired = false;
    let loadGeneration = 0;
    let messageTimer;
    const pageSize = 24;
    const viewerItems = new Map();
    // Only Archive returns per-item outcomes. Keep those reasons in this document,
    // separate from the current page's checkbox selection and Skip's aggregate result.
    const archiveIssues = new Map();
    const browse = window.BrowseContext.create({
        kind: "inbox", resolveViewer: group => viewerItems.get(group), reload: restoredPage => {
            return loadInbox({ targetPage: restoredPage, preservePosition: false });
        }, onViewerClose: (group, key) => {
            const config = viewerItems.get(group);
            const opener = config && config.mediaButtons.get(key);
            if (opener && opener.isConnected) opener.focus({ preventScroll: true });
        }
    });
    page = browse.initialPage;

    function setMessage(text, error, transient = false) {
        window.clearTimeout(messageTimer);
        message.textContent = text;
        message.classList.toggle("is-error", Boolean(error));
        message.classList.toggle("is-success", Boolean(text) && !error);
        if (transient && text && !error) messageTimer = window.setTimeout(() => setMessage("", false), 6000);
    }

    function showState(title, detail, retry) {
        stateTitle.textContent = title;
        stateMessage.textContent = detail;
        retryButton.hidden = !retry;
        statePanel.hidden = false;
    }

    function selectedIds() {
        const loadedIds = new Set(items.map(item => item.id));
        return Array.from(list.querySelectorAll("input[type=checkbox]:checked"), input => Number(input.value))
            .filter(id => loadedIds.has(id));
    }

    function updateActions() {
        const selectionCount = selectedIds().length;
        const locked = busy || loading || refreshRequired;
        selectedCount.textContent = `本页已选 ${selectionCount} 项`;
        syncButton.disabled = locked;
        selectAllButton.disabled = locked || items.length === 0 || selectionCount === items.length;
        clearButton.disabled = locked || selectionCount === 0;
        selectFailedButton.hidden = !items.some(item => archiveIssues.get(item.id)?.status === "FAILED");
        selectFailedButton.disabled = locked || selectFailedButton.hidden;
        skipButton.disabled = locked || selectionCount === 0;
        importButton.disabled = locked || selectionCount === 0;
        previousButton.disabled = busy || loading || page === 0;
        nextButton.disabled = busy || loading || page + 1 >= totalPages;
        list.querySelectorAll("input[type=checkbox]").forEach(input => {
            input.disabled = locked;
            input.closest("[data-browse-anchor]").classList.toggle("is-selected", input.checked);
        });
        list.querySelectorAll(".image-open-button").forEach(button => { button.disabled = busy || loading; });
        document.querySelectorAll(".inbox-reselect").forEach(button => {
            button.disabled = locked || !items.some(item => item.id === Number(button.dataset.itemId));
        });
    }

    function interactiveTarget(target, card) {
        for (let node = target; node && node !== card; node = node.parentNode) {
            // Text blocks remain selectable, including double-click/long-press word selection.
            if (["a", "button", "input", "label", "select", "textarea", "summary", "details", "p", "time"]
                .includes(node.tagName.toLowerCase()) || node.isContentEditable || node.dataset.noSelect != null) return true;
        }
        return false;
    }

    function bindCardSelection(card, checkbox) {
        let pointer = null;
        card.addEventListener("pointerdown", event => {
            pointer = { x: event.clientX, y: event.clientY, scrollY: window.scrollY,
                moved: !event.isPrimary || event.button !== 0 || window.getSelection()?.isCollapsed === false };
        });
        card.addEventListener("pointermove", event => {
            if (pointer && Math.hypot(event.clientX - pointer.x, event.clientY - pointer.y) > 8) pointer.moved = true;
        });
        card.addEventListener("pointercancel", () => { if (pointer) pointer.moved = true; });
        card.addEventListener("click", event => {
            const gesture = pointer;
            pointer = null;
            if (checkbox.disabled || event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey
                || event.shiftKey || event.altKey || interactiveTarget(event.target, card)
                || window.getSelection()?.isCollapsed === false
                || gesture && (gesture.moved || Math.abs(window.scrollY - gesture.scrollY) > 8)) return;
            checkbox.checked = !checkbox.checked;
            updateActions();
        });
    }

    function selectItem(itemId) {
        if (busy || loading || refreshRequired) return;
        const checkbox = Array.from(list.querySelectorAll("input[type=checkbox]"))
            .find(input => Number(input.value) === itemId);
        if (!checkbox) return;
        checkbox.checked = true;
        checkbox.focus({ preventScroll: true });
        updateActions();
    }

    function reselectButton(itemId) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "secondary-button inbox-reselect";
        button.dataset.itemId = String(itemId);
        button.textContent = "选择此项";
        button.addEventListener("click", () => selectItem(itemId));
        return button;
    }

    function issueFeedback(itemId) {
        const issue = archiveIssues.get(itemId);
        if (!issue) return null;
        const feedback = document.createElement("div");
        feedback.className = "inbox-item-feedback";
        feedback.dataset.noSelect = "";
        feedback.appendChild(paragraph("inbox-result-warning", issue.status === "DUPLICATE"
            ? "上次归档：图片重复，仍待处理" : "上次归档：失败，仍待处理"));
        const reason = document.createElement("details");
        const summary = document.createElement("summary");
        summary.textContent = "查看原因";
        reason.append(summary, paragraph("inbox-result-reason", issue.reason || "后端未提供原因。"));
        feedback.append(reason, reselectButton(itemId));
        return feedback;
    }

    function positionSnapshot() {
        const cards = Array.from(list.children);
        const edge = Math.max(0, toolbar.getBoundingClientRect().bottom);
        const anchor = cards.find(card => card.getBoundingClientRect().bottom > edge
            && card.getBoundingClientRect().top < window.innerHeight);
        return { page, ids: items.map(item => item.id), anchorId: anchor ? Number(anchor.dataset.itemId) : null,
            top: anchor ? anchor.getBoundingClientRect().top : edge + 8, scrollY: window.scrollY };
    }

    function restoreNearby(snapshot, changedPage) {
        if (!snapshot) return;
        const cards = Array.from(list.children);
        const byId = new Map(cards.map(card => [Number(card.dataset.itemId), card]));
        let target = !changedPage && byId.get(snapshot.anchorId);
        let top = snapshot.top;
        if (!target) {
            const index = snapshot.ids.indexOf(snapshot.anchorId);
            const nearby = index < 0 ? [] : [...snapshot.ids.slice(index + 1), ...snapshot.ids.slice(0, index).reverse()];
            target = !changedPage && nearby.map(id => byId.get(id)).find(Boolean) || cards[0];
            top = Math.max(0, toolbar.getBoundingClientRect().bottom) + 8;
            if (!changedPage && target && index >= 0) top = Math.max(top, snapshot.top);
        }
        browse.locate(target || null, top, target ? snapshot.scrollY : 0);
    }

    function inboxMediaUrl(itemId, media) {
        return media.mediaType === "animated_gif"
            ? `/api/x-import/inbox/${encodeURIComponent(String(itemId))}/media/${encodeURIComponent(media.mediaKey)}/content`
            : media.sourceUrl;
    }

    function paragraph(className, text) {
        const element = document.createElement("p");
        element.className = className;
        element.textContent = text;
        return element;
    }

    function renderItem(item) {
        const card = document.createElement("article");
        card.className = "inbox-card";
        const groupKey = `inbox:${item.id}`;
        card.dataset.browseAnchor = groupKey;
        card.dataset.itemId = String(item.id);
        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.value = String(item.id);
        checkbox.className = "inbox-checkbox";
        checkbox.setAttribute("aria-label", `选择 ${item.authorDisplayName || item.authorUsername} 的 Post`);
        checkbox.addEventListener("change", updateActions);
        const selectLabel = document.createElement("label");
        selectLabel.className = "inbox-select-control";
        const selectText = document.createElement("span");
        selectText.textContent = "选择";
        selectLabel.append(checkbox, selectText);
        bindCardSelection(card, checkbox);

        const content = document.createElement("div");
        content.className = "inbox-card-content";
        const header = document.createElement("div");
        header.className = "inbox-card-header";
        const author = document.createElement("div");
        author.append(paragraph("inbox-author", item.authorDisplayName || item.authorUsername || "Unknown author"),
            paragraph("inbox-handle", `@${item.authorUsername || "unknown"}`));
        const time = document.createElement("time");
        time.className = "inbox-time";
        if (item.postCreatedAt) {
            const date = new Date(item.postCreatedAt);
            if (!Number.isNaN(date.getTime())) {
                time.dateTime = item.postCreatedAt;
                time.textContent = date.toLocaleString();
            }
        }
        header.append(author, time);
        content.appendChild(header);
        if (item.discoveredAt) {
            const discovered = new Date(item.discoveredAt);
            if (!Number.isNaN(discovered.getTime())) {
                content.appendChild(paragraph("inbox-time", `进入 Inbox：${discovered.toLocaleString()}`));
            }
        }
        if (item.postText) {
            content.appendChild(paragraph("inbox-post-text", item.postText));
        }

        const media = Array.isArray(item.media) ? [...item.media] : [];
        media.sort((a, b) => a.sortOrder - b.sortOrder);
        const preview = document.createElement("div");
        preview.className = media.length === 1 ? "inbox-media single" : "inbox-media";
        const config = {
            groupKey, title: item.authorDisplayName || item.authorUsername || "X Post",
            mediaButtons: new Map(),
            onChange: key => {
                config.mediaButtons.forEach((button, mediaKey) => {
                    button.classList.toggle("is-current-media", mediaKey === key);
                    if (mediaKey === key) button.setAttribute("aria-current", "true");
                    else button.removeAttribute("aria-current");
                });
                if (config.mediaButtons.has(key)) config.opener = config.mediaButtons.get(key);
            },
            sourceHref: `https://x.com/${encodeURIComponent(item.authorUsername)}/status/${encodeURIComponent(item.xPostId)}`,
            items: media.map((photo, index) => ({ key: photo.mediaKey,
                fullUrl: inboxMediaUrl(item.id, photo), mimeType: photo.mediaType === "animated_gif" ? "video/mp4" : "image/jpeg",
                alt: `${item.authorDisplayName || item.authorUsername || "Post"} 的图片 ${index + 1}` }))
        };
        viewerItems.set(groupKey, config);
        media.forEach((photo, index) => {
            const openButton = document.createElement("button");
            openButton.type = "button";
            openButton.className = "image-open-button";
            openButton.setAttribute("aria-label", `放大查看第 ${index + 1} 张图片`);
            openButton.addEventListener("click", () => {
                if (!busy && !loading) browse.openViewer({ ...config, startKey: photo.mediaKey }, openButton);
            });
            config.mediaButtons.set(photo.mediaKey, openButton);
            if (photo.mediaKey === browse.imageKey(groupKey) || !config.opener) config.opener = openButton;
            const image = document.createElement(photo.mediaType === "animated_gif" ? "video" : "img");
            if (photo.mediaType === "animated_gif") {
                image.muted = true; image.loop = true; image.autoplay = true; image.playsInline = true;
                image.preload = "metadata";
            }
            image.src = inboxMediaUrl(item.id, photo);
            image.alt = `${item.authorDisplayName || item.authorUsername || "Post"} 的图片 ${index + 1}`;
            image.loading = "lazy";
            if (photo.width && photo.height) {
                image.width = photo.width;
                image.height = photo.height;
            }
            openButton.appendChild(image);
            preview.appendChild(openButton);
        });
        content.appendChild(preview);

        const footer = document.createElement("div");
        footer.className = "inbox-card-footer";
        footer.appendChild(paragraph("inbox-media-count", `${media.length} ${media.length === 1 ? "image" : "images"}`));
        const link = document.createElement("a");
        link.className = "back-link";
        link.href = `https://x.com/${encodeURIComponent(item.authorUsername)}/status/${encodeURIComponent(item.xPostId)}`;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        link.textContent = "Open on X ↗";
        footer.appendChild(link);
        content.appendChild(footer);
        const feedback = issueFeedback(item.id);
        if (feedback) content.appendChild(feedback);
        card.append(selectLabel, content);
        return card;
    }

    function renderImportResults(result, selectedItems) {
        importResultsSummary.textContent = `共 ${result.total} 项：成功 ${result.successCount}，重复 ${result.duplicateCount}，失败 ${result.failureCount}。`;
        importResultsList.replaceChildren();
        successLinks.replaceChildren();
        result.items.forEach(itemResult => {
            const source = selectedItems.get(itemResult.itemId);
            const name = source
                ? `${source.authorDisplayName || source.authorUsername || "Unknown author"} · X Post ${source.xPostId}`
                : `Inbox 项目 ${itemResult.itemId}`;
            const row = document.createElement("li");
            row.className = "inbox-result-item";
            row.appendChild(paragraph("inbox-result-name", name));
            if (itemResult.status === "SUCCESS") {
                row.appendChild(paragraph("inbox-result-success", "导入成功"));
                if (itemResult.illustrationId != null) {
                    const link = document.createElement("a");
                    browse.bindDetailLink(link, itemResult.illustrationId);
                    link.textContent = "查看 Illustration Detail";
                    row.appendChild(link);
                    const directLink = document.createElement("a");
                    directLink.className = "inbox-success-link";
                    browse.bindDetailLink(directLink, itemResult.illustrationId);
                    directLink.textContent = `查看已归档作品：${name}`;
                    successLinks.appendChild(directLink);
                }
            } else if (itemResult.status === "DUPLICATE") {
                row.appendChild(paragraph("inbox-result-warning", "图片重复，未导入；仍在待处理列表中，可重新尝试或选择 Skip。"));
                if (itemResult.reason) row.appendChild(paragraph("inbox-result-reason", itemResult.reason));
                row.appendChild(reselectButton(itemResult.itemId));
            } else {
                row.appendChild(paragraph("inbox-result-warning", "导入失败，仍在待处理列表中，可重新尝试或选择 Skip。"));
                row.appendChild(paragraph("inbox-result-reason", itemResult.reason || "未提供失败原因。"));
                row.appendChild(reselectButton(itemResult.itemId));
            }
            importResultsList.appendChild(row);
        });
        importResults.hidden = false;
        importResults.open = false;
        successLinks.hidden = successLinks.children.length === 0;
    }

    async function loadInbox({ targetPage = page, preservePosition = true } = {}) {
        const ticket = ++loadGeneration;
        let resolvedPage = targetPage;
        loading = true;
        if (items.length === 0) showState("正在加载…", "正在读取本地 Inbox。", false);
        retryButton.disabled = true;
        list.setAttribute("aria-busy", "true");
        updateActions();
        try {
            let data;
            while (true) {
                const response = await fetch(`/api/x-import/inbox?page=${resolvedPage}&size=${pageSize}`, { headers: { Accept: "application/json" } });
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
                data = await response.json();
                if (ticket !== loadGeneration) return false;
                if (!Array.isArray(data.items) || !Number.isInteger(data.totalPages) || data.totalPages < 0
                    || !Number.isInteger(data.totalItems) || data.totalItems < 0) throw new Error("Inbox response is invalid.");
                if (resolvedPage === 0 || resolvedPage < data.totalPages) break;
                resolvedPage = Math.max(0, data.totalPages - 1);
            }
            // Capture the still-visible page immediately before replacement, so scrolling
            // while a request is in flight is respected. Inbox owns the neighbour policy.
            const snapshot = preservePosition && items.length > 0 ? positionSnapshot() : null;
            const changedPage = resolvedPage !== page;
            page = resolvedPage;
            browse.setPage(page);
            items = data.items;
            totalPages = data.totalPages;
            viewerItems.clear();
            const cards = items.map(renderItem);
            list.querySelectorAll("video").forEach(video => {
                video.pause(); video.removeAttribute("src"); video.load();
            });
            list.replaceChildren(...cards);
            count.textContent = `${data.totalItems} pending`;
            pageStatus.textContent = `Page ${totalPages === 0 ? 0 : page + 1} / ${totalPages}`;
            statePanel.hidden = items.length > 0;
            if (items.length === 0) showState("No pending X Likes", "可以手动同步最近的 Likes。", false);
            refreshRequired = false;
            updateActions();
            restoreNearby(snapshot, changedPage);
            browse.ready();
            return true;
        } catch (error) {
            if (ticket !== loadGeneration) return false;
            refreshRequired = true;
            showState("加载失败", `无法读取 Inbox：${error.message}`, true);
            return false;
        } finally {
            if (ticket === loadGeneration) {
                loading = false;
                retryButton.disabled = false;
                list.setAttribute("aria-busy", "false");
                updateActions();
            }
        }
    }

    async function syncLatest() {
        if (busy || loading || refreshRequired) return;
        busy = true;
        updateActions();
        setMessage("正在同步最近最多 3 页 Likes…", false);
        try {
            const response = await fetch("/api/x-import/sync/recent?maxResults=5&maxPages=3", { method: "POST", headers: { Accept: "application/json" } });
            const result = await response.json();
            if (!response.ok) throw new Error(result.message || `HTTP ${response.status}`);
            setMessage(`同步完成：请求 ${result.pagesFetched} 页，读取 ${result.fetchedCount}，新增 ${result.newCount}，已有 ${result.existingCount}，待处理 ${result.pendingCount}。${result.stoppedByMaxPages ? "已达页数上限，下次点击仍从最新 Likes 开始。" : ""}${result.stoppedByInvalidToken ? "分页游标异常，已停止。" : ""}`, false);
            await loadInbox({ targetPage: 0, preservePosition: false });
        } catch (error) {
            setMessage(`同步失败：${error.message}`, true);
        } finally {
            busy = false;
            updateActions();
        }
    }

    async function processSelected(action) {
        if (busy || loading || refreshRequired) return;
        const ids = selectedIds();
        if (ids.length === 0) return;
        const selectedItems = new Map(items.filter(item => ids.includes(item.id)).map(item => [item.id, item]));
        const archiving = action === "import";
        busy = true;
        updateActions();
        setMessage(`正在${archiving ? "归档" : "跳过"} ${ids.length} 个选中项目…`, false);
        try {
            const response = await fetch(`/api/x-import/inbox/${action}`, {
                method: archiving ? "POST" : "PATCH",
                headers: { Accept: "application/json", "Content-Type": "application/json" },
                body: JSON.stringify({ itemIds: ids })
            });
            const result = await response.json();
            if (!response.ok) throw new Error(result.message || `HTTP ${response.status}`);
            let summary;
            if (archiving) {
                if (!Array.isArray(result.items) || result.items.length !== ids.length
                    || new Set(result.items.map(item => item.itemId)).size !== ids.length
                    || result.items.some(item => !selectedItems.has(item.itemId)
                        || !["SUCCESS", "DUPLICATE", "FAILED"].includes(item.status))
                    || result.total !== ids.length || ["successCount", "duplicateCount", "failureCount"]
                        .some((field, index) => result[field] !== result.items.filter(item => item.status === ["SUCCESS", "DUPLICATE", "FAILED"][index]).length)) {
                    throw new Error("归档结果无法确认，请重新加载 Inbox 核对状态。");
                }
                result.items.forEach(item => {
                    if (item.status === "SUCCESS") archiveIssues.delete(item.itemId);
                    else archiveIssues.set(item.itemId, item);
                });
                summary = `已归档 ${result.successCount} 项；重复 ${result.duplicateCount} 项；失败 ${result.failureCount} 项。`;
            } else {
                if (result.requestedCount !== ids.length || !Number.isInteger(result.skippedCount)
                    || result.skippedCount < 0 || result.skippedCount > result.requestedCount) {
                    throw new Error("跳过结果无法确认，请重新加载 Inbox 核对状态。");
                }
                // No per-item outcomes are returned. Only a complete transition count
                // confirms all selected IDs; page absence alone cannot identify a skipped ID.
                if (result.skippedCount === ids.length) ids.forEach(id => archiveIssues.delete(id));
                summary = `实际跳过 ${result.skippedCount} / ${result.requestedCount} 个项目。`;
            }
            const refreshed = await loadInbox();
            if (archiving) renderImportResults(result, selectedItems);
            else {
                importResultsSummary.textContent = summary;
                importResultsList.replaceChildren(paragraph("inbox-result-name", "Skip 仅返回实际处理数量；可根据更新后的待处理列表继续选择。"));
                importResults.hidden = false;
                importResults.open = false;
            }
            setMessage(summary + (refreshed ? "" : " 列表更新失败，请重新加载后继续处理。"), !refreshed, refreshed);
        } catch (error) {
            refreshRequired = true;
            setMessage(`${archiving ? "归档" : "跳过"}请求未能确认结果：${error.message}`, true);
            showState("请核对待处理状态", "重新加载 Inbox 后再选择项目；不会自动重试本次写操作。", true);
        } finally {
            busy = false;
            updateActions();
        }
    }

    syncButton.addEventListener("click", syncLatest);
    skipButton.addEventListener("click", () => processSelected("skip"));
    importButton.addEventListener("click", () => processSelected("import"));
    previousButton.addEventListener("click", async () => {
        if (busy || loading || page === 0) return;
        await loadInbox({ targetPage: page - 1, preservePosition: false });
    });
    nextButton.addEventListener("click", async () => {
        if (busy || loading || page + 1 >= totalPages) return;
        await loadInbox({ targetPage: page + 1, preservePosition: false });
    });
    selectAllButton.addEventListener("click", () => {
        if (busy || loading || refreshRequired) return;
        list.querySelectorAll("input[type=checkbox]").forEach(input => { input.checked = true; });
        updateActions();
    });
    clearButton.addEventListener("click", () => {
        if (busy || loading || refreshRequired) return;
        list.querySelectorAll("input[type=checkbox]").forEach(input => { input.checked = false; });
        updateActions();
    });
    selectFailedButton.addEventListener("click", () => {
        if (busy || loading || refreshRequired) return;
        list.querySelectorAll("input[type=checkbox]").forEach(input => {
            if (archiveIssues.get(Number(input.value))?.status === "FAILED") input.checked = true;
        });
        updateActions();
    });
    retryButton.addEventListener("click", async () => {
        if (busy || loading) return;
        if (await loadInbox()) setMessage("", false);
    });
    loadInbox();
}());
