"""Compare isolated full-game runs, retaining scene and setup evidence."""
import argparse
import json
import math
from pathlib import Path


def survival_view_warnings(before, after):
    """Reject clearly different final views; this does not certify the full route."""
    warnings = []
    parsed = {}
    for key, count in (("position", 3), ("camera", 2)):
        try:
            pairs = [tuple(map(float, values[key].split(","))) for values in (before, after)]
            if any(len(values) != count or not all(map(math.isfinite, values)) for values in pairs):
                raise ValueError("invalid view")
            parsed[key] = pairs
        except (KeyError, ValueError, TypeError, AttributeError):
            warnings.append(f"missing or invalid survival {key}; view equivalence unverified")
    if "position" in parsed:
        a, b = parsed["position"]
        distance = math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))
        if distance > 1.0:
            warnings.append(f"survival final position differs by {distance:.3f} m (>1 m)")
    if "camera" in parsed:
        a, b = parsed["camera"]
        yaw = abs((b[0] - a[0] + 180.0) % 360.0 - 180.0)
        pitch = abs(b[1] - a[1])
        if yaw > 5.0 or pitch > 5.0:
            warnings.append(f"survival final camera differs by yaw={yaw:.3f}, pitch={pitch:.3f} degrees (>5 degrees)")
    return warnings


def read_run(folder):
    folder = Path(folder)
    metadata = json.loads((folder / "benchmark-process.json").read_text(encoding="utf-8-sig"))
    scenes = {}
    for path in sorted((folder / "ice-benchmark").glob("*-summary.txt")):
        values = dict(line.split("=", 1) for line in path.read_text(encoding="utf-8-sig").splitlines() if "=" in line)
        scenes[values["scene"]] = values
    log = (folder / "benchmark-stdout.log").read_text(encoding="utf-8-sig", errors="replace")
    return folder, metadata, scenes, "ICE_BENCH COMPLETE" in log and "ICE_BENCH INVALID" not in log


