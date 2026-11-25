package com.ccdev.transit.engine;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能耗归因引擎核验：
 * 设备沿革校准、故障段不当零消耗、曲线重建误差传播、坡度域外拒绝外推、
 * 每乘客公里能耗 ITT 与精确随机化推断。
 */
class EnergyAttributionEngineTest {

    private static final Instant T0 = Instant.parse("2026-06-10T00:00:00Z");

    private List<EnergyAttributionEngine.DeviceEra> eras() {
        // 换表点用 offset 保证校准后量值连续：DEV-B(raw20)=20+offset20=40 = DEV-A(raw20)*2
        return List.of(
                new EnergyAttributionEngine.DeviceEra("DEV-A", T0.minusSeconds(3600), T0.plusSeconds(1800),
                        2.0, 0.0, 0.03),
                new EnergyAttributionEngine.DeviceEra("DEV-B", T0.plusSeconds(1800), null,
                        1.0, 20.0, 0.02));
    }

    @Test
    void telemetryFullyObservedIntegratesWithDeviceCalibration() {
        // 0~30min 用 DEV-A(factor 2)：raw 10->20 = 20kWh；30~60min 用 DEV-B(factor 1,offset 20)：
        // 校准值 40->50 = 10kWh；合计 30kWh，换表不混用常数且不产生伪缺口
        List<EnergyAttributionEngine.TelemetryPoint> pts = List.of(
                new EnergyAttributionEngine.TelemetryPoint(T0, 10.0, false),
                new EnergyAttributionEngine.TelemetryPoint(T0.plusSeconds(1800), 20.0, false),
                new EnergyAttributionEngine.TelemetryPoint(T0.plusSeconds(3600), 30.0, false));
        EnergyAttributionEngine.TelemetryIntegration r = EnergyAttributionEngine.integrateTelemetry(
                pts, eras(), T0, T0.plusSeconds(3600));
        assertTrue(r.fullyObserved());
        assertEquals(30.0, r.energyKwh(), 1e-9);
        assertTrue(r.seKwh() > 0); // 校准相对误差已传播
        assertEquals(0, r.gapSeconds());
    }

    @Test
    void sensorGapIsNeverTreatedAsZeroConsumption() {
        List<EnergyAttributionEngine.DeviceEra> oneEra = List.of(
                new EnergyAttributionEngine.DeviceEra("DEV-A", T0.minusSeconds(60), null, 1.0, 0, 0.01));
        List<EnergyAttributionEngine.TelemetryPoint> pts = List.of(
                new EnergyAttributionEngine.TelemetryPoint(T0, 10.0, false),
                new EnergyAttributionEngine.TelemetryPoint(T0.plusSeconds(600), null, true),
                new EnergyAttributionEngine.TelemetryPoint(T0.plusSeconds(1200), null, true),
                new EnergyAttributionEngine.TelemetryPoint(T0.plusSeconds(1800), 18.0, false));
        EnergyAttributionEngine.TelemetryIntegration r = EnergyAttributionEngine.integrateTelemetry(
                pts, oneEra, T0, T0.plusSeconds(1800));
        assertFalse(r.fullyObserved());
        assertNull(r.energyKwh(), "故障段存在时整段不可估计，绝不能把缺测当 0 后给出偏低总量");
        assertTrue(r.gapSeconds() >= 1200);
        assertEquals(1, r.gaps().size(), "连续缺测点合并为一个缺口窗口");
    }

    @Test
    void meterResetNegativeDeltaBecomesGapNotNegativeEnergy() {
        List<EnergyAttributionEngine.TelemetryPoint> pts = List.of(
                new EnergyAttributionEngine.TelemetryPoint(T0, 100.0, false),
                new EnergyAttributionEngine.TelemetryPoint(T0.plusSeconds(600), 5.0, false),
                new EnergyAttributionEngine.TelemetryPoint(T0.plusSeconds(1200), 12.0, false));
        EnergyAttributionEngine.TelemetryIntegration r = EnergyAttributionEngine.integrateTelemetry(
                pts, List.of(new EnergyAttributionEngine.DeviceEra("DEV-A", T0.minusSeconds(60),
                        null, 1.0, 0, 0.01)), T0, T0.plusSeconds(1200));
        assertNull(r.energyKwh());
        assertTrue(r.gaps().stream().anyMatch(g -> "METER_RESET".equals(g.reason())));
    }

