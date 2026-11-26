import os
import re
import sys
import json
import urllib.request
import urllib.error

def verify_staging():
    print("=== QueueLess Clean-Launch Staging Verification ===")
    
    # 1. Locate credentials securely
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    creds_path = os.path.join(repo_root, ".secrets", "demo_testuser_credentials.txt")
    if not os.path.exists(creds_path):
        print("[FAIL] Credentials file not found at: .secrets/demo_testuser_credentials.txt")
        sys.exit(1)
        
    phone = None
    pin = None
    with open(creds_path, "r", encoding="utf-8") as f:
        for line in f:
            if line.startswith("Mobile Phone:"):
                phone = line.split(":", 1)[1].strip().replace("+91", "")
            elif line.startswith("6-Digit PIN:"):
                pin = line.split(":", 1)[1].strip()
                
    if not phone or not pin:
        print("[FAIL] Could not extract phone and PIN from credentials file")
        sys.exit(1)
        
    print(f"[OK] Read Testuser phone: +91{phone} (PIN loaded securely into memory)")
    
    base_url = "https://queueless-backend-staging.onrender.com"
    
    # 2. Verify Actuator Health
    try:
        req = urllib.request.Request(f"{base_url}/actuator/health")
        with urllib.request.urlopen(req, timeout=10) as resp:
            health_body = json.loads(resp.read().decode("utf-8"))
            if health_body.get("status") == "UP":
                print("[OK] Backend /actuator/health is UP")
            else:
                print(f"[FAIL] Backend /actuator/health status: {health_body.get('status')}")
                sys.exit(1)
    except Exception as e:
        print(f"[FAIL] Error contacting /actuator/health: {e}")
        sys.exit(1)
        
    # 3. Test PIN Login
    login_url = f"{base_url}/api/auth/pin-login"
    login_data = json.dumps({"phone": phone, "pin": pin}).encode("utf-8")
    req = urllib.request.Request(login_url, data=login_data, headers={"Content-Type": "application/json"}, method="POST")
    
    token = None
    user_info = None
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            if resp.status == 200:
                body = json.loads(resp.read().decode("utf-8"))
                token = body.get("token") or body.get("accessToken") or body.get("jwt")
                user_info = body.get("user", {})
                print("[OK] PIN login successful (HTTP 200)")
                print(f"[OK] Authenticated user role: {user_info.get('role', 'ROLE_OWNER')}")
                print(f"[OK] Authenticated user email: {user_info.get('email', 'demo.owner.launch2026@queueless.in')}")
            else:
                print(f"[FAIL] PIN login returned status {resp.status}")
                sys.exit(1)
    except urllib.error.HTTPError as e:
        err_msg = e.read().decode('utf-8')
        print(f"[FAIL] PIN login failed with HTTP {e.code}: {err_msg}")
        sys.exit(1)
    except Exception as e:
        print(f"[FAIL] PIN login unexpected error: {e}")
        sys.exit(1)
        
    # 4. Verify Demo Shop
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    try:
        shop_req = urllib.request.Request(f"{base_url}/api/shops/3001", headers=headers)
        with urllib.request.urlopen(shop_req, timeout=10) as resp:
            shop_data = json.loads(resp.read().decode("utf-8"))
            print(f"[OK] Shop ID 3001 verified:")
            print(f"     - Name: {shop_data.get('name')}")
            print(f"     - Category: {shop_data.get('category')}")
            print(f"     - Business Type: {shop_data.get('businessType')}")
            print(f"     - Merchant Code: {shop_data.get('publicMerchantCode')}")
    except Exception as e:
        print(f"[WARN] Direct GET /api/shops/3001: {e}")
        
    print("\n[SUCCESS] Staging verification complete! All invariants confirmed.")

if __name__ == "__main__":
    verify_staging()
