"""Localized generation failures for conversation replies."""
MESSAGES = {
    'en': {
        'codex_missing': 'Install Codex CLI on the bot server to generate images.',
        'codex_login_required': 'Sign in to Codex with ChatGPT on the bot server: `codex login`.',
        'codex_plan_required': 'Image generation requires a supported paid ChatGPT plan.',
        'codex_unavailable': 'Codex is unavailable. Check its installation and try again.',
        'operator_required': 'Only the bot operator can use this image provider.',
        'image_busy': 'An image is already being generated. Try again when it finishes.',
        'image_limit': 'The Codex image usage limit has been reached. Try again after it resets.',
        'image_timeout': 'Image generation timed out. Try again.',
        'invalid_image_prompt': 'Describe the image in 1–8,000 characters.',
        'image_too_large': 'The generated image exceeds the 10 MB limit.',
        'image_failed': 'Codex did not return a generated image. Try again.',
        'image_reference_unsupported': 'This image provider currently creates images from text only. Describe the image without attachments.',
    },
    'uk': {
        'codex_missing': 'Встанови Codex CLI на сервері бота, щоб генерувати зображення.',
        'codex_login_required': 'Увійди в Codex через ChatGPT на сервері бота: `codex login`.',
        'codex_plan_required': 'Для генерації потрібна підтримувана платна підписка ChatGPT.',
        'codex_unavailable': 'Codex недоступний. Перевір його встановлення й спробуй ще раз.',
        'operator_required': 'Цей генератор доступний лише оператору бота.',
        'image_busy': 'Зображення вже генерується. Спробуй після завершення.',
        'image_limit': 'Ліміт генерації Codex вичерпано. Спробуй після його поновлення.',
        'image_timeout': 'Час очікування генерації вичерпано. Спробуй ще раз.',
        'invalid_image_prompt': 'Опиши зображення текстом від 1 до 8 000 символів.',
        'image_too_large': 'Згенероване зображення перевищує ліміт 10 МБ.',
        'image_failed': 'Codex не повернув згенероване зображення. Спробуй ще раз.',
        'image_reference_unsupported': 'Цей провайдер поки створює зображення лише з тексту. Опиши картинку без вкладень.',
    },
}


def error_message(code: str, language: str) -> str:
    messages = MESSAGES.get(language, MESSAGES['en'])
    return messages.get(code, messages['image_failed'])
