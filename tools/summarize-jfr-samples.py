"""Summarize external JFR execution samples; counts are not frame-time measurements."""
import argparse
import collections
import datetime
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("json_file", help="jfr print --json --events ExecutionSample,NativeMethodSample --stack-depth 64 output")
parser.add_argument("--after", required=True, help="First measured scene's ISO timestamp, including timezone")
args = parser.parse_args()
after = datetime.datetime.fromisoformat(args.after)
events = json.loads(Path(args.json_file).read_text(encoding="utf-8-sig"))["recording"]["events"]
threads = collections.Counter()
top = collections.Counter()
inclusive = collections.Counter()
types = collections.Counter()
client_samples = 0
for event in events:
    values = event["values"]
    timestamp = datetime.datetime.fromisoformat(values["startTime"].replace("Z", "+00:00"))
    if timestamp < after:
        continue
    thread = values.get("sampledThread") or values.get("eventThread") or {}
    name = thread.get("javaName") or thread.get("osName") or "unknown"
    threads[name] += 1
    if name != "Client thread":
        continue
    client_samples += 1
    types[event["type"]] += 1
    methods = []
    for frame in (values.get("stackTrace") or {}).get("frames", []):
        method = frame["method"]
        methods.append(method["type"]["name"].replace("/", ".") + "." + method["name"])
    if methods:
        top[methods[0]] += 1
        inclusive.update(set(methods))
print("Client samples:", client_samples, dict(types))
print("Thread sample counts:", threads.most_common(12))
for title, counts in (("Top frame", top), ("Inclusive", inclusive),
                      ("ICE inclusive", collections.Counter({k: v for k, v in inclusive.items() if k.startswith("dev.rlcraft.ice")}))):
    print("\n" + title)
    for name, count in counts.most_common(30):
        print(f"{count:6d} {100 * count / max(1, client_samples):6.2f}% {name}")
print("\nSample proportions describe sampled stacks, not exact CPU milliseconds or independent FPS trials.")
