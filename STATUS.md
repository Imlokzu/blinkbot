# Поточний статус

**Дата огляду:** 2026-09-03
**Scope:** документація, roadmap і стан компонентів за `HANDOFF.md`, специфікацією та README модулів.

Це оперативний сумарний файл: після кожного суттєвого блоку тут мають з’являтися
статус, короткий опис, перевірки та залишкові ризики. Детальний історичний
контекст залишається в [`HANDOFF.md`](HANDOFF.md).

**Emotions-row review: 2026-10-25.** The corrected row below reflects source
and test coverage; the other rows retain their historical review dates.
For current screen behavior, consult [`DESIGN.md`](DESIGN.md),
[`SCREEN-OFFICE.md`](Virtual%20Bot/docs/SCREEN-OFFICE.md), and
[`SCREEN-VOICE.md`](Virtual%20Bot/docs/SCREEN-VOICE.md).

## Roadmap і розділи

| Напрям | Статус | Опис | Перевірка |
|---|---|---|---|
| Фаза 0: програмний прототип | Виконується | Віртуальна робота модулів до придбання заліза | Частково перевірено |
| Крок 1: зір | Зроблено | Vision Agent: детекція обличчя/руху, HTTP і MJPEG | Тести й ручні перевірки виконані |
| Крок 2: RAG/пам’ять | Зроблено | Довготривала пам’ять, reasoning і пошук | `memory.py`, `dream_cycle.py`, `test_memory_reasoning.py` |
| Крок 3: голос | Зроблено з ризиком | Whisper STT → OpenClaw → pyttsx3 TTS | Перевірено; є критичний баг |
| Step 4: emotions | Implemented | Reply tags, streaming tag filtering, transient-state settling and ambient moods | `Virtual Bot/emotions.py`, `vision_watcher.py`, `tests/test_stream_emotion.py`, and `tests/test_spontaneous_mood.py`; source inspection, not a live-device test |
| Крок 5: камери | Частково | Інтеграція камер і просторове сприйняття | Частково |
| Крок 6: UI/дисплей | Частково зроблено | Pixel eyes, екрани й WebSocket | Build і 6 pytest чисті |
| Віртуальний бот | У роботі | Вебпанель до появи фізичного пристрою; Agent Talk/Watch і учасники розмови | Базово: 218 passed, 1 skipped; `npm run build` чистий |

Remote Control і Device Setup Wizard пройшли доступну верифікацію.

## Поточний зріз: 2026-09-03

- Гілка: `feat/bot-tools-workspace-and-chat-ui`, HEAD `070ef2b`.
- У робочій копії незакомічена фіча «учасники розмови»: бекенд, панель,
  зібрані статичні assets і тест `tests/test_chat_participants.py`.
- Перед комітом фіча має закрити leave-контракт, безпечну нормалізацію імен,
  прибирання порожніх сесій без обходу `_prune`, а також UI-помилки гарячої
  клавіші й системних повідомлень.
- Базові перевірки поточного зрізу: `.venv/bin/pytest -q` — **218 passed,
  1 skipped**; `npm run build` у `Virtual Bot/chat-panel` — чистий.

## Критичні та відомі баги

1. **Voice Loop (P1):** повторне використання pyttsx3 на macOS може зависати або мовчати; у разі помилки кешований двигун не скидається.
2. **Vision Agent (P2):** великі зображення декодуються до перевірки ліміту; порожній буфер може дати 500 замість 400.
3. **Display (P2):** cleanup WebSocket у StrictMode може залишати дублікати; спільний idle-таймер конфліктує з довгими кастомними екранами.
4. **Низький пріоритет:** вузька гонка shutdown камери та дрейф таймера дисплея.

## Наступні кроки

1. Завершити й закомітити фічу учасників розмови за узгодженим API-контрактом.
2. Запустити adversarial review і smoke test Virtual Bot, включно з
   path-traversal → 400.
3. Виправити P1 Voice Loop, потім P2 Vision Agent і display.
4. Завершити офлайн `wiki.html` українською.
5. Оновлювати цей файл після суттєвих змін.

Статуси є оперативними й не означають, що невиконані пункти готові до production.
