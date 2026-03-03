;(function (global) {
    const DEFAULT_REFRESH_MS = Number(global.__AGENT_REFRESH_MS__) || 12000;
    const API_BASE = global.__AGENT_API_BASE__
        || (global.location && global.location.port === '8082'
            ? global.location.origin
            : 'http://localhost:8082');

    const INCLUDE_KEYS = ['context', 'decisions', 'decisionLogs', 'leaderboard', 'profile', 'weeklyPlan'];
    const listeners = new Set();

    const state = {
        symbol: 'BTCUSDT',
        interval: '1m',
        strategyVersion: 'ai-momentum-v1.2.3',
        subjectType: 'HUMAN',
        subjectId: 'user_1001',
        context: null,
        decisions: [],
        decisionLogs: [],
        leaderboard: [],
        profile: null,
        weeklyPlan: null,
        health: 'idle',
        syncing: false,
        lastSyncAt: 0
    };

    let refreshMs = DEFAULT_REFRESH_MS;
    let timer = null;
    let inflight = null;
    let pending = false;
    let includeConfig = normalizeInclude(['decisions', 'decisionLogs']);

    function now() {
        return Date.now();
    }

    function clone(value) {
        return JSON.parse(JSON.stringify(value));
    }

    function emit(type, extra) {
        const payload = {
            type,
            state: clone(state),
            ...(extra || {})
        };
        listeners.forEach((listener) => {
            try {
                listener(payload);
            } catch (error) {
                console.warn('[agent-data-bus] listener error:', error);
            }
        });
    }

    function normalizeInclude(include) {
        const normalized = {
            context: false,
            decisions: false,
            decisionLogs: false,
            leaderboard: false,
            profile: false,
            weeklyPlan: false
        };

        if (Array.isArray(include)) {
            include.forEach((key) => {
                if (INCLUDE_KEYS.includes(key)) normalized[key] = true;
            });
            return normalized;
        }

        if (include && typeof include === 'object') {
            INCLUDE_KEYS.forEach((key) => {
                normalized[key] = Boolean(include[key]);
            });
            return normalized;
        }

        return normalized;
    }

    function parseUserId(subjectId) {
        const raw = String(subjectId || '');
        const match = raw.match(/^user_(\d+)$/i);
        if (!match) return 1001;
        const n = Number(match[1]);
        return Number.isFinite(n) ? n : 1001;
    }

    function setSelection(patch) {
        if (!patch || typeof patch !== 'object') return clone(state);
        if (patch.symbol) state.symbol = String(patch.symbol).toUpperCase();
        if (patch.interval) state.interval = String(patch.interval);
        if (patch.strategyVersion) state.strategyVersion = String(patch.strategyVersion);
        if (patch.subjectType) state.subjectType = String(patch.subjectType).toUpperCase() === 'AGENT' ? 'AGENT' : 'HUMAN';
        if (patch.subjectId) state.subjectId = String(patch.subjectId);
        return clone(state);
    }

    function subscribe(listener, options) {
        if (typeof listener !== 'function') {
            return function noop() {};
        }

        listeners.add(listener);
        const immediate = !options || options.immediate !== false;
        if (immediate) {
            emit('init');
        }

        return function unsubscribe() {
            listeners.delete(listener);
        };
    }

    async function request(path, options) {
        const headers = (options && options.headers) || {};
        const response = await fetch(`${API_BASE}${path}`, {
            ...(options || {}),
            headers: {
                'Content-Type': 'application/json',
                ...headers
            }
        });
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        const payload = await response.json();
        if (!payload || payload.code !== 0) {
            throw new Error('invalid payload');
        }
        return payload.data;
    }

    async function safeRequest(path, options) {
        try {
            return await request(path, options);
        } catch (error) {
            console.warn('[agent-data-bus] request fallback:', path, error);
            return null;
        }
    }

    function buildContextPath() {
        const query = new URLSearchParams({
            symbol: state.symbol,
            interval: state.interval,
            depthLevel: '20',
            windowMinutes: '60'
        });
        return `/api/v1/agent/context?${query.toString()}`;
    }

    function buildDecisionPath() {
        const query = new URLSearchParams({
            symbol: state.symbol,
            strategyVersion: state.strategyVersion
        });
        return `/api/v1/agent/decisions?${query.toString()}`;
    }

    function buildProfilePath() {
        const query = new URLSearchParams({
            subjectType: state.subjectType,
            subjectId: state.subjectId
        });
        return `/api/v1/agent/score/profile?${query.toString()}`;
    }

    function buildWeeklyPlanPath() {
        const userId = parseUserId(state.subjectId);
        const query = new URLSearchParams({ userId: String(userId) });
        return `/api/v1/agent/growth/weekly-plan?${query.toString()}`;
    }

    async function previewDecision() {
        return safeRequest('/api/v1/agent/decisions/preview', {
            method: 'POST',
            body: JSON.stringify({
                symbol: state.symbol,
                interval: state.interval,
                strategyVersion: state.strategyVersion,
                marketState: 'sync'
            })
        });
    }

    async function sync(options) {
        if (inflight) {
            pending = true;
            return inflight;
        }

        if (options && options.selection) {
            setSelection(options.selection);
        }

        const include = normalizeInclude((options && options.include) || includeConfig);
        includeConfig = include;
        const withPreview = Boolean(options && options.preview);

        inflight = (async function runSync() {
            state.syncing = true;
            emit('sync:start', { include });

            try {
                if (withPreview) {
                    await previewDecision();
                }

                const tasks = [];

                if (include.context) {
                    tasks.push(
                        safeRequest(buildContextPath()).then((data) => {
                            if (data) state.context = data;
                        })
                    );
                }

                if (include.decisions) {
                    tasks.push(
                        safeRequest(buildDecisionPath()).then((data) => {
                            if (Array.isArray(data)) state.decisions = data;
                        })
                    );
                }

                if (include.decisionLogs) {
                    tasks.push(
                        safeRequest('/api/v1/agent/decisions/logs').then((data) => {
                            if (Array.isArray(data)) state.decisionLogs = data;
                        })
                    );
                }

                if (include.leaderboard) {
                    tasks.push(
                        safeRequest('/api/v1/agent/score/leaderboard').then((data) => {
                            if (Array.isArray(data)) state.leaderboard = data;
                        })
                    );
                }

                if (include.profile) {
                    tasks.push(
                        safeRequest(buildProfilePath()).then((data) => {
                            if (data) state.profile = data;
                        })
                    );
                }

                if (include.weeklyPlan) {
                    tasks.push(
                        safeRequest(buildWeeklyPlanPath()).then((data) => {
                            if (data) state.weeklyPlan = data;
                        })
                    );
                }

                await Promise.all(tasks);
                state.lastSyncAt = now();
                state.health = 'online';
                emit('sync:success', { include });
                return clone(state);
            } catch (error) {
                state.health = 'degraded';
                emit('sync:error', { include, error: error.message || 'sync error' });
                throw error;
            } finally {
                state.syncing = false;
                inflight = null;

                if (pending) {
                    pending = false;
                    sync({ include: includeConfig }).catch(function noop() {});
                }
            }
        })();

        return inflight;
    }

    function start(options) {
        if (options && options.selection) {
            setSelection(options.selection);
        }
        if (options && options.include) {
            includeConfig = normalizeInclude(options.include);
        }
        if (options && Number(options.intervalMs) > 1000) {
            refreshMs = Number(options.intervalMs);
        }

        if (timer) {
            clearInterval(timer);
        }

        timer = setInterval(() => {
            sync({ include: includeConfig }).catch(function noop() {});
        }, refreshMs);

        sync({ include: includeConfig }).catch(function noop() {});
        emit('runtime:start', { refreshMs, include: includeConfig });
        return { refreshMs, include: clone(includeConfig) };
    }

    function stop() {
        if (timer) {
            clearInterval(timer);
            timer = null;
        }
        emit('runtime:stop');
    }

    function getState() {
        return clone(state);
    }

    async function fetchProfile(subjectType, subjectId) {
        const query = new URLSearchParams({
            subjectType: String(subjectType || state.subjectType || 'HUMAN').toUpperCase(),
            subjectId: String(subjectId || state.subjectId || 'user_1001')
        });
        return request(`/api/v1/agent/score/profile?${query.toString()}`);
    }

    async function fetchWeeklyPlan(userId) {
        const query = new URLSearchParams({ userId: String(userId || parseUserId(state.subjectId)) });
        return request(`/api/v1/agent/growth/weekly-plan?${query.toString()}`);
    }

    async function saveWeeklyPlan(userId, items) {
        const query = new URLSearchParams({ userId: String(userId || parseUserId(state.subjectId)) });
        return request(`/api/v1/agent/growth/weekly-plan?${query.toString()}`, {
            method: 'POST',
            body: JSON.stringify({ items: Array.isArray(items) ? items : [] })
        });
    }

    global.AgentDataBus = {
        apiBase: API_BASE,
        defaults: { refreshMs: DEFAULT_REFRESH_MS },
        subscribe,
        start,
        stop,
        sync,
        state: getState,
        setSelection,
        request,
        safeRequest,
        fetchProfile,
        fetchWeeklyPlan,
        saveWeeklyPlan,
        previewDecision
    };
})(window);
