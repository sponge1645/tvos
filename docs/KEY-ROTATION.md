# Подпись APK

Версия 1.0.4 используется для перехода со старого ключа.
Версии 1.1+ используют новый ключ и signing lineage
с APK Signature Scheme v3.

Действующие сборки используют `signing.local.json` и существующий ключ.
Для первоначального формирования цепочки требуется старый приватный ключ,
его реальный alias и файл с паролем:

```sh
python3 tools/signing.py init --legacy-keystore /path/to/legacy.keystore \
  --legacy-alias ORIGINAL_ALIAS --legacy-password-file /path/to/password.txt
```

Команда не нужна при обновлении исходников или переносе готовой конфигурации.

Сохраняйте защищённые резервные копии ключей.
Не пересоздавайте действующие ключи подписи.
