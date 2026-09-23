const { test, describe, beforeEach } = require('node:test');
const assert = require('node:assert');
const path = require('path');
const fs = require('fs');

const SCRIPT_PATH = path.join(__dirname, '../../../main/resources/static/js/wiki/wiki-cover-editor.js');
const ADMIN_CSS_PATH = path.join(__dirname, '../../../main/resources/static/css/wiki/admin.css');
const WikiCoverEditor = require(SCRIPT_PATH);

function createClassList(initialClasses = []) {
    const classes = new Set(initialClasses);
    return {
        add: (c) => classes.add(c),
        remove: (c) => classes.delete(c),
        contains: (c) => classes.has(c),
        get value() { return Array.from(classes).join(' '); }
    };
}

function createFileList(items = []) {
    const fileList = Object.create({
        item(index) { return items[index] || null; },
        [Symbol.iterator]: function* () {
            for (const item of items) yield item;
        }
    });
    Object.defineProperty(fileList, 'length', {
        get: () => items.length
    });
    items.forEach((item, index) => {
        fileList[index] = item;
    });
    return fileList;
}

function createMockElement(tagName = 'div', initialAttrs = {}) {
    const attrs = { ...initialAttrs };
    const classList = createClassList(attrs.class ? attrs.class.split(/\s+/).filter(Boolean) : []);
    const eventListeners = {};
    const children = [];
    const style = {};
    let _value = attrs.value || '';
    let _files = createFileList([]);

    const el = {
        tagName: tagName.toUpperCase(),
        classList,
        id: attrs.id || '',
        disabled: false,
        hidden: false,
        textContent: '',
        checked: false,
        src: attrs.src || '',
        style,
        parentNode: null,
        children,
        get value() {
            return _value;
        },
        set value(v) {
            _value = String(v);
            // In browser, clearing value of input[type=file] clears files list
            if (el.tagName === 'INPUT' && (attrs.type === 'file' || el.getAttribute('type') === 'file') && _value === '') {
                _files = createFileList([]);
            }
        },
        get files() {
            return _files;
        },
        set files(f) {
            if (Array.isArray(f)) {
                throw new TypeError('HTMLInputElement.files must be a FileList, not an Array');
            }
            _files = f;
        },
        getAttribute: (k) => {
            if (k === 'src') return el.src || null;
            if (k === 'id') return el.id || null;
            if (k in attrs) return attrs[k];
            return null;
        },
        setAttribute: (k, v) => {
            attrs[k] = String(v);
            if (k === 'src') el.src = String(v);
            if (k === 'id') el.id = String(v);
        },
        removeAttribute: (k) => {
            delete attrs[k];
            if (k === 'src') el.src = '';
            if (k === 'id') el.id = '';
        },
        addEventListener: (evt, fn) => {
            if (!eventListeners[evt]) eventListeners[evt] = [];
            eventListeners[evt].push(fn);
        },
        removeEventListener: (evt, fn) => {
            if (!eventListeners[evt]) return;
            const idx = eventListeners[evt].indexOf(fn);
            if (idx !== -1) eventListeners[evt].splice(idx, 1);
        },
        dispatchEvent: (evtName, evtObj = {}) => {
            const fns = eventListeners[evtName] || [];
            let prevented = false;
            let stopped = false;
            const event = {
                type: evtName,
                preventDefault: () => { prevented = true; },
                stopPropagation: () => { stopped = true; },
                defaultPrevented: () => prevented,
                target: el,
                button: 0,
                ...evtObj
            };
            for (const fn of fns) {
                fn(event);
            }
            return event;
        },
        click: () => {
            el.dispatchEvent('click', {});
        },
        focus: () => {},
        setPointerCapture: () => {},
        releasePointerCapture: () => {},
        getBoundingClientRect: () => ({
            left: 100,
            top: 100,
            width: 200,
            height: 250
        }),
        querySelector: (selector) => {
            return findDescendant(el, selector);
        },
        querySelectorAll: (selector) => {
            return findDescendants(el, selector);
        },
        appendChild: (child) => {
            child.parentNode = el;
            children.push(child);
            return child;
        }
    };

    return el;
}

function matchesSelector(el, selector) {
    if (!el || !selector) return false;
    selector = selector.trim();
    if (selector.includes(',')) {
        return selector.split(',').some(part => matchesSelector(el, part.trim()));
    }
    if (selector.startsWith('.')) {
        const cls = selector.substring(1);
        return el.classList && el.classList.contains(cls);
    }
    if (selector.startsWith('#')) {
        const id = selector.substring(1);
        return (el.id === id) || (el.getAttribute && el.getAttribute('id') === id);
    }
    if (selector.startsWith('input[name="')) {
        const name = selector.substring(12, selector.length - 2);
        return el.tagName === 'INPUT' && el.getAttribute('name') === name;
    }
    if (selector.startsWith('[data-action="')) {
        const action = selector.substring(14, selector.length - 2);
        return el.getAttribute && el.getAttribute('data-action') === action;
    }
    return false;
}

function findDescendant(parent, selector) {
    for (const child of parent.children) {
        if (matchesSelector(child, selector)) {
            return child;
        }
        const found = findDescendant(child, selector);
        if (found) return found;
    }
    return null;
}

function findDescendants(parent, selector) {
    let result = [];
    for (const child of parent.children) {
        if (matchesSelector(child, selector)) {
            result.push(child);
        }
        result = result.concat(findDescendants(child, selector));
    }
    return result;
}

