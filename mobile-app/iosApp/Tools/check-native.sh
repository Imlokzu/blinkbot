#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
swiftc -parse Sources/*.swift Tests/*.swift Tools/*.swift
plutil -lint Info.plist Resources/PrivacyInfo.xcprivacy Resources/en.lproj/*.strings Resources/uk.lproj/*.strings
test "$(/usr/libexec/PlistBuddy -c 'Print :CADisableMinimumFrameDurationOnPhone' Info.plist)" = true
check_directory=$(mktemp -d "${TMPDIR:-/tmp}/claudebot-native.XXXXXX")
trap 'rm -rf "$check_directory"' EXIT HUP INT TERM
swiftc Sources/BoundedData.swift Sources/NativeStorage.swift Tools/BoundedDataChecks.swift -o "$check_directory/reader-checks"
"$check_directory/reader-checks"
swiftc Sources/RecordingPCM.swift Tools/RecordingChecks.swift -o "$check_directory/recording-checks"
"$check_directory/recording-checks"
if command -v xcodegen >/dev/null 2>&1; then
    xcodegen generate --spec project.yml
else
    echo "XcodeGen is unavailable; install it to validate/generate the Xcode project."
fi
