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
    const selectAllButton = document.getElementById("select-all-button");
    const clearButton = document.getElementById("clear-button");
    const skipButton = document.getElementById("skip-button");
    let items = [];
    let busy = false;
    let loading = false;

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
        if (item.postText) {
            content.appendChild(paragraph("inbox-post-text", item.postText));
        }

        const media = Array.isArray(item.media) ? [...item.media] : [];
        media.sort((a, b) => a.sortOrder - b.sortOrder);
        const preview = document.createElement("div");
        preview.className = media.length === 1 ? "inbox-media single" : "inbox-media";
        media.forEach((photo, index) => {
            const image = document.createElement("img");
            image.src = photo.photoUrl;
            image.alt = `${item.authorDisplayName || item.authorUsername || "Post"} 的图片 ${index + 1}`;
            image.loading = "lazy";
            if (photo.width && photo.height) {
                image.width = photo.width;
                image.height = photo.height;
            }
            preview.appendChild(image);
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

    async function loadInbox() {
        loading = true;
        items = [];
        list.replaceChildren();
        count.textContent = "";
        showState("正在加载…", "正在读取本地 Inbox。", false);
        updateActions();
        try {
            const response = await fetch("/api/x-import/inbox", { headers: { Accept: "application/json" } });
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            const data = await response.json();
            if (!Array.isArray(data)) throw new Error("Inbox response is invalid.");
            items = data;
            data.forEach(item => list.appendChild(renderItem(item)));
            count.textContent = `${data.length} pending`;
            statePanel.hidden = data.length > 0;
            if (data.length === 0) showState("No pending X Likes", "可以手动同步最近的 Likes。", false);
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
        setMessage("正在同步最近 5 个 Likes…", false);
        try {
            const response = await fetch("/api/x-import/sync/recent?maxResults=5", { method: "POST", headers: { Accept: "application/json" } });
            const result = await response.json();
            if (!response.ok) throw new Error(result.message || `HTTP ${response.status}`);
            setMessage(`同步完成：读取 ${result.fetchedCount}，新增 ${result.newCount}，已有 ${result.existingCount}，待处理 ${result.pendingCount}。`, false);
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
            setMessage(`已跳过 ${result.skippedCount} / ${result.requestedCount} 个项目。`, false);
            await loadInbox();
        } catch (error) {
            setMessage(`跳过失败：${error.message}`, true);
        } finally {
            busy = false;
            updateActions();
        }
    }

    syncButton.addEventListener("click", syncLatest);
    skipButton.addEventListener("click", skipSelected);
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
