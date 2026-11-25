package com.ccdev.transit.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * 车辆能耗归因的纯计算引擎（无 Spring/DB 依赖，便于单测与离线复算）。
 *
 * <p>两条估计路线：
 * <ul>
 *   <li><b>TELEMETRY 遥测积分</b>：按车辆设备沿革的校准常数把 CAN 累计读数换算为标准 kWh，
 *       对每车每段相邻有效读数积分。传感器缺测/故障段 {@code reading=null}，
 *       绝不当作零消耗——该段标记为不可估计并从可估范围剔除（覆盖范围收缩）。</li>
 *   <li><b>RECONSTRUCTED 速度—坡度曲线重建</b>：用同车校准的
 *       {@code P(kW)=α+β1·v+β2·v²+β3·v³+γ·v·g} 对 AVL 点积分补全缺测段，
 *       参数协方差经雅可比线性传播，叠加设备校准相对误差；
 *       坡度未观测或超出同车校准支撑域时拒绝外推（不用全市平均替代）。</li>
 * </ul>
 *
 * <p>效应估计以随机化单元（班次）为单位，单元结果变量为<b>每乘客公里能耗</b>
 * {@code r_u = 该单元全部区段能耗(含空驶/等待/折返) / 该单元乘客公里(仅载客段)}，
 * 以乘客公里加权做 Hajek ITT，CR1 聚类区间；另给平衡分配下的精确随机化推断
 * （枚举全部 C(n,t) 种分配，plus-one p 值与 Hodges–Lehmann 配对差区间）。
 */
public final class EnergyAttributionEngine {

    private EnergyAttributionEngine() {
    }

    // ============================ 速度—坡度曲线 ============================

    public record CurveParams(double alpha, double beta1, double beta2, double beta3, double gamma) {
        public double[] asVector() {
            return new double[]{alpha, beta1, beta2, beta3, gamma};
        }
    }

    /** 瞬时功率 kW；v=km/h，grade=坡度百分数（如 3.0 表示 3%）。 */
    public static double powerKw(CurveParams p, double speedKmh, double gradePercent) {
        double v = Math.max(0, speedKmh);
        return p.alpha() + p.beta1() * v + p.beta2() * v * v
                + p.beta3() * v * v * v + p.gamma() * v * gradePercent;
    }

    public record AvlSample(double epochSeconds, double speedKmh, Double gradePercent) {
    }

    public record Reconstruction(
            Double energyKwh,
            Double seKwh,
            boolean estimable,
            boolean gradeUnobserved,
            boolean outsideCalibration,
            String reason) {
    }

    /**
     * 曲线重建积分。雅可比 J 对参数 θ 的各分量按 Δt 累积，
     * 参数方差 Var=J'ΣJ；再叠加设备校准相对误差 (calRelSe·E)²。
     * 任一点坡度缺失或超出同车校准支撑域 => 不可估计（拒绝外推）。
     */
    public static Reconstruction reconstruct(List<AvlSample> samples, CurveParams params, double[][] paramCov,
                                             double gradeMinObserved, double gradeMaxObserved,
                                             double speedMaxObserved, double calibrationRelSe) {
        if (samples == null || samples.size() < 2) {
            return new Reconstruction(null, null, false, false, false, "AVL 样本不足，无法积分");
        }
        boolean gradeUnobserved = false;
        boolean outside = false;
        for (AvlSample s : samples) {
            if (s.gradePercent() == null) {
                gradeUnobserved = true;
            } else if (s.gradePercent() < gradeMinObserved - 1e-9 || s.gradePercent() > gradeMaxObserved + 1e-9) {
                outside = true;
            }
            if (s.speedKmh() > speedMaxObserved + 1e-9) {
                outside = true;
            }
        }
        if (gradeUnobserved) {
            return new Reconstruction(null, null, false, true, false,
                    "区段存在坡度未观测点：同车校准曲线无该坡度支撑，拒绝外推（不得用全市平均替代）");
        }
        if (outside) {
            return new Reconstruction(null, null, false, false, true,
                    "速度/坡度超出同车校准支撑域 [" + gradeMinObserved + "%," + gradeMaxObserved
                            + "%] / v≤" + speedMaxObserved + "km/h：标记不可外推");
        }

        double energy = 0;
        double[] jacobian = new double[5];
        for (int i = 1; i < samples.size(); i++) {
            AvlSample a = samples.get(i - 1);
            AvlSample b = samples.get(i);
            double dtHours = Math.max(0, (b.epochSeconds() - a.epochSeconds())) / 3600.0;
            if (dtHours == 0) {
                continue;
            }
            double v = 0.5 * (a.speedKmh() + b.speedKmh());
            double g = 0.5 * (a.gradePercent() + b.gradePercent());
            double p = powerKw(params, v, g);
            energy += p * dtHours;
            // E = Σ P·Δt，对 θ 线性：各基函数在该区间的累积
            jacobian[0] += dtHours;
            jacobian[1] += dtHours * v;
            jacobian[2] += dtHours * v * v;
            jacobian[3] += dtHours * v * v * v;
            jacobian[4] += dtHours * v * g;
        }
        double paramVar = quadraticForm(jacobian, paramCov);
        double calVar = Math.pow(calibrationRelSe * energy, 2);
        double se = Math.sqrt(Math.max(0, paramVar + calVar));
        return new Reconstruction(energy, se, true, false, false,
                "参数协方差雅可比传播 + 设备校准相对误差 " + String.format("%.1f%%", calibrationRelSe * 100));
    }

