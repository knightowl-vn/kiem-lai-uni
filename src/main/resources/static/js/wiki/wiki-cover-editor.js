/**
 * KiemLai Universe — Wiki Cover Modal Composition & Editor (MS-05G7.2)
 *
 * Streamlined Admin Cover UX modeled after Identity avatar design language:
 * - Separate temporary source picker (#wikiCoverPickerInput) from submitted input (#wikiCoverImage).
 * - Compact 4:5 resting preview card with attached pencil action button.
 * - Action menu with "Cập nhật ảnh", "Chỉnh vị trí", "Xóa ảnh bìa" / "Bỏ ảnh đã chọn".
 * - Immediate OS/device file picker on "Cập nhật ảnh" and "Chọn ảnh bìa" (zero empty modal step).
 * - Asynchronous image decoding before modal opens (zero broken-image modals).
 * - Full state restoration (pickerReturnState) on picker cancel, invalid metadata, or decode failure.
 * - Local cover discard semantics (Create / Edit without server cover) vs persisted cover removal.
 * - Modal cancel while pending removal restores pending removal state.
 * - Commit boundary strictly on modal Apply (copies File into submitted input).
 * - Direct local File drag & drop onto resting card and modal workspace.
 * - Remote URL / text drag rejection ("Vui lòng thả tệp ảnh từ thiết bị.").
 * - Zero third-party cropper libraries, zero canvas cropping, zero blob conversion, zero client WebP conversion.
 */
