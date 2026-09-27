#!/usr/bin/env bash
# «Клод Бот» — Virtual Bot: запуск бекенду панелі керування.
# Створює .venv (якщо нема), ставить залежності, стартує uvicorn на 127.0.0.1:8100.
set -euo pipefail

cd "$(dirname "$0")"

# yt-dlp dropped Python 3.9, and on 3.9 it stops getting YouTube streams at
# all (every /api/music/stream answered 502). macOS's /usr/bin/python3 is
# 3.9, so pick the newest 3.10+ on PATH instead of trusting "python3".
pick_python() {
  for py in python3.14 python3.13 python3.12 python3.11 python3.10 python3; do
    if command -v "$py" >/dev/null 2>&1 &&
       "$py" -c 'import sys; sys.exit(sys.version_info < (3, 10))' 2>/dev/null; then
      echo "$py"; return 0
    fi
  done
  return 1
}

if [ -x .venv/bin/python ] && ! .venv/bin/python -c 'import sys; sys.exit(sys.version_info < (3, 10))' 2>/dev/null; then
  echo "[start.sh] .venv is on Python < 3.10; rebuild it: mv .venv .venv-old && ./start.sh" >&2
fi

if [ ! -d .venv ]; then
  PY="$(pick_python)" || { echo "[start.sh] Need Python 3.10+ (brew install python@3.12)" >&2; exit 1; }
  echo "[start.sh] Creating .venv with $PY ..."
  "$PY" -m venv .venv
fi

echo "[start.sh] Встановлюю залежності ..."
./.venv/bin/pip install --quiet --disable-pip-version-check -r requirements.txt

# Секрети живуть у .env поряд із config.yaml. Його ЧИТАЄ сам app_config на
# імпорті (надійно, за будь-якого способу запуску, з пріоритетом справжніх
# env-змінних) — тому шелом .env НЕ сорсимо: під `set -euo pipefail` довільний
# вміст .env (пробіли, $VAR тощо) міг би обірвати запуск. Тут лише замикаємо
# права доступу 600, щоб ключ не читали інші локальні користувачі.
if [ -f .env ]; then
  chmod 600 .env 2>/dev/null || true
fi

echo "[start.sh] Запускаю Virtual Bot на http://127.0.0.1:8100 ..."
# --timeout-graceful-shutdown: відкриті SSE-стріми (/api/events) нескінченні —
# без ліміту graceful shutdown чекав би на них вічно
exec ./.venv/bin/python -m uvicorn main:app --host 127.0.0.1 --port 8100 \
  --timeout-graceful-shutdown 3
