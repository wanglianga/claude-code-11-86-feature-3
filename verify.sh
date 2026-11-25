#!/usr/bin/env bash
# 灰度实验服务关键不变量验证。
# 用法：./verify.sh [BASE_URL]
# 依赖：bash、curl、python3（仅用标准库）
set -u
BASE="${1:-${BASE_URL:-http://127.0.0.1:8080}}"
PYBIN="$(command -v python3 || true)"
if [ -z "$PYBIN" ] && [ -x /opt/python-3.12/bin/python3 ]; then PYBIN=/opt/python-3.12/bin/python3; fi
[ -n "$PYBIN" ] || { echo "未找到 python3"; exit 1; }

echo "==> 等待服务健康：$BASE"
for i in $(seq 1 60); do
  if curl -fsS --max-time 3 "$BASE/api/health" 2>/dev/null | grep -q '"db":"UP"'; then
    echo "    health UP"; break
  fi
  sleep 2
  if [ "$i" = "60" ]; then echo "服务未就绪"; exit 1; fi
done

BASE="$BASE" "$PYBIN" - <<'PY'
import base64, json, os, sys, urllib.request, urllib.error

BASE = os.environ["BASE"].rstrip("/")
fails, passes = [], []

def req(method, path, user="analyst", pwd="analyst123", body=None, raw=False):
    r = urllib.request.Request(BASE + path, method=method)
    token = base64.b64encode(f"{user}:{pwd}".encode()).decode()
    r.add_header("Authorization", "Basic " + token)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, data=data, timeout=20) as resp:
            txt = resp.read().decode()
            return resp.status, (txt if raw else json.loads(txt or "{}"))
    except urllib.error.HTTPError as e:
        txt = e.read().decode()
        try:
            return e.code, json.loads(txt)
        except Exception:
            return e.code, txt

def check(name, cond, detail=""):
    (passes if cond else fails).append(name)
    print(("  ✅ " if cond else "  ❌ ") + name + ("" if cond or not detail else f"  -> {detail}"))

# 1. 实验存在
st, exp = req("GET", "/api/experiments/by-code/PILOT-R123-202606")
check("样例实验已登记", st == 200 and exp.get("id"), str(exp)[:200])
eid = exp["id"]

# 2. 两套分配严格 50%
for design, total in [("TRIP_RANDOMIZED", 720), ("CLUSTER_WEEK", 8)]:
    st, a = req("GET", f"/api/experiments/{eid}/assignments?design={design}")
    check(f"{design} 总数={total}", a.get("total") == total, str(a.get("total")))
    check(f"{design} 处理/对照各半", a.get("treatment") == total // 2
          and a.get("control") == total // 2
          and abs(a.get("treatmentShare", 0) - 0.5) < 1e-9,
          f"{a.get('treatment')}/{a.get('control')} share={a.get('treatmentShare')}")

# 3. 命名旅程完整路径标记
expected = {
    "J-DEMO-DIVERT-01": ("CROSS_MIXED", "TREATMENT", True, True),
    "J-DEMO-PURER2-01": ("FULL_CONTROL", "CONTROL", False, False),
    "J-DEMO-CROSS-01":  ("CROSS_MIXED", "CONTROL", True, False),
    "J-DEMO-FULLT-01":  ("FULL_TREATMENT", "TREATMENT", False, False),
    "J-DEMO-FULLC-01":  ("FULL_CONTROL", "CONTROL", False, False),
    "J-DEMO-NONEXEC-01": ("FULL_CONTROL", "TREATMENT", False, False),
    "J-DEMO-INCOMPLETE-01": ("UNKNOWN", None, False, False),
    "J-DEMO-MIDCHANGE-01": ("UNKNOWN", None, False, False),
    "J-DEMO-STORM-01": ("UNKNOWN", None, False, False),
}
for code, (path, arm, cross, diverted) in expected.items():
    st, d = req("GET", f"/api/experiments/{eid}/journeys/{code}")
    led = d.get("ledger") or {}
    ok = st == 200 and led.get("path_label") == path
    if arm:
        ok &= led.get("assigned_arm_trip") == arm
    if cross:
        ok &= bool(led.get("cross_line"))
    if diverted:
        ok &= bool(led.get("diverted"))
    check(f"{code} => {path}" + (f"/{arm}" if arm else ""), ok,
          f"got {led.get('path_label')} arm={led.get('assigned_arm_trip')} "
          f"cross={led.get('cross_line')} divert={led.get('diverted')}")

