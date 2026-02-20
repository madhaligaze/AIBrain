# AIBrain Android

## Быстрый старт в чистом окружении

Если сборка падает с ошибкой `SDK location not found`, установите Android SDK и создайте `local.properties`:

```bash
./scripts/setup-android-sdk.sh
```

По умолчанию скрипт устанавливает SDK в `/workspace/android-sdk` и ставит необходимые пакеты:
- `platform-tools`
- `platforms;android-34`
- `build-tools;34.0.0`
- `build-tools;35.0.0`

После установки проверьте компиляцию:

```bash
./gradlew :app:compileDebugKotlin
```

> При необходимости можно переопределить путь установки:
>
> ```bash
> ANDROID_SDK_ROOT=$HOME/Android/Sdk ./scripts/setup-android-sdk.sh
> ```