    /** x' A x（A 对称）。 */
    static double quadraticForm(double[] x, double[][] a) {
        double sum = 0;
        for (int i = 0; i < x.length; i++) {
            for (int j = 0; j < x.length; j++) {
                sum += x[i] * a[i][j] * x[j];
            }
        }
        return sum;
    }

    /**
     * 缺测重建段叠加模型残差不确定性：曲线校准 R² 对应的残差相对标准差
     * σ_res/ŷ ≈ √(1−R²)，仅在没有任何遥测锚点、完全依赖曲线重建的区段计入。
     * 有遥测交叉核对的区段不重复计该项，避免双重计误。
     */
    public static double withModelResidual(double energyKwh, double paramSeKwh, double calibratedR2) {
        double residualRelSe = Math.sqrt(Math.max(0, 1 - calibratedR2));
        double residualVar = Math.pow(residualRelSe * energyKwh, 2);
        return Math.sqrt(paramSeKwh * paramSeKwh + residualVar);
    }

    // ============================ CAN 遥测积分 ============================

    public record DeviceEra(
            String deviceCode,
            java.time.Instant validFrom,
            java.time.Instant validTo, // null=至今
            double factor,
            double offset,
            double calibrationRelSe) {

        public boolean covers(java.time.Instant t) {
            return !t.isBefore(validFrom) && (validTo == null || t.isBefore(validTo));
        }
    }

    public record TelemetryPoint(java.time.Instant observedAt, Double reading, boolean sensorGap) {
    }

    public record GapWindow(java.time.Instant start, java.time.Instant end, String reason) {
        public long seconds() {
            return java.time.Duration.between(start, end).getSeconds();
        }
    }

    public record TelemetryIntegration(
            Double energyKwh,       // 仅有效观测区间积分；全段无缺测时才非 null
            double seKwh,
            long observedSeconds,
            long gapSeconds,
            boolean fullyObserved,
            List<GapWindow> gaps) {
    }

