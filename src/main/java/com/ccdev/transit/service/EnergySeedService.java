package com.ccdev.transit.service;

import com.ccdev.transit.engine.Deterministic;
import com.ccdev.transit.engine.EnergyAttributionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车辆能耗归因虚构样例（确定性生成，可重复）：
 * <ul>
 *   <li>纯电车辆 V-EV-01 在 R1 上运行 <b>6 个处理资格班次 + 6 个对照资格班次</b>，
 *       随机分配只代表处理资格；其中 1 个处理班次司机未执行（无折返段），ITT 仍归处理臂；</li>
 *   <li>执行新规则的处理班各含一段 {@code SHORT_TURN} 空驶折返（0 乘客公里、能耗计入分子）；</li>
 *   <li>电量表 BMS-A 于 2026-06-15 更换为 BMS-B，校准常数按设备沿革分段；</li>
 *   <li>一个处理班次的电量表存在 <b>连续 2 小时缺测</b>（设备故障，读数为 NULL 而非 0）：
 *       遥测路线整单元剔除、覆盖收缩，曲线重建路线凭完整 AVL 补全；</li>
 *   <li>一个对照班次同时存在坡度未观测点与超出同车校准域的陡坡：
 *       重建路线标记不可外推（不用全市平均替代），遥测路线仍可估计。</li>
 * </ul>
 */
