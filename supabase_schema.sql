-- OnTheWay Supabase 테이블 (ZeroKitchen 동일 프로젝트)
-- source_app = 'on_the_way' 로 분리

CREATE TABLE IF NOT EXISTS otw_call_logs (
    id BIGSERIAL PRIMARY KEY,
    local_id BIGINT,
    ts BIGINT NOT NULL,
    platform TEXT,
    price INTEGER,
    distance DOUBLE PRECISION,
    unit_price INTEGER,
    point DOUBLE PRECISION,
    verdict TEXT,
    reason TEXT,
    bundle_count INTEGER DEFAULT 1,
    is_multi_pickup BOOLEAN DEFAULT FALSE,
    store_name TEXT,
    destination TEXT,
    pickup_km DOUBLE PRECISION,
    judge_version TEXT,
    tts_suppressed BOOLEAN DEFAULT FALSE,
    source_type TEXT DEFAULT 'unknown',
    parsing_method TEXT DEFAULT 'unknown',
    driver_action TEXT DEFAULT 'unknown',
    source_app TEXT DEFAULT 'on_the_way',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_otw_call_logs_ts ON otw_call_logs(ts);
CREATE INDEX IF NOT EXISTS idx_otw_call_logs_platform ON otw_call_logs(platform);

CREATE TABLE IF NOT EXISTS otw_feedback_logs (
    id BIGSERIAL PRIMARY KEY,
    ts BIGINT NOT NULL,
    session_id TEXT,
    platform TEXT,
    store TEXT,
    price INTEGER,
    distance_km DOUBLE PRECISION,
    verdict TEXT,
    reason TEXT,
    feedback TEXT,
    reasons JSONB,
    overwrote_ts BIGINT,
    driver_action TEXT DEFAULT 'unknown',
    pickup_rating TEXT,
    delivery_rating TEXT,
    price_rating TEXT,
    judgment_rating TEXT,
    entry_point TEXT,
    platform_distance_km DOUBLE PRECISION,
    ontheway_distance_km DOUBLE PRECISION,
    distance_diff_km DOUBLE PRECISION,
    event_type TEXT DEFAULT 'call_feedback_submitted',
    source_app TEXT DEFAULT 'on_the_way',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_otw_feedback_ts ON otw_feedback_logs(ts);

CREATE TABLE IF NOT EXISTS otw_judgment_match (
    id BIGSERIAL PRIMARY KEY,
    event_id TEXT,
    ts BIGINT NOT NULL,
    platform TEXT,
    price INTEGER,
    distance_km DOUBLE PRECISION,
    store_name TEXT,
    judgment TEXT,
    user_action TEXT,
    match_status TEXT,
    rejection_reason TEXT,
    acceptance_reason TEXT,
    user_feedback TEXT,
    event_type TEXT DEFAULT 'judgment_action_matched',
    source_app TEXT DEFAULT 'on_the_way',
    routed_agent TEXT DEFAULT 'mobility',
    summary TEXT,
    tags JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_otw_judgment_ts ON otw_judgment_match(ts);
CREATE INDEX IF NOT EXISTS idx_otw_judgment_status ON otw_judgment_match(match_status);
