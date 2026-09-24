(function () {
    "use strict";

    const pageSize = 24;
    const state = {
        page: 0,
        pendingPage: 0,
        totalPages: 0,
        totalElements: 0,
        loading: false,
        importing: false
    };

    const gallery = document.getElementById("gallery");
    const statePanel = document.getElementById("state-panel");
    const stateTitle = document.getElementById("state-title");
    const stateMessage = document.getElementById("state-message");
    const retryButton = document.getElementById("retry-button");
    const previousButton = document.getElementById("previous-button");
    const nextButton = document.getElementById("next-button");
    const pageInfo = document.getElementById("page-info");
    const totalCount = document.getElementById("total-count");
    const loadStatus = document.getElementById("load-status");
    const importFilesInput = document.getElementById("import-files");
    const selectedFilesCount = document.getElementById("selected-files-count");
    const selectedFilesList = document.getElementById("selected-files-list");
    const importButton = document.getElementById("import-button");
    const importStatus = document.getElementById("import-status");
    const importResult = document.getElementById("import-result");
    const importTotal = document.getElementById("import-total");
    const importSuccessCount = document.getElementById("import-success-count");
    const importFailureCount = document.getElementById("import-failure-count");
    const importItems = document.getElementById("import-items");

    function nonNegativeCount(value, fallback) {
        const count = Number(value);
        return Number.isInteger(count) && count >= 0 ? count : fallback;
    }

    function setImportStatus(message, kind) {
        importStatus.textContent = message;
        importStatus.classList.toggle("is-error", kind === "error");
        importStatus.classList.toggle("is-success", kind === "success");
    }

    function clearImportResult() {
        importResult.hidden = true;
        importItems.replaceChildren();
        importTotal.textContent = "0";
        importSuccessCount.textContent = "0";
        importFailureCount.textContent = "0";
    }

    function renderSelectedFiles() {
        const files = Array.from(importFilesInput.files || []);
        selectedFilesList.replaceChildren();
        importButton.disabled = files.length === 0 || state.importing;

        if (files.length === 0) {
            selectedFilesCount.textContent = "尚未选择文件";
            selectedFilesList.hidden = true;
            return;
        }

        selectedFilesCount.textContent = `已选择 ${files.length} 个文件`;
        files.forEach(function (file) {
            const listItem = document.createElement("li");
            listItem.textContent = file.name;
            selectedFilesList.appendChild(listItem);
        });
        selectedFilesList.hidden = false;
    }

    function clearProcessedFileSelection() {
        importFilesInput.value = "";
        renderSelectedFiles();
    }

    function renderImportResult(data) {
        const items = Array.isArray(data && data.items) ? data.items : [];
        importTotal.textContent = String(nonNegativeCount(data && data.total, items.length));
        importSuccessCount.textContent = String(nonNegativeCount(data && data.successCount, 0));
        importFailureCount.textContent = String(nonNegativeCount(data && data.failureCount, 0));
        importItems.replaceChildren();

        items.forEach(function (item) {
            const listItem = document.createElement("li");
            listItem.className = "import-item";

            const filename = document.createElement("span");
            filename.className = "import-item-filename";
            filename.textContent = item && typeof item.filename === "string" && item.filename.trim()
                ? item.filename
                : "未命名文件";

            const outcome = document.createElement("span");
            if (item && item.success === true) {
                outcome.className = "import-item-success";
                outcome.textContent = "导入成功";
            } else {
                outcome.className = "import-item-failure";
                const details = [];
                if (item && typeof item.errorCode === "string" && item.errorCode.trim()) {
                    details.push(item.errorCode);
                }
                if (item && typeof item.message === "string" && item.message.trim()) {
                    details.push(item.message);
                }
                outcome.textContent = details.length > 0
                    ? `导入失败：${details.join("，")}`
                    : "导入失败";
            }

            listItem.append(filename, outcome);
            importItems.appendChild(listItem);
        });
        importResult.hidden = false;
    }

    async function importIllustrations() {
        if (state.importing) {
            return;
        }

        const files = Array.from(importFilesInput.files || []);
        if (files.length === 0) {
            setImportStatus("请先选择要导入的文件。", "error");
            return;
        }

        state.importing = true;
        importFilesInput.disabled = true;
        importButton.disabled = true;
        clearImportResult();
        setImportStatus("正在导入…", "");

        const formData = new FormData();
        files.forEach(function (file) {
            formData.append("files", file);
        });

        let batchHandled = false;
        try {
            const response = await fetch("/api/illustrations/import", {
                method: "POST",
                headers: { Accept: "application/json" },
                body: formData
            });
            if (!response.ok) {
                throw new Error(`Import request failed with status ${response.status}`);
            }

            const data = await response.json();
            renderImportResult(data);
            clearProcessedFileSelection();
            batchHandled = true;

            const successCount = nonNegativeCount(data && data.successCount, 0);
            const failureCount = nonNegativeCount(data && data.failureCount, 0);
            const summary = `批量导入完成：成功 ${successCount} 个，失败 ${failureCount} 个`;
            setImportStatus(`${summary}。`, failureCount > 0 ? "" : "success");

            if (successCount > 0) {
                setImportStatus(`${summary}，正在刷新图库…`, failureCount > 0 ? "" : "success");
                await loadPage(state.page);
                setImportStatus(`${summary}。图库已刷新。`, failureCount > 0 ? "" : "success");
            }
        } catch (error) {
            setImportStatus("导入请求失败，请重试。", "error");
        } finally {
            state.importing = false;
            importFilesInput.disabled = false;
            if (!batchHandled) {
                renderSelectedFiles();
            }
        }
    }

    function titleFor(item) {
        return item && typeof item.title === "string" && item.title.trim()
            ? item.title
            : "未命名";
    }

    function authorFor(item) {
        const displayName = item && item.author && item.author.displayName;
        return typeof displayName === "string" && displayName.trim()
            ? displayName
            : "未知作者";
    }

    function galleryImageUrl(item) {
        const assetId = item && item.coverAssetId;
        if (assetId === null || assetId === undefined) {
            return null;
        }

        const mimeType = item.coverMimeType;
        if (mimeType === "image/jpeg" || mimeType === "image/png") {
            return `/api/assets/${encodeURIComponent(String(assetId))}/thumbnail`;
        }
        if (mimeType === "image/gif") {
            return `/api/assets/${encodeURIComponent(String(assetId))}/content`;
        }
        return null;
    }

    function updatePagination() {
        pageInfo.textContent = state.totalPages > 0
            ? `第 ${state.page + 1} / ${state.totalPages} 页`
            : "第 0 / 0 页";
        totalCount.textContent = state.totalElements > 0
            ? `${state.totalElements} 幅插画`
            : "";
        previousButton.disabled = state.loading || state.page <= 0;
        nextButton.disabled = state.loading
            || state.totalPages === 0
            || state.page >= state.totalPages - 1;
    }

    function showState(title, message, canRetry) {
        gallery.replaceChildren();
        gallery.hidden = true;
        statePanel.hidden = false;
        stateTitle.textContent = title;
        stateMessage.textContent = message;
        retryButton.hidden = !canRetry;
    }

    function showGallery(items) {
        gallery.replaceChildren();
        statePanel.hidden = true;

        if (items.length === 0) {
            showState("暂无插画", "还没有可以展示的插画。", false);
            return;
        }

        items.forEach(function (item) {
            gallery.appendChild(createCard(item));
        });
        gallery.hidden = false;
    }

    function createCard(item) {
        const title = titleFor(item);
        const card = document.createElement("a");
        card.className = "illustration-card";
        if (item && item.id !== null && item.id !== undefined) {
            card.href = `/detail.html?id=${encodeURIComponent(String(item.id))}`;
        }

        const imageContainer = document.createElement("div");
        imageContainer.className = "card-image";

        const fallback = document.createElement("span");
        fallback.className = "image-fallback";
        fallback.textContent = "图片加载失败";
        fallback.hidden = true;

        const assetId = item && item.coverAssetId;
        const imageUrl = galleryImageUrl(item);
        if (imageUrl !== null) {
            const image = document.createElement("img");
            image.src = imageUrl;
            image.alt = title;
            image.loading = "lazy";
            image.decoding = "async";
            image.addEventListener("error", function () {
                image.hidden = true;
                fallback.hidden = false;
            }, { once: true });
            imageContainer.appendChild(image);
        } else {
            fallback.textContent = assetId === null || assetId === undefined ? "暂无图片" : "图片加载失败";
            fallback.hidden = false;
        }
        imageContainer.appendChild(fallback);

        const cardBody = document.createElement("div");
        cardBody.className = "card-body";

        const titleElement = document.createElement("h3");
        titleElement.className = "card-title";
        titleElement.title = title;
        titleElement.textContent = title;

        const authorElement = document.createElement("p");
        authorElement.className = "card-author";
        authorElement.textContent = authorFor(item);

        cardBody.append(titleElement, authorElement);
        card.append(imageContainer, cardBody);
        return card;
    }

    async function loadPage(page) {
        if (state.loading || page < 0) {
            return;
        }

        state.loading = true;
        state.pendingPage = page;
        updatePagination();
        loadStatus.textContent = "正在加载…";
        showState("正在加载", "正在读取图库内容。", false);

        try {
            const response = await fetch(`/api/illustrations?page=${page}&size=${pageSize}`, {
                headers: { Accept: "application/json" }
            });
            if (!response.ok) {
                throw new Error(`Gallery request failed with status ${response.status}`);
            }

            const data = await response.json();
            const responsePage = Number(data.page);
            const responseTotalPages = Number(data.totalPages);
            const responseTotalElements = Number(data.totalElements);

            state.page = Number.isInteger(responsePage) && responsePage >= 0
                ? responsePage
                : page;
            state.totalPages = Number.isInteger(responseTotalPages) && responseTotalPages >= 0
                ? responseTotalPages
                : 0;
            state.totalElements = Number.isInteger(responseTotalElements) && responseTotalElements >= 0
                ? responseTotalElements
                : 0;

            showGallery(Array.isArray(data.items) ? data.items : []);
            loadStatus.textContent = "图库已更新";
        } catch (error) {
            loadStatus.textContent = "加载失败";
            showState("图库加载失败", "暂时无法读取图库，请稍后重试。", true);
        } finally {
            state.loading = false;
            updatePagination();
        }
    }

    previousButton.addEventListener("click", function () {
        loadPage(state.page - 1);
    });

    nextButton.addEventListener("click", function () {
        loadPage(state.page + 1);
    });

    retryButton.addEventListener("click", function () {
        loadPage(state.pendingPage);
    });

    importFilesInput.addEventListener("change", function () {
        clearImportResult();
        setImportStatus("", "");
        renderSelectedFiles();
    });

    importButton.addEventListener("click", importIllustrations);

    loadPage(0);
})();