@Service
@Order(30)
public class EnergySeedService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EnergySeedService.class);

    static final String VEHICLE = "V-EV-01";
    static final String DEVICE_A = "BMS-A210";
    static final String DEVICE_B = "BMS-B307";
    static final LocalDate DEVICE_SWAP = LocalDate.of(2026, 6, 15);

    // 同车校准曲线（BEV）：P(kW)=α+β1v+β2v²+β3v³+γ·v·grade
    static final double ALPHA = 8.0, B1 = 0.05, B2 = 0.008, B3 = 0.00012, GAMMA = 0.35;
    static final double GRADE_DOMAIN_MIN = -4.0, GRADE_DOMAIN_MAX = 4.0, SPEED_DOMAIN_MAX = 60.0;

    private final JdbcTemplate jdbc;
    private final EnergyService energyService;

    public EnergySeedService(JdbcTemplate jdbc, EnergyService energyService) {
        this.jdbc = jdbc;
        this.energyService = energyService;
    }

    @Override
    public void run(String... args) {
        Long expId = jdbc.queryForObject(
                "SELECT id FROM experiment WHERE code=?", Long.class, SeedService.SAMPLE_CODE);
        if (expId == null) {
            return;
        }
        Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM vehicle WHERE experiment_id=? AND vehicle_code=?",
                Integer.class, expId, VEHICLE);
        if (exists != null && exists > 0) {
            log.info("能耗归因样例已存在，跳过播种；可调用 /energy/rebuild 重算");
            return;
        }
        seed(expId);
    }

    private void seed(long expId) {
        log.info("开始构造车辆能耗归因样例 ...");
        Long vehicleId = jdbc.queryForObject("""
                INSERT INTO vehicle(experiment_id,vehicle_code,vehicle_type,drivetrain_note)
                VALUES (?,?, 'BEV', '纯电动城市客车，单电机，电池容量 280kWh') RETURNING id
                """, Long.class, expId, VEHICLE);

        seedDeviceHistory(expId, vehicleId);
        seedCurve(expId, vehicleId);

        List<TripPick> picks = chooseTrips(expId);
        int segSeq = 0;
        for (int i = 0; i < picks.size(); i++) {
            TripPick pick = picks.get(i);
            boolean gapTrip = pick.gapTrip;
            boolean gradeAnomaly = pick.gradeAnomaly;
            List<SegmentSpec> specs = buildSegments(pick, i, gradeAnomaly);

            double meterRaw = 12000.0 + i * 850.0;
            double tripStartRaw = meterRaw;
            for (int s = 0; s < specs.size(); s++) {
                SegmentSpec spec = specs.get(s);
                long segId = insertSegment(expId, vehicleId, pick, spec, ++segSeq);
                // 相邻区段共享边界时间戳：仅每班首段写边界起点，避免 AVL 重复点
                insertAvl(expId, vehicleId, pick.tripId, spec, s == 0);
                double truthEnergy = truthEnergy(spec);
                insertPassengerKm(expId, segId, spec, pick.tripId);
                meterRaw = insertTelemetry(expId, vehicleId, pick.tripId, spec,
                        meterRaw, truthEnergy, gapTrip, s == 0, s == specs.size() - 1);
            }
            if (gapTrip) {
                insertGapWindow(expId, vehicleId, pick.tripId, specs, tripStartRaw);
            }
        }

        Map<String, Object> summary = energyService.rebuildEnergy(expId);
        log.info("能耗归因样例构造完成：{}", summary);
    }

    // ============================ 设备沿革与曲线 ============================

    private void seedDeviceHistory(long expId, Long vehicleId) {
        jdbc.update("""
                INSERT INTO vehicle_device_history(experiment_id,vehicle_id,device_code,device_kind,
                    valid_from,valid_to,calibration_factor,calibration_offset,calibration_se,
                    calibration_n,calibration_note)
                VALUES (?,?,?, 'BATTERY_BMS',?, NULL,?, 0, ?, 42,
                        '台架+在役并联计量：累计电量 kWh = raw*factor，相对标准误 3.0%')
                """, expId, vehicleId, DEVICE_A,
                Timestamp.valueOf(LocalDateTime.of(2026, 5, 1, 0, 0)),
                1.05, 0.030);
        jdbc.update("""
                UPDATE vehicle_device_history SET valid_to=? WHERE vehicle_id=? AND device_code=?
                """, Timestamp.valueOf(DEVICE_SWAP.atStartOfDay()), vehicleId, DEVICE_A);
        jdbc.update("""
                INSERT INTO vehicle_device_history(experiment_id,vehicle_id,device_code,device_kind,
                    valid_from,valid_to,calibration_factor,calibration_offset,calibration_se,
                    calibration_n,calibration_note)
                VALUES (?,?,?, 'BATTERY_BMS',?, NULL,?, 0, ?, 36,
                        '换表后重新台架校准：分辨率提升，相对标准误 2.0%；旧表常数不得用于新表读数')
                """, expId, vehicleId, DEVICE_B,
                Timestamp.valueOf(DEVICE_SWAP.atStartOfDay()),
                1.02, 0.020);
    }

    private void seedCurve(long expId, Long vehicleId) {
        // 对角协方差 + 少量相邻参数相关，体现联合估计的参数误差
        double[] diag = {0.04, 0.0004, 0.000004, 0.00000003, 0.0025};
        double[][] cov = new double[5][5];
        for (int i = 0; i < 5; i++) {
            cov[i][i] = diag[i];
            if (i > 0) {
                cov[i][i - 1] = cov[i - 1][i] = 0.15 * Math.sqrt(diag[i] * diag[i - 1]);
            }
        }
        jdbc.update("""
                INSERT INTO vehicle_energy_curve(experiment_id,vehicle_id,alpha,beta1,beta2,beta3,gamma,
                    grade_min_observed,grade_max_observed,speed_max_observed,param_covariance,
                    calibrated_n,calibrated_r2)
                VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb, 216, 0.963)
                """, expId, vehicleId, ALPHA, B1, B2, B3, GAMMA,
                GRADE_DOMAIN_MIN, GRADE_DOMAIN_MAX, SPEED_DOMAIN_MAX,
                com.ccdev.transit.util.Json.write(cov));
    }

    // ============================ 班次选择 ============================

    private record TripPick(long tripId, String tripCode, LocalDate date, String templateKey,
                            String arm, LocalDateTime departure, boolean executed,
                            boolean gapTrip, boolean gradeAnomaly) {
    }

    private List<TripPick> chooseTrips(long expId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.id,t.trip_code,t.service_date,t.template_key,t.planned_departure,a.arm,
                       COALESCE(r.rule_executed,TRUE) AS executed,
                       COALESCE(r.deviation,'NONE') AS deviation
                FROM service_trip t
                JOIN assignment_unit a
                  ON a.experiment_id=t.experiment_id AND a.design_type='TRIP_RANDOMIZED'
                 AND a.unit_key=t.template_key
                LEFT JOIN actual_service_run r ON r.trip_id=t.id
                WHERE t.experiment_id=? AND t.route_code='R1'
                  AND COALESCE(r.deviation,'NONE') <> 'SUSPENDED'
                ORDER BY t.service_date, t.template_key
                """, expId);

        List<TripPick> treatment = new ArrayList<>();
        List<TripPick> control = new ArrayList<>();
        // 著名短线折返班次必须入选（与既有 J-DEMO-DIVERT-01 同源），按实际偏离动态识别
        TripPick famous = null;
        for (Map<String, Object> r : rows) {
            String arm = String.valueOf(r.get("arm"));
            TripPick p = new TripPick(
                    ((Number) r.get("id")).longValue(),
                    String.valueOf(r.get("trip_code")),
                    ((java.sql.Date) r.get("service_date")).toLocalDate(),
                    String.valueOf(r.get("template_key")),
                    arm,
                    ((Timestamp) r.get("planned_departure")).toLocalDateTime(),
                    Boolean.TRUE.equals(r.get("executed")),
                    false, false);
            if ("TREATMENT".equals(arm)) {
                treatment.add(p);
                if ("SHORT_TURN".equals(String.valueOf(r.get("deviation"))) && famous == null) {
                    famous = p;
                }
            } else {
                control.add(p);
            }
        }
        if (famous == null) {
            throw new IllegalStateException("找不到著名短线折返班次用于能耗样例");
        }

        // 12 个班次必须落在不同运营日：缺测窗口按 5 分钟网格写 NULL，
        // 同日不同模板的时间戳会违反 (vehicle, observed_at, metric) 唯一约束
        java.util.Set<LocalDate> usedDates = new java.util.HashSet<>();

        // 处理班：著名折返 + 每周各取一班（跨设备换表日）+ 一个不执行班次
        List<TripPick> pickedT = new ArrayList<>();
        pickedT.add(famous);
        usedDates.add(famous.date());
        for (int week = 1; week <= 4 && pickedT.size() < 5; week++) {
            int w = week;
            treatment.stream()
                    .filter(p -> !usedDates.contains(p.date()) && weekOf(p.date()) == w && p.executed())
                    .findFirst()
                    .ifPresent(p -> {
                        pickedT.add(p);
                        usedDates.add(p.date());
                    });
        }
        // 若按周未取满，从剩余可执行处理班补足
        treatment.stream().filter(p -> p.executed() && !usedDates.contains(p.date())).forEach(p -> {
            if (pickedT.size() < 5) {
                pickedT.add(p);
                usedDates.add(p.date());
            }
        });
        TripPick nonExec = treatment.stream()
                .filter(p -> !p.executed() && !usedDates.contains(p.date()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到日期不冲突的司机不执行处理班次"));
        pickedT.add(nonExec);
        usedDates.add(nonExec.date());
        if (pickedT.size() != 6) {
            throw new IllegalStateException("处理班次数应为 6，实际 " + pickedT.size());
        }

        // 对照班：6 个，覆盖 4 周且日期互不相同、不与处理班撞日
        List<TripPick> pickedC = new ArrayList<>();
        for (int week = 1; week <= 4; week++) {
            int w = week;
            control.stream()
                    .filter(p -> weekOf(p.date()) == w && !usedDates.contains(p.date()))
                    .limit(w <= 2 ? 2 : 1)
                    .forEach(p -> {
                        if (pickedC.size() < 6 && usedDates.add(p.date())) {
                            pickedC.add(p);
                        }
                    });
        }
        control.stream().filter(p -> !usedDates.contains(p.date())).forEach(p -> {
            if (pickedC.size() < 6 && usedDates.add(p.date())) {
                pickedC.add(p);
            }
        });
        if (pickedC.size() != 6) {
            throw new IllegalStateException("对照班次数应为 6，实际 " + pickedC.size());
        }

        // 标记特殊班次：第 3 个处理班（非著名、非不执行）放 2 小时缺测；第 3 个对照班放坡度异常
        List<TripPick> result = new ArrayList<>();
        int tIdx = 0;
        for (TripPick p : pickedT) {
            boolean gap = tIdx == 2 && p.executed();
            result.add(gap ? new TripPick(p.tripId(), p.tripCode(), p.date(), p.templateKey(), p.arm(),
                    p.departure(), p.executed(), true, false) : p);
            tIdx++;
        }
        for (int i = 0; i < pickedC.size(); i++) {
            TripPick p = pickedC.get(i);
            boolean anomaly = i == 2;
            result.add(anomaly ? new TripPick(p.tripId(), p.tripCode(), p.date(), p.templateKey(), p.arm(),
                    p.departure(), p.executed(), false, true) : p);
        }
        return result;
    }

    private int weekOf(LocalDate d) {
        return (int) java.time.temporal.ChronoUnit.WEEKS.between(LocalDate.of(2026, 6, 1), d) + 1;
    }

    // ============================ 区段构造 ============================

    private record SegmentSpec(
            String type, LocalDateTime start, LocalDateTime end, double distanceKm,
            boolean gradeUnobserved, boolean outsideCalibration, int pax, String pkmSource,
            long seedSalt) {
        int durationSeconds() {
            return (int) java.time.Duration.between(start, end).getSeconds();
        }
    }

    private List<SegmentSpec> buildSegments(TripPick pick, int tripIdx, boolean gradeAnomaly) {
        LocalDateTime b = pick.departure();
        List<SegmentSpec> out = new ArrayList<>();
        long salt = tripIdx * 100L;
        if ("TREATMENT".equals(pick.arm()) && pick.executed()) {
            // 执行新规则：怠速等待更短，但含空驶折返
            out.add(new SegmentSpec("IDLE", b.minusMinutes(10), b.minusMinutes(5), 0,
                    false, false, 0, "ZERO", salt + 1));
            out.add(new SegmentSpec("DEADHEAD", b.minusMinutes(5), b, 2.0,
                    gradeAnomaly, false, 0, "ZERO", salt + 2));
            out.add(new SegmentSpec("REVENUE", b, b.plusMinutes(35), 18.0,
                    false, gradeAnomaly, revenuePax(pick, tripIdx), "SMARTCARD", salt + 3));
            out.add(new SegmentSpec("SHORT_TURN", b.plusMinutes(35), b.plusMinutes(45), 3.0,
                    false, false, 0, "ZERO", salt + 4));
        } else {
            // 对照 / 分配处理但未执行：无折返，怠速更长，单车客流更高
            out.add(new SegmentSpec("IDLE", b.minusMinutes(12), b.minusMinutes(4), 0,
                    false, false, 0, "ZERO", salt + 1));
            out.add(new SegmentSpec("DEADHEAD", b.minusMinutes(4), b, 2.0,
                    gradeAnomaly, false, 0, "ZERO", salt + 2));
            out.add(new SegmentSpec("REVENUE", b, b.plusMinutes(35), 18.0,
                    false, gradeAnomaly, revenuePax(pick, tripIdx), "SMARTCARD", salt + 3));
        }
        return out;
    }

    private int revenuePax(TripPick pick, int tripIdx) {
        // 缩短间隔→同量需求由更多车承担：执行处理班单车客流显著低于对照
        double u = Deterministic.uniform01("ENERGY-PAX", pick.tripCode());
        if ("TREATMENT".equals(pick.arm()) && pick.executed()) {
            return 38 + (int) Math.round(u * 10); // 38~48
        }
        return 55 + (int) Math.round(u * 14);     // 55~69
    }

    private long insertSegment(long expId, Long vehicleId, TripPick pick, SegmentSpec spec, int seq) {
        double avgSpeed = spec.durationSeconds() == 0 ? 0
                : spec.distanceKm / (spec.durationSeconds() / 3600.0);
        Double avgGrade = spec.gradeUnobserved() ? null : avgGradeOf(spec);
        return jdbc.queryForObject("""
                INSERT INTO energy_segment(experiment_id,vehicle_id,trip_id,segment_code,seg_index,
                    segment_type,start_ts,end_ts,distance_km,duration_seconds,avg_speed_kmh,
                    avg_grade_percent,grade_observed,assigned_arm,eligibility_unit_key,rule_executed,note)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                """, Long.class, expId, vehicleId, pick.tripId(),
                "SEG:" + VEHICLE + ":" + pick.tripCode() + ":" + spec.type(),
                seq, spec.type(), Timestamp.valueOf(spec.start()), Timestamp.valueOf(spec.end()),
                spec.distanceKm(), spec.durationSeconds(), Math.round(avgSpeed * 100.0) / 100.0,
                avgGrade, !spec.gradeUnobserved(), pick.arm(), pick.templateKey(), pick.executed(),
                segmentNote(pick, spec));
    }

    private String segmentNote(TripPick pick, SegmentSpec spec) {
        List<String> notes = new ArrayList<>();
        notes.add("分配臂=" + pick.arm() + " 表示处理资格，实际执行=" + pick.executed());
        if ("SHORT_TURN".equals(spec.type())) {
            notes.add("短线空驶折返：无乘客，乘客公里=0，但车辆能耗必须计入每乘客公里能耗的分子");
        }
        if ("IDLE".equals(spec.type())) {
            notes.add("怠速等待：缩短间隔减少了该处怠速，但不得只看怠速忽略折返/空驶");
        }
        if (spec.gradeUnobserved()) {
            notes.add("该区段坡度未观测：曲线重建拒绝外推");
        }
        if (spec.outsideCalibration()) {
            notes.add("含超出同车校准坡度域的陡坡：标记不可外推，不用全市平均替代");
        }
        return String.join("；", notes);
    }

    // ============================ AVL ============================

    private void insertAvl(long expId, Long vehicleId, long tripId, SegmentSpec spec, boolean writeStart) {
        // 与 energy_segment 等全库一致，统一 Timestamp.valueOf(LocalDateTime)，不做额外时区换算
        int seconds = spec.durationSeconds();
        int points = seconds / 60;
        for (int k = writeStart ? 0 : 1; k <= points; k++) {
            LocalDateTime at = spec.start().plusSeconds(Math.min(seconds, k * 60L));
            double speed = speedProfile(spec.type(), k);
            Double grade;
            if (spec.gradeUnobserved()) {
                grade = null; // 坡度未观测
            } else if (spec.outsideCalibration() && k == points / 2) {
                grade = 5.8;  // 超出校准域 [-4%,4%] 的一次陡坡观测
            } else {
                grade = gradeCycle(k);
            }
            jdbc.update("""
                    INSERT INTO avl_point(experiment_id,vehicle_id,trip_id,observed_at,
                        speed_kmh,grade_percent,latitude,longitude,odometer_km)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, expId, vehicleId, tripId, Timestamp.valueOf(at),
                    Math.round(speed * 100.0) / 100.0, grade, null, null, null);
        }
    }

    private double speedProfile(String type, int k) {
        return switch (type) {
            case "IDLE" -> 0.0;
            case "DEADHEAD" -> List.of(0.0, 14.0, 22.0, 26.0, 24.0, 18.0).get(k % 6);
            case "SHORT_TURN" -> List.of(0.0, 10.0, 16.0, 18.0, 14.0, 7.0, 0.0).get(k % 7);
            default -> List.of(0.0, 18.0, 32.0, 26.0, 12.0, 28.0, 34.0, 22.0, 9.0, 0.0).get(k % 10);
        };
    }

    private double gradeCycle(int k) {
        double[] cycle = {-1.5, 0.5, 2.0, 3.0, 1.0, -2.5, -3.5, 0.0, 1.5, 3.5};
        return cycle[k % cycle.length];
    }

    private Double avgGradeOf(SegmentSpec spec) {
        int points = spec.durationSeconds() / 60;
        double sum = 0;
        int n = 0;
        for (int k = 0; k <= points; k++) {
            if (spec.outsideCalibration() && k == points / 2) {
                continue; // 平均坡度统计也排除超域点，保持区段常规坡度在域内
            }
            sum += gradeCycle(k);
            n++;
        }
        return Math.round(sum / n * 100.0) / 100.0;
    }

    // ============================ 乘客公里 ============================

    private void insertPassengerKm(long expId, long segId, SegmentSpec spec, long tripId) {
        double pkm;
        if ("REVENUE".equals(spec.type())) {
            // 刷卡 OD 得出的乘距略短于车辆里程（平均乘距约为线长的 92%），并与满载计数交叉核对
            pkm = Math.round(spec.pax() * spec.distanceKm() * 0.92 * 100.0) / 100.0;
        } else {
            pkm = 0;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("formula", "REVENUE: 刷卡OD平均乘距(0.92×线长)×人数；与 load_count 满载计数核对一致");
        detail.put("nonRevenueRule", "IDLE/DEADHEAD/SHORT_TURN 不产生乘客公里，分母恒为 0 人公里");
        if ("REVENUE".equals(spec.type())) {
            List<Map<String, Object>> lc = jdbc.queryForList("""
                    SELECT passengers, capacity, round(load_factor::numeric,3) AS load_factor
                    FROM load_count WHERE trip_id=?
                    """, tripId);
            detail.put("loadCountCrossCheck", lc.isEmpty() ? null : lc.get(0));
            detail.put("smartcardPax", spec.pax());
        }
        jdbc.update("""
                INSERT INTO segment_passenger_km(segment_id,experiment_id,passenger_km,passenger_count,
                    source,detail)
                VALUES (?,?,?,?,?,?::jsonb)
                """, segId, expId, pkm, spec.pax(), spec.pkmSource(),
                com.ccdev.transit.util.Json.write(detail));
    }

    // ============================ CAN 遥测 ============================

    /**
     * 正常班次遥测：段边界写累计电量读数；相邻段共享边界只写一次（isFirst 写起点）。
     *
     * @return 该段结束时的累计原始表读数（供下一段续接）
     */
    private double insertTelemetry(long expId, Long vehicleId, long tripId, SegmentSpec spec,
                                   double meterRaw, double truthEnergy, boolean gapTrip,
                                   boolean isFirst, boolean isLast) {
        if (gapTrip) {
            return meterRaw; // 缺测班次由 insertGapWindow 统一写 NULL 窗口与恢复读数
        }
        double factor = spec.start().isBefore(DEVICE_SWAP.atStartOfDay()) ? 1.05 : 1.02;
        String device = spec.start().isBefore(DEVICE_SWAP.atStartOfDay()) ? DEVICE_A : DEVICE_B;
        if (isFirst) {
            writeCanPoint(expId, vehicleId, tripId, spec.start(), device, meterRaw);
        }
        meterRaw += truthEnergy / factor;
        writeCanPoint(expId, vehicleId, tripId, spec.end(), device, meterRaw);
        return meterRaw;
    }

    private void writeCanPoint(long expId, Long vehicleId, long tripId, LocalDateTime at,
                               String device, Double reading) {
        jdbc.update("""
                INSERT INTO can_telemetry(experiment_id,vehicle_id,trip_id,observed_at,device_code,
                    metric,reading,sensor_gap,gap_reason)
                VALUES (?,?,?,?,?, 'BATTERY_KWH', ?, ?, ?)
                """, expId, vehicleId, tripId, Timestamp.valueOf(at), device,
                reading, reading == null, reading == null ? "DEVICE_FAULT" : null);
    }

    /**
     * 连续 2 小时电量缺测：覆盖该班次全部区段的 5 分钟网格读数全部为 NULL（不是 0）；
     * 窗口后第一个有效读数恢复，表在缺测期间的后台累计真值被续接到恢复点。
     */
    private void insertGapWindow(long expId, Long vehicleId, long tripId, List<SegmentSpec> specs,
                                 double tripStartRaw) {
        LocalDateTime firstStart = specs.get(0).start();
        String device = firstStart.isBefore(DEVICE_SWAP.atStartOfDay()) ? DEVICE_A : DEVICE_B;
        LocalDateTime gapStart = firstStart.minusMinutes(5);
        LocalDateTime gapEnd = gapStart.plusHours(2);
        double factor = firstStart.isBefore(DEVICE_SWAP.atStartOfDay()) ? 1.05 : 1.02;

        double tripTruthEnergy = specs.stream().mapToDouble(this::truthEnergy).sum();
        // 缺测窗口内全部读数 NULL（不是 0）
        for (LocalDateTime t = gapStart; !t.isAfter(gapEnd); t = t.plusMinutes(5)) {
            writeCanPoint(expId, vehicleId, tripId, t, device, null);
        }
        // 恢复点：表在缺测期间仍后台累计，从班次起始读数续接真值
        double recoveryRaw = Math.round((tripStartRaw + tripTruthEnergy / factor) * 1000.0) / 1000.0;
        writeCanPoint(expId, vehicleId, tripId, gapEnd.plusMinutes(5), device, recoveryRaw);
    }

    /**
     * 区段“真实”能耗：同车曲线对生成的 AVL 梯形积分，叠加确定性 ±2% 测量噪声。
     * 遥测路线积分该真值，重建路线回到无噪声曲线，二者差异即模型/参数误差来源。
     */
    private double truthEnergy(SegmentSpec spec) {
        EnergyAttributionEngine.CurveParams curve =
                new EnergyAttributionEngine.CurveParams(ALPHA, B1, B2, B3, GAMMA);
        int seconds = spec.durationSeconds();
        int points = seconds / 60;
        double energy = 0;
        double prevSpeed = speedProfile(spec.type(), 0);
        Double prevGrade = spec.gradeUnobserved() ? 0.0
                : (spec.outsideCalibration() && 0 == points / 2 ? GRADE_DOMAIN_MAX : gradeCycle(0));
        for (int k = 1; k <= points; k++) {
            double speed = speedProfile(spec.type(), k);
            Double grade;
            if (spec.gradeUnobserved()) {
                grade = 0.0; // 数据生成侧仍有物理真值，但观测侧为 NULL（重建时不可见）
            } else if (spec.outsideCalibration() && k == points / 2) {
                grade = 5.8;
            } else {
                grade = gradeCycle(k);
            }
            double v = 0.5 * (prevSpeed + speed);
            double g = 0.5 * (prevGrade + grade);
            energy += EnergyAttributionEngine.powerKw(curve, v, g) * (60.0 / 3600.0);
            prevSpeed = speed;
            prevGrade = grade;
        }
        double noise = 1.0 + (Deterministic.uniform01("ENERGY-NOISE", spec.seedSalt() + "") - 0.5) * 0.04;
        return Math.round(energy * noise * 1000.0) / 1000.0;
    }
}
