"""Quick API integration test for maintenance task AI verification workflow"""
import requests
import time
import json

BASE = "http://localhost:8080/weixiu"
s = requests.Session()

def p(label, resp):
    print(f"\n{'='*60}")
    print(f"[{label}] {resp.status_code}")
    try:
        data = resp.json()
        print(json.dumps(data, ensure_ascii=False, indent=2))
    except:
        print(resp.text[:500])
    return resp.json() if resp.ok else None

# 1. Login
r = p("1. LOGIN", s.post(f"{BASE}/user/login", json={"username": "4", "password": "123456"}))
if not r or r["code"] != "200":
    print("Login failed!")
    exit(1)

# 2. Use existing GENERATED task
task_id = 2059976679017947137

# 3. Get task detail + steps
r = p("3. GET TASK DETAIL", s.get(f"{BASE}/task/{task_id}"))
if not r or r["code"] != "200":
    print("Get task failed!")
    exit(1)

status = r["data"]["status"]
steps = r["data"].get("steps", [])
print(f"\n>>> Task status: {status}, Steps: {len(steps)}")
for st in steps:
    print(f"    [{st['id']}] {st['title']} | status={st['status']} | requirePhoto={st.get('requirePhoto')} requireNote={st.get('requireNote')}")

# 4. Start execution (GENERATED -> EXECUTING)
if status == "GENERATED":
    r = p("4. START EXECUTE", s.post(f"{BASE}/task/{task_id}/start"))
    if not r or r["code"] != "200":
        print("Start execute failed!")
        exit(1)
elif status != "EXECUTING":
    print(f">>> Task is in {status}, cannot proceed")
    exit(1)

# 5. Execute first PENDING step (submit evidence)
first_step = None
for st in steps:
    if st["status"] == "PENDING":
        first_step = st
        break

if not first_step:
    print(">>> No PENDING step found!")
    exit(1)

step_id = first_step["id"]
print(f"\n>>> Submitting evidence for step {step_id}: {first_step['title']}")

execute_body = {}
if first_step.get("requirePhoto"):
    execute_body["images"] = ["https://example.com/test-photo-bearing-check.jpg"]
if first_step.get("requireNote"):
    execute_body["note"] = "Checked and completed as per procedure. All parameters within spec."

# Always include some data even if not required
if "images" not in execute_body:
    execute_body["images"] = ["https://example.com/test-photo.jpg"]
if "note" not in execute_body:
    execute_body["note"] = "Step completed successfully. Verified correct operation."

r = p("5. EXECUTE STEP", s.post(f"{BASE}/task/{task_id}/steps/{step_id}/execute", json=execute_body))
if not r or r["code"] != "200":
    print(f">>> Execute step failed!")
    exit(1)

print(f">>> Step status after submit: {r['data']['status']}")

# 6. Poll for AI verification result (SUBMITTED -> COMPLETED/AI_PASSED/PENDING_REVIEW)
print("\n>>> Waiting for AI verification result...")
step_data = None
for i in range(20):
    time.sleep(3)
    r = s.get(f"{BASE}/task/{task_id}/steps")
    if r.ok:
        data = r.json()
        if data["code"] == "200":
            for st in data["data"]:
                if st["id"] == step_id:
                    step_data = st
                    break
            if step_data:
                st_status = step_data["status"]
                print(f"  Poll {i+1}: status={st_status}, aiPass={step_data.get('aiPass')}, confidence={step_data.get('aiConfidence')}")
                if st_status in ("COMPLETED", "AI_PASSED", "PENDING_REVIEW"):
                    break
    time.sleep(1)
else:
    print(">>> Timeout waiting for AI verification")

if step_data:
    p("6. AI VERIFICATION RESULT", r)
    print(f"\n>>> AI Verdict: pass={step_data.get('aiPass')}, confidence={step_data.get('aiConfidence')}, reason={step_data.get('aiReason')}")

# 7. If PENDING_REVIEW, test admin review
if step_data and step_data["status"] == "PENDING_REVIEW":
    print("\n>>> Step needs admin review, testing APPROVE...")
    r = p("7. ADMIN REVIEW (approve)", s.post(f"{BASE}/task/{task_id}/steps/{step_id}/review", json={
        "approved": True,
        "reviewNote": "Verified - work looks correct"
    }))
    if r and r["code"] == "200":
        print(f">>> Step status after review: {r['data']['status']}")

# 8. Final summary
r = p("8. FINAL STATUS", s.get(f"{BASE}/task/{task_id}"))
if r and r["code"] == "200":
    print(f"\n>>> Final task status: {r['data']['status']}")
    for st in r["data"].get("steps", []):
        print(f"    [{st['id']}] {st['title']}")
        print(f"        status={st['status']}, aiPass={st.get('aiPass')}, confidence={st.get('aiConfidence')}")
        if st.get("aiReason"):
            print(f"        reason={st['aiReason'][:80]}")

print("\n" + "="*60)
print("TEST COMPLETE")