    /**
     * 对落在 [segStart, segEnd) 内的遥测点按设备沿革校准后积分。
     * 缺测点只用于界定缺口，绝不参与求和、绝不按零消耗处理；
     * 只要段内存在缺口，该段即不可整体估计（energyKwh=null），由调用方收缩可估范围。
     */
    public static TelemetryIntegration integrateTelemetry(
            List<TelemetryPoint> points, List<DeviceEra> eras,
            java.time.Instant segStart, java.time.Instant segEnd) {
        List<TelemetryPoint> in = points.stream()
                .filter(p -> !p.observedAt().isBefore(segStart) && !p.observedAt().isAfter(segEnd))
                .sorted(java.util.Comparator.comparing(TelemetryPoint::observedAt))
                .toList();

        List<GapWindow> gaps = new ArrayList<>();
        long gapSeconds = 0;
        java.time.Instant gapStart = null;
        for (TelemetryPoint p : in) {
            if (p.sensorGap() || p.reading() == null) {
                if (gapStart == null) {
                    gapStart = p.observedAt();
                }
            } else if (gapStart != null) {
                GapWindow w = new GapWindow(gapStart, p.observedAt(), "DEVICE_FAULT");
                gaps.add(w);
                gapSeconds += w.seconds();
                gapStart = null;
            }
        }
        if (gapStart != null) {
            GapWindow w = new GapWindow(gapStart, segEnd, "DEVICE_FAULT");
            gaps.add(w);
            gapSeconds += w.seconds();
        }

        List<TelemetryPoint> valid = in.stream()
                .filter(p -> !p.sensorGap() && p.reading() != null)
                .toList();
        if (valid.size() < 2) {
            return new TelemetryIntegration(null, Double.NaN, 0,
                    java.time.Duration.between(segStart, segEnd).getSeconds(), false, gaps);
        }

        double energy = 0;
        double relSeWeightedNum = 0;
        long observedSeconds = 0;
        for (int i = 1; i < valid.size(); i++) {
            TelemetryPoint prev = valid.get(i - 1);
            TelemetryPoint cur = valid.get(i);
            DeviceEra eraPrev = eraFor(eras, prev.observedAt());
            DeviceEra eraCur = eraFor(eras, cur.observedAt());
            if (eraPrev == null || eraCur == null) {
                // 读数不在任何设备有效期内：视为不可校准缺测
                GapWindow w = new GapWindow(prev.observedAt(), cur.observedAt(), "NO_CALIBRATION_ERA");
                gaps.add(w);
                gapSeconds += w.seconds();
                continue;
            }
            double kwhPrev = prev.reading() * eraPrev.factor() + eraPrev.offset();
            double kwhCur = cur.reading() * eraCur.factor() + eraCur.offset();
            double delta = kwhCur - kwhPrev;
            if (delta < 0) {
                // 累计表单调递减（换表/重置）不属于正常消耗，按缺口处理而非负能耗
                GapWindow w = new GapWindow(prev.observedAt(), cur.observedAt(), "METER_RESET");
                gaps.add(w);
                gapSeconds += w.seconds();
                continue;
            }
            energy += delta;
            double avgRelSe = 0.5 * (eraPrev.calibrationRelSe() + eraCur.calibrationRelSe());
            relSeWeightedNum += avgRelSe * delta;
            observedSeconds += java.time.Duration.between(prev.observedAt(), cur.observedAt()).getSeconds();
        }

        boolean fullyObserved = gaps.isEmpty()
                && observedSeconds >= java.time.Duration.between(segStart, segEnd).getSeconds() - 1;
        if (!fullyObserved) {
            // 设备故障段不可当零消耗：存在缺口即拒绝给出段级总量
            return new TelemetryIntegration(null, Double.NaN, observedSeconds, gapSeconds, false, gaps);
        }
        double relSe = energy == 0 ? 0 : relSeWeightedNum / energy;
        double se = relSe * energy;
        return new TelemetryIntegration(energy, se, observedSeconds, 0, true, List.of());
    }

    private static DeviceEra eraFor(List<DeviceEra> eras, java.time.Instant t) {
        for (DeviceEra e : eras) {
            if (e.covers(t)) {
                return e;
            }
        }
        return null;
    }

    // ============================ 每乘客公里能耗 ITT ============================

    /** 随机化单元（班次）聚合：能耗含全部区段，乘客公里仅来自载客段。 */
    public record UnitRate(String unitKey, String assignedArm,
                           double totalEnergyKwh, double passengerKm,
                           boolean estimable, String scopeNote) {
        public double rate() {
            if (passengerKm <= 0) {
                throw new IllegalStateException("单元 " + unitKey + " 乘客公里为 0，不能构造每乘客公里能耗");
            }
            return totalEnergyKwh / passengerKm;
        }
    }

    public record RateEstimate(
            Stats.EffectEstimate effect,
            double treatmentRate,
            double controlRate,
            double treatmentEnergy,
            double controlEnergy,
            double treatmentPkm,
            double controlPkm,
            int estimableUnits,
            int droppedUnits) {
    }

    /**
     * 乘客公里加权的 Hajek ITT（处理−对照），CR1 区间按随机化单元聚类。
     * 不可估计单元（遥测缺口/拒绝外推）整体剔除并计数，体现覆盖范围收缩。
     */
    public static RateEstimate estimateItteRate(List<UnitRate> units) {
        List<Stats.Observation> obs = new ArrayList<>();
        double eT = 0, eC = 0, pT = 0, pC = 0;
        int dropped = 0;
        for (UnitRate u : units) {
            if (!u.estimable() || u.passengerKm() <= 0) {
                dropped++;
                continue;
            }
            int a = AssignmentEngine.TREATMENT.equals(u.assignedArm()) ? 1 : 0;
            obs.add(new Stats.Observation(u.rate(), u.passengerKm(), a, u.unitKey()));
            if (a == 1) {
                eT += u.totalEnergyKwh();
                pT += u.passengerKm();
            } else {
                eC += u.totalEnergyKwh();
                pC += u.passengerKm();
            }
        }
        Stats.EffectEstimate fit = Stats.clusterITT(obs);
        return new RateEstimate(fit, eT / pT, eC / pC, eT, eC, pT, pC, obs.size(), dropped);
    }