function buildMockEditorContainer({
    hasImage = false,
    hasPersistedCover = null,
    posX = '50',
    posY = '50',
    checkedRemove = false,
    initialServerSrc = '/media/assets/uuid-test/content'
} = {}) {
    const isPersisted = (hasPersistedCover !== null) ? hasPersistedCover : hasImage;
    const container = createMockElement('div', { class: 'wiki-admin-form-group' });

    // Hidden inputs
    const inputX = createMockElement('input', { id: 'wikiCoverPositionX', name: 'coverPositionX', value: String(posX), type: 'hidden' });
    const inputY = createMockElement('input', { id: 'wikiCoverPositionY', name: 'coverPositionY', value: String(posY), type: 'hidden' });

    // TEMPORARY SOURCE PICKER (never submitted)
    const pickerInput = createMockElement('input', { id: 'wikiCoverPickerInput', type: 'file' });
    pickerInput.hidden = true;

    // ACTUAL SUBMISSION INPUT
    const fileInput = createMockElement('input', { id: 'wikiCoverImage', name: 'coverImageFile', type: 'file' });
    fileInput.hidden = true;

    const removeCheckbox = createMockElement('input', { id: 'removeCover', name: 'removeCover', type: 'checkbox' });
    removeCheckbox.hidden = true;
    removeCheckbox.checked = checkedRemove;

    // Compact Card
    const compactCard = createMockElement('div', {
        class: 'wiki-admin-cover-upload-card',
        id: 'wikiCoverCard'
    });
    compactCard.setAttribute('data-has-persisted-cover', isPersisted ? 'true' : 'false');

    const compactWrapper = createMockElement('div', { class: 'wiki-admin-cover-preview-wrapper wiki-admin-cover-compact-wrapper' });
    const compactFrame = createMockElement('div', { class: 'wiki-cover-compact-frame', id: 'wikiCoverCompactFrame' });
    const compactImg = createMockElement('img', {
        class: 'wiki-cover-compact-img',
        id: 'wikiCoverCompactImg',
        src: hasImage ? initialServerSrc : ''
    });
    compactImg.hidden = !hasImage;

    const compactPlaceholder = createMockElement('div', {
        class: 'wiki-admin-cover-placeholder',
        id: 'wikiCoverCompactPlaceholder'
    });
    compactPlaceholder.hidden = hasImage;
    if (hasImage) {
        compactPlaceholder.style.display = 'none';
    }

    // Pencil Menu Wrapper & Action Menu
    const menuWrapper = createMockElement('div', { class: 'wiki-cover-menu-wrapper', id: 'wikiCoverMenuWrapper' });
    menuWrapper.style.display = hasImage ? '' : 'none';
    const editBtn = createMockElement('button', {
        type: 'button',
        class: 'wiki-cover-edit-button',
        id: 'wikiCoverEditBtn',
        'data-action': 'toggle-menu'
    });

    const actionMenu = createMockElement('ul', { class: 'wiki-cover-action-menu dropdown-menu', id: 'wikiCoverActionMenu' });
    actionMenu.hidden = true;

    const updateCoverLi = createMockElement('li');
    const updateCoverBtn = createMockElement('button', { type: 'button', class: 'wiki-cover-menu-item dropdown-item', 'data-action': 'update-cover' });
    updateCoverLi.appendChild(updateCoverBtn);

    const repositionCoverLi = createMockElement('li');
    const repositionCoverBtn = createMockElement('button', { type: 'button', class: 'wiki-cover-menu-item dropdown-item', 'data-action': 'reposition-cover' });
    repositionCoverLi.appendChild(repositionCoverBtn);

    const removeCoverLi = createMockElement('li');
    const removeCoverBtn = createMockElement('button', { type: 'button', class: 'wiki-cover-menu-item dropdown-item text-danger', 'data-action': 'remove-cover' });
    const removeTextSpan = createMockElement('span');
    removeTextSpan.textContent = isPersisted ? 'Xóa ảnh bìa' : 'Bỏ ảnh đã chọn';
    removeCoverBtn.appendChild(removeTextSpan);
    removeCoverLi.appendChild(removeCoverBtn);

    actionMenu.appendChild(updateCoverLi);
    actionMenu.appendChild(repositionCoverLi);
    actionMenu.appendChild(removeCoverLi);

    menuWrapper.appendChild(editBtn);
    menuWrapper.appendChild(actionMenu);

    compactFrame.appendChild(compactImg);
    compactFrame.appendChild(compactPlaceholder);
    compactFrame.appendChild(menuWrapper);
    compactWrapper.appendChild(compactFrame);
    compactCard.appendChild(compactWrapper);

    const compactControls = createMockElement('div', { class: 'wiki-admin-cover-compact-controls' });

    // Pending Removal Banner
    const pendingRemoval = createMockElement('div', { class: 'wiki-cover-pending-removal', id: 'wikiCoverPendingRemoval' });
    pendingRemoval.hidden = !checkedRemove;
    const undoRemoveBtn = createMockElement('button', { type: 'button', 'data-action': 'undo-remove' });
    pendingRemoval.appendChild(undoRemoveBtn);
    compactControls.appendChild(pendingRemoval);

    // No Image Action ("Chọn ảnh bìa")
    const noImageActions = createMockElement('div', { class: 'wiki-cover-actions', id: 'wikiCoverNoImageActions' });
    const chooseCoverBtn = createMockElement('button', { type: 'button', class: 'wiki-cover-btn', 'data-action': 'choose-cover' });
    noImageActions.appendChild(chooseCoverBtn);
    noImageActions.style.display = hasImage ? 'none' : '';
    compactControls.appendChild(noImageActions);

    const restingError = createMockElement('div', { class: 'wiki-cover-error wiki-admin-form-error', id: 'wikiCoverRestingError' });
    restingError.hidden = true;
    compactControls.appendChild(restingError);

    compactCard.appendChild(compactControls);

    // Modal
    const modal = createMockElement('div', { class: 'wiki-cover-modal modal fade', id: 'wikiCoverModal' });
    modal.hidden = true;

    const backdrop = createMockElement('div', { class: 'wiki-cover-modal-backdrop', 'data-action': 'close-modal' });
    const dialog = createMockElement('section', { class: 'wiki-cover-modal-dialog' });
    const modalContent = createMockElement('div', { class: 'wiki-cover-modal-content' });

    // Modal Header
    const modalHeader = createMockElement('div', { class: 'wiki-cover-modal-header' });
    const modalTitle = createMockElement('h3', { class: 'wiki-cover-modal-title', id: 'wikiCoverModalTitle' });
    const closeBtn = createMockElement('button', { class: 'wiki-cover-modal-close', 'data-action': 'close-modal' });
    modalHeader.appendChild(modalTitle);
    modalHeader.appendChild(closeBtn);

    // Modal Body
    const modalBody = createMockElement('div', { class: 'wiki-cover-modal-body' });
    const workspace = createMockElement('div', { class: 'wiki-cover-workspace', id: 'wikiCoverWorkspace' });

    const modalFrame = createMockElement('div', { class: 'wiki-cover-frame', id: 'wikiCoverModalFrame' });
    const modalImg = createMockElement('img', { class: 'wiki-cover-img', id: 'wikiCoverModalImg' });
    const modalBadge = createMockElement('div', { class: 'wiki-cover-focal-badge', id: 'wikiCoverModalBadge' });
    const modalDragHint = createMockElement('div', { class: 'wiki-cover-drag-hint', id: 'wikiCoverModalDragHint' });

    modalFrame.appendChild(modalImg);
    modalFrame.appendChild(modalBadge);
    modalFrame.appendChild(modalDragHint);
    workspace.appendChild(modalFrame);
    modalBody.appendChild(workspace);

    // Toolbar (Reset only)
    const modalToolbar = createMockElement('div', { class: 'wiki-cover-modal-toolbar' });
    const modalTools = createMockElement('div', { class: 'wiki-cover-modal-tools' });
    const modalResetBtn = createMockElement('button', { id: 'wikiCoverModalResetBtn', 'data-action': 'modal-reset' });
    const modalError = createMockElement('div', { class: 'wiki-cover-error wiki-admin-form-error', id: 'wikiCoverModalError' });
    modalError.hidden = true;

    modalTools.appendChild(modalResetBtn);
    modalToolbar.appendChild(modalTools);
    modalToolbar.appendChild(modalError);
    modalBody.appendChild(modalToolbar);

    // Footer
    const modalFooter = createMockElement('div', { class: 'wiki-cover-modal-footer' });
    const cancelBtn = createMockElement('button', { 'data-action': 'close-modal' });
    const applyBtn = createMockElement('button', { id: 'wikiCoverApplyBtn', 'data-action': 'modal-apply' });
    modalFooter.appendChild(cancelBtn);
    modalFooter.appendChild(applyBtn);

    modalContent.appendChild(modalHeader);
    modalContent.appendChild(modalBody);
    modalContent.appendChild(modalFooter);
    dialog.appendChild(modalContent);
    modal.appendChild(backdrop);
    modal.appendChild(dialog);

    container.appendChild(inputX);
    container.appendChild(inputY);
    container.appendChild(pickerInput);
    container.appendChild(fileInput);
    container.appendChild(removeCheckbox);
    container.appendChild(compactCard);
    container.appendChild(modal);

    return {
        container,
        inputX,
        inputY,
        pickerInput,
        fileInput,
        removeCheckbox,
        compactCard,
        compactFrame,
        compactImg,
        compactPlaceholder,
        noImageActions,
        chooseCoverBtn,
        pendingRemoval,
        undoRemoveBtn,
        restingError,
        menuWrapper,
        editBtn,
        actionMenu,
        updateCoverBtn,
        repositionCoverBtn,
        removeCoverBtn,
        modal,
        backdrop,
        closeBtn,
        cancelBtn,
        applyBtn,
        modalTitle,
        workspace,
        modalFrame,
        modalImg,
        modalBadge,
        modalDragHint,
        modalResetBtn,
        modalError
    };
}

