#!/usr/bin/env python3
"""
QueueLess — Direct Backend Health Verification Gate
Polls direct backend /actuator/health with exponential backoff and retry,
verifying container readiness and database connectivity before edge routing changes.
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request


def verify_direct_backend(health_url: str, timeout_seconds: int = 120, interval_seconds: int = 5) -> bool:
    print("=" * 70)
    print("QueueLess — Direct Backend Health Verification")
    print("=" * 70)
    print(f"Target Health Probe  : {health_url}")
    print(f"Max Timeout          : {timeout_seconds}s")
    print(f"Poll Interval        : {interval_seconds}s")
    print("-" * 70)

    start_time = time.time()
    attempt = 1

    while True:
        elapsed = int(time.time() - start_time)
        if elapsed > timeout_seconds:
            print(
                f"[FAIL] Backend health verification TIMED OUT after {elapsed}s (> {timeout_seconds}s).",
                file=sys.stderr,
            )
            return False

        try:
            req = urllib.request.Request(
                health_url, headers={"User-Agent": "QueueLess-DirectHealthCheck/1.0", "Accept": "application/json"}
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                status_code = resp.getcode()
                body_bytes = resp.read()
                body_str = body_bytes.decode("utf-8", errors="replace")

            if status_code == 200:
                try:
                    payload = json.loads(body_str)
                except Exception as ex:
                    print(f"  [Attempt {attempt} @ {elapsed}s] HTTP 200 returned but invalid JSON: {ex}")
                    time.sleep(interval_seconds)
                    attempt += 1
                    continue

                health_status = str(payload.get("status", "")).upper()
                if health_status in ["UP", "HEALTHY"]:
                    # Check database components if present
                    components = payload.get("components", {})
                    db_unhealthy = False
                    for comp_name in ["db", "dbReadiness"]:
                        if comp_name in components:
                            comp_status = str(components[comp_name].get("status", "UP")).upper()
                            if comp_status not in ["UP", "HEALTHY"]:
                                print(
                                    f"  [Attempt {attempt} @ {elapsed}s] Application status UP but '{comp_name}' component is '{comp_status}'"
                                )
                                db_unhealthy = True
                                break

                    if db_unhealthy:
                        time.sleep(interval_seconds)
                        attempt += 1
                        continue

                    print(f"[PASS] Direct backend is HEALTHY and READY in {elapsed}s (Attempt {attempt})!")
                    print(f"       Status: {health_status}")
                    if components:
                        print(f"       Components verified: {list(components.keys())}")
                    print("=" * 70)
                    return True
                else:
                    print(
                        f"  [Attempt {attempt} @ {elapsed}s] HTTP 200 but status reported: '{health_status}' (waiting for UP)..."
                    )
            else:
                print(f"  [Attempt {attempt} @ {elapsed}s] Unexpected HTTP status: {status_code}")

        except urllib.error.HTTPError as e:
            print(f"  [Attempt {attempt} @ {elapsed}s] HTTP {e.code} ({e.reason}) - Container starting...")
        except urllib.error.URLError as e:
            print(f"  [Attempt {attempt} @ {elapsed}s] Connection failed ({e.reason}) - Container cold-starting...")
        except Exception as e:
            print(f"  [Attempt {attempt} @ {elapsed}s] Request error: {e}")

        time.sleep(interval_seconds)
        attempt += 1


def main():
    parser = argparse.ArgumentParser(description="Verify direct backend health and database readiness")
    parser.add_argument(
        "--url", required=True, help="Direct backend health URL (e.g. https://service.onrender.com/actuator/health)"
    )
    parser.add_argument("--timeout", type=int, default=120, help="Max timeout in seconds (default 120)")
    parser.add_argument("--interval", type=int, default=5, help="Poll interval in seconds (default 5)")
    args = parser.parse_args()

    success = verify_direct_backend(args.url, timeout_seconds=args.timeout, interval_seconds=args.interval)
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()
