package com.ccdev.transit.web;

import com.ccdev.transit.service.EnergyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车辆能耗归因数据集与“每乘客公里能耗”试点效应接口。
 * 所有结果由冻结的设备沿革、CAN/AVL 遥测与随机分配离线重算，可重复执行。
 */
@RestController
@RequestMapping("/api/experiments/{id}")
@Tag(name = "车辆能耗归因", description = "CAN 校准、AVL 区段、乘客公里与两条能耗估计路线")
public class EnergyController {

    private final JdbcTemplate jdbc;
    private final EnergyService energyService;

    public EnergyController(JdbcTemplate jdbc, EnergyService energyService) {
        this.jdbc = jdbc;
        this.energyService = energyService;
    }

    @PostMapping("/energy/rebuild")
    @Operation(summary = "用冻结数据重算分段能耗、覆盖缺口与每乘客公里能耗 ITT/RI 估计")
    public Map<String, Object> rebuild(@PathVariable long id) {
        return energyService.rebuildEnergy(id);
    }

    @GetMapping("/energy/overview")
    @Operation(summary = "车辆台账、设备沿革、同车校准曲线与覆盖概览")
    public Map<String, Object> overview(@PathVariable long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vehicles", jdbc.queryForList("""
                SELECT v.vehicle_code,v.vehicle_type,v.drivetrain_note,
                       (SELECT count(*) FROM energy_segment s WHERE s.vehicle_id=v.id) AS segments
                FROM vehicle v WHERE v.experiment_id=? ORDER BY v.vehicle_code
                """, id));
        m.put("deviceHistory", jdbc.queryForList("""
                SELECT v.vehicle_code,h.device_code,h.device_kind,h.valid_from,h.valid_to,
                       h.calibration_factor,h.calibration_offset,h.calibration_se,h.calibration_n,
                       h.calibration_note
                FROM vehicle_device_history h JOIN vehicle v ON v.id=h.vehicle_id
                WHERE h.experiment_id=? ORDER BY v.vehicle_code,h.valid_from
                """, id));
        m.put("curves", jdbc.queryForList("""
                SELECT v.vehicle_code,c.alpha,c.beta1,c.beta2,c.beta3,c.gamma,
                       c.grade_min_observed,c.grade_max_observed,c.speed_max_observed,
                       c.calibrated_n,c.calibrated_r2,c.param_covariance::text AS param_covariance
                FROM vehicle_energy_curve c JOIN vehicle v ON v.id=c.vehicle_id
                WHERE c.experiment_id=?
                """, id));
        List<Map<String, Object>> coverageRows = jdbc.queryForList("""
                SELECT route_method,
                       count(*) AS segments,
                       count(*) FILTER (WHERE energy_kwh IS NOT NULL) AS estimable,
                       count(*) FILTER (WHERE energy_kwh IS NULL) AS not_estimable,
                       coalesce(avg(observed_share) FILTER (WHERE energy_kwh IS NOT NULL),0) AS avg_observed_share,
                       coalesce(sum(gap_seconds),0) AS gap_seconds
                FROM segment_energy WHERE experiment_id=?
                GROUP BY route_method ORDER BY route_method
                """, id);
        m.put("coverage", coverageRows);
        m.put("gapCount", jdbc.queryForObject(
                "SELECT count(*) FROM energy_coverage_gap WHERE experiment_id=?", Long.class, id));
        m.put("policy", List.of(
                "CAN 读数按车辆设备沿革逐设备校准，换表后不混用校准常数",
                "设备故障/缺测段绝不当零消耗：遥测路线剔除并收缩可估范围",
                "缺测段仅在同车速度—坡度曲线的校准支撑域内重建，参数协方差必须传播",
                "坡度未观测或超出校准域即报告不可外推，不用全市平均替代",
                "随机分配只关联处理资格，ITT 按资格臂比较，不按实际执行者分组"));
        return m;
    }

    @GetMapping("/energy/segments")
    @Operation(summary = "AVL 区段台账：区段类型、分配资格、两条路线能耗与乘客公里")
    public Map<String, Object> segments(@PathVariable long id,
                                        @RequestParam(required = false) String segmentType,
                                        @RequestParam(required = false) String vehicleCode) {
        StringBuilder where = new StringBuilder(" WHERE s.experiment_id=? ");
        List<Object> args = new java.util.ArrayList<>();
        args.add(id);
        if (segmentType != null && !segmentType.isBlank() && !"ALL".equals(segmentType)) {
            where.append(" AND s.segment_type=? ");
            args.add(segmentType);
        }
        if (vehicleCode != null && !vehicleCode.isBlank()) {
            where.append(" AND v.vehicle_code=? ");
            args.add(vehicleCode);
        }
        args.add(500);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.segment_code,v.vehicle_code,t.trip_code,s.segment_type,
                       s.start_ts,s.end_ts,s.distance_km,s.duration_seconds,s.avg_speed_kmh,
                       s.avg_grade_percent,s.grade_observed,s.assigned_arm,s.eligibility_unit_key,
                       s.rule_executed,s.note,
                       p.passenger_km,p.passenger_count,p.source AS pkm_source,
                       te.energy_kwh AS telemetry_kwh,te.energy_se_kwh AS telemetry_se,
                       te.observed_share AS telemetry_observed_share,te.gap_seconds AS telemetry_gap_s,
                       te.gap_reason AS telemetry_gap_reason,
                       re.energy_kwh AS reconstructed_kwh,re.energy_se_kwh AS reconstructed_se,
                       re.extrapolated AS reconstructed_extrapolated,
                       re.outside_calibration AS reconstructed_outside,
                       re.gap_reason AS reconstructed_gap_reason
                FROM energy_segment s
                JOIN vehicle v ON v.id=s.vehicle_id
                JOIN service_trip t ON t.id=s.trip_id
                LEFT JOIN segment_passenger_km p ON p.segment_id=s.id
                LEFT JOIN segment_energy te ON te.segment_id=s.id AND te.route_method='TELEMETRY'
                LEFT JOIN segment_energy re ON re.segment_id=s.id AND re.route_method='RECONSTRUCTED'
                """ + where + " ORDER BY s.start_ts,s.seg_index LIMIT ?", args.toArray());