describe('WikiCoverEditor MS-05G7.2 Hardened Cover Picker & State Machine Tests', () => {
    let mockUrls;
    let documentListeners;
    let shouldFailDecode;
    let pendingDecode;
    let pendingDecodeResolvers;

    beforeEach(() => {
        mockUrls = [];
        let urlCounter = 0;
        documentListeners = {};
        shouldFailDecode = false;
        pendingDecode = false;
        pendingDecodeResolvers = [];

        global.URL = {
            createObjectURL: (file) => {
                urlCounter++;
                const url = `blob:test/${urlCounter}`;
                mockUrls.push(url);
                return url;
            },
            revokeObjectURL: (url) => {
                const idx = mockUrls.indexOf(url);
                if (idx !== -1) mockUrls.splice(idx, 1);
            }
        };

        global.DataTransfer = class {
            constructor() {
                const itemsList = [];
                this.items = {
                    add: (file) => { itemsList.push(file); }
                };
                Object.defineProperty(this, 'files', {
                    get: () => createFileList(itemsList)
                });
            }
        };

        global.document = {
            addEventListener: (evt, fn) => {
                if (!documentListeners[evt]) documentListeners[evt] = [];
                documentListeners[evt].push(fn);
            },
            removeEventListener: (evt, fn) => {
                if (!documentListeners[evt]) return;
                const idx = documentListeners[evt].indexOf(fn);
                if (idx !== -1) documentListeners[evt].splice(idx, 1);
            },
            dispatchEvent: (evtName, evtObj = {}) => {
                const fns = documentListeners[evtName] || [];
                const event = {
                    type: evtName,
                    target: evtObj.target || global.document,
                    ...evtObj
                };
                for (const fn of fns) {
                    fn(event);
                }
                return event;
            },
            querySelector: () => null,
            querySelectorAll: () => []
        };

        global.Image = class {
            constructor() {
                this._src = '';
                this.onload = null;
                this.onerror = null;
            }
            set src(val) {
                this._src = val;
                if (!val) return;
                if (pendingDecode) {
                    pendingDecodeResolvers.push(() => {
                        if (this.onload) this.onload();
                    });
                    return;
                }
                if (shouldFailDecode || val.includes('corrupt')) {
                    queueMicrotask(() => {
                        if (this.onerror) this.onerror(new Error('Corrupt image'));
                    });
                } else {
                    queueMicrotask(() => {
                        if (this.onload) this.onload();
                    });
                }
            }
            get src() {
                return this._src;
            }
            decode() {
                return Promise.resolve();
            }
        };
    });

    // 1. Two inputs model: pickerInput separated from submission fileInput
    test('1. Two inputs model: pickerInput separated from submission fileInput', () => {
        const fixture = buildMockEditorContainer({ hasImage: true });
        const editor = new WikiCoverEditor(fixture.container);

        assert.notStrictEqual(editor.pickerInput, null);
        assert.notStrictEqual(editor.fileInput, null);
        assert.notStrictEqual(editor.pickerInput, editor.fileInput);
    });

    // 2. Choose cover clicks temporary picker input, not submission input
    test('2. Choose cover clicks temporary picker input, not submission input', () => {
        const fixture = buildMockEditorContainer({ hasImage: false });
        const editor = new WikiCoverEditor(fixture.container);

        let pickerClicked = false;
        let submitClicked = false;
        fixture.pickerInput.click = () => { pickerClicked = true; };
        fixture.fileInput.click = () => { submitClicked = true; };

        fixture.chooseCoverBtn.click();
        assert.strictEqual(pickerClicked, true);
        assert.strictEqual(submitClicked, false);
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.PICKER_PENDING);
    });

    // 3. Update cover clicks temporary picker input, not submission input
    test('3. Update cover clicks temporary picker input, not submission input', () => {
        const fixture = buildMockEditorContainer({ hasImage: true });
        const editor = new WikiCoverEditor(fixture.container);

        let pickerClicked = false;
        let submitClicked = false;
        fixture.pickerInput.click = () => { pickerClicked = true; };
        fixture.fileInput.click = () => { submitClicked = true; };

        fixture.editBtn.click();
        fixture.updateCoverBtn.click();

        assert.strictEqual(pickerClicked, true);
        assert.strictEqual(submitClicked, false);
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.PICKER_PENDING);
    });

    // 4. triggerFilePicker() does NOT clear the already-applied submission fileInput
    test('4. triggerFilePicker() does NOT clear the already-applied submission fileInput', () => {
        const fixture = buildMockEditorContainer({ hasImage: true });
        const editor = new WikiCoverEditor(fixture.container);

        const appliedFile = { name: 'previously-applied.jpg', type: 'image/jpeg', size: 1024 * 100 };
        const dt = new global.DataTransfer();
        dt.items.add(appliedFile);
        fixture.fileInput.files = dt.files;
        editor.appliedFile = appliedFile;

        fixture.editBtn.click();
        fixture.updateCoverBtn.click();

        // Submitted input MUST preserve applied file
        assert.strictEqual(fixture.fileInput.files.length, 1);
        assert.strictEqual(fixture.fileInput.files[0].name, 'previously-applied.jpg');
    });

    // 5. Native picker cancel restores EXISTING_COVER state
    test('5. Native picker cancel restores EXISTING_COVER state on existing cover', () => {
        const fixture = buildMockEditorContainer({ hasImage: true });
        const editor = new WikiCoverEditor(fixture.container);

        fixture.editBtn.click();
        fixture.updateCoverBtn.click();
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.PICKER_PENDING);

        // Native cancel event
        fixture.pickerInput.dispatchEvent('cancel', {});
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.EXISTING_COVER);
        assert.strictEqual(editor.isModalOpen(), false);
    });

    // 6. Native picker cancel restores NO_COVER state on create
    test('6. Native picker cancel restores NO_COVER state on create', () => {
        const fixture = buildMockEditorContainer({ hasImage: false });
        const editor = new WikiCoverEditor(fixture.container);

        fixture.chooseCoverBtn.click();
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.PICKER_PENDING);

        fixture.pickerInput.dispatchEvent('cancel', {});
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.NO_COVER);
        assert.strictEqual(editor.isModalOpen(), false);
    });

    // 7. Native picker cancel restores PENDING_REMOVAL state and keeps removeCover checked
    test('7. Native picker cancel restores PENDING_REMOVAL state and keeps removeCover checked', () => {
        const fixture = buildMockEditorContainer({ hasImage: true, checkedRemove: true });
        const editor = new WikiCoverEditor(fixture.container);
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.PENDING_REMOVAL);

        editor.triggerFilePicker();
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.PICKER_PENDING);

        fixture.pickerInput.dispatchEvent('cancel', {});
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.PENDING_REMOVAL);
        assert.strictEqual(fixture.removeCheckbox.checked, true);
        assert.strictEqual(fixture.pendingRemoval.hidden, false);
    });

    // 8. Native picker cancel restores APPLIED_REPLACEMENT state and preserves submitted file
    test('8. Native picker cancel restores APPLIED_REPLACEMENT state and preserves submitted file', async () => {
        const fixture = buildMockEditorContainer({ hasImage: false });
        const editor = new WikiCoverEditor(fixture.container);

        const fileA = { name: 'photo-A.jpg', type: 'image/jpeg', size: 1024 * 60 };
        await editor.handleSelectedFile(fileA);
        fixture.applyBtn.click();

        assert.strictEqual(editor.state, WikiCoverEditor.STATE.APPLIED_REPLACEMENT);
        assert.strictEqual(fixture.fileInput.files.length, 1);
        assert.strictEqual(fixture.fileInput.files[0].name, 'photo-A.jpg');

        // Update again, then cancel
        fixture.editBtn.click();
        fixture.updateCoverBtn.click();
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.PICKER_PENDING);

        fixture.pickerInput.dispatchEvent('cancel', {});
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.APPLIED_REPLACEMENT);
        assert.strictEqual(fixture.fileInput.files.length, 1);
        assert.strictEqual(fixture.fileInput.files[0].name, 'photo-A.jpg');
        assert.strictEqual(editor.isModalOpen(), false);
    });

    // 9. Validation failure restores state and does NOT wipe applied file
    test('9. Validation failure restores state and does NOT wipe applied file', async () => {
        const fixture = buildMockEditorContainer({ hasImage: true });
        const editor = new WikiCoverEditor(fixture.container);

        const appliedFile = { name: 'valid.png', type: 'image/png', size: 1024 * 50 };
        const dt = new global.DataTransfer();
        dt.items.add(appliedFile);
        fixture.fileInput.files = dt.files;
        editor.appliedFile = appliedFile;

        fixture.editBtn.click();
        fixture.updateCoverBtn.click();
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.PICKER_PENDING);

        // Oversized file (> 5MB)
        const badFile = { name: 'huge.jpg', type: 'image/jpeg', size: 6 * 1024 * 1024 };
        await editor.handleSelectedFile(badFile);

        assert.strictEqual(editor.state, WikiCoverEditor.STATE.APPLIED_REPLACEMENT);
        assert.strictEqual(fixture.fileInput.files.length, 1);
        assert.strictEqual(fixture.fileInput.files[0].name, 'valid.png');
        assert.strictEqual(fixture.restingError.hidden, false);
        assert.strictEqual(editor.isModalOpen(), false);
    });

    // 10. Valid image decode succeeds -> opens modal
    test('10. Valid image decode succeeds -> opens modal with preview', async () => {
        const fixture = buildMockEditorContainer({ hasImage: true });
        const editor = new WikiCoverEditor(fixture.container);

        const file = { name: 'good.png', type: 'image/png', size: 1024 * 100 };
        await editor.handleSelectedFile(file);

        assert.strictEqual(editor.isModalOpen(), true);
        assert.strictEqual(fixture.modal.hidden, false);
        assert.strictEqual(fixture.modalImg.src.startsWith('blob:test/'), true);
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.MODAL_NEW_FILE);
    });

    // 11. Decode pending -> modal remains closed
    test('11. Decode pending -> modal remains closed until decode resolves', async () => {
        const fixture = buildMockEditorContainer({ hasImage: false });
        const editor = new WikiCoverEditor(fixture.container);

        pendingDecode = true;
        const file = { name: 'slow.png', type: 'image/png', size: 1024 * 100 };
        const promise = editor.handleSelectedFile(file);

        assert.strictEqual(editor.isModalOpen(), false);
        assert.strictEqual(fixture.modal.hidden, true);

        // Now resolve decode
        pendingDecodeResolvers.forEach(fn => fn());
        await promise;

        assert.strictEqual(editor.isModalOpen(), true);
        assert.strictEqual(fixture.modal.hidden, false);
    });

    // 12. Decode failure -> modal remains closed, shows feedback, revokes object URL
    test('12. Decode failure -> modal remains closed, shows feedback, revokes object URL', async () => {
        const fixture = buildMockEditorContainer({ hasImage: true });
        const editor = new WikiCoverEditor(fixture.container);

        shouldFailDecode = true;
        const corruptFile = { name: 'corrupt.jpg', type: 'image/jpeg', size: 1024 * 50 };
        await editor.handleSelectedFile(corruptFile);

        assert.strictEqual(editor.isModalOpen(), false);
        assert.strictEqual(fixture.modal.hidden, true);
        assert.strictEqual(mockUrls.length, 0); // Revoked
        assert.strictEqual(fixture.restingError.hidden, false);
        assert.strictEqual(fixture.restingError.textContent, 'Không thể đọc ảnh đã chọn. Vui lòng chọn tệp ảnh khác.');
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.EXISTING_COVER);
    });

    // 13. Decode failure preserves pending removal
    test('13. Decode failure preserves pending removal', async () => {
        const fixture = buildMockEditorContainer({ hasImage: true, checkedRemove: true });
        const editor = new WikiCoverEditor(fixture.container);
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.PENDING_REMOVAL);

        shouldFailDecode = true;
        const corruptFile = { name: 'corrupt.png', type: 'image/png', size: 1024 * 50 };
        await editor.handleSelectedFile(corruptFile);

        assert.strictEqual(editor.state, WikiCoverEditor.STATE.PENDING_REMOVAL);
        assert.strictEqual(fixture.removeCheckbox.checked, true);
        assert.strictEqual(fixture.pendingRemoval.hidden, false);
        assert.strictEqual(editor.isModalOpen(), false);
    });

    // 14. Pending removal -> new valid file -> modal Cancel restores PENDING_REMOVAL
    test('14. Pending removal -> new valid file -> modal Cancel restores PENDING_REMOVAL', async () => {
        const fixture = buildMockEditorContainer({ hasImage: true, checkedRemove: true });
        const editor = new WikiCoverEditor(fixture.container);
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.PENDING_REMOVAL);

        const file = { name: 'candidate.jpg', type: 'image/jpeg', size: 1024 * 80 };
        await editor.handleSelectedFile(file);

        assert.strictEqual(editor.isModalOpen(), true);

        // Cancel modal
        fixture.cancelBtn.click();
        assert.strictEqual(editor.isModalOpen(), false);
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.PENDING_REMOVAL);
        assert.strictEqual(fixture.removeCheckbox.checked, true);
        assert.strictEqual(fixture.pendingRemoval.hidden, false);
    });

    // 15. Pending removal -> new valid file -> modal Apply commits new file and clears removal
    test('15. Pending removal -> new valid file -> modal Apply commits new file and clears removal', async () => {
        const fixture = buildMockEditorContainer({ hasImage: true, checkedRemove: true });
        const editor = new WikiCoverEditor(fixture.container);

        const file = { name: 'candidate.jpg', type: 'image/jpeg', size: 1024 * 80 };
        await editor.handleSelectedFile(file);

        fixture.applyBtn.click();
        assert.strictEqual(editor.isModalOpen(), false);
        assert.strictEqual(fixture.removeCheckbox.checked, false);
        assert.strictEqual(fixture.pendingRemoval.hidden, true);
        assert.strictEqual(fixture.fileInput.files.length, 1);
        assert.strictEqual(fixture.fileInput.files[0].name, 'candidate.jpg');
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.APPLIED_REPLACEMENT);
    });

    // 16. Real outside click on document closes action menu
    test('16. Real outside click on document closes action menu', () => {
        const fixture = buildMockEditorContainer({ hasImage: true });
        const editor = new WikiCoverEditor(fixture.container);

        fixture.editBtn.click();
        assert.strictEqual(editor.isActionMenuOpen(), true);
        assert.strictEqual(fixture.actionMenu.hidden, false);

        // Real document click event dispatched on target outside menuWrapper
        const outsideTarget = createMockElement('div');
        global.document.dispatchEvent('click', { target: outsideTarget });

        assert.strictEqual(editor.isActionMenuOpen(), false);
        assert.strictEqual(fixture.actionMenu.hidden, true);
        assert.strictEqual(fixture.editBtn.getAttribute('aria-expanded'), 'false');
    });

    // 17. Real Escape keydown closes action menu
    test('17. Real Escape keydown closes action menu', () => {
        const fixture = buildMockEditorContainer({ hasImage: true });
        const editor = new WikiCoverEditor(fixture.container);

        fixture.editBtn.click();
        assert.strictEqual(editor.isActionMenuOpen(), true);

        global.document.dispatchEvent('keydown', { key: 'Escape' });

        assert.strictEqual(editor.isActionMenuOpen(), false);
        assert.strictEqual(fixture.actionMenu.hidden, true);
    });

    // 18. Real Escape keydown closes modal
    test('18. Real Escape keydown closes modal', () => {
        const fixture = buildMockEditorContainer({ hasImage: true });
        const editor = new WikiCoverEditor(fixture.container);

        editor.openModal('reposition');
        assert.strictEqual(editor.isModalOpen(), true);

        global.document.dispatchEvent('keydown', { key: 'Escape' });
        assert.strictEqual(editor.isModalOpen(), false);
    });

    // 19. Create local cover discard semantics (Bỏ ảnh đã chọn)
    test('19. Create local cover discard: reverts to placeholder, does NOT set removeCover=true', async () => {
        const fixture = buildMockEditorContainer({ hasImage: false, hasPersistedCover: false });
        const editor = new WikiCoverEditor(fixture.container);

        const file = { name: 'local.png', type: 'image/png', size: 1024 * 70 };
        await editor.handleSelectedFile(file);
        fixture.applyBtn.click();

        assert.strictEqual(editor.state, WikiCoverEditor.STATE.APPLIED_REPLACEMENT);
        assert.strictEqual(fixture.fileInput.files.length, 1);
        assert.strictEqual(fixture.menuWrapper.style.display, '');

        // Click "Bỏ ảnh đã chọn"
        fixture.editBtn.click();
        fixture.removeCoverBtn.click();

        assert.strictEqual(editor.state, WikiCoverEditor.STATE.NO_COVER);
        assert.strictEqual(fixture.fileInput.files.length, 0);
        assert.strictEqual(fixture.fileInput.value, '');
        assert.strictEqual(fixture.removeCheckbox.checked, false);
        assert.strictEqual(fixture.pendingRemoval.hidden, true);
        assert.strictEqual(fixture.compactPlaceholder.style.display, '');
        assert.strictEqual(fixture.noImageActions.style.display, '');
        assert.strictEqual(fixture.menuWrapper.style.display, 'none');
    });

    // 20. Edit article with NO server cover has local discard semantics
    test('20. Edit article with NO server cover has local discard semantics', async () => {
        const fixture = buildMockEditorContainer({ hasImage: false, hasPersistedCover: false });
        const editor = new WikiCoverEditor(fixture.container);

        const file = { name: 'edit-local.jpg', type: 'image/jpeg', size: 1024 * 50 };
        await editor.handleSelectedFile(file);
        fixture.applyBtn.click();

        assert.strictEqual(editor.hasPersistedCover, false);

        editor.removeCover();
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.NO_COVER);
        assert.strictEqual(fixture.removeCheckbox.checked, false);
        assert.strictEqual(fixture.pendingRemoval.hidden, true);
        assert.strictEqual(fixture.fileInput.files.length, 0);
    });

    // 21. Edit article with persisted cover + replacement + Remove marks removeCover=true
    test('21. Edit article with persisted cover + replacement + Remove marks removeCover=true', async () => {
        const fixture = buildMockEditorContainer({ hasImage: true, hasPersistedCover: true });
        const editor = new WikiCoverEditor(fixture.container);

        const file = { name: 'replacement.png', type: 'image/png', size: 1024 * 90 };
        await editor.handleSelectedFile(file);
        fixture.applyBtn.click();

        assert.strictEqual(fixture.fileInput.files.length, 1);

        // User removes cover
        editor.removeCover();

        assert.strictEqual(editor.state, WikiCoverEditor.STATE.PENDING_REMOVAL);
        assert.strictEqual(fixture.removeCheckbox.checked, true);
        assert.strictEqual(fixture.pendingRemoval.hidden, false);
        assert.strictEqual(fixture.fileInput.files.length, 0);
        assert.strictEqual(fixture.compactImg.src, '/media/assets/uuid-test/content');
    });

    // 22. Local file drop on resting cover opens modal with preview after decode
    test('22. Local file drop on resting cover opens modal with preview after decode', async () => {
        const fixture = buildMockEditorContainer({ hasImage: true });
        const editor = new WikiCoverEditor(fixture.container);

        const dropFile = { name: 'dropped.jpg', type: 'image/jpeg', size: 200 * 1024 };
        fixture.compactFrame.dispatchEvent('drop', {
            dataTransfer: { files: createFileList([dropFile]) }
        });

        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(editor.isModalOpen(), true);
        assert.strictEqual(fixture.modalImg.src.startsWith('blob:test/'), true);
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.MODAL_NEW_FILE);
    });

    // 23. Local file drop on placeholder opens modal with preview after decode
    test('23. Local file drop on placeholder opens modal with preview after decode', async () => {
        const fixture = buildMockEditorContainer({ hasImage: false });
        const editor = new WikiCoverEditor(fixture.container);

        const dropFile = { name: 'dropped.png', type: 'image/png', size: 150 * 1024 };
        fixture.compactFrame.dispatchEvent('drop', {
            dataTransfer: { files: createFileList([dropFile]) }
        });

        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(editor.isModalOpen(), true);
        assert.strictEqual(fixture.modalImg.src.startsWith('blob:test/'), true);
    });

    // 24. Drop without File object does not attempt remote fetch
    test('24. Drop without File object does not attempt remote fetch (shows error)', () => {
        const fixture = buildMockEditorContainer({ hasImage: true });
        const editor = new WikiCoverEditor(fixture.container);

        fixture.compactFrame.dispatchEvent('drop', {
            dataTransfer: { files: createFileList([]) }
        });

        assert.strictEqual(editor.isModalOpen(), false);
        assert.strictEqual(fixture.restingError.hidden, false);
        assert.strictEqual(fixture.restingError.textContent, 'Vui lòng thả tệp ảnh từ thiết bị.');
    });

    // 25. Modal file replacement works through File drop onto workspace
    test('25. Modal file replacement works through File drop onto workspace', async () => {
        const fixture = buildMockEditorContainer({ hasImage: true });
        const editor = new WikiCoverEditor(fixture.container);
        editor.openModal('reposition');

        const newDropFile = { name: 'replace.webp', type: 'image/webp', size: 80 * 1024 };
        fixture.workspace.dispatchEvent('drop', {
            dataTransfer: { files: createFileList([newDropFile]) }
        });

        await new Promise(r => setTimeout(r, 10));

        assert.strictEqual(fixture.modalImg.src.startsWith('blob:test/'), true);
        assert.strictEqual(editor.modalFocalX, 50);
        assert.strictEqual(editor.modalFocalY, 50);
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.MODAL_NEW_FILE);
    });

    // 26. Reset button resets focal to 50/50
    test('26. Reset button resets focal to 50/50', () => {
        const fixture = buildMockEditorContainer({ hasImage: true, posX: '80', posY: '30' });
        const editor = new WikiCoverEditor(fixture.container);

        editor.openModal('reposition');
        assert.strictEqual(editor.modalFocalX, 80);

        fixture.modalResetBtn.click();
        assert.strictEqual(editor.modalFocalX, 50);
        assert.strictEqual(editor.modalFocalY, 50);
        assert.strictEqual(fixture.modalResetBtn.disabled, true);
    });

    // 27. Reset button disabled at 50/50, enabled when differing
    test('27. Reset button disabled at 50/50, enabled when differing', () => {
        const fixture = buildMockEditorContainer({ hasImage: true, posX: '50', posY: '50' });
        const editor = new WikiCoverEditor(fixture.container);

        editor.openModal('reposition');
        assert.strictEqual(fixture.modalResetBtn.disabled, true);

        // Move pointer
        fixture.modalFrame.dispatchEvent('pointerdown', { clientX: 200, clientY: 200 });
        fixture.modalFrame.dispatchEvent('pointermove', { clientX: 180, clientY: 180 });
        fixture.modalFrame.dispatchEvent('pointerup', {});

        assert.strictEqual(fixture.modalResetBtn.disabled, false);
    });

    // 28. No Cropper.js
    test('28. No Cropper.js', () => {
        const src = fs.readFileSync(SCRIPT_PATH, 'utf8');
        assert.strictEqual(src.includes('Cropper'), false);
    });

    // 29. No canvas element or context
    test('29. No canvas element or context', () => {
        const src = fs.readFileSync(SCRIPT_PATH, 'utf8');
        assert.strictEqual(src.includes('getContext'), false);
        assert.strictEqual(src.includes('document.createElement(\'canvas\')'), false);
    });

    // 30. No toBlob() conversion
    test('30. No toBlob() conversion', () => {
        const src = fs.readFileSync(SCRIPT_PATH, 'utf8');
        assert.strictEqual(src.includes('toBlob'), false);
    });

    // 31. No Wiki WebP conversion
    test('31. No Wiki WebP conversion', () => {
        const src = fs.readFileSync(SCRIPT_PATH, 'utf8');
        assert.strictEqual(src.includes('toDataURL'), false);
        assert.strictEqual(src.includes('image/webp'), true);
    });

    // 32. No AJAX upload in WikiCoverEditor
    test('32. No AJAX upload in WikiCoverEditor', () => {
        const src = fs.readFileSync(SCRIPT_PATH, 'utf8');
        assert.strictEqual(src.includes('fetch('), false);
        assert.strictEqual(src.includes('XMLHttpRequest'), false);
        assert.strictEqual(src.includes('axios'), false);
    });

    // 33. No remote URL fetch
    test('33. No remote URL fetch', () => {
        const src = fs.readFileSync(SCRIPT_PATH, 'utf8');
        assert.strictEqual(src.includes('http://'), false);
        assert.strictEqual(src.includes('https://'), false);
    });

    // 34. Object URLs properly revoked on destruction and replacement
    test('34. Object URLs properly revoked on destruction and replacement', async () => {
        const fixture = buildMockEditorContainer({ hasImage: false });
        const editor = new WikiCoverEditor(fixture.container);

        await editor.handleSelectedFile({ name: 'a.png', type: 'image/png', size: 50 * 1024 });
        fixture.applyBtn.click();
        assert.strictEqual(mockUrls.length, 1);

        editor.destroy();
        assert.strictEqual(mockUrls.length, 0);
    });

    // 35. Full state machine transitions through complete lifecycle
    test('35. Full state machine transitions through complete lifecycle', async () => {
        const fixture = buildMockEditorContainer({ hasImage: false, hasPersistedCover: false });
        const editor = new WikiCoverEditor(fixture.container);
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.NO_COVER);

        fixture.chooseCoverBtn.click();
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.PICKER_PENDING);

        await editor.handleSelectedFile({ name: 'test.jpg', type: 'image/jpeg', size: 1024 * 50 });
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.MODAL_NEW_FILE);

        fixture.applyBtn.click();
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.APPLIED_REPLACEMENT);

        fixture.editBtn.click();
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.ACTION_MENU_OPEN);

        fixture.repositionCoverBtn.click();
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.MODAL_EDIT_EXISTING);

        fixture.cancelBtn.click();
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.APPLIED_REPLACEMENT);

        fixture.editBtn.click();
        fixture.removeCoverBtn.click();
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.NO_COVER);
    });

    // 36. Source contract: zero .files = [] and zero .files = [
    test('36. Source contract: zero .files = [] and zero .files = [', () => {
        const src = fs.readFileSync(SCRIPT_PATH, 'utf8');
        assert.strictEqual(src.includes('.files = []'), false);
        assert.strictEqual(src.includes('.files = ['), false);
        // Only valid assignment is dt.files
        assert.strictEqual(src.includes('.files = dt.files;'), true);
    });

    // 37. Clearing submitted file uses input.value = ''
    test('37. Clearing submitted file uses input.value = empty string', async () => {
        const fixture = buildMockEditorContainer({ hasImage: false, hasPersistedCover: false });
        const editor = new WikiCoverEditor(fixture.container);

        const file = { name: 'clear-test.jpg', type: 'image/jpeg', size: 1024 * 50 };
        await editor.handleSelectedFile(file);
        fixture.applyBtn.click();
        assert.strictEqual(fixture.fileInput.files.length, 1);

        editor.removeCover();
        assert.strictEqual(fixture.fileInput.value, '');
        assert.strictEqual(fixture.fileInput.files.length, 0);
    });

    // 38. Apply uses DataTransfer FileList to assign fileInput.files
    test('38. Apply uses DataTransfer FileList to assign fileInput.files', async () => {
        const fixture = buildMockEditorContainer({ hasImage: true });
        const editor = new WikiCoverEditor(fixture.container);

        const file = { name: 'datatransfer-test.png', type: 'image/png', size: 1024 * 40 };
        await editor.handleSelectedFile(file);

        fixture.applyBtn.click();
        assert.strictEqual(fixture.fileInput.files.length, 1);
        assert.strictEqual(Array.isArray(fixture.fileInput.files), false);
        assert.strictEqual(fixture.fileInput.files[0].name, 'datatransfer-test.png');
        assert.strictEqual(editor.state, WikiCoverEditor.STATE.APPLIED_REPLACEMENT);
    });

    // 39. DataTransfer unavailable fails safely with modal error and does not commit visual state
    test('39. DataTransfer unavailable fails safely with modal error and does not commit visual state', async () => {
        const fixture = buildMockEditorContainer({ hasImage: false });
        const editor = new WikiCoverEditor(fixture.container);

        const file = { name: 'no-dt.jpg', type: 'image/jpeg', size: 1024 * 30 };
        await editor.handleSelectedFile(file);
        assert.strictEqual(editor.isModalOpen(), true);

        // Simulate browser without DataTransfer
        const originalDT = global.DataTransfer;
        delete global.DataTransfer;

        try {
            fixture.applyBtn.click();

            assert.strictEqual(editor.isModalOpen(), true); // Modal remains open!
            assert.strictEqual(editor.appliedFile, null);   // Not committed!
            assert.strictEqual(fixture.modalError.hidden, false);
            assert.strictEqual(fixture.modalError.textContent.includes('không hỗ trợ'), true);
        } finally {
            global.DataTransfer = originalDT;
        }
    });

    // 40. DataTransfer exception fails safely with modal error and does not commit visual state
    test('40. DataTransfer exception fails safely with modal error and does not commit visual state', async () => {
        const fixture = buildMockEditorContainer({ hasImage: false });
        const editor = new WikiCoverEditor(fixture.container);

        const file = { name: 'dt-throw.jpg', type: 'image/jpeg', size: 1024 * 30 };
        await editor.handleSelectedFile(file);

        // Simulate DataTransfer throwing on items.add
        const originalDT = global.DataTransfer;
        global.DataTransfer = class {
            constructor() {
                this.items = {
                    add: () => { throw new Error('DataTransfer quota error'); }
                };
            }
        };

        try {
            fixture.applyBtn.click();

            assert.strictEqual(editor.isModalOpen(), true);
            assert.strictEqual(editor.appliedFile, null);
            assert.strictEqual(fixture.modalError.hidden, false);
            assert.strictEqual(fixture.modalError.textContent, 'Không thể nạp tệp ảnh vào biểu mẫu. Vui lòng thử lại.');
        } finally {
            global.DataTransfer = originalDT;
        }
    });

    // 41. Modal CSS contract: modal content has viewport max-height and overflow: hidden
    test('41. Modal CSS contract: modal content has viewport max-height and overflow: hidden', () => {
        const css = fs.readFileSync(ADMIN_CSS_PATH, 'utf8');
        assert.strictEqual(css.includes('.wiki-cover-modal-content'), true);
        assert.strictEqual(css.includes('max-height: calc(100dvh - 32px);'), true);
        assert.strictEqual(css.includes('max-height: calc(100vh - 32px);'), true);
    });

    // 42. Modal CSS contract: modal body supports shrink via min-height: 0 and flex: 1 1 auto
    test('42. Modal CSS contract: modal body supports shrink via min-height: 0 and flex: 1 1 auto', () => {
        const css = fs.readFileSync(ADMIN_CSS_PATH, 'utf8');
        assert.strictEqual(css.includes('.wiki-cover-modal-body'), true);
        assert.strictEqual(css.includes('min-height: 0;'), true);
        assert.strictEqual(css.includes('flex: 1 1 auto;'), true);
        assert.strictEqual(css.includes('overflow-y: auto;'), true);
    });

    // 43. Modal CSS contract: footer is non-shrinking via flex: 0 0 auto
    test('43. Modal CSS contract: footer and header are non-shrinking via flex: 0 0 auto', () => {
        const css = fs.readFileSync(ADMIN_CSS_PATH, 'utf8');
        assert.strictEqual(css.includes('.wiki-cover-modal-footer'), true);
        assert.strictEqual(css.includes('.wiki-cover-modal-header'), true);
        // Header and footer must stay pinned and visible
        assert.strictEqual(css.includes('flex: 0 0 auto;'), true);
    });

    // 44. Modal CSS contract: workspace does NOT depend on unconditional fixed desktop height
    test('44. Modal CSS contract: workspace no longer depends on an unconditional fixed desktop height', () => {
        const css = fs.readFileSync(ADMIN_CSS_PATH, 'utf8');
        // Unconditional fixed height: 480px was the root cause
        assert.strictEqual(css.includes('height: 480px;'), false);
        // Uses clamp and flexible height
        assert.strictEqual(css.includes('height: clamp(200px, 48dvh, 480px);'), true);
        assert.strictEqual(css.includes('max-height: 100%;'), true);
    });

    // 45. Modal CSS contract: frame retains aspect-ratio: 4 / 5 and fits both dimensions
    test('45. Modal CSS contract: frame retains aspect-ratio: 4 / 5 and fits both dimensions', () => {
        const css = fs.readFileSync(ADMIN_CSS_PATH, 'utf8');
        assert.strictEqual(css.includes('.wiki-cover-frame'), true);
        assert.strictEqual(css.includes('aspect-ratio: 4 / 5;'), true);
        assert.strictEqual(css.includes('max-height: 100%;'), true);
        assert.strictEqual(css.includes('max-width: 100%;'), true);
    });
});

