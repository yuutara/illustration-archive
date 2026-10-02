(function () {
    "use strict";

    let ui = null;
    let options = null;
    let index = 0;
    let generation = 0;
    let image = null;
    let loaded = false;
    let scale = 1;
    let fitScale = 1;
    let offset = { x: 0, y: 0 };
    let pointer = null;
    let opener = null;
    let previousOverflow = "";

    function element(tag, className, text) {
        const node = document.createElement(tag);
        node.className = className;
        if (text) node.textContent = text;
        return node;
    }

    function button(text, label, action) {
        const node = element("button", "image-viewer-button", text);
        node.type = "button";
        node.setAttribute("aria-label", label);
        node.addEventListener("click", action);
        return node;
    }

    function requestClose() {
        if (!options) return;
        if (options.onRequestClose) options.onRequestClose();
        else close();
    }

    function build() {
        const dialog = element("dialog", "image-viewer");
        dialog.setAttribute("aria-labelledby", "image-viewer-title");
        const header = element("header", "image-viewer-header");
        const title = element("h2", "image-viewer-title");
        title.id = "image-viewer-title";
        const position = element("span", "image-viewer-position");
        position.setAttribute("aria-live", "polite");
        const closeButton = button("×", "关闭图片查看器", requestClose);
        header.append(title, position, closeButton);

        const stage = element("div", "image-viewer-stage");
        const status = element("p", "image-viewer-status");
        status.setAttribute("role", "status");
        const retry = button("重试", "重新加载当前图片", showImage);
        const feedback = element("div", "image-viewer-feedback");
        feedback.append(status, retry);
        const toolbar = element("div", "image-viewer-toolbar");
        toolbar.setAttribute("aria-label", "图片查看操作");
        const previous = button("←", "上一张图片", () => move(-1));
        const next = button("→", "下一张图片", () => move(1));
        const minus = button("−", "缩小图片", () => zoom(scale / 1.25));
        const plus = button("+", "放大图片", () => zoom(scale * 1.25));
        const fit = button("适应窗口", "适应窗口", () => zoom(fitScale));
        const actual = button("1:1", "原始尺寸", () => zoom(1));
        const zoomLabel = element("span", "image-viewer-zoom");
        const detail = element("a", "image-viewer-link", "查看详情");
        const source = element("a", "image-viewer-link", "打开来源 ↗");
        source.target = "_blank";
        source.rel = "noopener noreferrer";
        detail.addEventListener("click", event => {
            if (plainClick(event) && options && options.onNavigateDetail) {
                event.preventDefault();
                options.onNavigateDetail(detail.href);
            }
        });
        toolbar.append(previous, next, minus, zoomLabel, plus, fit, actual, detail, source);
        dialog.append(header, stage, feedback, toolbar);
        dialog.addEventListener("cancel", event => {
            event.preventDefault();
            requestClose();
        });
        dialog.addEventListener("keydown", event => {
            if (!options || event.ctrlKey || event.metaKey || event.altKey) return;
            const actions = {
                ArrowLeft: () => move(-1), ArrowRight: () => move(1),
                "+": () => zoom(scale * 1.25), "=": () => zoom(scale * 1.25),
                "-": () => zoom(scale / 1.25), "0": () => zoom(fitScale), "1": () => zoom(1)
            };
            if (actions[event.key]) {
                event.preventDefault();
                actions[event.key]();
            }
        });
        stage.addEventListener("pointerdown", event => {
            if (!loaded || pointer || event.isPrimary === false || event.button !== 0 || scale <= fitScale) return;
            pointer = { id: event.pointerId, x: event.clientX, y: event.clientY, offset: { ...offset } };
            stage.setPointerCapture(event.pointerId);
            stage.classList.add("is-dragging");
            event.preventDefault();
        });
        stage.addEventListener("pointermove", event => {
            if (!pointer || event.pointerId !== pointer.id) return;
            offset.x = pointer.offset.x + event.clientX - pointer.x;
            offset.y = pointer.offset.y + event.clientY - pointer.y;
            applyTransform();
        });
        function endDrag(event) {
            if (pointer && event.pointerId === pointer.id) {
                pointer = null;
                stage.classList.remove("is-dragging");
            }
        }
        stage.addEventListener("pointerup", endDrag);
        stage.addEventListener("pointercancel", endDrag);
        stage.addEventListener("lostpointercapture", endDrag);
        window.addEventListener("resize", () => {
            if (!loaded || !options) return;
            const wasFit = scale === fitScale;
            measure();
            zoom(wasFit ? fitScale : scale);
        });
        document.body.appendChild(dialog);
        ui = { dialog, title, position, closeButton, stage, status, retry, previous, next,
            minus, plus, fit, actual, zoomLabel, detail, source };
    }

    function plainClick(event) {
        return (event.button === undefined || event.button === 0)
            && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey;
    }

    function setLink(node, href) {
        try {
            const url = new URL(href, window.location.href);
            if (!href || !["http:", "https:"].includes(url.protocol)) throw new Error("Invalid link");
            node.href = url.href;
            node.hidden = false;
        } catch (error) {
            node.removeAttribute("href");
            node.hidden = true;
        }
    }

    function controls() {
        ui.previous.disabled = index === 0;
        ui.next.disabled = index === options.items.length - 1;
        ui.position.textContent = `${index + 1} / ${options.items.length}`;
        ui.minus.disabled = !loaded || scale <= fitScale;
        ui.plus.disabled = !loaded || scale >= 4;
        ui.fit.disabled = !loaded;
        ui.actual.disabled = !loaded;
        ui.zoomLabel.textContent = loaded ? `${Math.round(scale * 100)}%` : "";
    }

    function measure() {
        fitScale = Math.min(1, ui.stage.clientWidth / image.naturalWidth,
            ui.stage.clientHeight / image.naturalHeight);
    }

    function applyTransform() {
        if (!loaded) return;
        const maxX = Math.max(0, (image.naturalWidth * scale - ui.stage.clientWidth) / 2);
        const maxY = Math.max(0, (image.naturalHeight * scale - ui.stage.clientHeight) / 2);
        offset.x = Math.max(-maxX, Math.min(maxX, offset.x));
        offset.y = Math.max(-maxY, Math.min(maxY, offset.y));
        image.style.transform = `translate(-50%, -50%) translate(${offset.x}px, ${offset.y}px) scale(${scale})`;
        ui.stage.classList.toggle("is-zoomed", scale > fitScale);
        controls();
    }

    function zoom(value) {
        if (!loaded) return;
        const newScale = Math.max(fitScale, Math.min(4, value));
        offset.x *= newScale / scale;
        offset.y *= newScale / scale;
        scale = newScale;
        applyTransform();
    }

    function showImage() {
        if (!options) return;
        const item = options.items[index];
        const ticket = ++generation;
        loaded = false;
        pointer = null;
        scale = 1;
        offset = { x: 0, y: 0 };
        ui.stage.classList.remove("is-dragging", "is-zoomed");
        ui.stage.replaceChildren();
        ui.status.textContent = "正在加载图片…";
        ui.retry.hidden = true;
        if (item.previewUrl && item.previewUrl !== item.fullUrl) {
            const preview = element("img", "image-viewer-preview");
            preview.alt = "";
            preview.src = item.previewUrl;
            ui.stage.appendChild(preview);
        }
        const currentImage = element("img", "image-viewer-image");
        currentImage.alt = item.alt || options.title || "图片";
        currentImage.draggable = false;
        currentImage.hidden = true;
        currentImage.addEventListener("load", () => {
            if (!options || generation !== ticket) return;
            image = currentImage;
            loaded = image.naturalWidth > 0 && image.naturalHeight > 0;
            if (!loaded) return;
            ui.stage.replaceChildren(image);
            image.style.width = `${image.naturalWidth}px`;
            image.style.height = `${image.naturalHeight}px`;
            image.hidden = false;
            ui.status.textContent = "";
            measure();
            scale = fitScale;
            applyTransform();
        });
        currentImage.addEventListener("error", () => {
            if (!options || generation !== ticket) return;
            ui.status.textContent = "图片加载失败。可以重试、切换图片或关闭。";
            ui.retry.hidden = false;
        });
        ui.stage.appendChild(currentImage);
        currentImage.src = item.fullUrl;
        controls();
        if (options.onChange) options.onChange(item.key);
        const href = typeof options.detailHref === "function" ? options.detailHref(item.key) : options.detailHref;
        setLink(ui.detail, href);
    }

    function move(amount) {
        if (!options) return;
        const next = index + amount;
        if (next < 0 || next >= options.items.length) return;
        index = next;
        showImage();
    }

    function open(config) {
        if (!config || !Array.isArray(config.items) || config.items.length === 0) return false;
        if (!ui) build();
        if (typeof ui.dialog.showModal !== "function") return false;
        if (options) close();
        options = config;
        index = config.items.findIndex(item => item.key === config.startKey);
        if (index < 0) index = 0;
        opener = config.opener || document.activeElement;
        previousOverflow = document.documentElement.style.overflow;
        document.documentElement.style.overflow = "hidden";
        ui.title.textContent = config.title || "图片查看器";
        setLink(ui.source, config.sourceHref);
        ui.dialog.showModal();
        ui.closeButton.focus({ preventScroll: true });
        showImage();
        return true;
    }

    function close() {
        if (!options) return;
        ++generation;
        options = null;
        loaded = false;
        image = null;
        pointer = null;
        ui.stage.replaceChildren();
        ui.dialog.close();
        document.documentElement.style.overflow = previousOverflow;
        if (opener && opener.isConnected) opener.focus({ preventScroll: true });
        opener = null;
    }

    window.ImageViewer = { open, close, plainClick,
        supported: () => typeof document.createElement("dialog").showModal === "function" };
})();
