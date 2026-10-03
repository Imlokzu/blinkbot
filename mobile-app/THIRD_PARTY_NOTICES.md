# Mobile visual assets

## Solar Icons

Interface glyphs (`shared/src/commonMain/composeResources/drawable/ic_*.xml`,
except the project's own mascot) are adapted from Solar Linear by 480 Design:
https://github.com/480-Design/Solar-Icon-Set .
The source paths were converted to Android VectorDrawable XML for Compose
Multiplatform. Stroke geometry is retained; the application supplies tint.
Solar is distributed under CC BY 4.0 through its official Figma Community
publication: https://www.figma.com/community/file/1166831539721848736 .
License: https://creativecommons.org/licenses/by/4.0/ .
Attribution also appears in the mobile Profile screen.

## Model logos

Manufacturer marks (`brand_*.xml`) are adapted from Lobe Icons:
https://github.com/lobehub/lobe-icons . The vector format and monochrome tint
were adapted for the mobile renderer. Upstream MIT license is reproduced in
`licenses/lobe-icons-MIT.txt`. Brand names and marks remain their owners' marks;
these icons identify the model maker, not the inference provider.

## Wallpaper and mascot

The default wallpaper is the exact image supplied by the owner:
https://pbs.twimg.com/media/HTkKqtcakAAF3P7?format=jpg&name=large .
It is bundled for offline use. No broader license for that supplied image is
asserted here. The bot glyph follows the existing project's mascot.

## Runtime dependencies

Dependency versions are pinned in the Gradle files. Kotlin, Compose, Ktor,
Haze, the Markdown renderer, AndroidX and ZXing retain their respective upstream
licenses and notices. This document describes the separately vendored visual
assets; it does not replace the licenses contained in runtime artifacts.

## Typography

Manrope by Mikhail Sharanda and collaborators and Lora by Cyreal are bundled
from the Google Fonts upstream repositories under the SIL Open Font License.
The variable sources were instantiated at weights 400/600 (Manrope) and 500
italic (Lora), with Ukrainian character coverage checked before bundling.
License copies: `licenses/manrope-OFL.txt` and `licenses/lora-OFL.txt`.
Sources: https://github.com/google/fonts/tree/main/ofl/manrope and
https://github.com/google/fonts/tree/main/ofl/lora .

The instantiated Lora-derived font uses the internal family name **Claude Greeting**
to respect Lora's reserved font name. Original author, copyright and license
metadata remain in the font; its outlines are from the upstream Lora source.
