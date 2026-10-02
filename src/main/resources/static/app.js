(function () {
    "use strict";

    const pageSize = 24;
    const cardViews = new Map();
    const browse = window.BrowseContext.create({
        kind: "gallery",
        resolveViewer: group => cardViews.get(group)?.viewer(),
        reload: page => loadPage(page)
    });
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
    const importToggle = document.getElementById("import-toggle");
    const importPanel = document.getElementById("import-panel");
    let layoutRatios = [];
    let layoutWidth = 0;
    let resizeFrame = false;
    const sizeCacheKey = "ia:masonry:sizes:v1";
    const sizeCache = new Map();
    try {
        const saved = JSON.parse(window.sessionStorage.getItem(sizeCacheKey));
        if (Array.isArray(saved)) saved.slice(-128).forEach(([url, ratio]) => {
            if (typeof url === "string" && Number.isFinite(ratio) && ratio > 0) sizeCache.set(url, ratio);
        });
    } catch (_) { /* Browser storage is optional. */ }

    function assetsFor(item) {
        return Array.isArray(item && item.assets) && item.assets.length > 0
            ? item.assets.slice().sort((a, b) => Number(a.sortOrder) - Number(b.sortOrder) || Number(a.id) - Number(b.id))
            : item && item.coverAssetId != null ? [{ id: item.coverAssetId, mimeType: item.coverMimeType }] : [];
    }

    function measureRatio(url, timeout) {
        if (!url) return Promise.resolve(1);
        if (sizeCache.has(url)) return Promise.resolve(sizeCache.get(url));
        if (timeout <= 0) return Promise.resolve(1);
        return new Promise(resolve => {
            const probe = new Image();
            const timer = window.setTimeout(() => finish(false), timeout);
            const finish = success => {
                clearTimeout(timer);
                probe.onload = probe.onerror = null;
                const ratio = success && probe.naturalWidth > 0 && probe.naturalHeight > 0
                    ? probe.naturalWidth / probe.naturalHeight : 1;
                if (success && probe.naturalWidth > 0 && probe.naturalHeight > 0) sizeCache.set(url, ratio);
                resolve(ratio);
            };
            probe.onload = () => finish(true);
            probe.onerror = () => finish(false);
            probe.src = url;
        });
    }

    async function prepareRatios(items) {
        const ratios = Array(items.length);
        let next = 0;
        const deadline = Date.now() + 6000;
        async function worker() {
            while (next < items.length) {
                const index = next++;
                const assets = assetsFor(items[index]);
                const cover = assets.find(asset => asset.id === items[index].coverAssetId) || assets[0];
                ratios[index] = await measureRatio(galleryImageUrl(cover), deadline - Date.now());
            }
        }
        await Promise.all(Array.from({ length: Math.min(4, items.length) }, worker));
        while (sizeCache.size > 128) sizeCache.delete(sizeCache.keys().next().value);
        try { window.sessionStorage.setItem(sizeCacheKey, JSON.stringify(Array.from(sizeCache))); }
        catch (_) { /* In-memory sizes still work when storage is unavailable. */ }
        return ratios;
    }

    function layoutGallery(preserveAnchor) {
        const width = gallery.clientWidth;
        if (!width || (preserveAnchor && width === layoutWidth)) return;
        const cards = Array.from(gallery.children);
        const anchor = preserveAnchor && cards.filter(card => {
            const rect = card.getBoundingClientRect();
            return rect.bottom > 0 && rect.top < window.innerHeight;
        }).sort((a, b) => Math.abs(a.getBoundingClientRect().top) - Math.abs(b.getBoundingClientRect().top))[0];
        const anchorTop = anchor && anchor.getBoundingClientRect().top;
        const geometry = window.MasonryLayout.layout(width, layoutRatios, window.innerWidth);
        cards.forEach((card, index) => {
            const box = geometry.boxes[index];
            card.style.left = `${box.left}px`;
            card.style.top = `${box.top}px`;
            card.style.width = `${box.width}px`;
            card.style.height = `${box.height}px`;
        });
        gallery.style.height = `${geometry.height}px`;
        layoutWidth = width;
        if (anchor) window.scrollTo(0, Math.max(0, window.scrollY + anchor.getBoundingClientRect().top - anchorTop));
    }

    new ResizeObserver(() => {
        if (resizeFrame || !layoutRatios.length) return;
        resizeFrame = true;
        window.requestAnimationFrame(() => { resizeFrame = false; layoutGallery(true); });
    }).observe(gallery);

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
        importPanel.hidden = false;
        importToggle.setAttribute("aria-expanded", "true");
        importToggle.disabled = true;
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
            importToggle.disabled = false;
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

    function galleryImageUrl(asset) {
        const assetId = asset && asset.id;
        if (assetId === null || assetId === undefined) {
            return null;
        }

        const mimeType = asset.mimeType;
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
            ? `${state.totalElements} 件作品`
            : "";
        previousButton.disabled = state.loading || state.page <= 0;
        nextButton.disabled = state.loading
            || state.totalPages === 0
            || state.page >= state.totalPages - 1;
    }

    function showState(title, message, canRetry) {
        gallery.hidden = gallery.children.length === 0;
        statePanel.hidden = false;
        stateTitle.textContent = title;
        stateMessage.textContent = message;
        retryButton.hidden = !canRetry;
    }

    function showGallery(items, ratios) {
        cardViews.clear();
        gallery.replaceChildren();
        statePanel.hidden = true;
        layoutRatios = ratios;

        if (items.length === 0) {
            gallery.style.height = "0px";
            showState("暂无插画", "还没有可以展示的插画。", false);
            return;
        }

        items.forEach(function (item) {
            gallery.appendChild(createCard(item));
        });
        gallery.hidden = false;
        layoutGallery(false);
    }

    function createCard(item) {
        const title = titleFor(item);
        const validTitle = typeof item.title === "string" ? item.title.trim() : "";
        const author = typeof item.author?.displayName === "string" ? item.author.displayName.trim() : "";
        const card = document.createElement("article");
        card.className = "illustration-card";
        const groupKey = `illustration:${item.id}`;
        card.dataset.browseAnchor = groupKey;
        const detailUrl = item && item.id !== null && item.id !== undefined
            ? `/detail.html?id=${encodeURIComponent(String(item.id))}` : null;

        const imageLink = document.createElement("a");
        imageLink.className = "card-image-link";
        imageLink.setAttribute("aria-label", `放大查看${validTitle || author || `作品 ${item.id}`}`);
        imageLink.setAttribute("role", "button");
        if (item && item.id !== null && item.id !== undefined) {
            imageLink.href = detailUrl;
        }

        const imageContainer = document.createElement("div");
        imageContainer.className = "card-image";

        const fallback = document.createElement("span");
        fallback.className = "image-fallback";
        fallback.textContent = "图片加载失败";
        fallback.hidden = true;

        const assets = assetsFor(item);
        let currentIndex = Math.max(0, assets.findIndex(asset => String(asset.id) === browse.imageKey(groupKey)));
        const image = document.createElement("img");
        image.alt = "";
        image.loading = "lazy";
        image.decoding = "async";
        image.addEventListener("error", function () {
            image.hidden = true;
            fallback.hidden = false;
        });
        imageLink.append(image, fallback);
        imageContainer.appendChild(imageLink);

        function showAsset() {
            const asset = assets[currentIndex];
            const imageUrl = galleryImageUrl(asset);
            image.hidden = imageUrl === null;
            fallback.hidden = imageUrl !== null;
            fallback.textContent = asset ? "图片加载失败" : "暂无图片";
            if (imageUrl !== null) {
                image.src = imageUrl;
            }
            if (asset && detailUrl) {
                const href = browse.detailHref(item.id, String(asset.id));
                imageLink.href = href;
            }
        }
        showAsset();

        function selectAsset(key) {
            const selected = assets.findIndex(asset => String(asset.id) === key);
            if (selected < 0 || selected === currentIndex) return;
            currentIndex = selected;
            showAsset();
        }

        function viewer() {
            return {
                groupKey, title,
                startKey: assets[currentIndex] && String(assets[currentIndex].id),
                items: assets.map(asset => ({ key: String(asset.id),
                    fullUrl: `/api/assets/${encodeURIComponent(String(asset.id))}/content`,
                    previewUrl: galleryImageUrl(asset), alt: title })),
                detailHref: key => browse.detailHref(item.id, key),
                opener: imageLink, onChange: selectAsset
            };
        }
        cardViews.set(groupKey, { viewer });
        imageLink.addEventListener("click", function (event) {
            if (state.loading && window.ImageViewer.plainClick(event)) { event.preventDefault(); return; }
            if (window.ImageViewer.plainClick(event) && browse.openViewer(viewer(), imageLink)) event.preventDefault();
        });
        imageLink.addEventListener("keydown", event => {
            if (event.key === " " && browse.openViewer(viewer(), imageLink)) event.preventDefault();
        });

        if (assets.length > 1) {
            // Sibling buttons keep preview cycling separate from the Viewer link.
            function previewButton(step, label, className) {
                const button = document.createElement("button");
                button.type = "button";
                button.className = `card-asset-button ${className}`;
                button.setAttribute("aria-label", `${label}预览：${validTitle || author || `作品 ${item.id}`}`);
                button.textContent = step < 0 ? "‹" : "›";
                button.addEventListener("click", event => {
                    event.preventDefault();
                    event.stopPropagation();
                    if (state.loading) return;
                    currentIndex = (currentIndex + step + assets.length) % assets.length;
                    browse.remember(groupKey, String(assets[currentIndex].id));
                    showAsset();
                });
                return button;
            }
            imageContainer.append(
                previewButton(-1, "上一张", "card-asset-previous"),
                previewButton(1, "下一张", "card-asset-next")
            );
            const position = document.createElement("span");
            position.className = "card-asset-position";
            position.textContent = String(assets.length);
            position.setAttribute("aria-hidden", "true");
            imageLink.setAttribute("aria-label", `${imageLink.getAttribute("aria-label")}，共 ${assets.length} 张图片`);
            imageLink.appendChild(position);
        }

        if (author || validTitle) {
            const info = document.createElement("span");
            info.className = "card-hover-info";
            if (author) {
                const credit = document.createElement("span");
                credit.className = "card-author";
                credit.textContent = author;
                info.appendChild(credit);
            }
            if (validTitle) {
                const caption = document.createElement("span");
                caption.className = "card-title";
                caption.textContent = validTitle;
                info.appendChild(caption);
            }
            imageLink.appendChild(info);
        }
        card.append(imageContainer);
        return card;
    }

    async function loadPage(page) {
        if (state.loading || page < 0) {
            return;
        }

        state.loading = true;
        state.pendingPage = page;
        gallery.setAttribute("aria-busy", "true");
        gallery.inert = true;
        updatePagination();
        loadStatus.textContent = "正在加载…";
        if (!gallery.children.length) showState("正在加载", "正在读取图库内容。", false);
        else statePanel.hidden = true;

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

            const loadedPage = Number.isInteger(responsePage) && responsePage >= 0
                ? responsePage
                : page;
            const totalPages = Number.isInteger(responseTotalPages) && responseTotalPages >= 0
                ? responseTotalPages
                : 0;

            if (loadedPage > 0 && loadedPage >= totalPages) {
                state.loading = false;
                return await loadPage(Math.max(0, totalPages - 1));
            }
            const items = Array.isArray(data.items) ? data.items : [];
            const ratios = await prepareRatios(items);
            state.page = loadedPage;
            state.totalPages = totalPages;
            state.totalElements = Number.isInteger(responseTotalElements) && responseTotalElements >= 0 ? responseTotalElements : 0;
            browse.setPage(state.page);

            showGallery(items, ratios);
            browse.ready();
            loadStatus.textContent = "";
        } catch (error) {
            loadStatus.textContent = "加载失败";
            showState("图库加载失败", "暂时无法读取图库，请稍后重试。", true);
        } finally {
            state.loading = false;
            gallery.setAttribute("aria-busy", "false");
            gallery.inert = false;
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
    importToggle.addEventListener("click", () => {
        if (state.importing) return;
        importPanel.hidden = !importPanel.hidden;
        importToggle.setAttribute("aria-expanded", String(!importPanel.hidden));
    });

    loadPage(browse.initialPage);
})();
