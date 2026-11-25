-- 公交灰度实验服务 PostgreSQL schema（幂等，可重复启动）

CREATE TABLE IF NOT EXISTS app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    display_name  TEXT,
    role          TEXT NOT NULL CHECK (role IN ('VIEWER','ANALYST','APPROVER'))
);

CREATE TABLE IF NOT EXISTS experiment (
    id                  BIGSERIAL PRIMARY KEY,
    code                TEXT UNIQUE NOT NULL,
    name                TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'DRAFT', -- DRAFT/REGISTERED/RUNNING/PAUSED/STOPPED/COMPLETED
    active_design       TEXT NOT NULL DEFAULT 'TRIP_RANDOMIZED',
    seed                TEXT NOT NULL,
    assignment_prob     DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    saturation          DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    pilot_weeks         INT NOT NULL DEFAULT 4,
    start_date          DATE,
    end_date            DATE,
    target_population   TEXT,
    treatment_rules     TEXT,   -- JSON：处理规则（缩短间隔/短线折返）
    primary_metrics     TEXT,   -- JSON 数组
    safety_metrics      TEXT,   -- JSON 数组
    network_config      TEXT,   -- JSON：换乘边阈值等
    analysis_config     TEXT,   -- JSON：共同支持最小样本等
    registration_hash   TEXT,
    registered_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS route (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    code          TEXT NOT NULL,
    name          TEXT NOT NULL,
    UNIQUE (experiment_id, code)
);

CREATE TABLE IF NOT EXISTS stop_ (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    code          TEXT NOT NULL,
    name          TEXT NOT NULL,
    kind          TEXT NOT NULL DEFAULT 'NORMAL', -- NORMAL/HUB
    UNIQUE (experiment_id, code)
);

-- 实验前刷卡换乘比例（冻结，不允许用试点结果重算）
CREATE TABLE IF NOT EXISTS pre_transfer_edge (
    id                 BIGSERIAL PRIMARY KEY,
    experiment_id      BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    from_route         TEXT NOT NULL,
    to_route           TEXT NOT NULL,
    transfer_journeys  INTEGER NOT NULL,
    total_journeys     INTEGER NOT NULL,
    ratio              DOUBLE PRECISION NOT NULL,
    frozen             BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (experiment_id, from_route, to_route)
);

-- 依据实验前网络一次性冻结的线路簇
CREATE TABLE IF NOT EXISTS route_cluster (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    cluster_code  TEXT NOT NULL,
    route_codes   TEXT NOT NULL, -- 逗号分隔，排序
    frozen_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (experiment_id, cluster_code)
);

-- 随机化单元与分配结果（同一实验保存两套设计的分配）
CREATE TABLE IF NOT EXISTS assignment_unit (
    id                BIGSERIAL PRIMARY KEY,
    experiment_id     BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    design_type       TEXT NOT NULL, -- TRIP_RANDOMIZED / CLUSTER_WEEK
    unit_key          TEXT NOT NULL,
    stratum           TEXT NOT NULL,
    label             TEXT,
    route_codes       TEXT NOT NULL,
    cluster_code      TEXT,
    week_index        INTEGER,
    hash_value        TEXT NOT NULL,
    arm               TEXT NOT NULL, -- TREATMENT / CONTROL
    inclusion_prob    DOUBLE PRECISION NOT NULL,
    UNIQUE (experiment_id, design_type, unit_key)
);
CREATE INDEX IF NOT EXISTS idx_assignment_exp ON assignment_unit(experiment_id, design_type);

-- 计划班次（日期级实例，保留计划分配）
CREATE TABLE IF NOT EXISTS service_trip (
    id                BIGSERIAL PRIMARY KEY,
    experiment_id     BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    trip_code         TEXT NOT NULL,
    template_key      TEXT NOT NULL, -- TRIP:R1:T02
    cluster_week_key  TEXT NOT NULL, -- CW:C12:W1
    route_code        TEXT NOT NULL,
    service_date      DATE NOT NULL,
    week_index        INTEGER NOT NULL,
    period_band       TEXT NOT NULL,
    seq_in_band       INTEGER NOT NULL DEFAULT 1,
    planned_departure TIMESTAMPTZ NOT NULL,
    od_band           TEXT,
    capacity          INTEGER NOT NULL DEFAULT 80,
    eligible          BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (experiment_id, trip_code)
);
CREATE INDEX IF NOT EXISTS idx_trip_lookup ON service_trip(experiment_id, route_code, service_date, period_band);

-- 实际 GPS/AVL 运行与偏离（实际执行状态）
CREATE TABLE IF NOT EXISTS actual_service_run (
    id                  BIGSERIAL PRIMARY KEY,
    trip_id             BIGINT NOT NULL UNIQUE REFERENCES service_trip(id) ON DELETE CASCADE,
    gps_run_code        TEXT NOT NULL,
    actual_departure    TIMESTAMPTZ,
    actual_arrival      TIMESTAMPTZ,
    headway_seconds     INTEGER,
    rule_executed       BOOLEAN NOT NULL DEFAULT TRUE,
    deviation           TEXT NOT NULL DEFAULT 'NONE', -- NONE/SHORT_TURN/SUSPENDED/OVERTIME/DELAY
    short_turn_at_stop  TEXT,
    diverted_to_route   TEXT,
    delay_seconds       INTEGER NOT NULL DEFAULT 0,
    observed_lf         DOUBLE PRECISION,
    source              TEXT NOT NULL DEFAULT 'GPS_AVL'
);

-- 天气/施工/临时改线事件，按事件发生时间关联服务
CREATE TABLE IF NOT EXISTS external_event (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    event_type     TEXT NOT NULL, -- WEATHER_RAINSTORM/WEATHER_RAIN/CONSTRUCTION/ROUTE_CHANGE
    severity       TEXT,
    title          TEXT NOT NULL,
    start_ts       TIMESTAMPTZ NOT NULL,
    end_ts         TIMESTAMPTZ NOT NULL,
    affected_routes TEXT NOT NULL DEFAULT '*',
    suspension     BOOLEAN NOT NULL DEFAULT FALSE,
    source         TEXT
);

-- 刷卡完整旅程（未完成刷卡/中途改线保留为非 COMPLETE 状态）
CREATE TABLE IF NOT EXISTS card_journey (
    id                        BIGSERIAL PRIMARY KEY,
    experiment_id             BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    journey_code              TEXT NOT NULL UNIQUE,
    week_index                INTEGER NOT NULL,
    service_date              DATE NOT NULL,
    service_ts                TIMESTAMPTZ NOT NULL,
    period_band               TEXT NOT NULL,
    origin_stop               TEXT,
    hub_stop                  TEXT,
    dest_stop                 TEXT,
    od_band                   TEXT NOT NULL,
    route_pattern             TEXT NOT NULL, -- R1ONLY/R2ONLY/R1R2/R3ONLY/...
    weather_class             TEXT NOT NULL DEFAULT 'NORMAL',
    accessibility             BOOLEAN NOT NULL DEFAULT TRUE,
    status                    TEXT NOT NULL, -- COMPLETE/INCOMPLETE_TAP/MID_ROUTE_CHANGE
    passenger_count           INTEGER NOT NULL DEFAULT 1,
    anchor_unit_key           TEXT,
    anchor_arm_trip           TEXT,
    cluster_week_key          TEXT,
    cluster_code              TEXT,
    anchor_arm_cluster        TEXT,
    wait_seconds              DOUBLE PRECISION,
    first_wait_seconds        DOUBLE PRECISION,
    transfer_wait_seconds     DOUBLE PRECISION NOT NULL DEFAULT 0,
    crowd_exposure            DOUBLE PRECISION,
    severe_crowd_minutes      DOUBLE PRECISION,
    max_load_factor           DOUBLE PRECISION,
    -- 同一目标总体在“簇—周”方案下按同一 DGP 构造的可复现实现结果
    wait_cluster_seconds      DOUBLE PRECISION,
    crowd_cluster_exposure    DOUBLE PRECISION,
    severe_crowd_cluster_min  DOUBLE PRECISION,
    complaint_count           INTEGER NOT NULL DEFAULT 0,
    is_demo                   BOOLEAN NOT NULL DEFAULT FALSE,
    note                      TEXT
);
CREATE INDEX IF NOT EXISTS idx_journey_exp ON card_journey(experiment_id);
CREATE INDEX IF NOT EXISTS idx_journey_stratum ON card_journey(experiment_id, period_band, od_band, weather_class, accessibility);

CREATE TABLE IF NOT EXISTS journey_leg (
    id             BIGSERIAL PRIMARY KEY,
    journey_id     BIGINT NOT NULL REFERENCES card_journey(id) ON DELETE CASCADE,
    leg_index      INTEGER NOT NULL,
    route_code     TEXT NOT NULL,
    trip_id        BIGINT REFERENCES service_trip(id),
    actual_run_id  BIGINT REFERENCES actual_service_run(id),
    board_stop     TEXT,
    alight_stop    TEXT,
    is_transfer    BOOLEAN NOT NULL DEFAULT FALSE,
    unit_key       TEXT,
    planned_arm    TEXT,
    rule_effective BOOLEAN,
    deviation      TEXT,
    leg_exposure   TEXT NOT NULL, -- TREATMENT/CONTROL/CROSS/UNKNOWN
    wait_seconds   DOUBLE PRECISION NOT NULL DEFAULT 0,
    ride_minutes   DOUBLE PRECISION NOT NULL DEFAULT 0,
    load_factor    DOUBLE PRECISION,
    note           TEXT
);
CREATE INDEX IF NOT EXISTS idx_leg_journey ON journey_leg(journey_id);

-- 暴露账：每旅程完整路径一行
CREATE TABLE IF NOT EXISTS exposure_ledger (
    journey_id            BIGINT PRIMARY KEY REFERENCES card_journey(id) ON DELETE CASCADE,
    experiment_id         BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    journey_code          TEXT NOT NULL,
    status                TEXT NOT NULL,
    path_label            TEXT NOT NULL, -- FULL_TREATMENT/FULL_CONTROL/CROSS_MIXED/UNKNOWN
    cluster_path_label    TEXT NOT NULL, -- CLUSTER_TREATMENT/CLUSTER_CONTROL/CLUSTER_INTERNAL_MIX/UNKNOWN
    treatment_legs        INTEGER NOT NULL DEFAULT 0,
    control_legs          INTEGER NOT NULL DEFAULT 0,
    cross_legs            INTEGER NOT NULL DEFAULT 0,
    unknown_legs          INTEGER NOT NULL DEFAULT 0,
    assigned_arm_trip     TEXT,
    assigned_arm_cluster  TEXT,
    effective_treatment   BOOLEAN NOT NULL DEFAULT FALSE,
    cross_line            BOOLEAN NOT NULL DEFAULT FALSE,
    diverted              BOOLEAN NOT NULL DEFAULT FALSE,
    path_probability      DOUBLE PRECISION,
    path_detail           TEXT,
    rebuilt_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ledger_path ON exposure_ledger(experiment_id, path_label);

CREATE TABLE IF NOT EXISTS support_stratum (
    id               BIGSERIAL PRIMARY KEY,
    experiment_id    BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    stratum_key      TEXT NOT NULL,
    period_band      TEXT,
    od_band          TEXT,
    weather_class    TEXT,
    accessibility    BOOLEAN,
    n_total          INTEGER,
    weighted_total   DOUBLE PRECISION,
    n_t_trip         INTEGER,
    n_c_trip         INTEGER,
    w_t_trip         DOUBLE PRECISION,
    w_c_trip         DOUBLE PRECISION,
    n_t_cluster      INTEGER,
    n_c_cluster      INTEGER,
    w_t_cluster      DOUBLE PRECISION,
    w_c_cluster      DOUBLE PRECISION,
    supported_trip   BOOLEAN,
    supported_cluster BOOLEAN,
    gap_reason       TEXT,
    UNIQUE (experiment_id, stratum_key)
);

CREATE TABLE IF NOT EXISTS analysis_result (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    design_type   TEXT NOT NULL,
    estimator_code TEXT NOT NULL, -- ITT / CACE_WALD / PATH_MEAN / CROSS_RATE / RI ...
    metric_code   TEXT NOT NULL,  -- wait_seconds / crowd_exposure ...
    scope         TEXT NOT NULL,  -- COMMON_SUPPORT / ALL_ELIGIBLE / PATH_*
    estimate      DOUBLE PRECISION,
    se            DOUBLE PRECISION,
    ci_low        DOUBLE PRECISION,
    ci_high       DOUBLE PRECISION,
    p_value       DOUBLE PRECISION,
    n_journeys    INTEGER,
    n_units       INTEGER,
    detail        TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_result_exp ON analysis_result(experiment_id);

CREATE TABLE IF NOT EXISTS guardrail_rule (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    code          TEXT NOT NULL,
    name          TEXT NOT NULL,
    metric_source TEXT NOT NULL,
    comparator    TEXT NOT NULL DEFAULT 'GT', -- GT/LT
    threshold     DOUBLE PRECISION NOT NULL,
    min_sample    INTEGER NOT NULL DEFAULT 30,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (experiment_id, code)
);

CREATE TABLE IF NOT EXISTS guardrail_evaluation (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    rule_code     TEXT NOT NULL,
    observed      DOUBLE PRECISION,
    threshold     DOUBLE PRECISION,
    sample_n      INTEGER,
    breached      BOOLEAN NOT NULL,
    action        TEXT NOT NULL, -- OK / WARN / STOP_EXPANSION / INSUFFICIENT_EVIDENCE
    detail        TEXT,
    evaluated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ge_exp ON guardrail_evaluation(experiment_id);

CREATE TABLE IF NOT EXISTS expansion_decision (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    decision      TEXT NOT NULL, -- GO / HOLD / BLOCKED
    reason        TEXT,
    evidence      TEXT,
    decided_by    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS complaint (
    id                   BIGSERIAL PRIMARY KEY,
    experiment_id        BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    complaint_code       TEXT,
    journey_id           BIGINT REFERENCES card_journey(id) ON DELETE SET NULL,
    route_code           TEXT,
    category             TEXT,
    severity             TEXT,
    complaint_time       TIMESTAMPTZ NOT NULL,  -- 投诉提交时间（可滞后服务数日）
    service_event_time   TIMESTAMPTZ NOT NULL,  -- 实际服务发生时间（统计关联基准）
    channel              TEXT
);
CREATE INDEX IF NOT EXISTS idx_complaint_service_time ON complaint(experiment_id, service_event_time);

CREATE TABLE IF NOT EXISTS load_count (
    id             BIGSERIAL PRIMARY KEY,
    actual_run_id BIGINT REFERENCES actual_service_run(id) ON DELETE CASCADE,
    trip_id        BIGINT REFERENCES service_trip(id) ON DELETE CASCADE,
    observed_at    TIMESTAMPTZ,
    passengers     INTEGER,
    capacity       INTEGER,
    load_factor    DOUBLE PRECISION
);

-- ======================================================================
-- 车辆能耗归因数据集（feature-3）
-- 设计要点：
--   1. CAN 燃油/电量按“车辆设备沿革”逐设备校准，换表后不混用校准常数；
--   2. AVL 轨迹切成 REVENUE(载客)/DEADHEAD(空驶)/IDLE(等待)/SHORT_TURN(折返) 区段；
--   3. 乘客公里只由刷卡 + 满载计数产生，空驶/等待/折返区段乘客公里为 0（分母核验）；
--   4. 随机分配只关联“处理资格(eligibility)”而非实际执行者，ITT 按预注册分配臂；
--   5. 设备故障/缺测段不得当零消耗：遥测路线将其剔除并缩小可估范围，
--      重建路线用同车校准的速度—坡度曲线重建，参数误差必须传播；
--   6. 校准路线外的坡度不观测 → 标记不可外推，不用全市平均替代。
-- ======================================================================

-- 车辆台账
CREATE TABLE IF NOT EXISTS vehicle (
    id              BIGSERIAL PRIMARY KEY,
    experiment_id   BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    vehicle_code    TEXT NOT NULL,
    vehicle_type    TEXT NOT NULL, -- BEV 纯电 / DIESEL 柴油
    drivetrain_note TEXT,
    UNIQUE (experiment_id, vehicle_code)
);

-- 车辆设备沿革：同一辆车 CAN 表/电池组会更换，校准常数按设备有效期分段
CREATE TABLE IF NOT EXISTS vehicle_device_history (
    id              BIGSERIAL PRIMARY KEY,
    experiment_id   BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    vehicle_id      BIGINT NOT NULL REFERENCES vehicle(id) ON DELETE CASCADE,
    device_code     TEXT NOT NULL,
    device_kind     TEXT NOT NULL, -- FUEL_METER 燃油表 / BATTERY_BMS 电池管理
    valid_from      TIMESTAMPTZ NOT NULL,
    valid_to        TIMESTAMPTZ,  -- NULL 表示至今有效
    -- 原始读数 -> 标准能量(kWh)：diesel: kWh = raw_liters * factor + offset；
    -- bev: kWh = raw_soc_delta/100 * usable_kwh * factor + offset
    calibration_factor   DOUBLE PRECISION NOT NULL,
    calibration_offset   DOUBLE PRECISION NOT NULL DEFAULT 0,
    calibration_se       DOUBLE PRECISION NOT NULL, -- 校准相对标准误，用于重建路线参数误差传播
    calibration_n        INTEGER NOT NULL,
    calibration_note     TEXT,
    UNIQUE (experiment_id, vehicle_id, device_code, valid_from)
);
CREATE INDEX IF NOT EXISTS idx_device_vehicle ON vehicle_device_history(vehicle_id, valid_from);

-- 同车校准的速度—坡度能耗曲线参数（每车一组，仅在该校准覆盖的坡度域内有效）
CREATE TABLE IF NOT EXISTS vehicle_energy_curve (
    id                 BIGSERIAL PRIMARY KEY,
    experiment_id      BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    vehicle_id         BIGINT NOT NULL UNIQUE REFERENCES vehicle(id) ON DELETE CASCADE,
    -- 功率(kW) = alpha(怠速附件) + beta1*v + beta2*v^2 + beta3*v^3 + gamma*v*grade
    alpha              DOUBLE PRECISION NOT NULL,
    beta1              DOUBLE PRECISION NOT NULL,
    beta2              DOUBLE PRECISION NOT NULL,
    beta3              DOUBLE PRECISION NOT NULL,
    gamma              DOUBLE PRECISION NOT NULL,
    -- 校准支撑域（仅该速度/坡度范围内可重建；域外报告不可外推）
    grade_min_observed DOUBLE PRECISION NOT NULL,
    grade_max_observed DOUBLE PRECISION NOT NULL,
    speed_max_observed DOUBLE PRECISION NOT NULL,
    param_covariance   TEXT NOT NULL, -- JSON 5x5 参数协方差矩阵（误差传播用）
    calibrated_n       INTEGER NOT NULL,
    calibrated_r2      DOUBLE PRECISION
);

-- CAN 遥测原始流（含缺测标记；gap=true 的读数为 NULL，绝不写 0）
CREATE TABLE IF NOT EXISTS can_telemetry (
    id              BIGSERIAL PRIMARY KEY,
    experiment_id   BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    vehicle_id      BIGINT NOT NULL REFERENCES vehicle(id) ON DELETE CASCADE,
    trip_id         BIGINT REFERENCES service_trip(id) ON DELETE CASCADE,
    observed_at     TIMESTAMPTZ NOT NULL,
    device_code     TEXT NOT NULL,
    metric          TEXT NOT NULL,      -- FUEL_LITERS / BATTERY_KWH
    reading         DOUBLE PRECISION,   -- 累计表读数；缺测时为 NULL
    sensor_gap      BOOLEAN NOT NULL DEFAULT FALSE,
    gap_reason      TEXT,               -- DEVICE_FAULT 设备故障 / COMMS_LOSS 通信丢失
    UNIQUE (vehicle_id, observed_at, metric)
);
CREATE INDEX IF NOT EXISTS idx_can_vehicle_time ON can_telemetry(vehicle_id, observed_at);
CREATE INDEX IF NOT EXISTS idx_can_trip ON can_telemetry(trip_id);

-- AVL 轨迹点
CREATE TABLE IF NOT EXISTS avl_point (
    id              BIGSERIAL PRIMARY KEY,
    experiment_id   BIGINT NOT NULL NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    vehicle_id      BIGINT NOT NULL REFERENCES vehicle(id) ON DELETE CASCADE,
    trip_id         BIGINT REFERENCES service_trip(id) ON DELETE CASCADE,
    observed_at     TIMESTAMPTZ NOT NULL,
    speed_kmh       DOUBLE PRECISION NOT NULL,
    grade_percent   DOUBLE PRECISION,  -- 坡度%；NULL=该点坡度未观测（外推守卫依据）
    latitude        DOUBLE PRECISION,
    longitude       DOUBLE PRECISION,
    odometer_km     DOUBLE PRECISION
);
CREATE INDEX IF NOT EXISTS idx_avl_vehicle_time ON avl_point(vehicle_id, observed_at);

-- AVL 切分段：载客/空驶/等待/折返
CREATE TABLE IF NOT EXISTS energy_segment (
    id                 BIGSERIAL PRIMARY KEY,
    experiment_id      BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    vehicle_id         BIGINT NOT NULL REFERENCES vehicle(id) ON DELETE CASCADE,
    trip_id            BIGINT NOT NULL REFERENCES service_trip(id) ON DELETE CASCADE,
    segment_code       TEXT NOT NULL UNIQUE,
    seg_index          INTEGER NOT NULL,
    segment_type       TEXT NOT NULL, -- REVENUE 载客 / DEADHEAD 空驶 / IDLE 等待 / SHORT_TURN 折返
    start_ts           TIMESTAMPTZ NOT NULL,
    end_ts             TIMESTAMPTZ NOT NULL,
    distance_km        DOUBLE PRECISION NOT NULL,
    duration_seconds   INTEGER NOT NULL,
    avg_speed_kmh      DOUBLE PRECISION NOT NULL,
    avg_grade_percent  DOUBLE PRECISION,  -- NULL 表示坡度未观测
    grade_observed     BOOLEAN NOT NULL DEFAULT TRUE,
    assigned_arm       TEXT NOT NULL,     -- 预注册随机分配的处理资格 TREATMENT/CONTROL（非执行者）
    eligibility_unit_key TEXT NOT NULL,
    rule_executed      BOOLEAN NOT NULL,  -- 实际是否执行新规则（资格 ≠ 执行）
    note               TEXT,
    UNIQUE (trip_id, seg_index)
);
CREATE INDEX IF NOT EXISTS idx_segment_trip ON energy_segment(trip_id);
CREATE INDEX IF NOT EXISTS idx_segment_vehicle ON energy_segment(vehicle_id);

-- 分段能耗账：两条估计路线各一行（TELEMETRY 遥测积分 / RECONSTRUCTED 曲线重建）
CREATE TABLE IF NOT EXISTS segment_energy (
    id                 BIGSERIAL PRIMARY KEY,
    segment_id         BIGINT NOT NULL REFERENCES energy_segment(id) ON DELETE CASCADE,
    experiment_id      BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    route_method       TEXT NOT NULL, -- TELEMETRY / RECONSTRUCTED
    energy_kwh         DOUBLE PRECISION,      -- 缺测且不可重建时为 NULL（绝不用 0 代替）
    energy_se_kwh      DOUBLE PRECISION,      -- 不确定性（校准/参数传播后）
    observed_share     DOUBLE PRECISION NOT NULL, -- 该段能量被传感器实际观测到的比例
    gap_seconds        INTEGER NOT NULL DEFAULT 0,
    gap_reason         TEXT,
    extrapolated       BOOLEAN NOT NULL DEFAULT FALSE, -- 是否使用了缺测重建
    outside_calibration BOOLEAN NOT NULL DEFAULT FALSE, -- 坡度在同车校准域之外 → 不可外推
    estimation_detail  TEXT,
    UNIQUE (segment_id, route_method)
);
CREATE INDEX IF NOT EXISTS idx_segenergy_exp ON segment_energy(experiment_id, route_method);

-- 分段乘客公里：刷卡 OD + 满载计数；空驶/等待/折返段恒为 0
CREATE TABLE IF NOT EXISTS segment_passenger_km (
    id                 BIGSERIAL PRIMARY KEY,
    segment_id         BIGINT NOT NULL REFERENCES energy_segment(id) ON DELETE CASCADE,
    experiment_id      BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    passenger_km       DOUBLE PRECISION NOT NULL,
    passenger_count    INTEGER NOT NULL,
    source             TEXT NOT NULL, -- SMARTCARD 刷卡OD / LOAD_COUNT 满载计数 / ZERO 非载客段
    detail             TEXT,
    UNIQUE (segment_id)
);

-- 每乘客公里能耗的试点级估计结果（两条路线 × 分配臂/ITT）
CREATE TABLE IF NOT EXISTS energy_estimate (
    id                 BIGSERIAL PRIMARY KEY,
    experiment_id      BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    route_method       TEXT NOT NULL, -- TELEMETRY / RECONSTRUCTED
    scope              TEXT NOT NULL, -- OBSERVED_SUPPORT 可观测共同支持 / ALL_SEGMENTS 重建全量
    arm                TEXT,          -- TREATMENT / CONTROL（臂级均值），ITT 行为 NULL
    estimator_code     TEXT NOT NULL, -- ARM_RATE / ITT_RATE / RI_RATE
    energy_per_pkm     DOUBLE PRECISION,
    se                 DOUBLE PRECISION,
    ci_low             DOUBLE PRECISION,
    ci_high            DOUBLE PRECISION,
    p_value            DOUBLE PRECISION,
    total_energy_kwh   DOUBLE PRECISION,
    total_passenger_km DOUBLE PRECISION,
    n_segments         INTEGER,
    n_units            INTEGER,
    coverage_share     DOUBLE PRECISION, -- 该口径覆盖的试点里程比例（缺测缩小可估范围）
    detail             TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_energy_est_exp ON energy_estimate(experiment_id, route_method);

-- 覆盖与不可外推告警（审计留痕）
CREATE TABLE IF NOT EXISTS energy_coverage_gap (
    id                 BIGSERIAL PRIMARY KEY,
    experiment_id      BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    vehicle_code       TEXT NOT NULL,
    segment_code       TEXT,
    gap_kind           TEXT NOT NULL, -- SENSOR_GAP 传感器缺测 / GRADE_UNOBSERVED 坡度未观测 / OUTSIDE_CALIBRATION 超出校准域
    route_method       TEXT NOT NULL,
    seconds_or_grade   DOUBLE PRECISION,
    handling           TEXT NOT NULL,  -- DROPPED_FROM_TELEMETRY 遥测剔除 / RECONSTRUCTED 曲线重建 / NOT_EXTRAPOLATED 拒绝外推
    detail             TEXT
);
CREATE INDEX IF NOT EXISTS idx_energy_gap_exp ON energy_coverage_gap(experiment_id);