    private EnergyAttributionEngine.CurveParams curve() {
        return new EnergyAttributionEngine.CurveParams(8.0, 0.05, 0.008, 0.00012, 0.35);
    }

    private double[][] covariance(double scale) {
        double[][] cov = new double[5][5];
        double[] diag = {0.04, 0.0004, 0.000004, 0.00000003, 0.0025};
        for (int i = 0; i < 5; i++) {
            cov[i][i] = diag[i] * scale;
        }
        return cov;
    }

    private List<EnergyAttributionEngine.AvlSample> avlSamples(boolean nullGrade, boolean steep) {
        List<EnergyAttributionEngine.AvlSample> out = new ArrayList<>();
        double[] speeds = {0, 18, 32, 26, 12, 28, 34, 22, 9, 0};
        double[] grades = {-1.5, 0.5, 2.0, 3.0, 1.0, -2.5, -3.5, 0.0, 1.5, 3.5};
        for (int k = 0; k < speeds.length; k++) {
            Double g = nullGrade ? null : (steep && k == 4 ? 5.8 : grades[k]);
            out.add(new EnergyAttributionEngine.AvlSample(k * 60L, speeds[k], g));
        }
        return out;
    }

    @Test
    void reconstructionMatchesDirectPowerIntegrationAndPropagatesError() {
        List<EnergyAttributionEngine.AvlSample> samples = avlSamples(false, false);
        EnergyAttributionEngine.Reconstruction r = EnergyAttributionEngine.reconstruct(
                samples, curve(), covariance(1), -4.0, 4.0, 60.0, 0.02);
        assertTrue(r.estimable());
        double expected = 0;
        for (int i = 1; i < samples.size(); i++) {
            double v = 0.5 * (samples.get(i - 1).speedKmh() + samples.get(i).speedKmh());
            double g = 0.5 * (samples.get(i - 1).gradePercent() + samples.get(i).gradePercent());
            expected += EnergyAttributionEngine.powerKw(curve(), v, g) / 60.0;
        }
        assertEquals(expected, r.energyKwh(), 1e-9);
        assertTrue(r.seKwh() > 0, "参数协方差+校准误差必须传播为非零 SE");

        EnergyAttributionEngine.Reconstruction noisier = EnergyAttributionEngine.reconstruct(
                samples, curve(), covariance(4), -4.0, 4.0, 60.0, 0.02);
        assertTrue(noisier.seKwh() > r.seKwh(), "参数协方差放大 4 倍，SE 应增大");
    }

    @Test
    void unobservedGradeIsRefusedWithoutCitywideAverage() {
        EnergyAttributionEngine.Reconstruction r = EnergyAttributionEngine.reconstruct(
                avlSamples(true, false), curve(), covariance(1), -4.0, 4.0, 60.0, 0.02);
        assertFalse(r.estimable());
        assertTrue(r.gradeUnobserved());
        assertNull(r.energyKwh());
    }

    @Test
    void gradeOutsideCalibrationDomainIsNotExtrapolated() {
        EnergyAttributionEngine.Reconstruction r = EnergyAttributionEngine.reconstruct(
                avlSamples(false, true), curve(), covariance(1), -4.0, 4.0, 60.0, 0.02);
        assertFalse(r.estimable());
        assertTrue(r.outsideCalibration());
        assertNull(r.energyKwh());
    }

    @Test
    void idleSegmentHasPositiveEnergyAtZeroSpeed() {
        // 全 0 速度仍有怠速附件功率 α：故障表若按 0 处理会漏掉这部分
        List<EnergyAttributionEngine.AvlSample> idle = List.of(
                new EnergyAttributionEngine.AvlSample(0, 0, 0.0),
                new EnergyAttributionEngine.AvlSample(600, 0, 0.0));
        EnergyAttributionEngine.Reconstruction r = EnergyAttributionEngine.reconstruct(
                idle, curve(), covariance(1), -4.0, 4.0, 60.0, 0.0);
        assertTrue(r.estimable());
        assertEquals(8.0 * (600.0 / 3600.0), r.energyKwh(), 1e-9);
    }

