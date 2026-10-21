# Source form for OkHttp 4.12.0 public-suffix data

[OkHttp-4.12.0-public_suffix_list.dat](OkHttp-4.12.0-public_suffix_list.dat) is the complete, unmodified, editable UTF-8 Public Suffix List snapshot checked into the official OkHttp 4.12.0 source tree. It is **MPL-2.0**, not the application's MIT license. All original comments, attribution, and the MPL header are retained. See [MPL-2.0.txt](MPL-2.0.txt) and [OkHttp-Public-Suffix-NOTICE.txt](OkHttp-Public-Suffix-NOTICE.txt).

The file is supplied next to this notice in the public repository and in the APK at `assets/open_source_licenses/OkHttp-4.12.0-public_suffix_list.dat`. An APK is a ZIP archive; recipients can extract that entry with any ZIP tool. The fixed release tag's source archive contains the same file under `app/src/main/assets/open_source_licenses/`. This makes the corresponding source available with the distributed package rather than depending on a changing online list.

## Pinned official sources

- OkHttp release: `parent-4.12.0`, resolving to commit `4984568367caaf359b82c452bd28b5e192824d1c`.
- [Complete source snapshot](https://raw.githubusercontent.com/square/okhttp/4984568367caaf359b82c452bd28b5e192824d1c/okhttp/src/test/resources/okhttp3/internal/publicsuffix/public_suffix_list.dat).
- [Upstream generator](https://github.com/square/okhttp/blob/4984568367caaf359b82c452bd28b5e192824d1c/okhttp/src/test/java/okhttp3/internal/publicsuffix/PublicSuffixListGenerator.java), under Apache-2.0. It saves the downloaded source as the test resource and serializes its rules into the runtime gzip. Do not run its network-download step to reproduce this historical snapshot; it requests the current live list.
- [Runtime gzip](https://raw.githubusercontent.com/square/okhttp/4984568367caaf359b82c452bd28b5e192824d1c/okhttp/src/main/resources/okhttp3/internal/publicsuffix/publicsuffixes.gz).
- [Upstream NOTICE](https://github.com/square/okhttp/blob/4984568367caaf359b82c452bd28b5e192824d1c/okhttp/src/main/resources/okhttp3/internal/publicsuffix/NOTICE).
- [Data import commit](https://github.com/square/okhttp/commit/40cb04338da423f7f73e83025717652ed35b7896), dated 2023-04-22, changed both the source and runtime resource. This is the most recent change to both files in the 4.12.0 release history.

## Exact correspondence

The source contains 9,499 ordinary/wildcard rules and 8 exception rules. Following the pinned generator's format, we discard comment/blank lines, separate exception rules and remove their leading `!`, sort each set by unsigned UTF-8 bytes, newline-terminate every rule, and prefix each set with its four-byte big-endian byte length. This reproduces **all 115,042 bytes** of the decompressed runtime resource exactly. The app's runtime data has not been changed or regenerated.

| Item | SHA-256 |
| --- | --- |
| Complete source snapshot, 246,016 bytes | `e8b273972eb5a70e888bd3e7d7c5b9b04e600a59d69def9136a74d70ae6fcdd3` |
| `publicsuffixes.gz`, 41,394 bytes | `9af43e9995ec749e7a30eec4e5a87770d7e2027847084a07cca82060eff0c791` |
| Decompressed serialized rules, 115,042 bytes | `5beda335cfa1a9a9f84c4d17f2267ad7304c3c23d14d41256032d78db274431f` |

The source's Git blob ID is `fcdecb55e42a21fdc37014e0e45d1a348d7a5a2a`; the gzip's is `1e552b5aacf7a4dae8e7b51e1446bc7ab5100df0`. The official release-tag resource and the resource extracted from Maven's `com.squareup.okhttp3:okhttp:4.12.0` JAR have the same gzip SHA-256 above. Release verification must also compare the actual APK's resource and packaged source against these values.

This snapshot is the original textual upstream input, not a decompiled reconstruction or a binary presented as source. The exact original `publicsuffix/list` repository commit is not recorded by this OkHttp import and is not claimed here; the pinned official OkHttp source snapshot and complete byte-level correspondence establish which source applies to this runtime data. This note addresses this data resource only, not a complete dependency-license audit.
