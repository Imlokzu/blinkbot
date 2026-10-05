# Native React preview fixture

This fixture verifies a real Vite production bundle in `WebAppPreviewTest`.
It uses the dashboard's already installed React and Vite versions. Build in a
temporary directory; do not commit generated assets or `node_modules`.

From the repository root, with the dashboard dependencies installed:

```sh
fixture_dir=$(mktemp -d)
cp mobile-app/test-fixtures/react-preview/{index.html,main.jsx,style.css,package.json} "$fixture_dir/"
ln -s "$PWD/Virtual Bot/dashboard/node_modules" "$fixture_dir/node_modules"
node "$fixture_dir/node_modules/vite/bin/vite.js" build "$fixture_dir" --base ./
adb -s emulator-5556 shell mkdir -p /sdcard/Android/data/me.waveio.claudebot/files/react-preview
adb -s emulator-5556 push "$fixture_dir/dist/." /sdcard/Android/data/me.waveio.claudebot/files/react-preview/
```

Install the benchmark application and its instrumentation APK before pushing
the fixture. Run `WebAppPreviewTest` with AndroidJUnitRunner. The fixture is
mandatory for that suite: missing assets fail explicitly instead of skipping
the real React check. Remove the temporary directory after testing.

The counter must react to clicks, the extracted stylesheet must apply, and a
document navigation to `/details` must reload the SPA entry. All resource bytes
come from the host callback; no development server or external API is used.
