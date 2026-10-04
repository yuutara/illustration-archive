(function () {
    "use strict";

    let ui = null;
    let options = null;
    let index = 0;
    let generation = 0;
    let image = null;
    let naturalWidth = 0;
    let naturalHeight = 0;
    let loaded = false;
    let scale = 1;
    let fitScale = 1;
    let offset = { x: 0, y: 0 };
    let pointer = null;
    let opener = null;
    let previousOverflow = "";
    let inspectorOpen = false;
    let metadataSession = null;
    let fitMode = true;

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
        node.title = label;
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
        const brand = element("p", "image-viewer-brand", "Illustration\nArchive");
        header.append(brand, title);

        const pagination = element("div", "image-viewer-pagination");
        const pages = element("p", "image-viewer-pages");
        const current = element("span", "image-viewer-current");
        const total = element("span", "image-viewer-total");
        current.setAttribute("aria-hidden", "true");
        total.setAttribute("aria-hidden", "true");
        const position = element("span", "image-viewer-position");
        position.setAttribute("aria-live", "polite");
        pages.append(current, total, position);
        const closeButton = button("×", "关闭图片查看器", requestClose);
        closeButton.classList.add("image-viewer-close");

        const stage = element("div", "image-viewer-stage");
        const status = element("p", "image-viewer-status");
        status.setAttribute("role", "status");
        const retry = button("重试", "重新加载当前图片", showImage);
        const feedback = element("div", "image-viewer-feedback");
        feedback.append(status, retry);
        const toolbar = element("div", "image-viewer-toolbar");
        toolbar.setAttribute("role", "group");
        toolbar.setAttribute("aria-label", "图片查看操作");
        const previous = button("‹", "上一张图片", () => move(-1));
        const next = button("›", "下一张图片", () => move(1));
        const navigation = element("div", "image-viewer-navigation");
        navigation.append(previous, next);
        pagination.append(pages, navigation);
        const minus = button("−", "缩小图片", () => zoom(scale / 1.25));
        const plus = button("+", "放大图片", () => zoom(scale * 1.25));
        const more = element("details", "image-viewer-more");
        const moreTrigger = element("summary", "image-viewer-button", "⋯");
        moreTrigger.setAttribute("aria-label", "更多查看操作");
        moreTrigger.title = "更多查看操作";
        const menu = element("div", "image-viewer-menu");
        function selectZoom(value, fitting = false) {
            zoom(value, fitting);
            more.open = false;
            moreTrigger.focus({ preventScroll: true });
        }
        const fit = button("适应窗口", "适应窗口", () => selectZoom(fitScale, true));
        const actual = button("原始尺寸", "原始尺寸", () => selectZoom(1));
        fit.appendChild(element("span", "image-viewer-shortcut", "0"));
        actual.appendChild(element("span", "image-viewer-shortcut", "1:1"));
        menu.append(fit, actual);
        more.append(moreTrigger, menu);
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
        toolbar.append(minus, zoomLabel, plus, more);
        const links = element("div", "image-viewer-links");
        links.append(detail, source);
        const info = button("作品信息", "作品信息", () => toggleInspector(!inspectorOpen));
        info.classList.add("image-viewer-info");
        info.setAttribute("aria-expanded", "false");
        info.setAttribute("aria-controls", "image-viewer-inspector");
        const inspector = element("section", "image-viewer-inspector");
        inspector.id = "image-viewer-inspector";
        inspector.hidden = true;
        inspector.setAttribute("aria-labelledby", "image-viewer-inspector-title");
        const inspectorHeader = element("header", "image-viewer-inspector-header");
        const inspectorTitle = element("h3", "", "作品信息");
        inspectorTitle.id = "image-viewer-inspector-title";
        const inspectorClose = button("×", "收起作品信息", () => toggleInspector(false));
        inspectorHeader.append(inspectorTitle, inspectorClose);
        const metadata = element("div", "image-viewer-metadata");
        const metadataStatus = element("p", "image-viewer-metadata-status");
        metadataStatus.setAttribute("role", "status");
        const metadataRetry = button("重试", "重新加载作品信息", loadMetadata);
        metadataRetry.classList.add("image-viewer-info");
        const inspectorScroll = element("div", "image-viewer-inspector-scroll");
        inspectorScroll.append(metadataStatus, metadataRetry, metadata);
        const fullDetail = element("a", "image-viewer-link", "查看完整详情 →");
        fullDetail.addEventListener("click", event => {
            if (plainClick(event) && options && options.onNavigateDetail) {
                event.preventDefault();
                options.onNavigateDetail(fullDetail.href);
            }
        });
        inspector.append(inspectorHeader, inspectorScroll, fullDetail);
        // This layer is outside the image stage; scrolling and selecting text stay local.
        ["wheel", "pointerdown", "click"].forEach(type =>
            inspector.addEventListener(type, event => event.stopPropagation()));
        const edge = element("aside", "image-viewer-edge");
        edge.append(header, pagination, toolbar, info, links);
        dialog.append(stage, edge, inspector, feedback, closeButton);
        dialog.addEventListener("cancel", event => {
            event.preventDefault();
            if (inspectorOpen) toggleInspector(false);
            else requestClose();
        });
        dialog.addEventListener("keydown", event => {
            if (!options || event.ctrlKey || event.metaKey || event.altKey) return;
            if (event.key === "Escape" && inspectorOpen) {
                event.preventDefault();
                toggleInspector(false);
                return;
            }
            if (inspector.contains(event.target)) return;
            const actions = {
                ArrowLeft: () => move(-1), ArrowRight: () => move(1),
                "+": () => zoom(scale * 1.25), "=": () => zoom(scale * 1.25),
                "-": () => zoom(scale / 1.25), "0": () => zoom(fitScale, true), "1": () => zoom(1)
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
        window.addEventListener("resize", resizeImage);
        new ResizeObserver(resizeImage).observe(stage);
        document.body.appendChild(dialog);
        ui = { dialog, title, position, pagination, current, total, closeButton, stage, status, retry, previous, next,
            minus, plus, fit, actual, more, zoomLabel, detail, source, info, inspector, inspectorClose,
            metadata, metadataStatus, metadataRetry, fullDetail, inspectorScroll };
    }

    function resizeImage() {
        if (!loaded || !options) return;
        if (!metadataSession) {
            const wasFit = scale === fitScale;
            measure();
            zoom(wasFit ? fitScale : scale, wasFit);
            return;
        }
        measure();
        if (fitMode) { scale = fitScale; offset = { x: 0, y: 0 }; }
        // Manual magnification survives layout changes, including 1:1.
        applyTransform();
    }

    function toggleInspector(expanded) {
        if (!options || !metadataSession) return;
        inspectorOpen = expanded;
        pointer = null;
        ui.stage.classList.remove("is-dragging");
        ui.dialog.classList.toggle("has-inspector", expanded);
        ui.inspector.hidden = !expanded;
        ui.info.setAttribute("aria-expanded", String(expanded));
        resizeImage();
        if (expanded) {
            ui.inspectorClose.focus({ preventScroll: true });
            if (metadataSession.status === "idle") loadMetadata();
        } else ui.info.focus({ preventScroll: true });
    }

    function metadataText(value) { return typeof value === "string" ? value.trim() : ""; }

    function renderMetadata(detail) {
        ui.metadata.replaceChildren();
        const name = metadataText(detail.author?.displayName);
        const handle = metadataText(detail.author?.xUsername).replace(/^@+/, "");
        if (name) ui.metadata.appendChild(element("p", "image-viewer-author", name));
        if (handle) ui.metadata.appendChild(element("p", "image-viewer-handle", `@${handle}`));
        const tags = element("div", "image-viewer-tags");
        (detail.tags || []).forEach(tag => {
            const name = metadataText(tag.name);
            if (name) tags.appendChild(element("span", "tag-chip", name));
        });
        if (tags.children.length) ui.metadata.appendChild(tags);
        const source = metadataText(detail.sourceUrl);
        if (source) {
            const section = element("div", "image-viewer-metadata-section");
            const link = element("a", "image-viewer-link", source);
            setLink(link, source);
            if (!link.hidden) {
                link.target = "_blank"; link.rel = "noopener noreferrer";
                section.append(element("p", "image-viewer-field-label", "来源"), link);
                ui.metadata.appendChild(section);
            }
        }
        const note = metadataText(detail.note);
        if (note) {
            const section = element("div", "image-viewer-metadata-section");
            section.append(element("p", "image-viewer-field-label", "备注"), element("p", "image-viewer-note", note));
            ui.metadata.appendChild(section);
        }
    }

    async function loadMetadata() {
        const session = metadataSession;
        if (!session || session.status === "loading" || session.status === "ready") return;
        session.status = "loading";
        ui.metadataStatus.textContent = "正在加载作品信息…";
        ui.metadataRetry.hidden = true;
        try {
            const detail = await session.provider();
            if (metadataSession !== session || !options) return;
            if (!detail || typeof detail !== "object") throw new Error("Invalid metadata");
            renderMetadata(detail);
            session.status = "ready";
            ui.metadataStatus.textContent = "";
        } catch (error) {
            if (metadataSession !== session || !options) return;
            session.status = "error";
            ui.metadataStatus.textContent = "作品信息加载失败";
            ui.metadataRetry.hidden = false;
        }
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
        ui.pagination.hidden = options.items.length === 1;
        ui.current.textContent = String(index + 1).padStart(2, "0");
        ui.total.textContent = `/ ${String(options.items.length).padStart(2, "0")}`;
        ui.minus.disabled = !loaded || scale <= fitScale;
        ui.plus.disabled = !loaded || scale >= 4;
        ui.fit.disabled = !loaded;
        ui.actual.disabled = !loaded;
        ui.zoomLabel.textContent = loaded ? `${Math.round(scale * 100)}%` : "";
    }

    function measure() {
        fitScale = Math.min(1, ui.stage.clientWidth / naturalWidth,
            ui.stage.clientHeight / naturalHeight);
    }

    function applyTransform() {
        if (!loaded) return;
        const maxX = Math.max(0, (naturalWidth * scale - ui.stage.clientWidth) / 2);
        const maxY = Math.max(0, (naturalHeight * scale - ui.stage.clientHeight) / 2);
        offset.x = Math.max(-maxX, Math.min(maxX, offset.x));
        offset.y = Math.max(-maxY, Math.min(maxY, offset.y));
        image.style.transform = `translate(-50%, -50%) translate(${offset.x}px, ${offset.y}px) scale(${scale})`;
        ui.stage.classList.toggle("is-zoomed", scale > fitScale);
        controls();
    }

    function zoom(value, fitting = false) {
        if (!loaded) return;
        const newScale = Math.max(fitScale, Math.min(4, value));
        offset.x *= newScale / scale;
        offset.y *= newScale / scale;
        scale = newScale;
        fitMode = fitting || (value !== 1 && newScale === fitScale);
        applyTransform();
    }

    function releaseVideo() {
        if (image && image.tagName.toLowerCase() === "video") {
            image.pause();
            image.removeAttribute("src");
            image.load();
        }
        image = null;
    }

    function showImage() {
        if (!options) return;
        const item = options.items[index];
        const ticket = ++generation;
        releaseVideo();
        naturalWidth = naturalHeight = 0;
        loaded = false;
        pointer = null;
        scale = 1;
        offset = { x: 0, y: 0 };
        ui.stage.classList.remove("is-dragging", "is-zoomed");
        ui.stage.replaceChildren();
        ui.status.textContent = "正在加载图片…";
        ui.retry.hidden = true;
        ui.more.open = false;
        const isVideo = item.mimeType === "video/mp4";
        if (!isVideo && item.previewUrl && item.previewUrl !== item.fullUrl) {
            const preview = element("img", "image-viewer-preview");
            preview.alt = "";
            preview.src = item.previewUrl;
            ui.stage.appendChild(preview);
        }
        const currentImage = element(isVideo ? "video" : "img", "image-viewer-image");
        if (isVideo) {
            currentImage.muted = true;
            currentImage.loop = true;
            currentImage.autoplay = true;
            currentImage.playsInline = true;
            currentImage.preload = "metadata";
            currentImage.setAttribute("aria-label", item.alt || options.title || "动画");
        }
        currentImage.alt = item.alt || options.title || "图片";
        currentImage.draggable = false;
        currentImage.hidden = true;
        image = currentImage; // also release a still-loading video on navigation/close
        currentImage.addEventListener(isVideo ? "loadedmetadata" : "load", () => {
            if (!options || generation !== ticket) return;
            image = currentImage;
            naturalWidth = isVideo ? image.videoWidth : image.naturalWidth;
            naturalHeight = isVideo ? image.videoHeight : image.naturalHeight;
            loaded = naturalWidth > 0 && naturalHeight > 0;
            if (!loaded) return;
            ui.stage.replaceChildren(image);
            image.style.width = `${naturalWidth}px`;
            image.style.height = `${naturalHeight}px`;
            image.hidden = false;
            ui.status.textContent = "";
            measure();
            scale = fitScale;
            fitMode = true;
            applyTransform();
        });
        currentImage.addEventListener("error", () => {
            if (!options || generation !== ticket) return;
            loaded = false;
            controls();
            ui.status.textContent = "图片加载失败。可以重试、切换图片或关闭。";
            ui.retry.hidden = false;
        });
        ui.stage.appendChild(currentImage);
        currentImage.src = item.fullUrl;
        controls();
        if (options.onChange) options.onChange(item.key);
        const href = typeof options.detailHref === "function" ? options.detailHref(item.key) : options.detailHref;
        setLink(ui.detail, href);
        ui.detail.hidden = ui.detail.hidden || Boolean(options.detailInInspectorOnly);
        setLink(ui.fullDetail, href);
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
        inspectorOpen = false;
        metadataSession = typeof config.metadataProvider === "function"
            ? { provider: config.metadataProvider, status: "idle" } : null;
        ui.info.hidden = !metadataSession;
        ui.dialog.classList.toggle("has-metadata", Boolean(metadataSession));
        ui.info.setAttribute("aria-expanded", "false");
        ui.inspector.hidden = true;
        ui.dialog.classList.remove("has-inspector");
        ui.metadata.replaceChildren();
        ui.metadataStatus.textContent = "";
        ui.metadataRetry.hidden = true;
        ui.inspectorScroll.scrollTop = 0;
        index = config.items.findIndex(item => item.key === config.startKey);
        if (index < 0) index = 0;
        opener = config.opener || document.activeElement;
        previousOverflow = document.documentElement.style.overflow;
        document.documentElement.style.overflow = "hidden";
        ui.title.textContent = config.title || "图片查看器";
        ui.title.title = ui.title.textContent;
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
        metadataSession = null;
        inspectorOpen = false;
        ui.info.setAttribute("aria-expanded", "false");
        ui.inspector.hidden = true;
        ui.dialog.classList.remove("has-inspector");
        ui.metadata.replaceChildren();
        loaded = false;
        releaseVideo();
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