# 4. 交叉暴露不混入纯净对照
st, fc = req("GET", f"/api/experiments/{eid}/exposure?pathLabel=FULL_CONTROL&limit=500")
rows = fc.get("rows", [])
bad = [r["journey_code"] for r in rows if r.get("cross_line")]
check("FULL_CONTROL 中没有任何 cross_line=true 旅程", not bad, str(bad[:5]))
check("CROSS_MIXED 数量 > 0",
      any(c["path_label"] == "CROSS_MIXED" and c["n"] > 50 for c in fc.get("counts", [])))

# 5. 分析结果：两设计 ITT + RI + CACE
st, an = req("GET", f"/api/experiments/{eid}/analysis")
res = an.get("results", [])
def find(**kw):
    for r in res:
        if all(r.get(k) == v for k, v in kw.items()):
            return r
    return None
itt_t = find(design_type="TRIP_RANDOMIZED", estimator_code="ITT",
             metric_code="wait_seconds", scope="COMMON_SUPPORT")
itt_c = find(design_type="CLUSTER_WEEK", estimator_code="ITT",
             metric_code="wait_seconds", scope="COMMON_SUPPORT")
check("班次方案 ITT(候车) 存在且为负效应", bool(itt_t) and itt_t["estimate"] < 0, str(itt_t))
check("簇周方案 ITT(候车) 存在且为负、效应更大",
      bool(itt_c) and itt_c["estimate"] < itt_t["estimate"], str(itt_c and itt_c["estimate"]))
check("簇周方案 95% 区间宽于班次方案",
      (itt_c["ci_high"] - itt_c["ci_low"]) > (itt_t["ci_high"] - itt_t["ci_low"]),
      f"cluster width={itt_c['ci_high']-itt_c['ci_low']:.1f} vs "
      f"trip width={itt_t['ci_high']-itt_t['ci_low']:.1f}")
ri = find(design_type="CLUSTER_WEEK", estimator_code="RI_PERMUTATION")
check("簇周随机化推断 RI p 值存在（16 置换）", bool(ri) and 0 < ri["p_value"] <= 1)
cace = find(estimator_code="CACE_WALD")
check("CACE/Wald 估计存在", bool(cace) and cace["estimate"] < 0)
crossr = find(estimator_code="CROSS_RATE")
check("跨线暴露率带 Wilson 区间", bool(crossr) and crossr["ci_low"] < crossr["estimate"] < crossr["ci_high"])

# 6. 共同支持缺口存在（暴雨层不能外推）
st, sup = req("GET", f"/api/experiments/{eid}/support")
check("识别出无共同支持的缺口层", int(sup["gaps"]["gap_strata_trip"]) > 0
      or int(sup["gaps"]["gap_strata_cluster"]) > 0, str(sup["gaps"]))

# 7. 护栏：投诉与滞留超限
st, gg = req("GET", f"/api/experiments/{eid}/guardrails")
latest = {g["rule_code"]: g for g in gg.get("latest", [])}
check("投诉护栏超限", latest.get("COMPLAINT_RATE_MAX", {}).get("breached") is True)
check("滞留/停运安全护栏超限", latest.get("STRANDING_RATE_MAX", {}).get("breached") is True)
cmp_rule = latest.get("COMPLAINT_RATE_MAX", {})
cmp_detail = cmp_rule.get("detail", {})
if isinstance(cmp_detail, str):
    cmp_detail = json.loads(cmp_detail)
