#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# --- 加载环境配置 ---
if [ ! -f ".env.lightrag" ]; then
    echo "[ERROR] 缺少 .env.lightrag 配置文件"
    echo "  请复制 .env.lightrag.example 为 .env.lightrag 并填入真实值"
    exit 1
fi
source .env.lightrag

# --- 检查必需的配置 ---
REQUIRED_VARS=(
    LIGHTRAG_LLM_BASE_URL LIGHTRAG_LLM_MODEL LIGHTRAG_LLM_API_KEY
    LIGHTRAG_EMBED_BASE_URL LIGHTRAG_EMBED_MODEL LIGHTRAG_EMBED_API_KEY
    LIGHTRAG_EMBED_DIM LIGHTRAG_DATA_DIR
)
MISSING=()
for v in "${REQUIRED_VARS[@]}"; do
    if [ -z "${!v:-}" ]; then
        MISSING+=("$v")
    fi
done
if [ ${#MISSING[@]} -gt 0 ]; then
    echo "[ERROR] 缺少以下必需配置: ${MISSING[*]}"
    exit 1
fi

# --- Python 虚拟环境 ---
if [ ! -d "venv" ]; then
    echo "[INFO] 创建 Python 虚拟环境..."
    python3 -m venv venv
fi
source venv/bin/activate

# --- 依赖安装 ---
if ! python3 -c "import lightrag" 2>/dev/null; then
    echo "[INFO] 安装依赖..."
    pip install -r requirements.txt -q
fi

# --- 启动 ---
HOST="${LIGHTRAG_HOST:-127.0.0.1}"
PORT="${LIGHTRAG_PORT:-9621}"

echo "[INFO] LightRAG 启动 → http://${HOST}:${PORT}"
python3 lightrag_server.py