(function(root, factory) {
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = factory();
    } else {
        root.WikiCoverEditor = factory();
    }
}(typeof self !== 'undefined' ? self : this, function() {
    'use strict';

    const MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB
    const ALLOWED_MIME_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);
    const DEFAULT_FOCAL = 50;

    const STATE = {
        NO_COVER: 'NO_COVER',
        EXISTING_COVER: 'EXISTING_COVER',
        ACTION_MENU_OPEN: 'ACTION_MENU_OPEN',
        PICKER_PENDING: 'PICKER_PENDING',
        MODAL_EDIT_EXISTING: 'MODAL_EDIT_EXISTING',
        MODAL_NEW_FILE: 'MODAL_NEW_FILE',
        APPLIED_REPLACEMENT: 'APPLIED_REPLACEMENT',
        PENDING_REMOVAL: 'PENDING_REMOVAL'
    };

    function clamp(val, min, max) {
        return Math.min(Math.max(val, min), max);
    }

    /**
     * Browser image decode helper without canvas or network APIs.
     * Resolves ONLY when browser successfully parses and decodes the image binary.
     */
    function decodeImage(src) {
        return new Promise((resolve, reject) => {
            const img = new Image();
            img.onload = () => {
                if (typeof img.decode === 'function') {
                    img.decode().then(() => resolve(img)).catch(() => resolve(img));
                } else {
                    resolve(img);
                }
            };
            img.onerror = (err) => {
                reject(new Error('Decode failed'));
            };
            img.src = src;
        });
    }

    class WikiCoverEditor {
        constructor(container, options = {}) {
            if (!container) {
                throw new Error('Container element is required for WikiCoverEditor.');
            }
            this.container = container;
            this.options = options;

            // Form inputs
            this.inputX = container.querySelector(options.inputXSelector || 'input[name="coverPositionX"]')
                || (typeof document !== 'undefined' ? document.querySelector('#wikiCoverPositionX') : null);
            this.inputY = container.querySelector(options.inputYSelector || 'input[name="coverPositionY"]')
                || (typeof document !== 'undefined' ? document.querySelector('#wikiCoverPositionY') : null);

            // ACTUAL FORM SUBMISSION INPUT: Mutated ONLY on modal Apply
            this.fileInput = container.querySelector(options.fileInputSelector || 'input[name="coverImageFile"]')
                || (typeof document !== 'undefined' ? document.querySelector('#wikiCoverImage') : null);

            // TEMPORARY SOURCE PICKER: Used only to invoke native picker dialog, never submitted
            this.pickerInput = container.querySelector(options.pickerInputSelector || '#wikiCoverPickerInput')
                || (typeof document !== 'undefined' ? document.querySelector('#wikiCoverPickerInput') : null);

            this.removeCheckbox = container.querySelector(options.removeCheckboxSelector || '#removeCover')
                || (typeof document !== 'undefined' ? document.querySelector('#removeCover') : null);

            // Compact Resting Form UI
            this.compactCard = container.querySelector(options.compactCardSelector || '#wikiCoverCard, .wiki-admin-cover-upload-card') || container;
            this.compactFrame = this.compactCard.querySelector('#wikiCoverCompactFrame, .wiki-cover-compact-frame');
            this.compactImg = this.compactCard.querySelector('#wikiCoverCompactImg, .wiki-cover-compact-img');
            this.compactPlaceholder = this.compactCard.querySelector('#wikiCoverCompactPlaceholder, .wiki-admin-cover-placeholder');
            this.noImageActions = this.compactCard.querySelector('#wikiCoverNoImageActions');
            this.chooseCoverBtn = this.compactCard.querySelector('[data-action="choose-cover"]');
            this.pendingRemoval = this.compactCard.querySelector('#wikiCoverPendingRemoval, .wiki-cover-pending-removal');
            this.undoRemoveBtn = this.compactCard.querySelector('[data-action="undo-remove"]');
            this.restingError = this.compactCard.querySelector('#wikiCoverRestingError, .wiki-cover-error');

            // Pencil Button & Action Menu (MS-05G7.1)
            this.menuWrapper = this.compactCard.querySelector('#wikiCoverMenuWrapper, .wiki-cover-menu-wrapper');
            this.editBtn = this.compactCard.querySelector('#wikiCoverEditBtn, [data-action="toggle-menu"]');
            this.actionMenu = this.compactCard.querySelector('#wikiCoverActionMenu, .wiki-cover-action-menu');
            this.updateCoverBtn = this.compactCard.querySelector('[data-action="update-cover"]');
            this.repositionCoverBtn = this.compactCard.querySelector('[data-action="reposition-cover"]');
            this.removeCoverBtn = this.compactCard.querySelector('[data-action="remove-cover"]');

            // Modal elements
            this.modal = container.querySelector(options.modalSelector || '#wikiCoverModal, .wiki-cover-modal')
                || (typeof document !== 'undefined' ? document.querySelector('#wikiCoverModal, .wiki-cover-modal') : null);
            this.modalTitle = this.modal ? this.modal.querySelector('#wikiCoverModalTitle, .wiki-cover-modal-title') : null;
            this.modalCloseBtns = this.modal ? this.modal.querySelectorAll('[data-action="close-modal"], .btn-close, .wiki-cover-modal-close') : [];
            this.workspace = this.modal ? this.modal.querySelector('#wikiCoverWorkspace, .wiki-cover-workspace') : null;
            this.modalFrame = this.modal ? this.modal.querySelector('#wikiCoverModalFrame, .wiki-cover-frame') : null;
            this.modalImg = this.modal ? this.modal.querySelector('#wikiCoverModalImg, .wiki-cover-img') : null;
            this.modalBadge = this.modal ? this.modal.querySelector('#wikiCoverModalBadge, .wiki-cover-focal-badge') : null;
            this.modalDragHint = this.modal ? this.modal.querySelector('#wikiCoverModalDragHint, .wiki-cover-drag-hint') : null;
            this.modalResetBtn = this.modal ? this.modal.querySelector('#wikiCoverModalResetBtn, [data-action="modal-reset"]') : null;
            this.modalError = this.modal ? this.modal.querySelector('#wikiCoverModalError, .wiki-cover-error') : null;
            this.modalApplyBtn = this.modal ? this.modal.querySelector('#wikiCoverApplyBtn, [data-action="modal-apply"]') : null;

            // Source of Truth Flags
            const cardEl = this.compactCard;
            const attrHasPersisted = cardEl ? cardEl.getAttribute('data-has-persisted-cover') : null;
            if (attrHasPersisted !== null) {
                this.hasPersistedCover = (attrHasPersisted === 'true');
            } else {
                this.hasPersistedCover = !!(this.removeCheckbox && this.compactImg && this.compactImg.getAttribute('src') && !this.compactImg.hidden);
            }

            // Internal State — Applied
            const initialX = this.inputX ? parseInt(this.inputX.value, 10) : DEFAULT_FOCAL;
            const initialY = this.inputY ? parseInt(this.inputY.value, 10) : DEFAULT_FOCAL;
            this.appliedFocalX = isNaN(initialX) ? DEFAULT_FOCAL : clamp(initialX, 0, 100);
            this.appliedFocalY = isNaN(initialY) ? DEFAULT_FOCAL : clamp(initialY, 0, 100);
            this.appliedFile = null;
            this.appliedObjectUrl = null;
            this.appliedServerUrl = (this.compactImg && this.compactImg.getAttribute('src') && !this.compactImg.hidden)
                ? this.compactImg.getAttribute('src')
                : null;
            this.isRemovalPending = !!(this.removeCheckbox && this.removeCheckbox.checked);

            // Picker Return State (for safe cancel / error restoration)
            this.pickerReturnState = null;

            // Modal-Local Working Copy
            this.modalFocalX = this.appliedFocalX;
            this.modalFocalY = this.appliedFocalY;
            this.modalPendingFile = null;
            this.modalObjectUrl = null;
            this.modalImageSrc = null;
            this.modalMode = 'reposition';

            // Drag State
            this.isDragging = false;
            this.pointerStartX = 0;
            this.pointerStartY = 0;
            this.startFocalX = DEFAULT_FOCAL;
            this.startFocalY = DEFAULT_FOCAL;

            // State Machine
            this.state = STATE.NO_COVER;

            this._bindEvents();
            this._initState();
        }

        static init(container, options) {
            return new WikiCoverEditor(container, options);
        }

        static autoInit() {
            const containers = document.querySelectorAll('.wiki-cover-editor, .wiki-admin-cover-upload-card, .wiki-admin-form-group');
            const instances = [];
            containers.forEach(c => {
                if (c.querySelector('input[name="coverPositionX"]') || c.querySelector('#wikiCoverPositionX')) {
                    instances.push(new WikiCoverEditor(c));
                }
            });
            return instances;
        }

        _initState() {
            if (this.hasAppliedImage()) {
                if (this.compactImg) {
                    this.compactImg.hidden = false;
                    this.compactImg.style.objectPosition = `${this.appliedFocalX}% ${this.appliedFocalY}%`;
                }
                if (this.compactPlaceholder) {
                    this.compactPlaceholder.style.display = 'none';
                    this.compactPlaceholder.hidden = true;
                }
                if (this.noImageActions) this.noImageActions.style.display = 'none';
                if (this.menuWrapper) this.menuWrapper.style.display = '';
                this.state = this.appliedFile ? STATE.APPLIED_REPLACEMENT : STATE.EXISTING_COVER;
            } else {
                if (this.compactImg) this.compactImg.hidden = true;
                if (this.compactPlaceholder) {
                    this.compactPlaceholder.style.display = '';
                    this.compactPlaceholder.hidden = false;
                }
                if (this.noImageActions) this.noImageActions.style.display = '';
                if (this.menuWrapper) this.menuWrapper.style.display = 'none';
                this.state = STATE.NO_COVER;
            }

            this._updateRemoveButtonCopy();

            if (this.isRemovalPending) {
                this._renderPendingRemovalUI(true);
                this.state = STATE.PENDING_REMOVAL;
            } else {
                this._renderPendingRemovalUI(false);
            }
        }

        _bindEvents() {
            // 1. "Chọn ảnh bìa" button (when no cover) -> triggers temporary picker
            if (this.chooseCoverBtn) {
                this.chooseCoverBtn.addEventListener('click', (e) => {
                    e.preventDefault();
                    this.triggerFilePicker();
                });
            }

            // 2. Pencil toggle button -> toggles dropdown action menu
            if (this.editBtn) {
                this.editBtn.addEventListener('click', (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    this.toggleActionMenu();
                });
            }

            // 3. "Cập nhật ảnh" menu item -> closes menu and immediately triggers temporary picker
            if (this.updateCoverBtn) {
                this.updateCoverBtn.addEventListener('click', (e) => {
                    e.preventDefault();
                    this.closeActionMenu();
                    this.triggerFilePicker();
                });
            }

            // 4. "Chỉnh vị trí" menu item -> closes menu and opens modal with existing image
            if (this.repositionCoverBtn) {
                this.repositionCoverBtn.addEventListener('click', (e) => {
                    e.preventDefault();
                    this.closeActionMenu();
                    this.openModal('reposition');
                });
            }

            // 5. "Xóa ảnh bìa" / "Bỏ ảnh đã chọn" menu item
            if (this.removeCoverBtn) {
                this.removeCoverBtn.addEventListener('click', (e) => {
                    e.preventDefault();
                    this.closeActionMenu();
                    this.removeCover();
                });
            }

            // 6. "Hoàn tác" button -> restores cover from pending removal
            if (this.undoRemoveBtn) {
                this.undoRemoveBtn.addEventListener('click', (e) => {
                    e.preventDefault();
                    this.undoRemove();
                });
            }

            // 7. Temporary Picker Input Events (change and cancel)
            const pickerTarget = this.pickerInput || this.fileInput;
            if (pickerTarget) {
                pickerTarget.addEventListener('change', () => {
                    if (pickerTarget.files && pickerTarget.files.length > 0) {
                        this.handleSelectedFile(pickerTarget.files[0]);
                    } else {
                        this._handlePickerCancel();
                    }
                });

                pickerTarget.addEventListener('cancel', () => {
                    this._handlePickerCancel();
                });
            }

            // 8. Resting Card Drag and Drop
            const restingDropTarget = this.compactFrame || this.compactCard;
            if (restingDropTarget) {
                restingDropTarget.addEventListener('dragenter', (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    restingDropTarget.classList.add('is-dragover');
                });
                restingDropTarget.addEventListener('dragover', (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    restingDropTarget.classList.add('is-dragover');
                });
                restingDropTarget.addEventListener('dragleave', (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    restingDropTarget.classList.remove('is-dragover');
                });
                restingDropTarget.addEventListener('drop', (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    restingDropTarget.classList.remove('is-dragover');
                    this._onRestingCardDrop(e);
                });
            }

            // 9. Modal Workspace Drag and Drop
            if (this.workspace) {
                this.workspace.addEventListener('dragenter', (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    this.workspace.classList.add('is-dragover');
                });
                this.workspace.addEventListener('dragover', (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    this.workspace.classList.add('is-dragover');
                });
                this.workspace.addEventListener('dragleave', (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    this.workspace.classList.remove('is-dragover');
                });
                this.workspace.addEventListener('drop', (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    this.workspace.classList.remove('is-dragover');
                    this._onModalWorkspaceDrop(e);
                });
            }

            // 10. Pointer reposition dragging inside modal
            if (this.modalFrame) {
                this.modalFrame.addEventListener('pointerdown', this._onPointerDown.bind(this));
                this.modalFrame.addEventListener('pointermove', this._onPointerMove.bind(this));
                this.modalFrame.addEventListener('pointerup', this._onPointerUp.bind(this));
                this.modalFrame.addEventListener('pointercancel', this._onPointerUp.bind(this));
            }

            // 11. Modal buttons: Reset, Close, Apply
            if (this.modalResetBtn) {
                this.modalResetBtn.addEventListener('click', (e) => {
                    e.preventDefault();
                    this.resetFocal();
                });
            }
            if (this.modalCloseBtns) {
                this.modalCloseBtns.forEach(btn => {
                    btn.addEventListener('click', (e) => {
                        e.preventDefault();
                        this.closeModal();
                    });
                });
            }
            if (this.modalApplyBtn) {
                this.modalApplyBtn.addEventListener('click', (e) => {
                    e.preventDefault();
                    this.applyModal();
                });
            }

            // 12. Global click listener for closing action menu on outside click
            if (typeof document !== 'undefined') {
                this._documentClickHandler = (e) => {
                    if (this.isActionMenuOpen() && this.menuWrapper && !this._isEventInside(e, this.menuWrapper)) {
                        this.closeActionMenu();
                    }
                };
                document.addEventListener('click', this._documentClickHandler);

                // 13. Escape key listener for modal and action menu
                this._documentKeyHandler = (e) => {
                    if (e.key === 'Escape') {
                        if (this.isActionMenuOpen()) {
                            this.closeActionMenu();
                            if (this.editBtn && typeof this.editBtn.focus === 'function') {
                                this.editBtn.focus();
                            }
                        } else if (this.isModalOpen()) {
                            this.closeModal();
                        }
                    }
                };
                document.addEventListener('keydown', this._documentKeyHandler);
            }

            // 14. Window beforeunload cleanup
            if (typeof window !== 'undefined') {
                this._windowBeforeUnloadHandler = () => {
                    this.destroy();
                };
                window.addEventListener('beforeunload', this._windowBeforeUnloadHandler);
            }
        }

        _isEventInside(e, element) {
            if (!element) return false;
            let target = e.target;
            while (target) {
                if (target === element) return true;
                target = target.parentNode;
            }
            return false;
        }

        // =========================================================
        // ACTION MENU CONTROLS
        // =========================================================

        isActionMenuOpen() {
            return !!(this.actionMenu && !this.actionMenu.hidden);
        }

        openActionMenu() {
            if (!this.actionMenu) return;
            this.actionMenu.hidden = false;
            if (this.editBtn) {
                this.editBtn.setAttribute('aria-expanded', 'true');
            }
            this.state = STATE.ACTION_MENU_OPEN;
        }

        closeActionMenu() {
            if (!this.actionMenu) return;
            this.actionMenu.hidden = true;
            if (this.editBtn) {
                this.editBtn.setAttribute('aria-expanded', 'false');
            }
            if (this.state === STATE.ACTION_MENU_OPEN) {
                this.state = this._calculateRestingState();
            }
        }

        toggleActionMenu() {
            if (this.isActionMenuOpen()) {
                this.closeActionMenu();
            } else {
                this.openActionMenu();
            }
        }

        _calculateRestingState() {
            if (this.isRemovalPending) {
                return STATE.PENDING_REMOVAL;
            }
            if (this.appliedFile) {
                return STATE.APPLIED_REPLACEMENT;
            }
            if (this.hasAppliedImage()) {
                return STATE.EXISTING_COVER;
            }
            return STATE.NO_COVER;
        }

        _updateRemoveButtonCopy() {
            if (!this.removeCoverBtn) return;
            const textSpan = this.removeCoverBtn.querySelector('span');
            if (!textSpan) return;

            if (!this.hasPersistedCover) {
                textSpan.textContent = 'Bỏ ảnh đã chọn';
            } else {
                textSpan.textContent = 'Xóa ảnh bìa';
            }
        }

        // =========================================================
        // FILE PICKER INVOCATION & CANCEL
        // =========================================================

        triggerFilePicker() {
            this.clearRestingError();
            this.pickerReturnState = this.state;
            this.state = STATE.PICKER_PENDING;

            // Trigger temporary source picker ONLY — NEVER mutate or clear the submitted file input!
            if (this.pickerInput) {
                this.pickerInput.value = '';
                this.pickerInput.click();
            } else if (this.fileInput) {
                // Fallback only if picker input element does not exist
                this.fileInput.click();
            }
        }

        _handlePickerCancel() {
            if (this.state !== STATE.PICKER_PENDING) return;
            this.state = this.pickerReturnState || this._calculateRestingState();
            this.pickerReturnState = null;
            if (this.pickerInput) {
                this.pickerInput.value = '';
            }
        }

        // =========================================================
        // FILE HANDLING & VALIDATION WITH ASYNC DECODE
        // =========================================================

        _validateFile(file) {
            if (!file) {
                return { valid: false, error: 'Vui lòng chọn tệp ảnh.' };
            }
            const mime = (file.type || '').toLowerCase();
            if (!ALLOWED_MIME_TYPES.has(mime)) {
                return { valid: false, error: 'Định dạng ảnh bìa không được hỗ trợ. Chỉ chấp nhận JPG, PNG hoặc WebP.' };
            }
            if (file.size > MAX_FILE_SIZE_BYTES) {
                return { valid: false, error: 'Kích thước ảnh bìa không được vượt quá 5MB.' };
            }
            return { valid: true };
        }

        async handleSelectedFile(file) {
            const validation = this._validateFile(file);
            if (!validation.valid) {
                this.showRestingError(validation.error);
                if (this.pickerInput) this.pickerInput.value = '';
                if (this.state === STATE.PICKER_PENDING) {
                    this.state = this.pickerReturnState || this._calculateRestingState();
                    this.pickerReturnState = null;
                }
                return;
            }

            this.clearRestingError();

            let tempObjectUrl = null;
            try {
                tempObjectUrl = URL.createObjectURL(file);
            } catch (err) {
                this.showRestingError('Không thể đọc tệp ảnh đã chọn.');
                if (this.pickerInput) this.pickerInput.value = '';
                if (this.state === STATE.PICKER_PENDING) {
                    this.state = this.pickerReturnState || this._calculateRestingState();
                    this.pickerReturnState = null;
                }
                return;
            }

            // ASYNC IMAGE DECODE BEFORE MODAL OPENS
            try {
                await decodeImage(tempObjectUrl);
            } catch (decodeErr) {
                // Decode failed: modal remains closed, rollback state, revoke URL
                try { URL.revokeObjectURL(tempObjectUrl); } catch (_) {}
                if (this.pickerInput) this.pickerInput.value = '';
                if (this.state === STATE.PICKER_PENDING) {
                    this.state = this.pickerReturnState || this._calculateRestingState();
                    this.pickerReturnState = null;
                }
                this.showRestingError('Không thể đọc ảnh đã chọn. Vui lòng chọn tệp ảnh khác.');
                return;
            }

            // DECODE SUCCEEDED: Establish modal working copy
            if (this.modalObjectUrl && this.modalObjectUrl !== this.appliedObjectUrl) {
                try { URL.revokeObjectURL(this.modalObjectUrl); } catch (_) {}
            }

            this.modalPendingFile = file;
            this.modalObjectUrl = tempObjectUrl;
            this.modalImageSrc = this.modalObjectUrl;
            this.modalFocalX = DEFAULT_FOCAL;
            this.modalFocalY = DEFAULT_FOCAL;
            this.modalMode = 'new';
            this.pickerReturnState = null;

            // Note: If picker was opened while pending removal, do NOT cancel removal on decode!
            // Leave this.isRemovalPending untouched so cancel returns cleanly to pending removal.

            this.openModalWithImage('new', this.modalImageSrc, DEFAULT_FOCAL, DEFAULT_FOCAL);
            this.state = STATE.MODAL_NEW_FILE;
        }

        _onRestingCardDrop(e) {
            const dt = e.dataTransfer;
            const hasFiles = dt && dt.files && dt.files.length > 0;

            if (!hasFiles) {
                this.showRestingError('Vui lòng thả tệp ảnh từ thiết bị.');
                return;
            }

            const file = dt.files[0];
            const validation = this._validateFile(file);
            if (!validation.valid) {
                this.showRestingError(validation.error);
                return;
            }

            this.pickerReturnState = this.state;
            this.handleSelectedFile(file);
        }

        async _onModalWorkspaceDrop(e) {
            const dt = e.dataTransfer;
            const hasFiles = dt && dt.files && dt.files.length > 0;

            if (!hasFiles) {
                this.showModalError('Vui lòng thả tệp ảnh từ thiết bị.');
                return;
            }

            const file = dt.files[0];
            const validation = this._validateFile(file);
            if (!validation.valid) {
                this.showModalError(validation.error);
                return;
            }

            this.clearModalError();

            let tempObjectUrl = null;
            try {
                tempObjectUrl = URL.createObjectURL(file);
            } catch (err) {
                this.showModalError('Không thể đọc tệp ảnh đã chọn.');
                return;
            }

            try {
                await decodeImage(tempObjectUrl);
            } catch (decodeErr) {
                try { URL.revokeObjectURL(tempObjectUrl); } catch (_) {}
                this.showModalError('Không thể đọc ảnh đã chọn. Vui lòng chọn tệp ảnh khác.');
                return;
            }

            if (this.modalObjectUrl && this.modalObjectUrl !== this.appliedObjectUrl) {
                try { URL.revokeObjectURL(this.modalObjectUrl); } catch (_) {}
            }

            this.modalPendingFile = file;
            this.modalObjectUrl = tempObjectUrl;
            this.modalImageSrc = this.modalObjectUrl;
            this.modalFocalX = DEFAULT_FOCAL;
            this.modalFocalY = DEFAULT_FOCAL;

            if (this.modalImg) {
                this.modalImg.src = this.modalImageSrc;
            }
            this._updateModalFocalDisplay(DEFAULT_FOCAL, DEFAULT_FOCAL);
            this.state = STATE.MODAL_NEW_FILE;
        }

        // =========================================================
        // MODAL LIFECYCLE
        // =========================================================

        openModal(mode = 'reposition') {
            this.modalMode = mode;
            this.clearModalError();

            if (mode === 'reposition') {
                this.modalImageSrc = this.appliedObjectUrl || this.appliedServerUrl;
                this.modalFocalX = this.appliedFocalX;
                this.modalFocalY = this.appliedFocalY;
                this.openModalWithImage('reposition', this.modalImageSrc, this.modalFocalX, this.modalFocalY);
                this.state = STATE.MODAL_EDIT_EXISTING;
            }
        }

        openModalWithImage(mode, src, focalX, focalY) {
            this.modalMode = mode;
            this.clearModalError();

            if (this.modalTitle) {
                this.modalTitle.textContent = (mode === 'reposition')
                    ? 'Điều chỉnh ảnh bìa'
                    : 'Chọn vùng ảnh bìa';
            }

            if (this.modalImg) {
                this.modalImg.src = src || '';
            }
            if (this.modalFrame) {
                this.modalFrame.hidden = false;
            }

            this._updateModalFocalDisplay(focalX, focalY);

            if (this.modal) {
                this.modal.hidden = false;
                this.modal.classList.add('is-open', 'show');
                if (typeof this.modal.focus === 'function') {
                    this.modal.focus();
                }
            }
        }

        closeModal() {
            // Discard modal-local working changes
            if (this.modalObjectUrl && this.modalObjectUrl !== this.appliedObjectUrl) {
                try { URL.revokeObjectURL(this.modalObjectUrl); } catch (_) {}
                this.modalObjectUrl = null;
            }
            this.modalPendingFile = null;

            if (this.pickerInput) {
                this.pickerInput.value = '';
            }

            if (this.modal) {
                this.modal.hidden = true;
                this.modal.classList.remove('is-open', 'show');
            }
            this.clearModalError();

            // Transactional rollback: if entered from PENDING_REMOVAL and cancelled, return to PENDING_REMOVAL
            if (this.isRemovalPending) {
                this.state = STATE.PENDING_REMOVAL;
                this._renderPendingRemovalUI(true);
            } else if (this.appliedFile) {
                this.state = STATE.APPLIED_REPLACEMENT;
            } else if (this.hasAppliedImage()) {
                this.state = STATE.EXISTING_COVER;
            } else {
                this.state = STATE.NO_COVER;
            }
        }

        applyModal() {
            // Commit modal-local coordinates
            this.appliedFocalX = this.modalFocalX;
            this.appliedFocalY = this.modalFocalY;
            if (this.inputX) this.inputX.value = String(this.appliedFocalX);
            if (this.inputY) this.inputY.value = String(this.appliedFocalY);

            // Commit new file if chosen
            if (this.modalPendingFile) {
                // COMMIT INTO SUBMISSION INPUT ONLY NOW!
                if (this.fileInput) {
                    if (typeof DataTransfer === 'undefined') {
                        this.showModalError('Trình duyệt không hỗ trợ gán tệp ảnh tự động. Vui lòng chọn tệp lại.');
                        return;
                    }
                    try {
                        const dt = new DataTransfer();
                        dt.items.add(this.modalPendingFile);
                        this.fileInput.files = dt.files;
                    } catch (err) {
                        this.showModalError('Không thể nạp tệp ảnh vào biểu mẫu. Vui lòng thử lại.');
                        return;
                    }
                }

                this.appliedFile = this.modalPendingFile;

                if (this.appliedObjectUrl && this.appliedObjectUrl !== this.modalObjectUrl) {
                    try { URL.revokeObjectURL(this.appliedObjectUrl); } catch (_) {}
                }

                this.appliedObjectUrl = this.modalObjectUrl;
                this.modalObjectUrl = null;

                if (this.compactImg) {
                    this.compactImg.src = this.appliedObjectUrl;
                    this.compactImg.hidden = false;
                    this.compactImg.style.objectPosition = `${this.appliedFocalX}% ${this.appliedFocalY}%`;
                }
            } else if (this.hasAppliedImage()) {
                // Repositioned existing image
                if (this.compactImg) {
                    this.compactImg.hidden = false;
                    this.compactImg.style.objectPosition = `${this.appliedFocalX}% ${this.appliedFocalY}%`;
                }
            }

            if (this.compactPlaceholder) {
                this.compactPlaceholder.style.display = 'none';
                this.compactPlaceholder.hidden = true;
            }

            // Commit removal cancellation on Apply
            this.isRemovalPending = false;
            if (this.removeCheckbox) {
                this.removeCheckbox.checked = false;
            }
            this._renderPendingRemovalUI(false);

            if (this.noImageActions) this.noImageActions.style.display = 'none';
            if (this.menuWrapper) this.menuWrapper.style.display = '';

            this._updateRemoveButtonCopy();

            if (this.modal) {
                this.modal.hidden = true;
                this.modal.classList.remove('is-open', 'show');
            }
            this.clearModalError();
            this.clearRestingError();

            if (this.pickerInput) {
                this.pickerInput.value = '';
            }

            this.state = this.appliedFile ? STATE.APPLIED_REPLACEMENT : STATE.EXISTING_COVER;
        }

        // =========================================================
        // REMOVAL & LOCAL DISCARD SEMANTICS
        // =========================================================

        removeCover() {
            if (!this.hasPersistedCover) {
                // LOCAL DISCARD SEMANTICS (Create or Edit with no persisted server cover)
                this.discardLocalCover();
                return;
            }

            // PERSISTED SERVER COVER REMOVAL SEMANTICS
            this.isRemovalPending = true;
            if (this.removeCheckbox) {
                this.removeCheckbox.checked = true;
            }

            // Clear submitted file input and pending local replacement
            if (this.fileInput) {
                this.fileInput.value = '';
            }
            if (this.pickerInput) {
                this.pickerInput.value = '';
            }
            this.appliedFile = null;

            if (this.appliedObjectUrl) {
                try { URL.revokeObjectURL(this.appliedObjectUrl); } catch (_) {}
                this.appliedObjectUrl = null;
            }

            // Reset coordinates to 50/50
            this.appliedFocalX = DEFAULT_FOCAL;
            this.appliedFocalY = DEFAULT_FOCAL;
            if (this.inputX) this.inputX.value = String(DEFAULT_FOCAL);
            if (this.inputY) this.inputY.value = String(DEFAULT_FOCAL);

            // Restore server URL as visual reference behind dim
            if (this.appliedServerUrl && this.compactImg) {
                this.compactImg.src = this.appliedServerUrl;
            }

            this._renderPendingRemovalUI(true);
            this.state = STATE.PENDING_REMOVAL;
        }

        discardLocalCover() {
            // Clear actual submission input
            if (this.fileInput) {
                this.fileInput.value = '';
            }
            if (this.pickerInput) {
                this.pickerInput.value = '';
            }
            this.appliedFile = null;

            if (this.appliedObjectUrl) {
                try { URL.revokeObjectURL(this.appliedObjectUrl); } catch (_) {}
                this.appliedObjectUrl = null;
            }

            this.appliedFocalX = DEFAULT_FOCAL;
            this.appliedFocalY = DEFAULT_FOCAL;
            if (this.inputX) this.inputX.value = String(DEFAULT_FOCAL);
            if (this.inputY) this.inputY.value = String(DEFAULT_FOCAL);

            this.isRemovalPending = false;
            if (this.removeCheckbox) {
                this.removeCheckbox.checked = false;
            }

            if (this.compactImg) {
                this.compactImg.src = '';
                this.compactImg.hidden = true;
            }
            if (this.compactPlaceholder) {
                this.compactPlaceholder.style.display = '';
                this.compactPlaceholder.hidden = false;
            }
            if (this.menuWrapper) {
                this.menuWrapper.style.display = 'none';
            }
            if (this.noImageActions) {
                this.noImageActions.style.display = '';
            }
            if (this.pendingRemoval) {
                this.pendingRemoval.hidden = true;
            }

            this.clearRestingError();
            this.clearModalError();
            this.state = STATE.NO_COVER;
        }

        undoRemove() {
            if (!this.hasPersistedCover) return;
            this.isRemovalPending = false;
            if (this.removeCheckbox) {
                this.removeCheckbox.checked = false;
            }
            this._renderPendingRemovalUI(false);
            this.state = STATE.EXISTING_COVER;
        }

        _renderPendingRemovalUI(isRemoving) {
            if (this.pendingRemoval) {
                this.pendingRemoval.hidden = !isRemoving;
            }
            if (this.compactFrame) {
                if (isRemoving) {
                    this.compactFrame.classList.add('is-pending-removal');
                } else {
                    this.compactFrame.classList.remove('is-pending-removal');
                }
            }
            if (this.menuWrapper) {
                this.menuWrapper.style.display = isRemoving ? 'none' : (this.hasAppliedImage() ? '' : 'none');
            }
        }

        // =========================================================
        // DELTA-BASED POINTER DRAG INTERACTION
        // =========================================================

        _onPointerDown(e) {
            if (!this.modalHasImage()) return;
            if (e.button !== undefined && e.button !== 0) return;

            e.preventDefault();
            this.isDragging = true;
            this.pointerStartX = e.clientX;
            this.pointerStartY = e.clientY;
            this.startFocalX = this.modalFocalX;
            this.startFocalY = this.modalFocalY;

            if (this.modalFrame) {
                this.modalFrame.classList.add('is-repositioning');
                if (typeof this.modalFrame.setPointerCapture === 'function' && e.pointerId !== undefined) {
                    try { this.modalFrame.setPointerCapture(e.pointerId); } catch (_) {}
                }
            }
        }

        _onPointerMove(e) {
            if (!this.isDragging) return;
            e.preventDefault();

            const rect = this.modalFrame ? this.modalFrame.getBoundingClientRect() : null;
            if (!rect || rect.width === 0 || rect.height === 0) return;

            const deltaX = e.clientX - this.pointerStartX;
            const deltaY = e.clientY - this.pointerStartY;

            const deltaFocalX = (deltaX / rect.width) * 100;
            const deltaFocalY = (deltaY / rect.height) * 100;

            const newX = clamp(Math.round(this.startFocalX - deltaFocalX), 0, 100);
            const newY = clamp(Math.round(this.startFocalY - deltaFocalY), 0, 100);

            this.modalFocalX = newX;
            this.modalFocalY = newY;

            this._updateModalFocalDisplay(newX, newY);
        }

        _onPointerUp(e) {
            if (!this.isDragging) return;
            this.isDragging = false;

            if (this.modalFrame) {
                this.modalFrame.classList.remove('is-repositioning');
                if (typeof this.modalFrame.releasePointerCapture === 'function' && e && e.pointerId !== undefined) {
                    try { this.modalFrame.releasePointerCapture(e.pointerId); } catch (_) {}
                }
            }
        }

        resetFocal() {
            this.modalFocalX = DEFAULT_FOCAL;
            this.modalFocalY = DEFAULT_FOCAL;
            this._updateModalFocalDisplay(DEFAULT_FOCAL, DEFAULT_FOCAL);
        }

        _updateModalFocalDisplay(x, y) {
            if (this.modalImg) {
                this.modalImg.style.objectPosition = `${x}% ${y}%`;
            }
            if (this.modalBadge) {
                this.modalBadge.textContent = `Tâm: ${x}% · ${y}%`;
            }
            if (this.modalResetBtn) {
                this.modalResetBtn.disabled = (x === DEFAULT_FOCAL && y === DEFAULT_FOCAL);
            }
        }

        // =========================================================
        // HELPER QUERIES & ERROR DISPLAY
        // =========================================================

        hasAppliedImage() {
            return !!(this.appliedObjectUrl || (this.appliedServerUrl && !this.isRemovalPending));
        }

        modalHasImage() {
            return !!(this.modalImageSrc && this.modalFrame && !this.modalFrame.hidden);
        }

        isModalOpen() {
            return !!(this.modal && !this.modal.hidden);
        }

        getFocal() {
            return {
                x: this.appliedFocalX,
                y: this.appliedFocalY
            };
        }

        getPosition() {
            return this.getFocal();
        }

        showRestingError(message) {
            if (this.restingError) {
                this.restingError.textContent = message;
                this.restingError.hidden = false;
            } else {
                this.showModalError(message);
            }
        }

        clearRestingError() {
            if (this.restingError) {
                this.restingError.textContent = '';
                this.restingError.hidden = true;
            }
        }

        showModalError(message) {
            if (this.modalError) {
                this.modalError.textContent = message;
                this.modalError.hidden = false;
            } else if (typeof alert === 'function') {
                alert(message);
            }
        }

        clearModalError() {
            if (this.modalError) {
                this.modalError.textContent = '';
                this.modalError.hidden = true;
            }
        }

        showError(message) {
            if (this.isModalOpen()) {
                this.showModalError(message);
            } else {
                this.showRestingError(message);
            }
        }

        clearError() {
            this.clearRestingError();
            this.clearModalError();
        }

        destroy() {
            if (this._documentClickHandler && typeof document !== 'undefined') {
                document.removeEventListener('click', this._documentClickHandler);
            }
            if (this._documentKeyHandler && typeof document !== 'undefined') {
                document.removeEventListener('keydown', this._documentKeyHandler);
            }
            if (this._windowBeforeUnloadHandler && typeof window !== 'undefined') {
                window.removeEventListener('beforeunload', this._windowBeforeUnloadHandler);
            }
            if (this.modalObjectUrl && this.modalObjectUrl !== this.appliedObjectUrl) {
                try { URL.revokeObjectURL(this.modalObjectUrl); } catch (_) {}
                this.modalObjectUrl = null;
            }
            if (this.appliedObjectUrl) {
                try { URL.revokeObjectURL(this.appliedObjectUrl); } catch (_) {}
                this.appliedObjectUrl = null;
            }
        }
    }

    WikiCoverEditor.STATE = STATE;
    WikiCoverEditor.decodeImage = decodeImage;

    return WikiCoverEditor;
}));