    // ============================ 精确随机化推断 ============================

    public record RandomizationResult(
            double observedStat,
            double pValue,
            double hlEstimate,
            double ciLow,
            double ciHigh,
            int permutations,
            int treatedCount) {
    }

    /**
     * 平衡分配下枚举全部 C(n,t) 种处理位组合，统计量为乘客公里加权均值差；
     * plus-one p=(1+#(|perm|≥|obs|))/(M+1)。
     * 随机化区间用 Hodges–Lehmann 配对差（处理×对照单元率差）的 2.5%/97.5% 分位数。
     */
    public static RandomizationResult randomizationInference(List<UnitRate> units) {
        List<UnitRate> usable = units.stream()
                .filter(u -> u.estimable() && u.passengerKm() > 0)
                .toList();
        int n = usable.size();
        int t = (int) usable.stream().filter(u -> AssignmentEngine.TREATMENT.equals(u.assignedArm())).count();
        if (t == 0 || t == n) {
            throw new IllegalStateException("随机化推断需要两臂均有可估计单元");
        }
        double[] r = new double[n];
        double[] w = new double[n];
        int[] observedTreated = new int[t];
        int ti = 0;
        for (int i = 0; i < n; i++) {
            r[i] = usable.get(i).rate();
            w[i] = usable.get(i).passengerKm();
            if (AssignmentEngine.TREATMENT.equals(usable.get(i).assignedArm())) {
                observedTreated[ti++] = i;
            }
        }
        double observed = weightedMeanDiff(observedTreated, r, w);

        List<int[]> combos = new ArrayList<>();
        enumerateCombinations(n, t, 0, new int[t], 0, combos);
        int ge = 0;
        for (int[] combo : combos) {
            double stat = weightedMeanDiff(combo, r, w);
            if (Math.abs(stat) >= Math.abs(observed) - 1e-12) {
                ge++;
            }
        }
        double pValue = (1.0 + ge) / (combos.size() + 1.0);

        // Hodges–Lehmann 两样本移位：全部处理-对照配对率差
        List<Double> pairDiffs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!AssignmentEngine.TREATMENT.equals(usable.get(i).assignedArm())) {
                continue;
            }
            for (int j = 0; j < n; j++) {
                if (!AssignmentEngine.CONTROL.equals(usable.get(j).assignedArm())) {
                    continue;
                }
                pairDiffs.add(r[i] - r[j]);
            }
        }
        pairDiffs.sort(Double::compare);
        double hl = pairDiffs.get(pairDiffs.size() / 2);
        double ciLow = pairDiffs.get((int) Math.floor(0.025 * (pairDiffs.size() - 1)));
        double ciHigh = pairDiffs.get((int) Math.ceil(0.975 * (pairDiffs.size() - 1)));
        return new RandomizationResult(observed, pValue, hl, ciLow, ciHigh, combos.size(), t);
    }

    private static double weightedMeanDiff(int[] treatedIdx, double[] r, double[] w) {
        boolean[] isT = new boolean[r.length];
        for (int i : treatedIdx) {
            isT[i] = true;
        }
        double swT = 0, swC = 0, swyT = 0, swyC = 0;
        for (int i = 0; i < r.length; i++) {
            if (isT[i]) {
                swT += w[i];
                swyT += w[i] * r[i];
            } else {
                swC += w[i];
                swyC += w[i] * r[i];
            }
        }
        return swyT / swT - swyC / swC;
    }

    private static void enumerateCombinations(int n, int k, int start, int[] picked, int depth,
                                              List<int[]> out) {
        if (depth == k) {
            out.add(picked.clone());
            return;
        }
        for (int i = start; i <= n - (k - depth); i++) {
            picked[depth] = i;
            enumerateCombinations(n, k, i + 1, picked, depth + 1, out);
        }
    }
}
