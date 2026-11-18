# Winlator с иконками игр на главном экране

`home-screen-shortcut.patch` — доработка официального Winlator 11.2. Она добавляет пункт «Добавить на главный экран» в меню файла (файловый менеджер контейнера) и в меню ярлыка (раздел «Ярлыки»). На главном экране Android появляется иконка, которая запускает программу сразу, без меню Winlator.

Исходный код Winlator: <https://github.com/brunodev85/winlator-app> (лицензия LGPL-2.1). Патч сделан поверх коммита `ecd5ab2980de79eb955621cef26a3b4672013875` и распространяется на тех же условиях.

## Сборка

Нужны JDK 17 или новее и Android SDK с NDK 24.0.8215888 и CMake 3.22.1.

```
git clone https://github.com/brunodev85/winlator-app
cd winlator-app
git checkout ecd5ab2980de79eb955621cef26a3b4672013875
git apply ../home-screen-shortcut.patch
./gradlew :app:assembleDebug
```

Чтобы сборка ставилась поверх предыдущей, перед сборкой скопируйте `app/debug.keystore` из этого репозитория в `~/.android/debug.keystore`.

## Что изменено

- `HomeScreenShortcut` — создаёт закреплённый ярлык через `ShortcutManager`.
- `ShortcutLauncherActivity` — принимает нажатие на иконку, проверяет, что контейнер и файл на месте, и запускает `XServerDisplayActivity`. Если чего-то нет, открывает обычный экран Winlator.
- В меню файла и ярлыка добавлен новый пункт; строки переведены на русский и португальский.

Имя пакета оставлено `com.winlator`: на нём завязаны пути внутри системы Winlator. Поэтому эта сборка ставится вместо официальной, а не рядом с ней.
