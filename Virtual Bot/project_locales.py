"""Project registry messages use the same locale-key convention as the screen."""

STRINGS: dict[str, dict[str, str]] = {
    "uk": {
        "project.invalid_id": "Некоректний ідентифікатор проєкту",
        "project.invalid_path": "Небезпечний або змінений шлях проєкту",
        "project.not_found": "Немає такого проєкту",
        "project.name_required": "Потрібна назва",
        "project.untitled": "Без назви",
    },
    "en": {
        "project.invalid_id": "Invalid project identifier",
        "project.invalid_path": "Unsafe or replaced project path",
        "project.not_found": "Project not found",
        "project.name_required": "A name is required",
        "project.untitled": "Untitled",
    },
}


def t(key: str, lang: str = "uk") -> str:
    return STRINGS.get(lang, STRINGS["uk"]).get(key, key)
