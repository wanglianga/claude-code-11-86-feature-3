package com.ccdev.transit.service;

import com.ccdev.transit.engine.EnergyAttributionEngine;
import com.ccdev.transit.engine.Stats;
import com.ccdev.transit.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能耗归因离线重算：从车辆设备沿革、CAN 遥测、AVL 轨迹与分段乘客公里等冻结数据，
 * 用两条路线（遥测积分 / 速度—坡度曲线重建）重算分段能耗与试点级
 * “每乘客公里能耗” ITT 效应。可重复执行，不依赖任何外部状态。
 *
 * <p>核心纪律：
 * <ul>
 *   <li>设备故障/缺测段绝不当零消耗：遥测路线剔除并收缩可估范围；</li>
 *   <li>重建路线仅在同车校准支撑域内补全，参数协方差必须传播；</li>
 *   <li>坡度未观测或超出校准域 => 报告不可外推，不用全市平均替代；</li>
 *   <li>随机分配只代表处理资格，ITT 按资格臂比较，不按实际执行者分组。</li>
 * </ul>
 */
@Service
public class EnergyService {

    private static final Logger log = LoggerFactory.getLogger(EnergyService.class);

    public static final String TELEMETRY = "TELEMETRY";
    public static final String RECONSTRUCTED = "RECONSTRUCTED";
    public static final String OBSERVED_SUPPORT = "OBSERVED_SUPPORT";
    public static final String ALL_SEGMENTS = "ALL_SEGMENTS";

    private final JdbcTemplate jdbc;

