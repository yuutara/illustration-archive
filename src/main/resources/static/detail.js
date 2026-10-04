(function () {
    "use strict";

    const state = {
        illustrationId: null,
        loading: false,
        detail: null,
        editing: false,
        saving: false,
        deleting: false,
        creatingAuthor: false,
        authorDirty: false,
        selectedAuthorId: null,
        selectedAuthor: null,
        tagsDirty: false,
        selectedTags: [],
        creatingTag: false
    };

    const detailStatus = document.getElementById("detail-status");
    const statePanel = document.getElementById("detail-state-panel");
    const stateTitle = document.getElementById("detail-state-title");
    const stateMessage = document.getElementById("detail-state-message");
    const retryButton = document.getElementById("detail-retry-button");
    const detailContent = document.getElementById("detail-content");
    const imagesElement = document.getElementById("detail-images");
    const editButton = document.getElementById("detail-edit-button");
    const deleteButton = document.getElementById("detail-delete-button");
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
    const authorCreateButton = document.getElementById("detail-author-create-button");
    const authorCreatePanel = document.getElementById("detail-author-create-panel");
    const authorCreateDisplayNameInput = document.getElementById("detail-author-create-display-name");
    const authorCreateXUsernameInput = document.getElementById("detail-author-create-x-username");
    const authorCreateSubmitButton = document.getElementById("detail-author-create-submit");
    const authorCreateCancelButton = document.getElementById("detail-author-create-cancel");
    const authorCreateStatus = document.getElementById("detail-author-create-status");
    const tagSearchInput = document.getElementById("detail-tag-search-input");
    const tagSearchButton = document.getElementById("detail-tag-search-button");
    const tagSelectionElement = document.getElementById("detail-tag-selection");
    const tagSearchStatus = document.getElementById("detail-tag-search-status");
    const tagSearchResults = document.getElementById("detail-tag-search-results");
    const tagCreateButton = document.getElementById("detail-tag-create-button");
    const tagCreatePanel = document.getElementById("detail-tag-create-panel");
    const tagCreateNameInput = document.getElementById("detail-tag-create-name");
    const tagCreateSubmitButton = document.getElementById("detail-tag-create-submit");
    const tagCreateCancelButton = document.getElementById("detail-tag-create-cancel");
    const tagCreateStatus = document.getElementById("detail-tag-create-status");
    const saveButton = document.getElementById("detail-save-button");
    const cancelButton = document.getElementById("detail-cancel-button");
    const titleElement = document.getElementById("detail-title");
    const authorNameElement = document.getElementById("detail-author-name");
    const authorHandleElement = document.getElementById("detail-author-handle");
    const tagsElement = document.getElementById("detail-tags");
    const noteElement = document.getElementById("detail-note");
    const sourceElement = document.getElementById("detail-source");
    const metaGrid = document.getElementById("detail-meta-grid");
    const imageButtons = new Map();
    const browse = window.BrowseContext.create({
        kind: "detail", resolveViewer: group => group === `illustration:${state.illustrationId}` ? viewer() : null,
        reload: () => loadDetail()
    });
    browse.bindReturnLink(document.getElementById("detail-back-link"));
    const authorPicker = window.MetadataPicker.create({
        prefix: "detail-author", endpoint: "/api/authors", label: authorLabel,
        selected: () => {
            const author = state.authorDirty ? state.selectedAuthor : state.detail?.author;
            return author ? [author] : [];
        }, choose: selectAuthor, blocked: () => !state.editing || state.saving || creationInProgress()
    });
    const tagPicker = window.MetadataPicker.create({
        prefix: "detail-tag", endpoint: "/api/tags", label: tag => tag.name,
        selected: () => state.selectedTags,
        choose: tag => state.selectedTags.some(item => item.id === Number(tag.id)) ? removeTag(Number(tag.id)) : selectTag(tag),
        blocked: () => !state.editing || state.saving || creationInProgress()
    });

    function viewer() {
        const assets = orderedAssetsFor(state.detail);
        const groupKey = `illustration:${state.illustrationId}`;
        const key = browse.imageKey(groupKey);
        return { groupKey, title: titleFor(state.detail), sourceHref: state.detail && state.detail.sourceUrl,
            metadataProvider: () => state.detail,
            detailInInspectorOnly: true,
            detailHref: key => {
                const href = new URL(window.location.href);
                href.searchParams.set("asset", key);
                return href.href;
            },
            items: assets.map(asset => ({ key: String(asset.id),
                fullUrl: `/api/assets/${encodeURIComponent(String(asset.id))}/content`, mimeType: asset.mimeType, alt: titleFor(state.detail) })),
            opener: imageButtons.get(key) || imageButtons.values().next().value };
    }

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

    function creationInProgress() {
        return state.creatingAuthor || state.creatingTag;
    }

    function setCreationControlsDisabled(disabled) {
        authorCreateButton.disabled = disabled;
        authorCreateSubmitButton.disabled = disabled;
        authorCreateCancelButton.disabled = disabled;
        authorCreateDisplayNameInput.disabled = disabled;
        authorCreateXUsernameInput.disabled = disabled;
        tagCreateButton.disabled = disabled;
        tagCreateSubmitButton.disabled = disabled;
        tagCreateCancelButton.disabled = disabled;
        tagCreateNameInput.disabled = disabled;
        saveButton.disabled = disabled;
        cancelButton.disabled = disabled;
        authorPicker.setDisabled(disabled);
        tagPicker.setDisabled(disabled);
    }

    function populateEditForm(detail) {
        titleInput.value = editableValue(detail && detail.title);
        sourceInput.value = editableValue(detail && detail.sourceUrl);
        noteInput.value = editableValue(detail && detail.note);
    }

    function authorHandle(author) {
        const xUsername = textOrFallback(author && author.xUsername, "").trim();
        if (!xUsername) {
            return "";
        }

        return xUsername.startsWith("@") ? xUsername : `@${xUsername}`;
    }

    function authorLabel(author) {
        const displayName = textOrFallback(author && author.displayName, "未知作者");
        const xUsername = authorHandle(author);
        return xUsername ? `${displayName} · ${xUsername}` : displayName;
    }

    function renderAuthorSelection() {
        authorPicker.sync();
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
        authorPicker.close();
        closeAuthorCreateForm();
        state.authorDirty = false;
        state.selectedAuthorId = null;
        state.selectedAuthor = null;
        authorSearchInput.value = "";
        authorSearchResults.replaceChildren();
        authorSearchStatus.textContent = "";
        renderAuthorSelection();
    }

    function selectAuthor(author) {
        if (!state.editing || state.saving) return;
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

    function setCreatingAuthor(creating) {
        state.creatingAuthor = creating;
        setCreationControlsDisabled(state.saving || state.deleting || creationInProgress());
    }

    function closeAuthorCreateForm() {
        setCreatingAuthor(false);
        authorCreatePanel.hidden = true;
        authorCreateDisplayNameInput.value = "";
        authorCreateXUsernameInput.value = "";
        authorCreateStatus.textContent = "";
    }

    function openAuthorCreateForm() {
        if (!state.editing || state.saving || state.deleting || state.creatingAuthor
            || state.creatingTag
            || !authorCreatePanel.hidden) {
            return;
        }

        authorCreateDisplayNameInput.value = "";
        authorCreateXUsernameInput.value = "";
        authorCreateStatus.textContent = "";
        authorCreatePanel.hidden = false;
        authorCreateDisplayNameInput.focus();
    }

    async function createAuthor() {
        if (!state.editing || state.creatingAuthor || state.creatingTag
            || state.saving || state.deleting) {
            return;
        }

        const displayName = authorCreateDisplayNameInput.value.trim();
        if (!displayName) {
            authorCreateStatus.textContent = "作者名称不能为空。";
            authorCreateDisplayNameInput.focus();
            return;
        }

        const xUsername = trimmedOrNull(authorCreateXUsernameInput.value);
        setCreatingAuthor(true);
        authorCreateStatus.textContent = "正在创建…";

        try {
            const response = await fetch("/api/authors", {
                method: "POST",
                headers: {
                    Accept: "application/json",
                    "Content-Type": "application/json"
                },
                body: JSON.stringify({
                    displayName: displayName,
                    xUsername: xUsername
                })
            });
            if (!response.ok) {
                throw new Error(`Author creation failed with status ${response.status}`);
            }

            const createdAuthor = await response.json();
            const authorId = Number(createdAuthor && createdAuthor.id);
            if (!Number.isSafeInteger(authorId) || authorId <= 0) {
                throw new Error("Created author response has no valid id.");
            }

            closeAuthorCreateForm();
            selectAuthor(createdAuthor);
            authorPicker.refresh();
            authorSearchStatus.textContent = "已创建并选择作者，保存后生效。";
        } catch (error) {
            authorCreateStatus.textContent = "创建作者失败，请检查输入后重试。";
        } finally {
            if (state.creatingAuthor) {
                setCreatingAuthor(false);
            }
        }
    }

    function validTag(tag) {
        const tagId = Number(tag && tag.id);
        return Number.isSafeInteger(tagId)
            && tagId > 0
            && typeof (tag && tag.name) === "string"
            && tag.name.trim();
    }

    function normalizeTag(tag) {
        return {
            id: Number(tag.id),
            name: tag.name.trim()
        };
    }

    function renderSelectedTags() {
        tagPicker.sync();
        tagSelectionElement.replaceChildren();
        if (state.selectedTags.length === 0) {
            const emptyTags = document.createElement("p");
            emptyTags.className = "empty-value";
            emptyTags.textContent = "暂无标签";
            tagSelectionElement.appendChild(emptyTags);
            return;
        }

        state.selectedTags.forEach(function (tag) {
            const tagButton = document.createElement("button");
            tagButton.className = "tag-chip tag-edit-chip";
            tagButton.type = "button";
            tagButton.disabled = state.saving;
            tagButton.textContent = `${tag.name} ×`;
            tagButton.setAttribute("aria-label", `移除标签 ${tag.name}`);
            tagButton.addEventListener("click", function () {
                removeTag(tag.id);
            });
            tagSelectionElement.appendChild(tagButton);
        });
    }

    function resetTagEditor() {
        tagPicker.close();
        closeTagCreateForm();
        state.tagsDirty = false;
        state.selectedTags = Array.isArray(state.detail && state.detail.tags)
            ? state.detail.tags.filter(validTag).map(normalizeTag)
            : [];
        tagSearchInput.value = "";
        tagSearchResults.replaceChildren();
        tagSearchStatus.textContent = "";
        renderSelectedTags();
    }

    function selectTag(tag) {
        if (!state.editing || state.saving) return;
        if (!validTag(tag)) {
            return;
        }

        const normalizedTag = normalizeTag(tag);
        const alreadySelected = state.selectedTags.some(function (selectedTag) {
            return selectedTag.id === normalizedTag.id;
        });
        if (alreadySelected) {
            tagSearchStatus.textContent = "该标签已选择。";
            return;
        }

        state.selectedTags.push(normalizedTag);
        state.tagsDirty = true;
        renderSelectedTags();
        tagSearchStatus.textContent = "已选择标签，保存后生效。";
    }

    function removeTag(tagId) {
        if (!state.editing || state.saving || creationInProgress()) return;
        const remainingTags = state.selectedTags.filter(function (tag) {
            return tag.id !== tagId;
        });
        if (remainingTags.length === state.selectedTags.length) {
            return;
        }

        state.selectedTags = remainingTags;
        state.tagsDirty = true;
        renderSelectedTags();
        tagSearchStatus.textContent = "已移除标签，保存后生效。";
    }

    function setCreatingTag(creating) {
        state.creatingTag = creating;
        const disabled = state.saving || state.deleting || creationInProgress();
        setCreationControlsDisabled(disabled);
        tagSearchInput.disabled = disabled;
        tagSearchButton.disabled = disabled;
        tagSelectionElement.querySelectorAll("button").forEach(function (button) {
            button.disabled = disabled;
        });
        tagSearchResults.querySelectorAll("button").forEach(function (button) {
            button.disabled = disabled;
        });
    }

    function closeTagCreateForm() {
        setCreatingTag(false);
        tagCreatePanel.hidden = true;
        tagCreateNameInput.value = "";
        tagCreateStatus.textContent = "";
    }

    function openTagCreateForm() {
        if (!state.editing || state.saving || state.deleting || state.creatingTag
            || state.creatingAuthor
            || !tagCreatePanel.hidden) {
            return;
        }

        tagCreateNameInput.value = "";
        tagCreateStatus.textContent = "";
        tagCreatePanel.hidden = false;
        tagCreateNameInput.focus();
    }

    async function createTag() {
        if (!state.editing || state.creatingTag || state.creatingAuthor
            || state.saving || state.deleting) {
            return;
        }

        const name = tagCreateNameInput.value.trim();
        if (!name) {
            tagCreateStatus.textContent = "标签名称不能为空。";
            tagCreateNameInput.focus();
            return;
        }

        setCreatingTag(true);
        tagCreateStatus.textContent = "正在创建…";

        try {
            const response = await fetch("/api/tags", {
                method: "POST",
                headers: {
                    Accept: "application/json",
                    "Content-Type": "application/json"
                },
                body: JSON.stringify({ name: name })
            });
            if (!response.ok) {
                throw new Error(`Tag creation failed with status ${response.status}`);
            }

            const createdTag = await response.json();
            if (!validTag(createdTag)) {
                throw new Error("Created tag response is invalid.");
            }

            closeTagCreateForm();
            selectTag(createdTag);
            tagPicker.refresh();
            tagSearchStatus.textContent = "已创建并选择标签，保存后生效。";
        } catch (error) {
            tagCreateStatus.textContent = "创建标签失败，请检查输入后重试。";
        } finally {
            if (state.creatingTag) {
                setCreatingTag(false);
            }
        }
    }

    function setSaving(saving) {
        state.saving = saving;
        setCreationControlsDisabled(saving || state.deleting || creationInProgress());
        titleInput.disabled = saving;
        sourceInput.disabled = saving;
        noteInput.disabled = saving;
        authorSearchInput.disabled = saving;
        authorSearchButton.disabled = saving;
        authorClearButton.disabled = saving;
        authorSearchResults.querySelectorAll("button").forEach(function (button) {
            button.disabled = saving;
        });
        tagSearchInput.disabled = saving || state.creatingTag;
        tagSearchButton.disabled = saving || state.creatingTag;
        tagSelectionElement.querySelectorAll("button").forEach(function (button) {
            button.disabled = saving || state.creatingTag;
        });
        tagSearchResults.querySelectorAll("button").forEach(function (button) {
            button.disabled = saving || state.creatingTag;
        });
    }

    function setEditing(editing) {
        state.editing = editing;
        editButton.hidden = editing;
        deleteButton.hidden = editing;
        editForm.hidden = !editing;
        titleElement.hidden = editing || !textOrFallback(state.detail && state.detail.title, "");
        metaGrid.hidden = editing;
        if (!editing) { authorPicker.close(); tagPicker.close(); }
        authorPicker.sync();
        tagPicker.sync();
    }

    function setDeleting(deleting) {
        state.deleting = deleting;
        deleteButton.disabled = deleting;
        editButton.disabled = deleting;
    }

    async function deleteIllustration() {
        if (state.deleting || state.editing || !state.detail || state.illustrationId === null) {
            return;
        }

        const confirmed = window.confirm(
            "删除后插画记录和本地文件都会被删除，当前 V0.1 没有回收站。确定继续吗？"
        );
        if (!confirmed) {
            return;
        }

        setDeleting(true);
        detailStatus.textContent = "正在删除…";

        try {
            const response = await fetch(`/api/illustrations/${state.illustrationId}`, {
                method: "DELETE",
                headers: { Accept: "application/json" }
            });
            if (!response.ok) {
                throw new Error(`Delete request failed with status ${response.status}`);
            }

            browse.invalidateSource();
            browse.returnToSource(true);
        } catch (error) {
            setDeleting(false);
            detailStatus.textContent = "删除失败，请重试。";
        }
    }

    function startEditing() {
        if (!state.detail || state.saving || state.deleting || state.creatingAuthor || state.creatingTag) {
            return;
        }

        populateEditForm(state.detail);
        resetAuthorEditor();
        resetTagEditor();
        setEditing(true);
        detailStatus.textContent = "正在编辑";
        titleInput.focus();
    }

    function cancelEditing() {
        if (state.saving || state.deleting || state.creatingAuthor || state.creatingTag) {
            return;
        }

        resetAuthorEditor();
        resetTagEditor();
        setEditing(false);
        detailStatus.textContent = "";
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

    function orderedAssetsFor(detail) {
        const assets = Array.isArray(detail && detail.assets)
            ? detail.assets.filter(function (asset) {
                return asset && asset.id !== null && asset.id !== undefined;
            })
            : [];

        return assets.sort(function (left, right) {
            return Number(left.sortOrder) - Number(right.sortOrder)
                || Number(left.id) - Number(right.id);
        });
    }

    function renderImages(detail) {
        imageButtons.clear();
        imagesElement.querySelectorAll("video").forEach(video => {
            video.pause(); video.removeAttribute("src"); video.load();
        });
        imagesElement.replaceChildren();
        const assets = orderedAssetsFor(detail);
        if (assets.length === 0) {
            const emptyImage = document.createElement("div");
            emptyImage.className = "detail-image";
            const emptyMessage = document.createElement("span");
            emptyMessage.className = "detail-image-fallback";
            emptyMessage.textContent = "暂无图片";
            emptyImage.appendChild(emptyMessage);
            imagesElement.appendChild(emptyImage);
            return;
        }

        assets.forEach(function (asset, index) {
            const imageContainer = document.createElement("button");
            imageContainer.type = "button";
            imageContainer.className = "detail-image image-open-button";
            imageContainer.dataset.browseAnchor = `asset:${asset.id}`;
            imageContainer.setAttribute("aria-label", `放大查看第 ${index + 1} 张图片`);
            imageContainer.addEventListener("click", () => {
                if (state.saving || state.deleting) return;
                browse.openViewer({ ...viewer(), startKey: String(asset.id) }, imageContainer);
            });
            imageButtons.set(String(asset.id), imageContainer);
            const image = document.createElement(asset.mimeType === "video/mp4" ? "video" : "img");
            if (asset.mimeType === "video/mp4") {
                image.muted = true; image.loop = true; image.autoplay = true; image.playsInline = true;
                image.preload = "metadata";
            }
            image.alt = assets.length === 1
                ? titleFor(detail)
                : `${titleFor(detail)} · 第 ${index + 1} 张`;
            image.loading = index === 0 ? "eager" : "lazy";
            const fallback = document.createElement("span");
            fallback.className = "detail-image-fallback";
            fallback.textContent = "图片加载失败";
            fallback.hidden = true;
            image.addEventListener("error", function () {
                image.hidden = true;
                fallback.hidden = false;
            }, { once: true });
            imageContainer.appendChild(image);
            imageContainer.appendChild(fallback);
            image.src = `/api/assets/${encodeURIComponent(String(asset.id))}/content`;
            imagesElement.appendChild(imageContainer);
        });
    }

    function renderAuthor(detail) {
        const author = detail && detail.author;
        authorNameElement.replaceChildren();
        const name = textOrFallback(author && author.displayName, "作者未填写");
        if (author && author.id != null && /^[1-9]\d*$/.test(String(author.id))) {
            const link = document.createElement("a");
            link.className = "detail-author-link";
            link.href = window.BrowseContext.galleryHref({ authorId: String(author.id) });
            link.textContent = name;
            authorNameElement.appendChild(link);
        } else authorNameElement.textContent = name;
        authorNameElement.classList.toggle("is-empty", !textOrFallback(author && author.displayName, ""));

        const xUsername = authorHandle(author);
        if (xUsername) {
            authorHandleElement.textContent = xUsername;
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
            document.getElementById("detail-tags-section").hidden = true;
            return;
        }
        document.getElementById("detail-tags-section").hidden = false;

        tags.forEach(function (tag) {
            const navigable = tag.id != null && /^[1-9]\d*$/.test(String(tag.id));
            const tagElement = document.createElement(navigable ? "a" : "span");
            if (navigable) tagElement.href = window.BrowseContext.galleryHref({ tagId: String(tag.id) });
            tagElement.className = "tag-chip";
            tagElement.textContent = tag.name;
            tagsElement.appendChild(tagElement);
        });
    }

    function renderSource(detail) {
        sourceElement.replaceChildren();
        const section = document.getElementById("detail-source-section");
        section.hidden = true;
        const sourceUrl = textOrFallback(detail && detail.sourceUrl, "");
        if (!sourceUrl) {
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
            sourceLink.textContent = `${parsedUrl.hostname} ↗`;
            sourceLink.title = sourceUrl;
            sourceElement.appendChild(sourceLink);
            section.hidden = false;
        } catch (error) {
            // Preserve the existing protocol validation; invalid sources have no reading block.
        }
    }

    function renderDetail(detail) {
        state.detail = detail;
        titleElement.textContent = textOrFallback(detail && detail.title, "");
        renderImages(detail);
        renderAuthor(detail);
        renderTags(detail);
        noteElement.textContent = textOrFallback(detail && detail.note, "");
        document.getElementById("detail-note-section").hidden = !noteElement.textContent;
        renderSource(detail);
        resetAuthorEditor();
        resetTagEditor();
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
                cache: "no-store",
                headers: { Accept: "application/json" }
            });
            if (!response.ok) {
                throw new Error(`Detail request failed with status ${response.status}`);
            }

            const detail = await response.json();
            renderDetail(detail);
            const assetId = new URLSearchParams(window.location.search).get("asset");
            if (assetId && !browse.imageKey(`illustration:${state.illustrationId}`)) {
                const target = imageButtons.get(assetId);
                if (target) {
                    browse.remember(`illustration:${state.illustrationId}`, assetId);
                    browse.locate(target);
                }
            }
            browse.ready();
            detailStatus.textContent = "";
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
        if (state.saving || state.deleting || state.creatingAuthor || state.creatingTag
            || state.illustrationId === null) {
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
        if (state.tagsDirty) {
            payload.tagIds = state.selectedTags.map(function (tag) {
                return tag.id;
            });
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
            browse.invalidateSource();
            await loadDetail();
        } catch (error) {
            detailStatus.textContent = "保存失败，请重试";
        } finally {
            setSaving(false);
        }
    }

    retryButton.addEventListener("click", loadDetail);
    editButton.addEventListener("click", startEditing);
    deleteButton.addEventListener("click", deleteIllustration);
    editForm.addEventListener("submit", saveDetail);
    cancelButton.addEventListener("click", cancelEditing);
    authorClearButton.addEventListener("click", clearAuthorSelection);
    authorCreateButton.addEventListener("click", openAuthorCreateForm);
    authorCreateSubmitButton.addEventListener("click", createAuthor);
    authorCreateCancelButton.addEventListener("click", closeAuthorCreateForm);
    tagCreateButton.addEventListener("click", openTagCreateForm);
    tagCreateSubmitButton.addEventListener("click", createTag);
    tagCreateCancelButton.addEventListener("click", closeTagCreateForm);
    state.illustrationId = readIllustrationId();
    if (state.illustrationId === null) {
        detailStatus.textContent = "无法加载";
        showState("插画 ID 无效", "请从图库选择一幅插画后再查看详情。", false);
        return;
    }

    loadDetail();
})();