    @Test
    void ittRateRecoversConstantPerPkmEffectAndCountsDroppedUnits() {
        List<EnergyAttributionEngine.UnitRate> units = new ArrayList<>();
        // 12 个单元：6 处理 6 对照；处理率固定低 0.05 kWh/pkm
        for (int i = 0; i < 12; i++) {
            boolean treated = i % 2 == 0;
            double pkm = 100 + i * 7;
            double rate = 0.80 - (treated ? 0.05 : 0);
            units.add(new EnergyAttributionEngine.UnitRate("U" + i,
                    treated ? AssignmentEngine.TREATMENT : AssignmentEngine.CONTROL,
                    rate * pkm, pkm, true, ""));
        }
        EnergyAttributionEngine.RateEstimate re = EnergyAttributionEngine.estimateItteRate(units);
        assertEquals(-0.05, re.effect().estimate(), 1e-9);
        assertEquals(12, re.estimableUnits());
        assertEquals(0, re.droppedUnits());
        // 构造数据为完美恒定效应：组内残差为 0，聚类方差可退化为 0，区间收敛到点估计
        assertTrue(re.effect().se() >= 0);
        assertTrue(re.effect().ciLow() <= re.effect().estimate() && re.effect().estimate() <= re.effect().ciHigh());

        // 加入一个含故障段不可估计单元与一个零乘客公里单元：都应剔除
        List<EnergyAttributionEngine.UnitRate> withGaps = new ArrayList<>(units);
        withGaps.add(new EnergyAttributionEngine.UnitRate("UGAP", AssignmentEngine.TREATMENT,
                50, 80, false, "传感器缺测"));
        withGaps.add(new EnergyAttributionEngine.UnitRate("UZERO", AssignmentEngine.CONTROL,
                10, 0, true, ""));
        EnergyAttributionEngine.RateEstimate re2 = EnergyAttributionEngine.estimateItteRate(withGaps);
        assertEquals(12, re2.estimableUnits());
        assertEquals(2, re2.droppedUnits());
    }

    @Test
    void zeroPassengerKmUnitCannotBuildRate() {
        EnergyAttributionEngine.UnitRate u = new EnergyAttributionEngine.UnitRate(
                "U0", AssignmentEngine.CONTROL, 10, 0, true, "");
        assertThrows(IllegalStateException.class, u::rate);
    }

    @Test
    void randomizationInferenceEnumeratesBalancedAssignments() {
        // 8 单元 4/4：C(8,4)=70 种分配
        List<EnergyAttributionEngine.UnitRate> units = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            boolean treated = i < 4;
            double pkm = 50 + i;
            double rate = 0.7 + (i % 3) * 0.02 - (treated ? 0.08 : 0);
            units.add(new EnergyAttributionEngine.UnitRate("U" + i,
                    treated ? AssignmentEngine.TREATMENT : AssignmentEngine.CONTROL,
                    rate * pkm, pkm, true, ""));
        }
        EnergyAttributionEngine.RandomizationResult rr =
                EnergyAttributionEngine.randomizationInference(units);
        assertEquals(70, rr.permutations());
        assertTrue(rr.pValue() > 0 && rr.pValue() <= 1);
        assertTrue(rr.ciLow() <= rr.hlEstimate() && rr.hlEstimate() <= rr.ciHigh());
        // plus-one 分母
        assertTrue(rr.pValue() >= 1.0 / 71.0 - 1e-12);
        assertNotNull(rr);
    }

    @Test
    void quadraticFormMatchesExpectedSum() {
        double[] x = {1, 2};
        double[][] a = {{3, 1}, {1, 2}};
        // x'Ax = 3 + 2*2 + 8 = 15
        assertEquals(15.0, EnergyAttributionEngine.quadraticForm(x, a), 1e-12);
    }

    @Test
    void modelResidualWidensGapReconstructionButNotPerfectFit() {
        // R²=0.963：残差相对 SE=√(1−R²)≈0.192，缺测重建 SE 必须明显大于纯参数 SE
        double paramSe = 0.5;
        double widened = EnergyAttributionEngine.withModelResidual(10.0, paramSe, 0.963);
        assertTrue(widened > paramSe);
        assertEquals(Math.sqrt(0.25 + Math.pow(Math.sqrt(0.037) * 10.0, 2)), widened, 1e-9);
        // R²=1（完美校准）时不额外加宽
        assertEquals(paramSe, EnergyAttributionEngine.withModelResidual(10.0, paramSe, 1.0), 1e-12);
    }
}
