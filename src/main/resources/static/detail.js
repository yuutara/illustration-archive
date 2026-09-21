(function () {
    "use strict";

    const state = {
        illustrationId: null,
        loading: false,
        detail: null,
        editing: false,
        saving: false,
        authorDirty: false,
        selectedAuthorId: null,
        selectedAuthor: null,
        authorSearching: false
    };

    const detailStatus = document.getElementById("detail-status");
    const statePanel = document.getElementById("detail-state-panel");
    const stateTitle = document.getElementById("detail-state-title");
    const stateMessage = document.getElementById("detail-state-message");
    const retryButton = document.getElementById("detail-retry-button");
    const detailContent = document.getElementById("detail-content");
    const imageElement = document.getElementById("detail-image-element");
    const imageFallback = document.getElementById("detail-image-fallback");
    const editButton = document.getElementById("detail-edit-button");
    const editForm = document.getElementById("detail-edit-form");
    const titleInput = document.getElementById("detail-title-input");
    const sourceInput = document.getElementById("detail-source-input");
    const noteInput = document.getElementById("detail-note-input");
    const authorSearchInput = document.getElementById("detail-author-search-input");
    const authorSearchButton = document.getElementById("detail-author-search-button");
    const authorClearButton = document.getElementById("detail-author-clear-button");
    const authorSelectionElement = document.getElementById("detail-author-selection");
    const authorSearchStatus = document.getElementById("detail-author-search-status");
    const authorSearchResults = document.getElementById("detail-author-search-results");
    const saveButton = document.getElementById("detail-save-button");
    const cancelButton = document.getElementById("detail-cancel-button");
    const titleElement = document.getElementById("detail-title");
    const authorNameElement = document.getElementById("detail-author-name");
    const authorHandleElement = document.getElementById("detail-author-handle");
    const tagsElement = document.getElementById("detail-tags");
    const noteElement = document.getElementById("detail-note");
    const sourceElement = document.getElementById("detail-source");
    const metaGrid = document.getElementById("detail-meta-grid");

    function readIllustrationId() {
        const rawId = new URLSearchParams(window.location.search).get("id");
        if (!rawId || !/^\d+$/.test(rawId)) {
            return null;
        }

        const id = Number(rawId);
        return Number.isSafeInteger(id) && id > 0 ? id : null;
    }

    function titleFor(detail) {
        return detail && typeof detail.title === "string" && detail.title.trim()
            ? detail.title
            : "未命名";
    }

    function textOrFallback(value, fallback) {
        return typeof value === "string" && value.trim() ? value : fallback;
    }

    function editableValue(value) {
        return typeof value === "string" ? value : "";
    }

    function trimmedOrNull(value) {
        const trimmed = value.trim();
        return trimmed ? trimmed : null;
    }

    function populateEditForm(detail) {
        titleInput.value = editableValue(detail && detail.title);
        sourceInput.value = editableValue(detail && detail.sourceUrl);
        noteInput.value = editableValue(detail && detail.note);
    }

    function authorLabel(author) {
        const displayName = textOrFallback(author && author.displayName, "未知作者");
        const xUsername = textOrFallback(author && author.xUsername, "");
        return xUsername ? `${displayName} · ${xUsername}` : displayName;
    }

    function renderAuthorSelection() {
        if (state.authorDirty) {
            authorSelectionElement.textContent = state.selectedAuthorId === null
                ? "已选择清除作者关联（保存后生效）"
                : `已选择作者：${authorLabel(state.selectedAuthor)}（保存后生效）`;
            return;
        }

        const currentAuthor = state.detail && state.detail.author;
        authorSelectionElement.textContent = currentAuthor
            ? `当前作者：${authorLabel(currentAuthor)}（未修改）`
            : "当前作者：未知作者（未修改）";
    }

    function resetAuthorEditor() {
        state.authorDirty = false;
        state.selectedAuthorId = null;
        state.selectedAuthor = null;
        authorSearchInput.value = "";
        authorSearchResults.replaceChildren();
        authorSearchStatus.textContent = "";
        renderAuthorSelection();
    }

    function selectAuthor(author) {
        const authorId = Number(author && author.id);
        if (!Number.isSafeInteger(authorId) || authorId <= 0) {
            return;
        }

        state.authorDirty = true;
        state.selectedAuthorId = authorId;
        state.selectedAuthor = author;
        renderAuthorSelection();
        authorSearchStatus.textContent = "已选择作者，保存后生效。";
    }

    function clearAuthorSelection() {
        if (state.saving) {
            return;
        }

        state.authorDirty = true;
        state.selectedAuthorId = null;
        state.selectedAuthor = null;
        renderAuthorSelection();
        authorSearchStatus.textContent = "已选择清除作者关联，保存后生效。";
    }

    function renderAuthorSearchResults(authors) {
        authorSearchResults.replaceChildren();
        const validAuthors = Array.isArray(authors)
            ? authors.filter(function (author) {
                const authorId = Number(author && author.id);
                return Number.isSafeInteger(authorId) && authorId > 0;
            })
            : [];

        if (validAuthors.length === 0) {
            const emptyResults = document.createElement("li");
            emptyResults.className = "empty-value";
            emptyResults.textContent = "没有找到匹配作者";
            authorSearchResults.appendChild(emptyResults);
            return 0;
        }

        validAuthors.forEach(function (author) {
            const resultItem = document.createElement("li");
            const resultButton = document.createElement("button");
            resultButton.className = "author-search-result";
            resultButton.type = "button";
            resultButton.disabled = state.saving;
            resultButton.textContent = authorLabel(author);
            resultButton.addEventListener("click", function () {
                selectAuthor(author);
            });
            resultItem.appendChild(resultButton);
            authorSearchResults.appendChild(resultItem);
        });

        return validAuthors.length;
    }

    async function searchAuthors() {
        if (state.saving || state.authorSearching) {
            return;
        }

        const keyword = authorSearchInput.value.trim();
        if (!keyword) {
            authorSearchResults.replaceChildren();
            authorSearchStatus.textContent = "请输入关键词后搜索。";
            return;
        }

        state.authorSearching = true;
        authorSearchButton.disabled = true;
        authorSearchStatus.textContent = "正在搜索…";
        authorSearchResults.replaceChildren();

        try {
            const query = new URLSearchParams({ keyword: keyword });
            const response = await fetch(`/api/authors?${query.toString()}`, {
                headers: { Accept: "application/json" }
            });
            if (!response.ok) {
                throw new Error(`Author search failed with status ${response.status}`);
            }

            const authors = await response.json();
            const resultCount = renderAuthorSearchResults(authors);
            authorSearchStatus.textContent = resultCount > 0
                ? `找到 ${resultCount} 位作者。`
                : "没有找到匹配作者。";
        } catch (error) {
            authorSearchStatus.textContent = "搜索作者失败，请重试。";
        } finally {
            state.authorSearching = false;
            authorSearchButton.disabled = state.saving;
        }
    }

    function setSaving(saving) {
        state.saving = saving;
        saveButton.disabled = saving;
        cancelButton.disabled = saving;
        titleInput.disabled = saving;
        sourceInput.disabled = saving;
        noteInput.disabled = saving;
        authorSearchInput.disabled = saving;
        authorSearchButton.disabled = saving || state.authorSearching;
        authorClearButton.disabled = saving;
        authorSearchResults.querySelectorAll("button").forEach(function (button) {
            button.disabled = saving;
        });
    }

    function setEditing(editing) {
        state.editing = editing;
        editButton.hidden = editing;
        editForm.hidden = !editing;
        titleElement.hidden = editing;
        metaGrid.hidden = editing;
    }

    function startEditing() {
        if (!state.detail || state.saving) {
            return;
        }

        populateEditForm(state.detail);
        resetAuthorEditor();
        setEditing(true);
        detailStatus.textContent = "正在编辑";
        titleInput.focus();
    }

    function cancelEditing() {
        if (state.saving) {
            return;
        }

        resetAuthorEditor();
        setEditing(false);
        detailStatus.textContent = "详情已加载";
    }

    function showState(title, message, canRetry) {
        detailContent.hidden = true;
        statePanel.hidden = false;
        stateTitle.textContent = title;
        stateMessage.textContent = message;
        retryButton.hidden = !canRetry;
    }

    function showDetail() {
        statePanel.hidden = true;
        detailContent.hidden = false;
    }

    function primaryAssetFor(detail) {
        const assets = Array.isArray(detail && detail.assets)
            ? detail.assets.filter(function (asset) {
                return asset && asset.id !== null && asset.id !== undefined;
            })
            : [];

        return assets.reduce(function (primaryAsset, asset) {
            if (!primaryAsset) {
                return asset;
            }

            const assetSortOrder = Number(asset.sortOrder);
            const primarySortOrder = Number(primaryAsset.sortOrder);
            if (assetSortOrder < primarySortOrder) {
                return asset;
            }
            if (assetSortOrder === primarySortOrder
                && Number(asset.id) < Number(primaryAsset.id)) {
                return asset;
            }
            return primaryAsset;
        }, null);
    }

    function renderImage(detail) {
        imageElement.hidden = true;
        imageElement.removeAttribute("src");
        imageFallback.hidden = false;
        imageFallback.textContent = "暂无图片";

        const primaryAsset = primaryAssetFor(detail);
        if (!primaryAsset) {
            return;
        }

        imageFallback.hidden = true;
        imageFallback.textContent = "图片加载失败";
        imageElement.alt = titleFor(detail);
        imageElement.src = `/api/assets/${encodeURIComponent(String(primaryAsset.id))}/content`;
        imageElement.addEventListener("load", function () {
            imageElement.hidden = false;
            imageFallback.hidden = true;
        }, { once: true });
        imageElement.addEventListener("error", function () {
            imageElement.hidden = true;
            imageFallback.hidden = false;
        }, { once: true });
    }

    function renderAuthor(detail) {
        const author = detail && detail.author;
        authorNameElement.textContent = textOrFallback(
            author && author.displayName,
            "未知作者"
        );

        const xUsername = textOrFallback(author && author.xUsername, "");
        if (xUsername) {
            authorHandleElement.textContent = xUsername.startsWith("@")
                ? xUsername
                : `@${xUsername}`;
            authorHandleElement.hidden = false;
        } else {
            authorHandleElement.textContent = "";
            authorHandleElement.hidden = true;
        }
    }

    function renderTags(detail) {
        tagsElement.replaceChildren();
        const tags = Array.isArray(detail && detail.tags)
            ? detail.tags.filter(function (tag) {
                return tag && typeof tag.name === "string" && tag.name.trim();
            })
            : [];

        if (tags.length === 0) {
            const emptyTags = document.createElement("p");
            emptyTags.className = "empty-value";
            emptyTags.textContent = "暂无标签";
            tagsElement.appendChild(emptyTags);
            return;
        }

        tags.forEach(function (tag) {
            const tagElement = document.createElement("span");
            tagElement.className = "tag-chip";
            tagElement.textContent = tag.name;
            tagsElement.appendChild(tagElement);
        });
    }

    function renderSource(detail) {
        sourceElement.replaceChildren();
        const sourceUrl = textOrFallback(detail && detail.sourceUrl, "");
        if (!sourceUrl) {
            const emptySource = document.createElement("p");
            emptySource.className = "empty-value";
            emptySource.textContent = "暂无来源";
            sourceElement.appendChild(emptySource);
            return;
        }

        try {
            const parsedUrl = new URL(sourceUrl);
            if (parsedUrl.protocol !== "http:" && parsedUrl.protocol !== "https:") {
                throw new Error("Unsupported source URL protocol");
            }

            const sourceLink = document.createElement("a");
            sourceLink.className = "detail-source-link";
            sourceLink.href = parsedUrl.href;
            sourceLink.target = "_blank";
            sourceLink.rel = "noreferrer";
            sourceLink.textContent = sourceUrl;
            sourceElement.appendChild(sourceLink);
        } catch (error) {
            const invalidSource = document.createElement("p");
            invalidSource.className = "empty-value";
            invalidSource.textContent = "暂无来源";
            sourceElement.appendChild(invalidSource);
        }
    }

    function renderDetail(detail) {
        state.detail = detail;
        titleElement.textContent = titleFor(detail);
        renderImage(detail);
        renderAuthor(detail);
        renderTags(detail);
        noteElement.textContent = textOrFallback(detail && detail.note, "暂无备注");
        renderSource(detail);
        resetAuthorEditor();
        setEditing(false);
        showDetail();
    }

    async function loadDetail() {
        if (state.loading || state.illustrationId === null) {
            return false;
        }

        state.loading = true;
        detailStatus.textContent = "正在加载…";
        showState("正在加载", "正在读取插画详情。", false);

        try {
            const response = await fetch(`/api/illustrations/${state.illustrationId}`, {
                headers: { Accept: "application/json" }
            });
            if (!response.ok) {
                throw new Error(`Detail request failed with status ${response.status}`);
            }

            const detail = await response.json();
            renderDetail(detail);
            detailStatus.textContent = "详情已加载";
            return true;
        } catch (error) {
            detailStatus.textContent = "加载失败";
            showState("详情加载失败", "暂时无法读取这幅插画，请稍后重试。", true);
            return false;
        } finally {
            state.loading = false;
        }
    }

    async function saveDetail(event) {
        event.preventDefault();
        if (state.saving || state.illustrationId === null) {
            return;
        }

        const payload = {
            title: trimmedOrNull(titleInput.value),
            sourceUrl: trimmedOrNull(sourceInput.value),
            note: trimmedOrNull(noteInput.value)
        };
        if (state.authorDirty) {
            payload.authorId = state.selectedAuthorId;
        }

        setSaving(true);
        detailStatus.textContent = "正在保存…";

        try {
            const response = await fetch(`/api/illustrations/${state.illustrationId}`, {
                method: "PATCH",
                headers: {
                    Accept: "application/json",
                    "Content-Type": "application/json"
                },
                body: JSON.stringify(payload)
            });
            if (!response.ok) {
                throw new Error(`Update request failed with status ${response.status}`);
            }

            setEditing(false);
            const refreshed = await loadDetail();
            if (refreshed) {
                detailStatus.textContent = "已保存";
            }
        } catch (error) {
            detailStatus.textContent = "保存失败，请重试";
        } finally {
            setSaving(false);
        }
    }

    retryButton.addEventListener("click", loadDetail);
    editButton.addEventListener("click", startEditing);
    editForm.addEventListener("submit", saveDetail);
    cancelButton.addEventListener("click", cancelEditing);
    authorSearchButton.addEventListener("click", searchAuthors);
    authorSearchInput.addEventListener("keydown", function (event) {
        if (event.key === "Enter") {
            event.preventDefault();
            searchAuthors();
        }
    });
    authorClearButton.addEventListener("click", clearAuthorSelection);

    state.illustrationId = readIllustrationId();
    if (state.illustrationId === null) {
        detailStatus.textContent = "无法加载";
        showState("插画 ID 无效", "请从图库选择一幅插画后再查看详情。", false);
        return;
    }

    loadDetail();
})();
