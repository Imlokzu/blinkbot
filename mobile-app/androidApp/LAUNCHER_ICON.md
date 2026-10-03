# Native launcher mascot

Both native app icons reuse the existing project mascot from
[`Virtual Bot/dashboard/public/icon-maskable.svg`](../../Virtual%20Bot/dashboard/public/icon-maskable.svg).
Its original geometry and colors are preserved. Android converts its three
rounded rectangles into vector paths on a separate adaptive background;
Android 13's monochrome version uses the same outline with transparent eyes.
The Android manifest references this adaptive icon for both launcher shapes.

The iOS `AppIcon` asset is an opaque 1024 x 1024 raster of the same SVG. It is
selected by `ASSETCATALOG_COMPILER_APPICON_NAME` in the XcodeGen specification.
Regenerate it deterministically from the repository root with ImageMagick:

```sh
magick -background '#13110f' -density 1536 \
  'Virtual Bot/dashboard/public/icon-maskable.svg' -resize 1024x1024 \
  -alpha off -strip \
  'PNG24:mobile-app/iosApp/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png'
```

Provenance and licensing: this is in-repository project artwork; see the root
[`LICENSE`](../../LICENSE) (GNU GPL version 3). No generated artwork, external
mascot, model-provider logo, or third-party icon pack is included in these
launcher assets. Other mobile assets retain their separate attribution in
[`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).
