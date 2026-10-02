(function (root) {
    "use strict";

    // Geometry only: input order stays the API/keyboard order, never load order.
    function layout(width, ratios, viewportWidth) {
        width = Math.max(1, Number(width) || 1);
        const narrow = viewportWidth < 620;
        const gap = narrow ? 6 : 8;
        const columns = narrow ? (viewportWidth < 360 ? 1 : 2)
            : Math.min(8, Math.max(1, Math.floor((width + gap) / 204)));
        const columnWidth = Math.max(1, (width - gap * (columns - 1)) / columns);
        const bottoms = Array(columns).fill(0);
        const boxes = ratios.map(value => {
            const ratio = Number.isFinite(value) && value > 0 ? value : 1;
            const column = bottoms.indexOf(Math.min(...bottoms));
            const top = bottoms[column];
            const height = columnWidth / ratio;
            bottoms[column] = top + height + gap;
            return { left: column * (columnWidth + gap), top, width: columnWidth, height };
        });
        return { boxes, columns, gap, height: boxes.length ? Math.max(...bottoms) - gap : 0 };
    }

    if (typeof module !== "undefined" && module.exports) module.exports = { layout };
    else root.MasonryLayout = { layout };
})(typeof window !== "undefined" ? window : null);
