# Blink launcher mark

Android and iOS use the owner's white spiral identity for Blink. The canonical
artwork, original reference and generation details live in
[`assets/brand`](../../assets/brand/README.md).

Android uses vector foreground and monochrome layers over a near-black
background. Both are kept inside the 66dp adaptive-icon safe circle; the
center and gaps remain transparent. The manifest retains the existing
launcher resource name, application ID and update identity.

The iOS AppIcon is an opaque, 8-bit RGB 1024 x 1024 PNG. XcodeGen continues to
select the same AppIcon asset catalog. The in-app Compose BotMark uses the
same vector silhouette, tinted with the current theme's foreground color.
Provider logos retain their separate identities.

Regenerate the bundled assets from the repository root:

```sh
node scripts/sync_brand_assets.mjs
```

Requires ImageMagick 7 and Node; macOS iconutil additionally refreshes desktop
ICNS output. The native launcher regression test checks the white foreground,
transparent center and safe-zone bounds. Other mobile assets retain their
attribution in [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).
