# vendor/

Local copies of third-party libraries — WITHOUT CDN: the bot's screen must work
on a Raspberry Pi without the internet and without a build step.

## smd.min.js — streaming-markdown 0.2.15

- npm: https://www.npmjs.com/package/streaming-markdown
- source: https://github.com/thetarnav/streaming-markdown
- license: MIT (see `smd.LICENSE`)
- sha1 of the tarball from the registry: b0d63b001fcbcf300bd0751fc199a20d78253dba
- 12.6 KB minified, ESM

Why this exact one: the parser is INCREMENTAL — it appends tokens into the DOM as
stream chunks arrive, without re-parsing the entire text every time. On an A53
(Pi 3) the difference between this and "re-parse everything on every chunk" — is crucial.

Updates: download a new tarball from the registry, verify the shasum, replace
the file. You must not edit the contents manually.
