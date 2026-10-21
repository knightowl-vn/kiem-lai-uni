/**
 * KiemLai Universe — Screen Video Recovery & Replacement UX (MS-08E3-S3)
 *
 * Responsibilities:
 * - Discovers completed, recoverable video upload sessions for the current ScreenEntry and authorized actor.
 * - Source of truth: Backend query is strictly authoritative; sessionStorage is only a local correlation hint.
 * - Maps actual backend RecoveryClassification and session intent to safe, unambiguous Admin actions.
 * - Prevents unsafe actions: byte resume is NOT simulated; B2 direct operations are never called.
 * - Generation fencing: protects against stale async responses from out-of-order network calls.
 * - Concurrency & Duplicate prevention: disables actions during in-flight mutations.
 * - Guarded attach/replace and guarded discard with explicit administrator confirmation.
 * - Sanitized DOM rendering using textContent to prevent XSS.
 */
(function (root, factory) {
    'use strict';
    if (typeof module === 'object' && typeof module.exports === 'object') {
        module.exports = factory();
    } else {
        const exports = factory();
        root.ScreenVideoRecovery = exports;
        if (!root.KiemLai) {
            root.KiemLai = {};
        }
        root.KiemLai.ScreenVideoRecovery = exports;
    }
})(typeof globalThis !== 'undefined' ? globalThis : typeof window !== 'undefined' ? window : this, function () {
    'use strict';

    /**
     * Recovery classifications aligned with com.universe.screen.application.dto.RecoveryClassification.
     */
    const RecoveryClassification = Object.freeze({
        UNATTACHED: 'UNATTACHED',
        ATTACHED_TO_TARGET: 'ATTACHED_TO_TARGET',
        REFERENCED_ELSEWHERE: 'REFERENCED_ELSEWHERE',
        TARGET_MISSING: 'TARGET_MISSING',
        ALREADY_DISCARDED: 'ALREADY_DISCARDED',
        INCONSISTENT: 'INCONSISTENT'
    });

    /**
     * Formats bytes to standard binary units (KiB, MiB, GiB).
     */
    function formatBytes(bytes) {
        if (typeof bytes !== 'number' || isNaN(bytes) || bytes < 0) return '0 B';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1048576) return (bytes / 1024).toFixed(2) + ' KiB';
        if (bytes < 1073741824) return (bytes / 1048576).toFixed(2) + ' MiB';
        return (bytes / 1073741824).toFixed(2) + ' GiB';
    }

    /**
     * Formats ISO timestamp to Vietnamese locale date string.
     */
    function formatDate(isoString) {
        if (!isoString) return 'Chưa rõ';
        try {
            const d = new Date(isoString);
            if (isNaN(d.getTime())) return isoString;
            return d.toLocaleString('vi-VN', {
                year: 'numeric',
                month: '2-digit',
                day: '2-digit',
                hour: '2-digit',
                minute: '2-digit',
                second: '2-digit',
                hour12: false
            });
        } catch (e) {
            return isoString;
        }
    }

    /**
     * Determines allowed UI actions and user-facing status from backend classification, intent,
     * target presence, and current attached video asset ID.
     *
     * @param {Object} session - RecoverableVideoUploadView DTO
     * @param {string|null} currentAttachedAssetId - Currently attached MediaAsset ID on the ScreenEntry
     * @returns {Object} { canAttach, canReplace, canDiscard, statusText, badgeClass, reason }
     */
    function determineAllowedActions(session, currentAttachedAssetId) {
        if (!session || typeof session !== 'object') {
            return {
                canAttach: false,
                canReplace: false,
                canDiscard: false,
                statusText: 'Dữ liệu phiên không hợp lệ',
                badgeClass: 'badge-danger',
                reason: 'Không nhận được dữ liệu phiên từ máy chủ.'
            };
        }

        const classification = session.classification;
        const intent = session.intent;
        const canRetry = Boolean(session.canRetry);
        const canDiscard = Boolean(session.canDiscard);
        const hasAttachedVideo = Boolean(currentAttachedAssetId);

        switch (classification) {
            case RecoveryClassification.UNATTACHED: {
                if (intent === 'REPLACEMENT') {
                    if (canRetry) {
                        // Check if current attached video still matches replacement expected asset ID
                        if (hasAttachedVideo && session.replacementExpectedAssetId === currentAttachedAssetId) {
                            return {
                                canAttach: false,
                                canReplace: true,
                                canDiscard: canDiscard,
                                statusText: 'Đã tải lên, chờ thay thế video',
                                badgeClass: 'badge-info',
                                reason: null
                            };
                        } else {
                            return {
                                canAttach: false,
                                canReplace: false,
                                canDiscard: canDiscard,
                                statusText: 'Xung đột thay thế video',
                                badgeClass: 'badge-warning',
                                reason: 'Video hiện tại của tập phim đã thay đổi so với video dự kiến thay thế của phiên này.'
                            };
                        }
                    } else {
                        return {
                            canAttach: false,
                            canReplace: false,
                            canDiscard: canDiscard,
                            statusText: 'Không thể thay thế',
                            badgeClass: 'badge-warning',
                            reason: 'Tập phim hiện tại không ở trạng thái phù hợp để thay thế (có thể chưa có video hoặc đã bị thay đổi).'
                        };
                    }
                } else {
                    // INITIAL intent
                    if (canRetry) {
                        if (hasAttachedVideo) {
                            // Target now has video attached; initial session cannot replace it
                            return {
                                canAttach: false,
                                canReplace: false,
                                canDiscard: canDiscard,
                                statusText: 'Tập phim đã có video',
                                badgeClass: 'badge-warning',
                                reason: 'Tập phim đã có video gắn sẵn. Phiên tải lên ban đầu không thể dùng để thay thế.'
                            };
                        } else {
                            return {
                                canAttach: true,
                                canReplace: false,
                                canDiscard: canDiscard,
                                statusText: 'Đã tải lên, chờ gắn video',
                                badgeClass: 'badge-info',
                                reason: null
                            };
                        }
                    } else {
                        return {
                            canAttach: false,
                            canReplace: false,
                            canDiscard: canDiscard,
                            statusText: 'Không thể gắn video',
                            badgeClass: 'badge-warning',
                            reason: 'Tập phim không ở trạng thái sẵn sàng để gắn video (có thể đã có video hoặc đã bị xóa).'
                        };
                    }
                }
            }

            case RecoveryClassification.ATTACHED_TO_TARGET:
                return {
                    canAttach: false,
                    canReplace: false,
                    canDiscard: false,
                    statusText: 'Đã gắn vào tập phim này',
                    badgeClass: 'badge-success',
                    reason: null
                };

            case RecoveryClassification.REFERENCED_ELSEWHERE:
                return {
                    canAttach: false,
                    canReplace: false,
                    canDiscard: false,
                    statusText: 'Đang được sử dụng bởi tập phim khác',
                    badgeClass: 'badge-warning',
                    reason: 'Video này đang được tham chiếu bởi một ScreenEntry khác trên hệ thống.'
                };

            case RecoveryClassification.TARGET_MISSING:
                return {
                    canAttach: false,
                    canReplace: false,
                    canDiscard: canDiscard,
                    statusText: 'Tập phim mục tiêu không còn tồn tại',
                    badgeClass: 'badge-danger',
                    reason: 'Tập phim mục tiêu ban đầu của phiên tải lên này đã bị xóa.'
                };

            case RecoveryClassification.ALREADY_DISCARDED:
                return {
                    canAttach: false,
                    canReplace: false,
                    canDiscard: false,
                    statusText: 'Phiên tải lên đã được hủy',
                    badgeClass: 'badge-neutral',
                    reason: null
                };

            case RecoveryClassification.INCONSISTENT:
                return {
                    canAttach: false,
                    canReplace: false,
                    canDiscard: false,
                    statusText: 'Trạng thái không nhất quán / cần kiểm tra thủ công',
                    badgeClass: 'badge-danger',
                    reason: 'Tài nguyên Media hoặc phiên tải lên có trạng thái bất thường (ví dụ: bị lưu trữ hoặc thiếu metadata).'
                };

            default:
                return {
                    canAttach: false,
                    canReplace: false,
                    canDiscard: false,
                    statusText: 'Trạng thái chưa xác định (' + (classification || 'N/A') + ')',
                    badgeClass: 'badge-neutral',
                    reason: 'Máy chủ trả về trạng thái không nằm trong phân loại hợp lệ.'
                };
        }
    }

    /**
     * Clears local storage attempt metadata only if it positively matches the given targetSessionId.
     * If targetSessionId is missing, or the saved attempt has no sessionId or a different sessionId,
     * the saved attempt is strictly preserved.
     *
     * @param {Storage} storage - localStorage/sessionStorage instance
     * @param {string} entryId - ScreenEntry UUID
     * @param {string} targetSessionId - Session UUID being attached or discarded
     * @returns {boolean} true if matching attempt was found and removed, false otherwise
     */
    function clearMatchingLocalAttempt(storage, entryId, targetSessionId) {
        if (!storage || !entryId || !targetSessionId) {
            return false;
        }
        const storageKey = 'screen_video_attempt_' + entryId;
        try {
            const raw = storage.getItem(storageKey);
            if (!raw) return false;
            let attempt;
            try {
                attempt = JSON.parse(raw);
            } catch (parseErr) {
                return false;
            }
            if (!attempt || typeof attempt !== 'object') {
                return false;
            }
            if (!attempt.sessionId) {
                return false;
            }
            if (attempt.sessionId === targetSessionId) {
                storage.removeItem(storageKey);
                return true;
            }
            return false;
        } catch (e) {
            return false;
        }
    }

    /**
     * Checks whether an uploader coordinator currently has an active, unsettled attempt.
     *
     * @param {Object} uploaderCoordinator - Uploader.UploadCoordinator instance
     * @returns {boolean} true if an attempt exists and is not settled; false otherwise
     */
    function isUploaderActive(uploaderCoordinator) {
        if (!uploaderCoordinator || typeof uploaderCoordinator.getCurrentAttempt !== 'function') {
            return false;
        }
        const attempt = uploaderCoordinator.getCurrentAttempt();
        return Boolean(attempt && !attempt.isSettled);
    }

    /**
     * Coordinator managing recovery discovery, generation-fenced async queries,
     * safe attach/replace, and guarded discard operations.
     */
    function RecoveryCoordinator(options) {
        options = options || {};
        this.titleId = options.titleId || null;
        this.entryId = options.entryId || null;
        this.currentAttachedAssetId = options.currentAttachedAssetId || null;
        this.fetchFn = options.fetchFn || (typeof fetch !== 'undefined' ? fetch.bind(globalThis) : null);
        this.getCsrfHeaders = options.getCsrfHeaders || function (h) { return h || {}; };
        this.storage = options.storage || (typeof sessionStorage !== 'undefined' ? sessionStorage : null);
        this.confirmFn = options.confirmFn || (typeof confirm !== 'undefined' ? confirm.bind(globalThis) : function () { return true; });
        this.uploaderCoordinator = options.uploaderCoordinator || null;
        this.canAttach = options.canAttach || null;

        this.queryGeneration = 0;
        this.isMutating = false;
        this.lastLoadedSessions = [];
    }

    /**
     * Queries the backend for recoverable completed video uploads for the current entry.
     * Enforces generation fencing: older in-flight query responses are ignored if a newer query was issued.
     * Checks generation after EVERY asynchronous boundary.
     *
     * @param {number} [page=0]
     * @param {number} [size=20]
     * @returns {Promise<Object>}
     */
    RecoveryCoordinator.prototype.fetchRecoverableSessions = async function (page, size) {
        page = (typeof page === 'number' && page >= 0) ? page : 0;
        size = (typeof size === 'number' && size > 0) ? size : 20;

        const thisGen = ++this.queryGeneration;
        const entryId = this.entryId;
        if (!entryId) {
            throw new Error('entryId không được để trống khi truy vấn phiên khôi phục.');
        }

        const url = '/admin/screen/video-uploads/recoverable?entryId=' + encodeURIComponent(entryId)
                + '&page=' + encodeURIComponent(page)
                + '&size=' + encodeURIComponent(size);

        let response;
        try {
            response = await this.fetchFn(url, {
                method: 'GET',
                headers: {
                    'Accept': 'application/json'
                }
            });
        } catch (netErr) {
            if (thisGen !== this.queryGeneration) {
                return { isStale: true };
            }
            throw new Error('Lỗi kết nối mạng khi lấy danh sách phiên khôi phục: ' + netErr.message);
        }

        if (thisGen !== this.queryGeneration) {
            return { isStale: true };
        }

        if (!response.ok) {
            let errText = 'Lỗi máy chủ khi tải danh sách phiên (' + response.status + ').';
            if (response.status === 401 || response.status === 403) {
                errText = 'Yêu cầu xác thực tài khoản hoặc bạn không có quyền truy cập danh sách phiên khôi phục.';
            } else {
                try {
                    const errJson = await response.json();
                    if (errJson && errJson.message) {
                        errText = errJson.message;
                    }
                } catch (e) { /* ignore parse error */ }
            }

            if (thisGen !== this.queryGeneration) {
                return { isStale: true };
            }
            throw new Error(errText);
        }

        let data;
        try {
            data = await response.json();
        } catch (parseErr) {
            if (thisGen !== this.queryGeneration) {
                return { isStale: true };
            }
            throw new Error('Dữ liệu trả về từ máy chủ không đúng định dạng JSON.');
        }

        if (thisGen !== this.queryGeneration) {
            return { isStale: true };
        }

        const items = (data && Array.isArray(data.items)) ? data.items : [];
        this.lastLoadedSessions = items;

        // Synchronize and invalidate stale sessionStorage hints
        this.reconcileLocalStorage(items);

        return {
            isStale: false,
            items: items,
            page: data.page || 0,
            size: data.size || 20,
            totalElements: data.totalElements || 0,
            totalPages: data.totalPages || 0,
            first: Boolean(data.first),
            last: Boolean(data.last)
        };
    };

    /**
     * Checks sessionStorage attempt against authoritative server sessions.
     * Clears or invalidates stale hints where the session has already settled or ceased to exist.
     *
     * @param {Array} serverSessions - List of RecoverableVideoUploadView
     */
    RecoveryCoordinator.prototype.reconcileLocalStorage = function (serverSessions) {
        if (!this.storage || !this.entryId) return;
        const storageKey = 'screen_video_attempt_' + this.entryId;
        try {
            const raw = this.storage.getItem(storageKey);
            if (!raw) return;
            let attempt;
            try {
                attempt = JSON.parse(raw);
            } catch (e) {
                return;
            }
            if (!attempt || !attempt.sessionId) return;

            // Check if matching session is returned by server
            const matched = serverSessions.find(function (s) {
                return s.sessionId === attempt.sessionId;
            });

            if (matched) {
                // If session is already attached or already discarded, clear local storage hint
                if (matched.classification === RecoveryClassification.ATTACHED_TO_TARGET
                        || matched.classification === RecoveryClassification.ALREADY_DISCARDED
                        || matched.classification === RecoveryClassification.REFERENCED_ELSEWHERE) {
                    clearMatchingLocalAttempt(this.storage, this.entryId, matched.sessionId);
                }
            }
        } catch (e) {
            /* ignore storage errors */
        }
    };

    /**
     * Clears local storage attempt metadata only if it positively matches the given targetSessionId.
     *
     * @param {string} targetSessionId - Session UUID being cleared
     * @returns {boolean}
     */
    RecoveryCoordinator.prototype.clearLocalAttempt = function (targetSessionId) {
        return clearMatchingLocalAttempt(this.storage, this.entryId, targetSessionId);
    };

    /**
     * Attaches or replaces a completed unattached video upload session onto the current ScreenEntry.
     *
     * @param {Object} session - RecoverableVideoUploadView item
     * @returns {Promise<Object>} CompleteVideoUploadResponse
     */
    RecoveryCoordinator.prototype.attachSession = async function (session) {
        if (!session || !session.sessionId || !session.videoMediaAssetId) {
            throw new Error('Thông tin phiên không hợp lệ: thiếu sessionId hoặc videoMediaAssetId.');
        }

        if (this.isMutating) {
            throw new Error('Một thao tác khác đang được thực hiện. Vui lòng chờ phản hồi.');
        }

        // Mutual exclusion: Refuse recovery attachment if uploader has an active, unsettled attempt
        if (isUploaderActive(this.uploaderCoordinator)) {
            throw new Error('Không thể gắn hoặc thay thế video từ phiên khôi phục trong khi một tiến trình tải lên video khác đang được thực hiện. Vui lòng chờ tiến trình hoàn tất hoặc hủy tiến trình trước khi thao tác.');
        }

        const isReplacement = (session.intent === 'REPLACEMENT');

        if (typeof this.canAttach === 'function') {
            const check = this.canAttach(session, isReplacement);
            if (check && !check.allowed) {
                throw new Error(check.reason || 'Không thể gắn hoặc thay thế video lúc này.');
            }
        }

        const expectedCurrentVideoMediaAssetId = isReplacement
                ? (session.replacementExpectedAssetId || this.currentAttachedAssetId)
                : null;

        if (isReplacement && !expectedCurrentVideoMediaAssetId) {
            throw new Error('Không thể thay thế video vì thiếu expectedCurrentVideoMediaAssetId.');
        }

        const titleId = this.titleId;
        const entryId = this.entryId;
        if (!titleId || !entryId) {
            throw new Error('Thiếu thông tin titleId hoặc entryId của ScreenEntry.');
        }

        const url = '/admin/screen/titles/' + encodeURIComponent(titleId)
                + '/entries/' + encodeURIComponent(entryId)
                + '/video/attach';

        const payload = {
            sessionId: session.sessionId,
            videoMediaAssetId: session.videoMediaAssetId,
            isReplacement: String(Boolean(isReplacement)),
            expectedCurrentVideoMediaAssetId: expectedCurrentVideoMediaAssetId ? String(expectedCurrentVideoMediaAssetId) : ''
        };

        const headers = {
            'Content-Type': 'application/json',
            'Accept': 'application/json'
        };
        this.getCsrfHeaders(headers);

        this.isMutating = true;
        try {
            const response = await this.fetchFn(url, {
                method: 'POST',
                headers: headers,
                body: JSON.stringify(payload)
            });

            if (!response.ok) {
                let errText = 'Lỗi máy chủ khi gắn video (' + response.status + ').';
                let errorCode = null;
                try {
                    const errJson = await response.json();
                    if (errJson) {
                        errorCode = errJson.errorCode;
                        if (errJson.message) {
                            errText = errJson.message;
                        }
                    }
                } catch (e) { /* ignore */ }

                if (response.status === 409 || errorCode === 'SCREEN_ENTRY_VIDEO_CONFLICT') {
                    throw new Error('Xung đột thay thế video: ' + errText + ' (Vui lòng làm mới trang để kiểm tra trạng thái video mới nhất).');
                }
                throw new Error(errText);
            }

            const data = await response.json();

            // Success: update authoritative local state
            this.currentAttachedAssetId = data.videoMediaAssetId || session.videoMediaAssetId;
            this.clearLocalAttempt(session.sessionId);

            return data;
        } finally {
            this.isMutating = false;
        }
    };

    /**
     * Executes guarded discard of an unattached completed video upload session.
     * Enforces explicit confirmation and prevents duplicate dispatch.
     *
     * @param {Object} session - RecoverableVideoUploadView item
     * @returns {Promise<Object>} DiscardVideoUploadResponse
     */
    RecoveryCoordinator.prototype.discardSession = async function (session) {
        if (!session || !session.sessionId) {
            throw new Error('Thông tin phiên không hợp lệ: thiếu sessionId.');
        }

        if (this.isMutating) {
            throw new Error('Một thao tác khác đang được thực hiện. Vui lòng chờ phản hồi.');
        }

        const fileName = session.originalFilename || 'video';
        const fileSize = formatBytes(session.declaredSizeBytes);
        const confirmMsg = 'Bạn có chắc chắn muốn hủy phiên tải lên này không?\n\n'
                + 'Tên file: ' + fileName + '\n'
                + 'Dung lượng: ' + fileSize + '\n'
                + 'Session ID: ' + session.sessionId + '\n\n'
                + 'LƯU Ý QUAN TRỌNG: Thao tác này chỉ hủy phiên tải lên chưa gắn trên hệ thống Media. '
                + 'Hoàn toàn KHÔNG xóa hoặc ảnh hưởng đến video đang phát sóng của tập phim.';

        if (!this.confirmFn(confirmMsg)) {
            return { cancelled: true };
        }

        const url = '/admin/screen/video-uploads/' + encodeURIComponent(session.sessionId) + '/discard';
        const payload = {
            videoMediaAssetId: session.videoMediaAssetId || null
        };

        const headers = {
            'Content-Type': 'application/json',
            'Accept': 'application/json'
        };
        this.getCsrfHeaders(headers);

        this.isMutating = true;
        try {
            const response = await this.fetchFn(url, {
                method: 'POST',
                headers: headers,
                body: JSON.stringify(payload)
            });

            if (!response.ok) {
                let errText = 'Lỗi máy chủ khi hủy phiên (' + response.status + ').';
                let errorCode = null;
                try {
                    const errJson = await response.json();
                    if (errJson) {
                        errorCode = errJson.errorCode;
                        if (errJson.message) errText = errJson.message;
                    }
                } catch (e) { /* ignore */ }

                if (response.status === 409 || errorCode === 'SCREEN_VIDEO_ALREADY_REFERENCED') {
                    throw new Error('Không thể hủy: Video này đang được tham chiếu bởi một ScreenEntry khác.');
                }
                if (response.status === 403 || errorCode === 'SCREEN_VIDEO_DISCARD_ACCESS_DENIED') {
                    throw new Error('Bạn không có quyền hủy phiên tải lên này.');
                }
                throw new Error(errText);
            }

            const data = await response.json();
            this.clearLocalAttempt(session.sessionId);

            return data;
        } finally {
            this.isMutating = false;
        }
    };

    /**
     * DOM Renderer for the Recoverable Sessions UI.
     * Strictly inserts untrusted text via textContent to prevent XSS.
     */
    function RecoveryViewRenderer(elements, coordinator, callbacks) {
        this.elements = elements || {};
        this.coordinator = coordinator;
        this.callbacks = callbacks || {};
        this.loadGeneration = 0;

        this.init();
    }

    RecoveryViewRenderer.prototype.init = function () {
        const self = this;
        if (this.elements.refreshBtn) {
            this.elements.refreshBtn.addEventListener('click', function () {
                return self.load();
            });
        }
    };

    RecoveryViewRenderer.prototype.showAlert = function (type, message) {
        const box = this.elements.alertBox;
        if (!box) return;
        box.style.display = 'block';
        if (type === 'error') {
            box.style.background = '#fce8e6';
            box.style.color = '#c5221f';
            box.style.border = '1px solid #fad2cf';
        } else if (type === 'success') {
            box.style.background = '#e6f4ea';
            box.style.color = '#137333';
            box.style.border = '1px solid #ceead6';
        } else {
            box.style.background = '#feefe3';
            box.style.color = '#b06000';
            box.style.border = '1px solid #fddbba';
        }
        box.textContent = message;
    };

    RecoveryViewRenderer.prototype.hideAlert = function () {
        const box = this.elements.alertBox;
        if (box) {
            box.style.display = 'none';
            box.textContent = '';
        }
    };

    RecoveryViewRenderer.prototype.setLoading = function (loading) {
        if (this.elements.loadingSpinner) {
            this.elements.loadingSpinner.style.display = loading ? 'block' : 'none';
        }
        if (this.elements.refreshBtn) {
            this.elements.refreshBtn.disabled = loading || this.coordinator.isMutating;
        }
    };

    RecoveryViewRenderer.prototype.load = async function (preserveAlert) {
        const thisGen = ++this.loadGeneration;
        if (!preserveAlert) {
            this.hideAlert();
        }
        this.setLoading(true);

        try {
            const result = await this.coordinator.fetchRecoverableSessions();
            if (thisGen !== this.loadGeneration || (result && result.isStale)) {
                return;
            }

            this.renderList(result.items);
        } catch (err) {
            if (thisGen !== this.loadGeneration) {
                return;
            }
            this.showAlert('error', err.message);
            this.renderList([]);
        } finally {
            if (thisGen === this.loadGeneration) {
                this.setLoading(false);
            }
        }
    };

    RecoveryViewRenderer.prototype.renderList = function (sessions) {
        const container = this.elements.sessionListContainer;
        const emptyMsg = this.elements.emptyMessage;
        if (!container) return;

        // Clear existing children safely
        while (container.firstChild) {
            container.removeChild(container.firstChild);
        }

        if (!sessions || sessions.length === 0) {
            if (emptyMsg) emptyMsg.style.display = 'block';
            return;
        }

        if (emptyMsg) emptyMsg.style.display = 'none';

        const self = this;
        const currentAttached = this.coordinator.currentAttachedAssetId;

        sessions.forEach(function (session) {
            const actions = determineAllowedActions(session, currentAttached);
            const card = self.createSessionCard(session, actions);
            container.appendChild(card);
        });
    };

    RecoveryViewRenderer.prototype.createSessionCard = function (session, actions) {
        const self = this;
        const card = document.createElement('div');
        card.style.cssText = 'border: 1px solid #dadce0; border-radius: 6px; padding: 14px 16px; background: #fff; display: flex; flex-direction: column; gap: 10px;';

        // Header row: filename + badge
        const headerRow = document.createElement('div');
        headerRow.style.cssText = 'display: flex; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; gap: 8px;';

        const titleCol = document.createElement('div');
        const filenameStrong = document.createElement('strong');
        filenameStrong.style.cssText = 'font-size: 14px; color: #202124; word-break: break-all;';
        filenameStrong.textContent = session.originalFilename || 'File video không rõ tên';
        titleCol.appendChild(filenameStrong);

        const badge = document.createElement('span');
        badge.style.cssText = 'padding: 3px 8px; border-radius: 4px; font-size: 12px; font-weight: 600; white-space: nowrap;';
        if (actions.badgeClass === 'badge-success') {
            badge.style.background = '#e6f4ea';
            badge.style.color = '#137333';
        } else if (actions.badgeClass === 'badge-info') {
            badge.style.background = '#e8f0fe';
            badge.style.color = '#1967d2';
        } else if (actions.badgeClass === 'badge-warning') {
            badge.style.background = '#feefe3';
            badge.style.color = '#b06000';
        } else if (actions.badgeClass === 'badge-danger') {
            badge.style.background = '#fce8e6';
            badge.style.color = '#c5221f';
        } else {
            badge.style.background = '#f1f3f4';
            badge.style.color = '#5f6368';
        }
        badge.textContent = actions.statusText;
        headerRow.appendChild(titleCol);
        headerRow.appendChild(badge);
        card.appendChild(headerRow);

        // Metadata grid
        const metaGrid = document.createElement('div');
        metaGrid.style.cssText = 'display: flex; flex-wrap: wrap; gap: 16px; font-size: 13px; color: #5f6368;';

        const sizeSpan = document.createElement('span');
        sizeSpan.textContent = 'Dung lượng: ' + formatBytes(session.declaredSizeBytes);
        metaGrid.appendChild(sizeSpan);

        const timeSpan = document.createElement('span');
        timeSpan.textContent = 'Hoàn tất lúc: ' + formatDate(session.completedAt);
        metaGrid.appendChild(timeSpan);

        if (session.sessionId) {
            const sessionSpan = document.createElement('span');
            sessionSpan.textContent = 'Session: ';
            const codeEl = document.createElement('code');
            codeEl.style.fontSize = '12px';
            codeEl.textContent = session.sessionId.substring(0, 8) + '...';
            codeEl.title = session.sessionId;
            sessionSpan.appendChild(codeEl);
            metaGrid.appendChild(sessionSpan);
        }

        if (session.videoMediaAssetId) {
            const assetSpan = document.createElement('span');
            assetSpan.textContent = 'Asset: ';
            const assetCode = document.createElement('code');
            assetCode.style.fontSize = '12px';
            assetCode.textContent = session.videoMediaAssetId.substring(0, 8) + '...';
            assetCode.title = session.videoMediaAssetId;
            assetSpan.appendChild(assetCode);
            metaGrid.appendChild(assetSpan);
        }

        card.appendChild(metaGrid);

        // Reason/warning note if present
        if (actions.reason) {
            const noteEl = document.createElement('div');
            noteEl.style.cssText = 'font-size: 12px; color: #b06000; background: #fef7e0; border: 1px solid #feefc3; padding: 6px 10px; border-radius: 4px;';
            noteEl.textContent = actions.reason;
            card.appendChild(noteEl);
        }

        // Actions row
        const actionsRow = document.createElement('div');
        actionsRow.style.cssText = 'display: flex; gap: 10px; align-items: center; margin-top: 4px; flex-wrap: wrap;';

        if (actions.canAttach) {
            const attachBtn = document.createElement('button');
            attachBtn.type = 'button';
            attachBtn.className = 'admin-primary-button';
            attachBtn.style.cssText = 'padding: 6px 14px; font-size: 13px; cursor: pointer;';
            attachBtn.textContent = 'Gắn video ✓';
            attachBtn.addEventListener('click', function () {
                return self.handleAttach(session, attachBtn, false);
            });
            actionsRow.appendChild(attachBtn);
        }

        if (actions.canReplace) {
            const replaceBtn = document.createElement('button');
            replaceBtn.type = 'button';
            replaceBtn.className = 'admin-primary-button';
            replaceBtn.style.cssText = 'padding: 6px 14px; font-size: 13px; cursor: pointer; background: #e37400; border-color: #e37400;';
            replaceBtn.textContent = 'Thay thế video ⟳';
            replaceBtn.addEventListener('click', function () {
                return self.handleAttach(session, replaceBtn, true);
            });
            actionsRow.appendChild(replaceBtn);
        }

        if (actions.canDiscard) {
            const discardBtn = document.createElement('button');
            discardBtn.type = 'button';
            discardBtn.className = 'admin-secondary-button';
            discardBtn.style.cssText = 'padding: 6px 14px; font-size: 13px; cursor: pointer; color: #c5221f; border-color: #c5221f;';
            discardBtn.textContent = 'Hủy phiên tải lên ✕';
            discardBtn.addEventListener('click', function () {
                return self.handleDiscard(session, discardBtn);
            });
            actionsRow.appendChild(discardBtn);
        }

        if (actionsRow.childNodes.length > 0) {
            card.appendChild(actionsRow);
        }

        return card;
    };

    RecoveryViewRenderer.prototype.handleAttach = async function (session, buttonEl, isReplacement) {
        this.hideAlert();

        // Mutual exclusion guard: refuse before confirmation or HTTP POST if uploader is active
        const canAttachFn = this.callbacks.canAttach || (this.coordinator && this.coordinator.canAttach);
        if (typeof canAttachFn === 'function') {
            const check = canAttachFn(session, isReplacement);
            if (check && !check.allowed) {
                this.showAlert('warning', check.reason || 'Không thể gắn hoặc thay thế video lúc này.');
                if (buttonEl) buttonEl.disabled = false;
                return;
            }
        } else if (this.coordinator && isUploaderActive(this.coordinator.uploaderCoordinator)) {
            this.showAlert('warning', 'Không thể gắn hoặc thay thế video từ phiên khôi phục trong khi một tiến trình tải lên video khác đang được thực hiện. Vui lòng chờ tiến trình hoàn tất hoặc hủy tiến trình trước khi thao tác.');
            if (buttonEl) buttonEl.disabled = false;
            return;
        }

        if (buttonEl) buttonEl.disabled = true;

        if (isReplacement) {
            const confirmMsg = 'Bạn có chắc chắn muốn thay thế video hiện tại bằng file "'
                    + (session.originalFilename || 'video') + '" không?\n'
                    + 'Video cũ sẽ được giữ nguyên an toàn trên hệ thống Media.';
            if (!this.coordinator.confirmFn(confirmMsg)) {
                if (buttonEl) buttonEl.disabled = false;
                return;
            }
        }

        try {
            const result = await this.coordinator.attachSession(session);
            const successMsg = (result && result.status === 'REPLACED')
                    ? 'Video đã được thay thế thành công vào tập phim!'
                    : 'Video đã được gắn thành công vào tập phim!';
            this.showAlert('success', successMsg);

            if (this.callbacks.onAttached) {
                this.callbacks.onAttached(result, session);
            }

            // Refresh recovery list
            await this.load(true);
        } catch (err) {
            this.showAlert('error', err.message);
            if (buttonEl) buttonEl.disabled = false;
        }
    };

    RecoveryViewRenderer.prototype.handleDiscard = async function (session, buttonEl) {
        this.hideAlert();
        if (buttonEl) buttonEl.disabled = true;

        try {
            const result = await this.coordinator.discardSession(session);
            if (result && result.cancelled) {
                if (buttonEl) buttonEl.disabled = false;
                return;
            }

            this.showAlert('success', 'Phiên tải lên đã được hủy an toàn trên hệ thống Media.');

            if (this.callbacks.onDiscarded) {
                this.callbacks.onDiscarded(result, session);
            }

            // Refresh recovery list
            await this.load(true);
        } catch (err) {
            this.showAlert('error', err.message);
            if (buttonEl) buttonEl.disabled = false;
        }
    };

    return {
        RecoveryClassification: RecoveryClassification,
        formatBytes: formatBytes,
        formatDate: formatDate,
        determineAllowedActions: determineAllowedActions,
        clearMatchingLocalAttempt: clearMatchingLocalAttempt,
        isUploaderActive: isUploaderActive,
        RecoveryCoordinator: RecoveryCoordinator,
        RecoveryViewRenderer: RecoveryViewRenderer
    };
});
