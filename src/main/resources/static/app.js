(function () {
    "use strict";

    const pageSize = 24;
    const state = {
        page: 0,
        pendingPage: 0,
        totalPages: 0,
        totalElements: 0,
        loading: false
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
        const card = document.createElement("article");
        card.className = "illustration-card";

        const imageContainer = document.createElement("div");
        imageContainer.className = "card-image";

        const fallback = document.createElement("span");
        fallback.className = "image-fallback";
        fallback.textContent = "图片加载失败";
        fallback.hidden = true;

        const assetId = item && item.coverAssetId;
        if (assetId !== null && assetId !== undefined) {
            const image = document.createElement("img");
            image.src = `/api/assets/${encodeURIComponent(String(assetId))}/content`;
            image.alt = title;
            image.loading = "lazy";
            image.decoding = "async";
            image.addEventListener("error", function () {
                image.hidden = true;
                fallback.hidden = false;
            }, { once: true });
            imageContainer.appendChild(image);
        } else {
            fallback.textContent = "暂无图片";
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

    loadPage(0);
})();