        List<Map<String, Object>> typeSummary = jdbc.queryForList("""
                SELECT s.segment_type, count(*) n,
                       coalesce(sum(p.passenger_km),0) AS passenger_km,
                       coalesce(sum(p.passenger_count) FILTER (WHERE s.segment_type='REVENUE'),0) AS revenue_pax
                FROM energy_segment s
                LEFT JOIN segment_passenger_km p ON p.segment_id=s.id
                WHERE s.experiment_id=? GROUP BY 1 ORDER BY 1
                """, id);
        return Map.of("rows", rows, "typeSummary", typeSummary);
    }

    @GetMapping("/energy/estimates")
    @Operation(summary = "每乘客公里能耗效应：两条路线的臂级比率、ITT(CR1) 与精确随机化推断")
    public Map<String, Object> estimates(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT route_method,scope,arm,estimator_code,energy_per_pkm,se,ci_low,ci_high,p_value,
                       total_energy_kwh,total_passenger_km,n_segments,n_units,coverage_share,
                       detail,created_at
                FROM energy_estimate WHERE experiment_id=?
                ORDER BY route_method,estimator_code,arm NULLS FIRST
                """, id);
        return Map.of("results", rows,
                "metricDefinition", "每乘客公里能耗 kWh/pkm = 单元全部区段能耗(含空驶/等待/折返) / "
                        + "单元载客段乘客公里；负值 ITT 表示缩短间隔降低单位乘客公里能耗");
    }

    @GetMapping("/energy/gaps")
    @Operation(summary = "传感器缺测、坡度未观测与超出校准域的覆盖缺口及处理方式")
    public Map<String, Object> gaps(@PathVariable long id) {
        return Map.of("rows", jdbc.queryForList("""
                SELECT vehicle_code,segment_code,gap_kind,route_method,seconds_or_grade,handling,detail
                FROM energy_coverage_gap WHERE experiment_id=?
                ORDER BY route_method,gap_kind,segment_code
                """, id));
    }

    @GetMapping("/energy/telemetry")
    @Operation(summary = "CAN 原始遥测（含缺测点：reading=NULL 而非 0）")
    public Map<String, Object> telemetry(@PathVariable long id,
                                         @RequestParam(defaultValue = "V-EV-01") String vehicleCode) {
        return Map.of("rows", jdbc.queryForList("""
                SELECT c.observed_at,c.device_code,c.metric,c.reading,c.sensor_gap,c.gap_reason,
                       t.trip_code
                FROM can_telemetry c
                JOIN vehicle v ON v.id=c.vehicle_id
                LEFT JOIN service_trip t ON t.id=c.trip_id
                WHERE c.experiment_id=? AND v.vehicle_code=?
                ORDER BY c.observed_at
                """, id, vehicleCode),
                "rule", "sensor_gap=TRUE 的读数 reading 为 NULL：设备故障段不得当零消耗");
    }

    @GetMapping("/energy/sample-check")
    @Operation(summary = "核验样例：6+6 班次、分母规则、2 小时缺测、随机化区间与不可外推")
    public Map<String, Object> sampleCheck(@PathVariable long id) {
        Map<String, Object> m = new LinkedHashMap<>();

        List<Map<String, Object>> tripArms = jdbc.queryForList("""
                SELECT s.assigned_arm, count(DISTINCT s.trip_id) AS trips
                FROM energy_segment s WHERE s.experiment_id=?
                GROUP BY 1 ORDER BY 1
                """, id);
        m.put("tripsByAssignedArm", tripArms);

        m.put("denominatorCheck", jdbc.queryForList("""
                SELECT segment_type,
                       count(*) AS segments,
                       coalesce(sum(passenger_km),0) AS passenger_km,
                       bool_and(passenger_km=0) AS zero_when_non_revenue
                FROM energy_segment s
                LEFT JOIN segment_passenger_km p ON p.segment_id=s.id
                WHERE s.experiment_id=?
                GROUP BY 1 ORDER BY 1
                """, id));

        List<Map<String, Object>> gapRows = jdbc.queryForList("""
                SELECT count(DISTINCT t.trip_code) AS gap_trips,
                       count(*) FILTER (WHERE c.sensor_gap) AS gap_points,
                       max(c.observed_at) FILTER (WHERE c.sensor_gap)
                         - min(c.observed_at) FILTER (WHERE c.sensor_gap) AS gap_span
                FROM can_telemetry c
                JOIN vehicle v ON v.id=c.vehicle_id
                LEFT JOIN service_trip t ON t.id=c.trip_id
                WHERE c.experiment_id=? AND v.vehicle_code='V-EV-01' AND c.sensor_gap
                """, id);
        m.put("twoHourMeterGap", gapRows.isEmpty() ? Map.of() : gapRows.get(0));

        m.put("methodCoverage", jdbc.queryForList("""
                SELECT route_method,
                       count(*) FILTER (WHERE energy_kwh IS NOT NULL) AS estimable_segments,
                       count(*) FILTER (WHERE energy_kwh IS NULL) AS dropped_segments,
                       round(avg(observed_share)::numeric,4) AS avg_observed_share
                FROM segment_energy WHERE experiment_id=?
                GROUP BY 1 ORDER BY 1
                """, id));

        m.put("notExtrapolated", jdbc.queryForList("""
                SELECT s.segment_code,s.segment_type,e.route_method,e.gap_reason,e.outside_calibration
                FROM segment_energy e JOIN energy_segment s ON s.id=e.segment_id
                WHERE e.experiment_id=? AND e.energy_kwh IS NULL
                ORDER BY e.route_method,s.segment_code
                """, id));

        m.put("randomizationEstimates", jdbc.queryForList("""
                SELECT route_method,estimator_code,energy_per_pkm,ci_low,ci_high,p_value,
                       n_units,coverage_share
                FROM energy_estimate WHERE experiment_id=? AND estimator_code IN ('ITT_RATE','RI_RATE')
                ORDER BY route_method,estimator_code
                """, id));
        m.put("acceptance", List.of(
                "同一车辆处理/对照资格班次各 6 个，处理班含空驶折返段（乘客公里=0，能耗入分子）",
                "2 小时电量缺测：遥测路线该单元剔除且覆盖下降；重建路线补全且 SE 增大（参数误差传播）",
                "坡度未观测/超校准域段：重建路线标记 NOT_EXTRAPOLATED，不使用全市平均",
                "ITT 按预注册分配资格计算，RI 枚举全部平衡分配并给 plus-one p 值与区间"));
        return m;
    }
}
