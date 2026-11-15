#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
JAR_PATH="$DIR/backend/target/queueless-backend-1.0.0.jar"

echo "========================================================"
echo "         QueueLess - Digital Token Register            "
echo "========================================================"
echo ""

if ! command -v java >/dev/null 2>&1; then
    echo "[ERROR] Java is not installed or not in your PATH."
    echo "Please install Java 21 or later from https://adoptium.net/"
    exit 1
fi

if [ ! -f "$JAR_PATH" ]; then
    echo "[INFO] Application JAR not found. Packaging with Maven..."
    (cd "$DIR/backend" && mvn clean package -DskipTests)
fi

echo "[OK] Launching QueueLess on http://localhost:8080 ..."
echo ""
echo "========================================================"
echo "  Demo Mobile Login: 9876543210 (PIN: 123456)"
echo "  Demo Email Login:  owner@queueless.com / password123"
echo "  Admin Portal:      http://localhost:8080/admin"
echo "  Admin Master Key:  admin-secret-queueless-2026"
echo "========================================================"
echo ""

# Try opening default browser on Linux or macOS
if command -v xdg-open >/dev/null 2>&1; then
    (sleep 2 && xdg-open "http://localhost:8080") &
elif command -v open >/dev/null 2>&1; then
    (sleep 2 && open "http://localhost:8080") &
fi

exec java -jar "$JAR_PATH" --spring.profiles.active=local
