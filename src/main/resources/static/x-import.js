(function () {
    "use strict";

    const list = document.getElementById("inbox-list");
    const count = document.getElementById("inbox-count");
    const message = document.getElementById("inbox-message");
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
    const pageSize = 24;
    const viewerItems = new Map();
    const browse = window.BrowseContext.create({
        kind: "inbox", resolveViewer: group => viewerItems.get(group), reload: restoredPage => {
            page = restoredPage;
            return loadInbox();
        }
    });
    page = browse.initialPage;

    function setMessage(text, error) {
        message.textContent = text;
        message.classList.toggle("is-error", Boolean(error));
        message.classList.toggle("is-success", Boolean(text) && !error);
    }

    function showState(title, detail, retry) {
        stateTitle.textContent = title;
        stateMessage.textContent = detail;
        retryButton.hidden = !retry;
        statePanel.hidden = false;
    }

    function selectedIds() {
        return Array.from(list.querySelectorAll("input[type=checkbox]:checked"), input => Number(input.value));
    }

    function updateActions() {
        const selectionCount = selectedIds().length;
        syncButton.disabled = busy;
        selectAllButton.disabled = busy || loading || items.length === 0 || selectionCount === items.length;
        clearButton.disabled = busy || loading || selectionCount === 0;
        skipButton.disabled = busy || loading || selectionCount === 0;
        importButton.disabled = busy || loading || selectionCount === 0;
        previousButton.disabled = busy || loading || page === 0;
        nextButton.disabled = busy || loading || page + 1 >= totalPages;
        list.querySelectorAll("input[type=checkbox]").forEach(input => { input.disabled = busy; });
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
        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.value = String(item.id);
        checkbox.className = "inbox-checkbox";
        checkbox.setAttribute("aria-label", `选择 ${item.authorDisplayName || item.authorUsername} 的 Post`);
        checkbox.addEventListener("change", updateActions);

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
            sourceHref: `https://x.com/${encodeURIComponent(item.authorUsername)}/status/${encodeURIComponent(item.xPostId)}`,
            items: media.map((photo, index) => ({ key: photo.mediaKey,
                fullUrl: photo.photoUrl, alt: `${item.authorDisplayName || item.authorUsername || "Post"} 的图片 ${index + 1}` }))
        };
        viewerItems.set(groupKey, config);
        media.forEach((photo, index) => {
            const openButton = document.createElement("button");
            openButton.type = "button";
            openButton.className = "image-open-button";
            openButton.setAttribute("aria-label", `放大查看第 ${index + 1} 张图片`);
            openButton.addEventListener("click", () => browse.openViewer({ ...config, startKey: photo.mediaKey }, openButton));
            if (photo.mediaKey === browse.imageKey(groupKey) || !config.opener) config.opener = openButton;
            const image = document.createElement("img");
            image.src = photo.photoUrl;
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
        card.append(checkbox, content);
        return card;
    }

    function renderImportResults(result, selectedItems) {
        importResultsSummary.textContent = `共 ${result.total} 项：成功 ${result.successCount}，重复 ${result.duplicateCount}，失败 ${result.failureCount}。`;
        importResultsList.replaceChildren();
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
                }
            } else if (itemResult.status === "DUPLICATE") {
                row.appendChild(paragraph("inbox-result-warning", "图片重复，未导入；仍在待处理列表中，可重新尝试或选择 Skip。"));
                if (itemResult.reason) row.appendChild(paragraph("inbox-result-reason", itemResult.reason));
            } else {
                row.appendChild(paragraph("inbox-result-warning", "导入失败，仍在待处理列表中，可重新尝试或选择 Skip。"));
                row.appendChild(paragraph("inbox-result-reason", itemResult.reason || "未提供失败原因。"));
            }
            importResultsList.appendChild(row);
        });
        importResults.hidden = false;
    }

    async function loadInbox() {
        loading = true;
        items = [];
        viewerItems.clear();
        list.replaceChildren();
        count.textContent = "";
        showState("正在加载…", "正在读取本地 Inbox。", false);
        updateActions();
        try {
            const response = await fetch(`/api/x-import/inbox?page=${page}&size=${pageSize}`, { headers: { Accept: "application/json" } });
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const data = await response.json();
            if (!Array.isArray(data.items) || !Number.isInteger(data.totalPages)
                    || !Number.isInteger(data.totalItems)) throw new Error("Inbox response is invalid.");
            if (page > 0 && page >= data.totalPages) {
                page = Math.max(0, data.totalPages - 1);
                return await loadInbox();
            }
            items = data.items;
            totalPages = data.totalPages;
            browse.setPage(page);
            items.forEach(item => list.appendChild(renderItem(item)));
            count.textContent = `${data.totalItems} pending`;
            pageStatus.textContent = `Page ${totalPages === 0 ? 0 : page + 1} / ${totalPages}`;
            statePanel.hidden = items.length > 0;
            if (items.length === 0) showState("No pending X Likes", "可以手动同步最近的 Likes。", false);
            browse.ready();
        } catch (error) {
            showState("加载失败", `无法读取 Inbox：${error.message}`, true);
        } finally {
            loading = false;
            updateActions();
        }
    }

    async function syncLatest() {
        if (busy) return;
        busy = true;
        updateActions();
        setMessage("正在同步最近最多 3 页 Likes…", false);
        try {
            const response = await fetch("/api/x-import/sync/recent?maxResults=5&maxPages=3", { method: "POST", headers: { Accept: "application/json" } });
            const result = await response.json();
            if (!response.ok) throw new Error(result.message || `HTTP ${response.status}`);
            setMessage(`同步完成：请求 ${result.pagesFetched} 页，读取 ${result.fetchedCount}，新增 ${result.newCount}，已有 ${result.existingCount}，待处理 ${result.pendingCount}。${result.stoppedByMaxPages ? "已达页数上限，下次点击仍从最新 Likes 开始。" : ""}${result.stoppedByInvalidToken ? "分页游标异常，已停止。" : ""}`, false);
            page = 0;
            await loadInbox();
        } catch (error) {
            setMessage(`同步失败：${error.message}`, true);
        } finally {
            busy = false;
            updateActions();
        }
    }

    async function skipSelected() {
        if (busy) return;
        const ids = selectedIds();
        if (ids.length === 0) return;
        busy = true;
        updateActions();
        setMessage("正在跳过选中的项目…", false);
        try {
            const response = await fetch("/api/x-import/inbox/skip", {
                method: "PATCH",
                headers: { Accept: "application/json", "Content-Type": "application/json" },
                body: JSON.stringify({ itemIds: ids })
            });
            const result = await response.json();
            if (!response.ok) throw new Error(result.message || `HTTP ${response.status}`);
            setMessage(`实际跳过 ${result.skippedCount} / ${result.requestedCount} 个项目。${result.skippedCount < result.requestedCount ? "其余项目已处理或不存在。" : ""}`, false);
            await loadInbox();
        } catch (error) {
            setMessage(`跳过失败：${error.message}`, true);
        } finally {
            busy = false;
            updateActions();
        }
    }

    async function importSelected() {
        if (busy) return;
        const ids = selectedIds();
        if (ids.length === 0) return;
        const selectedItems = new Map(items.filter(item => ids.includes(item.id)).map(item => [item.id, item]));
        busy = true;
        updateActions();
        setMessage("正在导入选中的项目…", false);
        try {
            const response = await fetch("/api/x-import/inbox/import", {
                method: "POST",
                headers: { Accept: "application/json", "Content-Type": "application/json" },
                body: JSON.stringify({ itemIds: ids })
            });
            const result = await response.json();
            if (!response.ok) throw new Error(result.message || `HTTP ${response.status}`);
            renderImportResults(result, selectedItems);
            await loadInbox();
            setMessage("本次导入已处理，逐项结果如下。", false);
        } catch (error) {
            setMessage(`导入失败：${error.message}`, true);
        } finally {
            busy = false;
            updateActions();
        }
    }

    syncButton.addEventListener("click", syncLatest);
    skipButton.addEventListener("click", skipSelected);
    importButton.addEventListener("click", importSelected);
    previousButton.addEventListener("click", async () => {
        if (busy || loading || page === 0) return;
        page--;
        await loadInbox();
    });
    nextButton.addEventListener("click", async () => {
        if (busy || loading || page + 1 >= totalPages) return;
        page++;
        await loadInbox();
    });
    selectAllButton.addEventListener("click", () => {
        list.querySelectorAll("input[type=checkbox]").forEach(input => { input.checked = true; });
        updateActions();
    });
    clearButton.addEventListener("click", () => {
        list.querySelectorAll("input[type=checkbox]").forEach(input => { input.checked = false; });
        updateActions();
    });
    retryButton.addEventListener("click", loadInbox);
    loadInbox();
}());