check("投诉按 service_event_time 关联且存在滞后提交",
      cmp_detail.get("timeBasis", "").startswith("service_event_time")
      and int(cmp_detail.get("complaintsLoggedOnLaterDate", 0)) > 0)

# 8. 权限：analyst 不能执行扩围结论
st, _ = req("POST", f"/api/experiments/{eid}/expansion/evaluate", user="analyst")
check("analyst 调用扩围评估被 403 拒绝", st == 403, f"status={st}")

# 9. approver 评估 -> BLOCKED；人工 GO 被拒
st, dec = req("POST", f"/api/experiments/{eid}/expansion/evaluate", user="approver", pwd="approver123")
check("approver 评估后结论为 BLOCKED", st == 200 and dec.get("decision") == "BLOCKED",
      f"status={st} decision={dec.get('decision') if isinstance(dec,dict) else dec}")
st, body = req("POST", f"/api/experiments/{eid}/expansion/decide",
               user="approver", pwd="approver123",
               body={"decision": "GO", "reason": "try override"})
check("护栏超限时人工 GO 被服务端拒绝(409)", st == 409, f"status={st}")

# ======================================================================
# 10. 车辆能耗归因（feature-3）
# ======================================================================
st, sc = req("GET", f"/api/experiments/{eid}/energy/sample-check")
check("能耗样例核验接口可访问", st == 200, str(sc)[:200])

# 10.1 同一车辆处理/对照资格班次各 6 个
arms = {x["assigned_arm"]: x["trips"] for x in sc.get("tripsByAssignedArm", [])}
check("同一车辆处理/对照资格班次各 6 个",
      arms.get("TREATMENT") == 6 and arms.get("CONTROL") == 6, str(arms))

# 10.2 分母核验：空驶/等待/折返段乘客公里恒为 0，仅载客段有乘客公里
denom = {d["segment_type"]: d for d in sc.get("denominatorCheck", [])}
non_rev_ok = all(denom.get(t, {}).get("zero_when_non_revenue") is True
                 for t in ("DEADHEAD", "IDLE", "SHORT_TURN"))
rev_pkm = denom.get("REVENUE", {}).get("passenger_km", 0)
check("非载客段(空驶/等待/折返)乘客公里恒为 0", non_rev_ok, str(denom))
check("仅载客段产生乘客公里且为正", rev_pkm > 0, str(rev_pkm))

# 10.3 电量表 2 小时缺测：读数 NULL（不是 0），跨度 2 小时
gap = sc.get("twoHourMeterGap", {})
check("存在 1 个班次的 2 小时电量缺测",
      gap.get("gap_trips") == 1 and "2 hours" in str(gap.get("gap_span"))
      and int(gap.get("gap_points", 0)) > 0, str(gap))
st, tel = req("GET", f"/api/experiments/{eid}/energy/telemetry")
trows = tel.get("rows", [])
gap_rows = [r for r in trows if r.get("sensor_gap")]
check("缺测点读数全部为 NULL 且绝不为 0",
      gap_rows and all(r.get("reading") is None for r in gap_rows)
      and not any(r.get("reading") == 0 for r in gap_rows),
      f"{len(gap_rows)} gap points")

# 10.4 两条路线覆盖范围：遥测因缺测更窄，重建覆盖更全但仍拒绝外推
cov = {c["route_method"]: c for c in sc.get("methodCoverage", [])}
tel_cov = cov.get("TELEMETRY", {})
rec_cov = cov.get("RECONSTRUCTED", {})
check("遥测路线因传感器缺口收缩可估范围(有剔除段)",
      int(tel_cov.get("dropped_segments", 0)) >= 1, str(tel_cov))
check("重建路线覆盖更全(可估段不少于遥测)且仍有不可外推段",
      int(rec_cov.get("estimable_segments", 0)) >= int(tel_cov.get("estimable_segments", 0))
      and int(rec_cov.get("dropped_segments", 0)) >= 1, str(rec_cov))