def fixed_path_view_warnings(before, after):
    warnings = []
    for key in ("render_camera_path", "render_camera_anchor"):
        if not before.get(key) or before.get(key) != after.get(key):
            warnings.append(f"fixed render camera missing or mismatched {key}")
    for key, count, tolerance in (("render_camera_position", 3, .05), ("render_camera_angles", 2, .5)):
        try:
            a, b = [tuple(map(float, values[key].split(","))) for values in (before, after)]
            if len(a) != count or len(b) != count or not all(map(math.isfinite, a + b)):
                raise ValueError("invalid camera")
            differences = [abs(x-y) for x,y in zip(a,b)]
            if key.endswith("angles"):
                differences[0] = abs((b[0]-a[0]+180) % 360-180)
            if max(differences) > tolerance:
                warnings.append(f"fixed render camera {key} drift exceeds {tolerance}")
        except (KeyError, ValueError, TypeError, AttributeError):
            warnings.append(f"fixed render camera lacks valid {key}")
    return warnings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline")
    parser.add_argument("candidate")
    parser.add_argument("--required-gain-percent", type=float, default=50.0,
                        help="Required gain for this comparison (default: 50).")
    args = parser.parse_args()
    if not math.isfinite(args.required_gain_percent) or args.required_gain_percent < 0:
        parser.error("required gain must be a finite nonnegative percentage")
    gate = f"single_pair_{args.required_gain_percent:g}_percent_gate"
    old, new = read_run(args.baseline), read_run(args.candidate)
    warnings = []
    for run in (old, new):
        if not run[3]:
            warnings.append(f"incomplete run: {run[0]}")
        if not run[2]:
            warnings.append(f"no scene results: {run[0]}")
    for key in ("warmSeconds", "sampleSeconds", "survival", "walk", "groundStart", "ablation", "runtimeAblation", "terrainAblation", "modelAblation", "entityAblation", "captureTerrain", "scenario", "profileSections", "coarseSections", "flightSample", "matchVanillaEntityDistance", "stableCreative", "fixedCameraPath", "fixedFinalCapture", "deterministicCombat", "classDumpAgentSha256", "initialWorldSha256", "fixtureSha256"):
        if key in ("initialWorldSha256", "fixtureSha256") and (key not in old[1] or key not in new[1]):
            warnings.append(f"older run lacks {key}; inspect its snapshot provenance separately")
            continue
        if old[1].get(key, False) != new[1].get(key, False):
            warnings.append(f"setup mismatch {key}: {old[1].get(key)} / {new[1].get(key)}")
    for run in (old, new):
        if "initialOptions" not in run[1]:
            warnings.append(f"older run lacks initialOptions: {run[0]}")
    old_options = {item["name"]: item["sha256"] for item in old[1].get("initialOptions", [])}
    new_options = {item["name"]: item["sha256"] for item in new[1].get("initialOptions", [])}
    if old_options != new_options:
        warnings.append("initial graphics option file hashes differ; inspect settings before accepting comparison")
    if old[2].keys() != new[2].keys():
        warnings.append("scene sets differ")
    ratios = []
    for key in ("requestedCameraYaw", "requestedCameraPitch"):
        if old[1].get(key) != new[1].get(key):
            warnings.append(f"requested camera mismatch {key}")
    tail_regressions = []
    print(f"baseline={old[0]}\ncandidate={new[0]}")
    for scene in sorted(old[2].keys() & new[2].keys()):
        before, after = old[2][scene], new[2][scene]
        if "entity_render_distance_weight" in before or "entity_render_distance_weight" in after:
            print(f"{scene} entity_render_distance_weight: {before.get('entity_render_distance_weight')} / {after.get('entity_render_distance_weight')}")
            if before.get("entity_render_distance_weight") != after.get("entity_render_distance_weight"):
                warnings.append(f"{scene}: entity render distance weights differ; render workloads are not equivalent")
        if before.get("entity_distance_policy") != after.get("entity_distance_policy"):
            warnings.append(f"{scene}: entity distance test policies differ")
        ratio = float(after["fps"]) / float(before["fps"])
        ratios.append(ratio)
        print(f"{scene}: {float(before['fps']):.3f} -> {float(after['fps']):.3f} FPS ({(ratio - 1) * 100:+.2f}%)")
        for key in ("frame_p95_ms", "frame_p99_ms", "server_p95_ms", "gpu_p50_ms"):
            if key not in before or key not in after:
                continue
            a, b = float(before[key]), float(after[key])
            print(f"  {key}: {a:.4f} -> {b:.4f} ({(b / a - 1) * 100:+.2f}%)")
            if key in ("frame_p95_ms", "frame_p99_ms") and b > a:
                tail_regressions.append(f"{scene}:{key}")
        for key in ("frame_p95_ms", "frame_p99_ms"):
            if key not in before or key not in after:
                warnings.append(f"{scene}: missing slow-frame evidence {key}")
        for key in ("position", "camera", "terrain", "entities_min", "entities_max"):
            print(f"  {key}: {before.get(key)} / {after.get(key)}")
        if old[1].get("fixedCameraPath") or new[1].get("fixedCameraPath"):
            warnings.extend(f"{scene}: {warning}" for warning in fixed_path_view_warnings(before, after))
            for key in ("render_camera_path", "render_camera_position", "render_camera_angles"):
                print(f"  {key}: {before.get(key)} / {after.get(key)}")
        if "gameplay_scenario" in before or "gameplay_scenario" in after:
            for key in ("gameplay_fixture_version", "gameplay_equipment_quality", "gameplay_start_gear", "gameplay_start_effects"):
                if key not in before or key not in after:
                    warnings.append(f"{scene}: older fixture lacks {key}; gameplay equivalence unverified")
                elif before[key] != after[key]:
                    warnings.append(f"{scene}: mismatched {key}")
            for key in sorted(set(before) | set(after)):
                if key.startswith("gameplay_initial_attribute.") and before.get(key) != after.get(key):
                    warnings.append(f"{scene}: starting attribute mismatch {key}: {before.get(key)} / {after.get(key)}")
                if key.startswith("gameplay_opponent_profile.") and before.get(key) != after.get(key):
                    warnings.append(f"{scene}: opponent attributes/abilities differ: {key}")
            if before.get("gameplay_scenario") == "survival":
                warnings.extend(f"{scene}: {warning}" for warning in survival_view_warnings(before, after))
                distances = [float(v.get("gameplay_walk_distance", 0)) for v in (before, after)]
                if max(distances) > max(1, min(distances)) * 1.1:
                    warnings.append(f"{scene}: walking distance differs by over10%; inspect speed/effects and repeat")
            for key in ("gameplay_server_ticks", "gameplay_attacks", "gameplay_zombie_hits",
                        "gameplay_zombie_deaths", "gameplay_player_hits", "gameplay_walk_distance"):
                print(f"  {key}: {before.get(key)} / {after.get(key)}")
            if before.get("gameplay_scenario") == "combat":
                if before.get("gameplay_spawned") != after.get("gameplay_spawned"):
                    warnings.append(f"{scene}: combat wave counts differ: {before.get('gameplay_spawned')} / {after.get('gameplay_spawned')}")
                hits = [float(v.get("gameplay_player_hits", 0)) for v in (before, after)]
                if max(hits) >= 10 and max(hits) > max(1, min(hits)) * 2:
                    warnings.append(f"{scene}: combat exposure diverged substantially; repeat with comparable activity before attributing FPS difference")
                if before.get("gameplay_fixture_version") != after.get("gameplay_fixture_version"):
                    warnings.append(f"{scene}: gameplay fixture version mismatch")
                if any(float(v.get("gameplay_external_player_hits", 0)) > 0 for v in (before, after)):
                    warnings.append(f"{scene}: external damage detected; inspect source counts before attributing combat FPS differences")
        for key in ("dimension", "creative", "fov"):
            if key not in before or key not in after:
                warnings.append(f"{scene}: older summary lacks {key}")
            elif before.get(key) != after.get(key):
                warnings.append(f"{scene}: {key} mismatch")
        if scene == "loaded_static" and not (old[1].get("fixedCameraPath") and new[1].get("fixedCameraPath")):
            for key in ("position", "camera"):
                if before.get(key) != after.get(key):
                    warnings.append(f"{scene}: stationary {key} mismatch")
        for values in (before, after):
            if "gameplay_scenario" in values and values.get("gameplay_valid_activity") != "true":
                warnings.append(f"{scene}: insufficient gameplay activity")
            if values.get("pilot_only") != "false" or values.get("display_active_all_frames") != "true":
                warnings.append(f"{scene}: pilot or inactive display")
    if ratios:
        gain = math.exp(sum(map(math.log, ratios)) / len(ratios)) - 1
        print(f"scene_geometric_mean_gain_percent={gain * 100:.6f}")
    print("These are paired scene ratios from independent live worlds; no confidence interval is inferred from correlated frames.")
    print("Representative views, image correctness, driver/thermal stability and multiple run repeats require separate review.")
    for warning in dict.fromkeys(warnings):
        print("WARNING: " + warning)
    if warnings or not ratios:
        print(gate + "=not_evaluable")
    elif gain < args.required_gain_percent / 100.0 or tail_regressions:
        print(gate + "=failed")
    else:
        print(gate + "=observed_only_requires_independent_repeats")
    if tail_regressions:
        print("slow_frame_regressions=" + ",".join(tail_regressions))
    if warnings:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
