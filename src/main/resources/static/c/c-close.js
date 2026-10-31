/*
 * 关闭窗口：应用窗口（WebView2）的关闭按钮在这里问“最小化到托盘还是退出”。
 * 窗口调用 CClose.ask()，这里先回一声 asking，窗口就不再弹系统对话框；选定后再告诉窗口怎么做。
 * 勾选“记住”时先写进设置，以后窗口直接照做，可以在设置页改回来。普通浏览器里没有窗口，也就没有这一步。
 */
(function () {
    'use strict';

    if (!window.iaDesktop) return;

    const MARKUP = `
        <section class="sheet sheet-small" role="dialog" aria-modal="true" aria-labelledby="close-title">
            <h2 id="close-title">关闭插画档案</h2>
            <div class="close-options">
                <button class="close-option" id="close-tray" type="button">
                    <svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v9m0 0-4-4m4 4 4-4M5 19h14"/></svg>
                    <b>最小化到托盘</b>
                    <small>继续在后台运行。点任务栏右侧的图标，或再次打开程序就能回来</small>
                </button>
                <button class="close-option" id="close-exit" type="button">
                    <svg class="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4v8M7.5 6.8a7 7 0 1 0 9 0"/></svg>
                    <b>退出应用</b>
                    <small>结束运行并释放内存，下次打开时重新启动</small>
                </button>
            </div>
            <p class="close-warning" id="close-warning" role="alert" hidden></p>
            <div class="sheet-foot">
                <label class="close-remember"><input id="close-remember" type="checkbox">记住选择，可在设置中修改</label>
                <button class="btn" id="close-cancel" type="button">取消</button>
            </div>
        </section>`;

    let box = null;
    let returnFocus = null;
    let controls = [];

    const part = (id) => box.querySelector('#' + id);

    // 只提醒退出才会丢掉的东西：留在托盘时，这些都原样保留
    function unfinishedWork() {
        if (window.CImport && window.CImport.isRunning()) return '还有图片正在导入，退出后没开始的文件不会导入。';
        if (window.CEditor && window.CEditor.isDirty()) return '编辑面板里有未保存的修改，退出后会丢失。';
        return '';
    }

    function leave() {
        box.hidden = true;
        if (returnFocus && returnFocus.isConnected && typeof returnFocus.focus === 'function') returnFocus.focus();
        returnFocus = null;
    }

    async function choose(choice) {
        const remember = part('close-remember').checked;
        leave();
        if (remember) {
            try {
                await fetch('/api/desktop/settings/close-action', {
                    method: 'PUT',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ action: choice.toUpperCase() })
                });
            } catch (e) { /* 这次照常关闭，下次再问一遍 */ }
        }
        window.iaDesktop('close', choice);
    }

    function onKey(e) {
        // 这里的按键只归这个对话框，不触发图库或看图器的快捷键
        e.stopPropagation();
        const here = controls.indexOf(document.activeElement);
        if (e.key === 'Escape') {
            e.preventDefault();
            leave();
        } else if (e.key === 'Tab') {
            e.preventDefault();
            controls[(here + (e.shiftKey ? controls.length - 1 : 1)) % controls.length].focus();
        } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault();
            (e.key === 'ArrowDown' ? part('close-exit') : part('close-tray')).focus();
        }
    }

    function build() {
        box = document.createElement('div');
        box.className = 'sheet-backdrop close-sheet';
        box.hidden = true;
        box.innerHTML = MARKUP;
        document.body.appendChild(box);
        controls = ['close-tray', 'close-exit', 'close-remember', 'close-cancel'].map(part);
        part('close-tray').addEventListener('click', () => choose('tray'));
        part('close-exit').addEventListener('click', () => choose('exit'));
        part('close-cancel').addEventListener('click', leave);
        box.addEventListener('mousedown', (e) => { if (e.target === box) leave(); });
        box.addEventListener('keydown', onKey);
    }

    function ask() {
        window.iaDesktop('close', 'asking');
        if (!box) build();
        if (box.hidden) {
            returnFocus = document.activeElement;
            part('close-remember').checked = false;
        }
        const warning = part('close-warning');
        warning.textContent = unfinishedWork();
        warning.hidden = !warning.textContent;
        box.hidden = false;
        part('close-tray').focus();
    }

    window.CClose = { ask };
})();