# 10.5 坡度未观测与超出校准域均报告不可外推（不用全市平均）
not_ext = {(x["gap_reason"], x["route_method"]) for x in sc.get("notExtrapolated", [])}
check("坡度未观测段标记 GRADE_UNOBSERVED 不可外推",
      ("GRADE_UNOBSERVED", "RECONSTRUCTED") in not_ext, str(not_ext))
check("超出同车校准域段标记 OUTSIDE_CALIBRATION 不可外推",
      ("OUTSIDE_CALIBRATION", "RECONSTRUCTED") in not_ext, str(not_ext))

# 10.6 每乘客公里能耗效应：两路线均有 ITT(CR1) 与精确随机化推断 RI
st, est = req("GET", f"/api/experiments/{eid}/energy/estimates")
erows = est.get("results", [])
def efind(method, code):
    for r in erows:
        if r.get("route_method") == method and r.get("estimator_code") == code:
            return r
    return None
for method in ("TELEMETRY", "RECONSTRUCTED"):
    itt_r = efind(method, "ITT_RATE")
    ri_r = efind(method, "RI_RATE")
    arm_rows = [r for r in erows if r.get("route_method") == method and r.get("estimator_code") == "ARM_RATE"]
    check(f"{method} 路线 ITT 带 CR1 区间与 p 值",
          bool(itt_r) and itt_r.get("ci_low") is not None and itt_r.get("ci_high") is not None
          and itt_r.get("p_value") is not None, str(itt_r))
    check(f"{method} 路线精确随机化推断 RI 存在(plus-one p, HL 区间)",
          bool(ri_r) and 0 < ri_r.get("p_value", 0) <= 1
          and ri_r.get("ci_low") is not None and ri_r.get("ci_high") is not None, str(ri_r))
    check(f"{method} 路线含处理/对照臂级比率",
          len({r.get("arm") for r in arm_rows}) == 2
          and all(r.get("energy_per_pkm") is not None for r in arm_rows), str(len(arm_rows)))

# 10.7 重建路线对缺测段的 SE 必须为正（参数误差已传播），且覆盖率口径不同
st, segs = req("GET", f"/api/experiments/{eid}/energy/segments")
recon = [s for s in segs.get("rows", []) if s.get("reconstructed_kwh") is not None]
check("可重建段的重建 SE 全部为正(误差传播)",
      recon and all((s.get("reconstructed_se") or 0) > 0 for s in recon),
      f"{len(recon)} reconstructed segments")
extrap = [s for s in segs.get("rows", []) if s.get("reconstructed_extrapolated")]
check("缺测段被标记为 extrapolated 重建补全", len(extrap) >= 1, f"{len(extrap)} segments")

# 10.8 权限：viewer 不能触发重算；analyst 可以，且重算幂等
st, _ = req("POST", f"/api/experiments/{eid}/energy/rebuild", user="viewer", pwd="viewer123")
check("viewer 重算能耗被 403 拒绝", st == 403, f"status={st}")
st, rb1 = req("POST", f"/api/experiments/{eid}/energy/rebuild", user="analyst")
st2, rb2 = req("POST", f"/api/experiments/{eid}/energy/rebuild", user="analyst")
check("analyst 可重算且结果稳定(段数/缺口一致)",
      st == 200 and st2 == 200 and rb1.get("segments") == rb2.get("segments")
      and rb1.get("coverageGaps") == rb2.get("coverageGaps"), f"{rb1} vs {rb2}")

# 11. OpenAPI / Swagger / 前端
for path in ["/v3/api-docs", "/swagger-ui/index.html", "/index.html", "/login.html", "/app.js"]:
    try:
        with urllib.request.urlopen(BASE + path, timeout=10) as r:
            ok = r.status == 200
    except Exception:
        ok = False
    check(f"可访问 {path}", ok)
check("前端含能耗归因标签", "energy" in urllib.request.urlopen(BASE + "/app.js", timeout=10).read().decode())

print(f"\n通过 {len(passes)} 项，失败 {len(fails)} 项")
if fails:
    print("失败项：" + "; ".join(fails))
    sys.exit(1)
print("ALL_VERIFY_CHECKS_PASSED")
PY
