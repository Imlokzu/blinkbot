# CLAUDE.md — правила для Claude Code

`AGENTS.md` читають **усі** інструменти, і він лишається обовʼязковим. Цей
файл — те, що стосується саме Claude Code, бо він працює інакше за робочого
агента: сесія довга, поруч живий власник, а контекст стискається на ходу.

---

## 1. Коміть. Усе, що робиш — коміть

**Це перше правило, і воно важливіше за решту файлу.**

Сесія може обірватись, контекст — стиснутись, машина — заснути. Незакомічене
зникає без сліду, і відкотитись нема куди. Тому:

- закінчив логічний шматок — **одразу коміт**, не «складу все в кінці»;
- зробив правку, прогнав тести, вони зелені — коміт;
- збираєшся братись за наступне — спершу поглянь, чи попереднє закомічене.

Commit as the owner, in English, and push to GitHub. Every logical change.
Do not leave it local.

- **Author:** `Imlokzu <lokzuhd@gmail.com>` — author and committer. Do not
  commit as anyone else. Do not change `git config`. Set the identity on
  that commit only, as below.
- **English only.** Commit messages, code, comments, and docs you add are
  English. The language of the request does not change this.
- **Push.** After the commit succeeds, push the current branch to `origin`.
  If it has no upstream, `git push -u origin HEAD`. Never force-push. Never
  switch branches in order to push.

```bash
git status                       # always first — see what is there
git add <only the files you changed>   # never -A and never . blindly
GIT_AUTHOR_NAME='Imlokzu' \
GIT_AUTHOR_EMAIL='lokzuhd@gmail.com' \
GIT_COMMITTER_NAME='Imlokzu' \
GIT_COMMITTER_EMAIL='lokzuhd@gmail.com' \
git commit -m "fix(virtual-bot): ..."
git push                         # or: git push -u origin HEAD
```

Message format and scopes are in `AGENTS.md`, section 1.

> As of 2026-09-19 this repo had **42 uncommitted paths**, and the last
> commit was **two weeks old**. That is why this rule comes first.

Do not change the current branch.

### Delegate routine asynchronous work

When Jules is configured, prefer handing off routine, self-contained, and
non-urgent work that can wait for a pull request, such as translating comments
or documentation, mechanical refactors, or repetitive test updates. Give Jules
the exact scope, acceptance criteria, and validation commands. Keep work in the
current session when it needs immediate completion, interactive decisions,
local-only context, or secrets Jules cannot access.

## 2. Чим Claude Code відрізняється від робочого агента

**Поруч є людина — питай замість здогадок.** Робочий агент мусить вгадувати,
бо спитати нема кого. Ти можеш. Але питай лише там, де різні відповіді дадуть
різну роботу; дрібні рішення приймай сам і рухайся далі.

**Доповідай чесно.** Тести впали — скажи з виводом. Крок пропустив — скажи, що
пропустив. Не «здається, працює», а «перевірив ось так, ось результат».

**Не роби за власника незворотного.** Перезапуск його служб, видалення,
відправка назовні (крім `git push` поточного бранча, який обовʼязковий після
кожного коміту) — питай. Особливо:

- **OpenClaw не чіпати.** Конфіг правити можна, процес — ні.
- **Запущеного бота не перезапускати.** Він може крутити стару версію коду —
  це нормально, це вибір власника. Перевіряти свої зміни треба в окремому
  процесі, а не рестартом його служби.

**Перевіряй у тому ж процесі, що правив.** Класична пастка цього репо: правиш
код, тестуєш через `curl` на запущений сервер — а він працює на коді
двотижневої давнини, і тест нічого не показує.

**Secrets always go through the agents' vault.** Every password, API key, and
login lives in the agents' Bitwarden (`vault@ag.waveio.me`, CLI profile
`~/.config/bw-agents`). Need one: look there first. Got a new one: save it
there in the same step. Never print a secret in chat or notes. Commands and
rules are in `AGENTS.md`, section 3.

## 3. Пастки цього репо

**У шляхах є пробіли.** Завжди лапки: `cd "Virtual Bot"`.

**Тести без `PYTHONPATH` не запускаються:**

```bash
cd "Virtual Bot"
PYTHONPATH="$PWD" .venv/bin/pytest tests/ -q
```

Без нього — `ModuleNotFoundError: No module named 'chat_store'` ще на
`conftest.py`. Системного `pytest` теж нема, лише у `.venv`.

**Системний промпт бота розводиться.** Блок `tools` займає більше половини
промпту, і нове правило, дописане в кінець, модель ігнорує. Перевіряй правило
**окремо** через `brains.chat_openclaw(питання, ПРАВИЛО, [])` — якщо окремо
працює, а разом ні, це розведення, лікується стисненням.

**Мова запиту не задає мову коду.** Тебе можуть попросити українською — це не
означає, що українською треба писати назви змінних. Власник свідомо промптить
англійською саме тому, що не хоче українського коду: підтримувати такий код
гірше. Правило просте й не залежить від того, якою мовою до тебе звернулись:

**У репозиторії все англійською. Українська лишається у двох місцях: Обсідіан
власника і файли локалізації.**

Причина — реліз, а не смак: репо збираються опублікувати, а код із
україномовними коментарями стороннім не віддаси.

| Що | Якою мовою |
|---|---|
| назви, типи, поля API, імена файлів, коміти | **англійською** |
| **коментарі й докстрінги** | **англійською** — пояснюй «чому» так само детально, просто іншою мовою |
| будь-що, що бачить користувач | **ключем, не рядком** |
| нотатки, оцінки, міркування власника | **українською, в Обсідіані** |

Вшитий текст в інтерфейсі — не готова робота, навіть якщо текст правильний.
Еталон — `Virtual Bot/static/screen/i18n.js`: 604 ключі, `uk` і `en`,
виклик `t("state.head")`.

**Поточний стан ще не такий.** Заміряно 19.09.2026: ~3220 рядків українських
коментарів і ~3329 рядків вшитих українських рядків; дашборд i18n не має
взагалі. Чіпаєш файл — приводь до ладу те, що чіпаєш: переклади коментарі, які
редагуєш, і винеси рядки, які додаєш. **Міграцію всього репо самостійно не
починай** — це рішення власника, коли на неї витрачатись. Подробиці в
`AGENTS.md`, розділ 5.

## 4. Що прочитати перед роботою

| Файл | Коли |
|---|---|
| `AGENTS.md` | завжди, перед першою правкою |
| `STATUS.md` · `HANDOFF.md` | щоб зрозуміти стан — але звіряй із реальністю, вони відстають |
| `Virtual Bot/docs/SCREEN-PLATFORM.md` | екран, магазин, застосунки |
| `Virtual Bot/dashboard/DESIGN.md` | будь-що у вебпанелі |

Обсідіан власника (`Проєкти/Клод бот/`) — жива документація: архітектура,
дорожня карта, відомі баги, оцінки етапу. Там свіжіше, ніж у репо.

## 5. Наприкінці задачі

- [ ] тести модуля зелені, вивід показано власнику
- [ ] `git status` чистий — усе своє закомічено як `Imlokzu <lokzuhd@gmail.com>` і запушено в `origin`
- [ ] у дифі нема ключів і токенів
- [ ] сказано чесно, що зроблено, а що ні