    public EnergyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> rebuildEnergy(long expId) {
        jdbc.update("DELETE FROM segment_energy WHERE experiment_id=?", expId);
        jdbc.update("DELETE FROM energy_estimate WHERE experiment_id=?", expId);
        jdbc.update("DELETE FROM energy_coverage_gap WHERE experiment_id=?", expId);

        Map<Long, Map<String, Object>> vehicles = loadVehicles(expId);
        Map<Long, List<EnergyAttributionEngine.DeviceEra>> erasByVehicle = loadDeviceEras(expId);
        Map<Long, CurveBundle> curves = loadCurves(expId);
        Map<Long, List<EnergyAttributionEngine.TelemetryPoint>> telemetryByVehicle = loadTelemetry(expId);
        Map<Long, List<EnergyAttributionEngine.AvlSample>> avlByVehicle = loadAvl(expId);
        List<Map<String, Object>> segments = jdbc.queryForList("""
                SELECT * FROM energy_segment WHERE experiment_id=?
                ORDER BY vehicle_id, start_ts, seg_index
                """, expId);
        Map<Long, Double> pkmBySegment = new LinkedHashMap<>();
        Map<Long, Integer> paxBySegment = new LinkedHashMap<>();
        Map<Long, String> pkmSource = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT segment_id,passenger_km,passenger_count,source
                FROM segment_passenger_km WHERE experiment_id=?
                """, expId)
                .forEach(r -> {
                    long sid = ((Number) r.get("segment_id")).longValue();
                    pkmBySegment.put(sid, ((Number) r.get("passenger_km")).doubleValue());
                    paxBySegment.put(sid, ((Number) r.get("passenger_count")).intValue());
                    pkmSource.put(sid, String.valueOf(r.get("source")));
                });
        // 每个班次首段起点：共享时间戳归前一段终点，后续段 AVL 用半开区间 (start, end]
        Map<Long, Instant> tripFirstStart = new LinkedHashMap<>();
        for (Map<String, Object> seg : segments) {
            long tripId = ((Number) seg.get("trip_id")).longValue();
            Instant st = ((Timestamp) seg.get("start_ts")).toInstant();
            tripFirstStart.merge(tripId, st, (a, b) -> a.isBefore(b) ? a : b);
        }

        int gapCount = 0;
        // segId -> 该段两条路线的可估计性
        Map<Long, Boolean> telemetryOk = new LinkedHashMap<>();
        Map<Long, Boolean> reconstructedOk = new LinkedHashMap<>();

        for (Map<String, Object> seg : segments) {
            long segId = ((Number) seg.get("id")).longValue();
            long vehicleId = ((Number) seg.get("vehicle_id")).longValue();
            Instant start = ((Timestamp) seg.get("start_ts")).toInstant();
            Instant end = ((Timestamp) seg.get("end_ts")).toInstant();
            String segCode = String.valueOf(seg.get("segment_code"));
            String vehicleCode = String.valueOf(vehicles.get(vehicleId).get("vehicle_code"));
            long duration = ((Number) seg.get("duration_seconds")).longValue();

            // ---------- 路线一：CAN 遥测积分 ----------
            EnergyAttributionEngine.TelemetryIntegration ti = EnergyAttributionEngine.integrateTelemetry(
                    telemetryByVehicle.getOrDefault(vehicleId, List.of()),
                    erasByVehicle.getOrDefault(vehicleId, List.of()), start, end);
            double observedShare = duration == 0 ? 0
                    : Math.min(1.0, ti.observedSeconds() / (double) duration);
            if (ti.fullyObserved() && ti.energyKwh() != null) {
                telemetryOk.put(segId, true);
                insertSegmentEnergy(segId, expId, TELEMETRY, ti.energyKwh(), ti.seKwh(),
                        observedShare, 0, null, false, false,
                        Map.of("integration", "按设备沿革校准的相邻有效 CAN 读数梯形积分",
                                "calibrationPropagated", "设备校准相对标准误已按能量加权传播",
                                "observedSeconds", ti.observedSeconds()));
            } else {
                telemetryOk.put(segId, false);
                long gapSec = ti.gapSeconds() > 0 ? ti.gapSeconds() : duration;
                insertSegmentEnergy(segId, expId, TELEMETRY, null, null,
                        observedShare, gapSec, "DEVICE_FAULT", false, false,
                        Map.of("reason", "传感器缺测/故障或无校准有效期：设备故障段不可当零消耗，整段剔除",
                                "gapWindows", ti.gaps().stream()
                                        .map(g -> Map.of("from", g.start().toString(), "to", g.end().toString(),
                                                "reason", g.reason(), "seconds", g.seconds()))
                                        .toList()));
                jdbc.update("""
                        INSERT INTO energy_coverage_gap(experiment_id,vehicle_code,segment_code,gap_kind,
                            route_method,seconds_or_grade,handling,detail)
                        VALUES (?,?,?, 'SENSOR_GAP', 'TELEMETRY', ?, 'DROPPED_FROM_TELEMETRY', ?)
                        """, expId, vehicleCode, segCode, (double) gapSec,
                        "遥测路线可估范围收缩：该段不参与 TELEMETRY 口径，能量绝不按 0 计");
                gapCount++;
            }

            // ---------- 路线二：同车速度—坡度曲线重建 ----------
            CurveBundle curve = curves.get(vehicleId);
            long segStartSec = start.getEpochSecond();
            long segEndSec = end.getEpochSecond();
            // 共享边界点属于前一段终点：非首段用 (start, end]，避免把邻段（如 NULL 坡度）误纳入
            long lowerSec = tripFirstStart.get(((Number) seg.get("trip_id")).longValue()).equals(start)
                    ? segStartSec : segStartSec + 1;
            List<EnergyAttributionEngine.AvlSample> avlIn = avlByVehicle
                    .getOrDefault(vehicleId, List.of()).stream()
                    .filter(a -> a.epochSeconds() >= lowerSec && a.epochSeconds() <= segEndSec)
                    .toList();
            if (curve == null) {
                reconstructedOk.put(segId, false);
                insertSegmentEnergy(segId, expId, RECONSTRUCTED, null, null, 0, duration,
                        "NO_CURVE", false, false, Map.of("reason", "车辆缺少同车校准曲线，无法重建"));
                continue;
            }
            double calRelSe = currentCalibrationSe(erasByVehicle.getOrDefault(vehicleId, List.of()), start);
            EnergyAttributionEngine.Reconstruction rc = EnergyAttributionEngine.reconstruct(
                    avlIn, curve.params(), curve.covariance(),
                    curve.gradeMin(), curve.gradeMax(), curve.speedMax(), calRelSe);
            if (rc.estimable()) {
                reconstructedOk.put(segId, true);
                boolean extrapolated = ti.energyKwh() == null; // 仅在遥测缺测时真正发挥补全作用
                // 缺测重建段无遥测锚点：叠加曲线校准 R² 对应的模型残差不确定性
                double finalSe = rc.seKwh();
                if (extrapolated) {
                    finalSe = EnergyAttributionEngine.withModelResidual(
                            rc.energyKwh(), rc.seKwh(), curve.calibratedR2());
                }
                insertSegmentEnergy(segId, expId, RECONSTRUCTED, rc.energyKwh(), finalSe,
                        1.0, ti.gapSeconds(), ti.gapSeconds() > 0 ? "DEVICE_FAULT" : null,
                        extrapolated, false,
                        Map.of("integration", "同车校准 P=α+β1v+β2v²+β3v³+γvg 对 AVL 积分",
                                "errorPropagation", extrapolated
                                        ? rc.reason() + "；缺测重建叠加模型残差 √(1−R²)·E（R²="
                                            + curve.calibratedR2() + "）"
                                        : rc.reason(),
                                "avlPoints", avlIn.size(),
                                "calibrationDomain", Map.of("gradeMin", curve.gradeMin(),
                                        "gradeMax", curve.gradeMax(), "speedMax", curve.speedMax())));
                if (extrapolated) {
                    jdbc.update("""
                            INSERT INTO energy_coverage_gap(experiment_id,vehicle_code,segment_code,gap_kind,
                                route_method,seconds_or_grade,handling,detail)
                            VALUES (?,?,?, 'SENSOR_GAP', 'RECONSTRUCTED', ?, 'RECONSTRUCTED', ?)
                            """, expId, vehicleCode, segCode, (double) ti.gapSeconds(),
                            "遥测缺测段由同车速度—坡度曲线重建，参数误差已传播，区间相应变宽");
                    gapCount++;
                }
            } else {
                reconstructedOk.put(segId, false);
                String kind = rc.gradeUnobserved() ? "GRADE_UNOBSERVED"
                        : rc.outsideCalibration() ? "OUTSIDE_CALIBRATION" : "AVL_MISSING";
                jdbc.update("""
                        INSERT INTO energy_coverage_gap(experiment_id,vehicle_code,segment_code,gap_kind,
                            route_method,seconds_or_grade,handling,detail)
                        VALUES (?,?,?, ?, 'RECONSTRUCTED', NULL, 'NOT_EXTRAPOLATED', ?)
                        """, expId, vehicleCode, segCode, kind, rc.reason());
                insertSegmentEnergy(segId, expId, RECONSTRUCTED, null, null, 0, duration,
                        kind, false, rc.outsideCalibration(),
                        Map.of("reason", rc.reason(), "policy", "报告不可外推，不用全市平均替代"));
                gapCount++;
            }
        }

        // ---------- 随机化单元（班次）聚合与效应估计 ----------
        int estimateRows = 0;
        estimateRows += estimateRoute(expId, segments, pkmBySegment, paxBySegment, pkmSource,
                telemetryOk, TELEMETRY, OBSERVED_SUPPORT);
        estimateRows += estimateRoute(expId, segments, pkmBySegment, paxBySegment, pkmSource,
                reconstructedOk, RECONSTRUCTED, ALL_SEGMENTS);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("segments", segments.size());
        summary.put("coverageGaps", gapCount);
        summary.put("estimateRows", estimateRows);
        summary.put("vehicles", vehicles.size());
        log.info("实验 {} 能耗归因重算完成：分段 {}，覆盖缺口 {}，估计行 {}",
                expId, segments.size(), gapCount, estimateRows);
        return summary;
    }

    // ============================ 单元聚合与估计 ============================

    private int estimateRoute(long expId, List<Map<String, Object>> segments,
                              Map<Long, Double> pkmBySegment, Map<Long, Integer> paxBySegment,
                              Map<Long, String> pkmSource, Map<Long, Boolean> methodOk,
                              String method, String scope) {
        // unitKey -> 聚合器
        Map<String, UnitAgg> units = new LinkedHashMap<>();
        long totalSeconds = 0, estimableSeconds = 0;
        int totalSegments = segments.size();

        for (Map<String, Object> seg : segments) {
            long segId = ((Number) seg.get("id")).longValue();
            String unitKey = String.valueOf(seg.get("eligibility_unit_key"));
            String arm = String.valueOf(seg.get("assigned_arm"));
            long dur = ((Number) seg.get("duration_seconds")).longValue();
            totalSeconds += dur;
            UnitAgg agg = units.computeIfAbsent(unitKey, k -> new UnitAgg(k, arm));
            agg.totalSeconds += dur;
            double pkm = pkmBySegment.getOrDefault(segId, 0.0);
            agg.passengerKm += pkm;
            agg.passengerCount += paxBySegment.getOrDefault(segId, 0);
            if (Boolean.TRUE.equals(methodOk.get(segId))) {
                Map<String, Object> energyRow = jdbc.queryForMap(
                        "SELECT energy_kwh,energy_se_kwh FROM segment_energy WHERE segment_id=? AND route_method=?",
                        segId, method);
                double e = ((Number) energyRow.get("energy_kwh")).doubleValue();
                double se = energyRow.get("energy_se_kwh") == null ? 0
                        : ((Number) energyRow.get("energy_se_kwh")).doubleValue();
                agg.energy += e;
                agg.energyVar += se * se;
                agg.estimableSeconds += dur;
            } else {
                agg.allSegmentsEstimable = false;
                agg.droppedSegments.add(String.valueOf(seg.get("segment_code")));
            }
            if (!"ZERO".equals(pkmSource.get(segId))) {
                agg.pkmSources.add(pkmSource.getOrDefault(segId, "SMARTCARD"));
            }
        }

        List<EnergyAttributionEngine.UnitRate> rates = new ArrayList<>();
        List<Map<String, Object>> unitDetails = new ArrayList<>();
        int dropped = 0;
        for (UnitAgg agg : units.values()) {
            boolean estimable = agg.allSegmentsEstimable && agg.passengerKm > 0;
            if (estimable) {
                estimableSeconds += agg.estimableSeconds;
            } else {
                dropped++;
            }
            rates.add(new EnergyAttributionEngine.UnitRate(agg.unitKey, agg.arm,
                    agg.energy, agg.passengerKm, estimable,
                    estimable ? "" : "含不可估计段或乘客公里为 0，整体剔除（覆盖范围收缩）"));
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("unitKey", agg.unitKey);
            d.put("assignedArm", agg.arm);
            d.put("estimable", estimable);
            d.put("energyKwh", round4(agg.energy));
            d.put("passengerKm", round3(agg.passengerKm));
            d.put("energyPerPkm", agg.passengerKm > 0 ? round4(agg.energy / agg.passengerKm) : null);
            d.put("passengerCount", agg.passengerCount);
            d.put("pkmSources", agg.pkmSources.stream().distinct().sorted().toList());
            d.put("droppedSegments", agg.droppedSegments);
            unitDetails.add(d);
        }

        EnergyAttributionEngine.RateEstimate rate = EnergyAttributionEngine.estimateItteRate(rates);
        double coverage = totalSeconds == 0 ? 0 : estimableSeconds / (double) totalSeconds;
        int rows = 0;

        // 臂级比率（总量口径：Σ能量/Σ乘客公里）
        rows += insertArmRate(expId, method, scope, "TREATMENT", rate.treatmentRate(),
                rate.treatmentEnergy(), rate.treatmentPkm(), rate, coverage, unitDetails, totalSegments);
        rows += insertArmRate(expId, method, scope, "CONTROL", rate.controlRate(),
                rate.controlEnergy(), rate.controlPkm(), rate, coverage, unitDetails, totalSegments);

        // ITT：乘客公里加权 Hajek + CR1 随机化单元聚类区间
        Stats.EffectEstimate fit = rate.effect();
        Map<String, Object> ittDetail = baseDetail(method, scope, coverage, rate, unitDetails);
        ittDetail.put("estimator", "乘客公里加权 Hajek 均值差，CR1 按随机化单元聚类");
        ittDetail.put("interpretation", "ITT 按预注册随机分配的处理资格分组，与司机是否实际执行无关");
        jdbc.update("""
                INSERT INTO energy_estimate(experiment_id,route_method,scope,arm,estimator_code,
                    energy_per_pkm,se,ci_low,ci_high,p_value,total_energy_kwh,total_passenger_km,
                    n_segments,n_units,coverage_share,detail)
                VALUES (?,?,?,NULL,'ITT_RATE',?,?,?,?,?,NULL,NULL,?,?,?,?::jsonb)
                """, expId, method, scope,
                round6(fit.estimate()), round6(fit.se()), round6(fit.ciLow()), round6(fit.ciHigh()),
                round6(fit.pValue()), totalSegments, fit.groups(), round4(coverage), Json.write(ittDetail));
        rows++;

        // 精确随机化推断（枚举全部平衡分配）
        try {
            EnergyAttributionEngine.RandomizationResult rr =
                    EnergyAttributionEngine.randomizationInference(rates);
            Map<String, Object> riDetail = baseDetail(method, scope, coverage, rate, unitDetails);
            riDetail.put("estimator", "精确随机化推断：枚举全部 C(n,t) 种平衡分配，plus-one p=(1+#>=obs)/(M+1)");
            riDetail.put("permutations", rr.permutations());
            riDetail.put("treatedUnits", rr.treatedCount());
            riDetail.put("intervalMethod", "Hodges–Lehmann 处理×对照单元率差 2.5%/97.5% 分位");
            jdbc.update("""
                    INSERT INTO energy_estimate(experiment_id,route_method,scope,arm,estimator_code,
                        energy_per_pkm,se,ci_low,ci_high,p_value,total_energy_kwh,total_passenger_km,
                        n_segments,n_units,coverage_share,detail)
                    VALUES (?,?,?,NULL,'RI_RATE',?,NULL,?,?,?,NULL,NULL,?,?,?,?::jsonb)
                    """, expId, method, scope,
                    round6(rr.hlEstimate()), round6(rr.ciLow()), round6(rr.ciHigh()),
                    round6(rr.pValue()), totalSegments, fit.groups(), round4(coverage), Json.write(riDetail));
            rows++;
        } catch (IllegalStateException e) {
            log.warn("{} 路线随机化推断跳过：{}", method, e.getMessage());
        }
        return rows;
    }

    private int insertArmRate(long expId, String method, String scope, String arm, double armRate,
                              double energy, double pkm, EnergyAttributionEngine.RateEstimate rate,
                              double coverage, List<Map<String, Object>> unitDetails, int totalSegments) {
        Map<String, Object> detail = baseDetail(method, scope, coverage, rate, unitDetails);
        detail.put("estimator", "臂级总量比率 Σ能耗/Σ乘客公里（描述口径，非因果差）");
        jdbc.update("""
                INSERT INTO energy_estimate(experiment_id,route_method,scope,arm,estimator_code,
                    energy_per_pkm,se,ci_low,ci_high,p_value,total_energy_kwh,total_passenger_km,
                    n_segments,n_units,coverage_share,detail)
                VALUES (?,?,?,?, 'ARM_RATE', ?, NULL,NULL,NULL, NULL, ?,?, ?,?,?,?::jsonb)
                """, expId, method, scope, arm, round6(armRate),
                round4(energy), round3(pkm), totalSegments,
                rate.estimableUnits() + rate.droppedUnits(), round4(coverage), Json.write(detail));
        return 1;
    }

    private Map<String, Object> baseDetail(String method, String scope, double coverage,
                                           EnergyAttributionEngine.RateEstimate rate,
                                           List<Map<String, Object>> unitDetails) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("routeMethod", method);
        d.put("scope", scope);
        d.put("coverageShare", round4(coverage));
        d.put("estimableUnits", rate.estimableUnits());
        d.put("droppedUnits", rate.droppedUnits());
        d.put("treatmentRateKwhPerPkm", round6(rate.treatmentRate()));
        d.put("controlRateKwhPerPkm", round6(rate.controlRate()));
        d.put("unitBreakdown", unitDetails);
        d.put("denominatorRule", "乘客公里只计 REVENUE 载客段（刷卡OD/满载计数）；"
                + "DEADHEAD/IDLE/SHORT_TURN 段乘客公里恒为 0，其能耗仍在分子中");
        return d;
    }

    // ============================ 数据装载 ============================

    private Map<Long, Map<String, Object>> loadVehicles(long expId) {
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        jdbc.queryForList("SELECT * FROM vehicle WHERE experiment_id=?", expId)
                .forEach(r -> out.put(((Number) r.get("id")).longValue(), r));
        return out;
    }

    private Map<Long, List<EnergyAttributionEngine.DeviceEra>> loadDeviceEras(long expId) {
        Map<Long, List<EnergyAttributionEngine.DeviceEra>> out = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT vehicle_id,valid_from,valid_to,calibration_factor,calibration_offset,calibration_se
                FROM vehicle_device_history WHERE experiment_id=? ORDER BY vehicle_id,valid_from
                """, expId).forEach(r -> {
            long vid = ((Number) r.get("vehicle_id")).longValue();
            Instant to = r.get("valid_to") == null ? null : ((Timestamp) r.get("valid_to")).toInstant();
            out.computeIfAbsent(vid, k -> new ArrayList<>()).add(new EnergyAttributionEngine.DeviceEra(
                    String.valueOf(r.get("device_code")),
                    ((Timestamp) r.get("valid_from")).toInstant(), to,
                    ((Number) r.get("calibration_factor")).doubleValue(),
                    ((Number) r.get("calibration_offset")).doubleValue(),
                    ((Number) r.get("calibration_se")).doubleValue()));
        });
        return out;
    }

    private record CurveBundle(EnergyAttributionEngine.CurveParams params, double[][] covariance,
                               double gradeMin, double gradeMax, double speedMax, double calibratedR2) {
    }

    private Map<Long, CurveBundle> loadCurves(long expId) {
        Map<Long, CurveBundle> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT * FROM vehicle_energy_curve WHERE experiment_id=?", expId)) {
            long vid = ((Number) r.get("vehicle_id")).longValue();
            EnergyAttributionEngine.CurveParams p = new EnergyAttributionEngine.CurveParams(
                    ((Number) r.get("alpha")).doubleValue(),
                    ((Number) r.get("beta1")).doubleValue(),
                    ((Number) r.get("beta2")).doubleValue(),
                    ((Number) r.get("beta3")).doubleValue(),
                    ((Number) r.get("gamma")).doubleValue());
            @SuppressWarnings("unchecked")
            List<List<Double>> covList = Json.read(String.valueOf(r.get("param_covariance")), List.class);
            double[][] cov = new double[covList.size()][];
            for (int i = 0; i < covList.size(); i++) {
                cov[i] = covList.get(i).stream().mapToDouble(Double::doubleValue).toArray();
            }
            out.put(vid, new CurveBundle(p, cov,
                    ((Number) r.get("grade_min_observed")).doubleValue(),
                    ((Number) r.get("grade_max_observed")).doubleValue(),
                    ((Number) r.get("speed_max_observed")).doubleValue(),
                    r.get("calibrated_r2") == null ? 0.9 : ((Number) r.get("calibrated_r2")).doubleValue()));
        }
        return out;
    }

    private Map<Long, List<EnergyAttributionEngine.TelemetryPoint>> loadTelemetry(long expId) {
        Map<Long, List<EnergyAttributionEngine.TelemetryPoint>> out = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT vehicle_id,observed_at,reading,sensor_gap FROM can_telemetry
                WHERE experiment_id=? ORDER BY vehicle_id,observed_at
                """, expId).forEach(r -> {
            long vid = ((Number) r.get("vehicle_id")).longValue();
            out.computeIfAbsent(vid, k -> new ArrayList<>()).add(new EnergyAttributionEngine.TelemetryPoint(
                    ((Timestamp) r.get("observed_at")).toInstant(),
                    r.get("reading") == null ? null : ((Number) r.get("reading")).doubleValue(),
                    Boolean.TRUE.equals(r.get("sensor_gap"))));
        });
        return out;
    }

    private Map<Long, List<EnergyAttributionEngine.AvlSample>> loadAvl(long expId) {
        Map<Long, List<EnergyAttributionEngine.AvlSample>> out = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT vehicle_id,observed_at,speed_kmh,grade_percent FROM avl_point
                WHERE experiment_id=? ORDER BY vehicle_id,observed_at
                """, expId).forEach(r -> {
            long vid = ((Number) r.get("vehicle_id")).longValue();
            Double grade = r.get("grade_percent") == null ? null
                    : ((Number) r.get("grade_percent")).doubleValue();
            out.computeIfAbsent(vid, k -> new ArrayList<>()).add(new EnergyAttributionEngine.AvlSample(
                    ((Timestamp) r.get("observed_at")).toInstant().getEpochSecond(),
                    ((Number) r.get("speed_kmh")).doubleValue(), grade));
        });
        return out;
    }

    private double currentCalibrationSe(List<EnergyAttributionEngine.DeviceEra> eras, Instant at) {
        return eras.stream().filter(e -> e.covers(at)).mapToDouble(EnergyAttributionEngine.DeviceEra::calibrationRelSe)
                .findFirst().orElse(0.05);
    }

    private void insertSegmentEnergy(long segId, long expId, String method,
                                     Double energy, Double se, double observedShare,
                                     long gapSeconds, String gapReason,
                                     boolean extrapolated, boolean outsideCalibration,
                                     Map<String, Object> detail) {
        jdbc.update("""
                INSERT INTO segment_energy(segment_id,experiment_id,route_method,energy_kwh,energy_se_kwh,
                    observed_share,gap_seconds,gap_reason,extrapolated,outside_calibration,estimation_detail)
                VALUES (?,?,?,?,?,?,?,?,?,?,?::jsonb)
                """, segId, expId, method, energy, se, round4(observedShare), gapSeconds, gapReason,
                extrapolated, outsideCalibration, Json.write(detail));
    }

    private static double round3(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? v : Math.round(v * 1000.0) / 1000.0;
    }

    private static double round4(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? v : Math.round(v * 10000.0) / 10000.0;
    }

    private static double round6(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? v : Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    private static final class UnitAgg {
        final String unitKey;
        final String arm;
        double energy;
        double energyVar;
        double passengerKm;
        int passengerCount;
        long totalSeconds;
        long estimableSeconds;
        boolean allSegmentsEstimable = true;
        final List<String> droppedSegments = new ArrayList<>();
        final List<String> pkmSources = new ArrayList<>();

        UnitAgg(String unitKey, String arm) {
            this.unitKey = unitKey;
            this.arm = arm;
        }
    }
}
