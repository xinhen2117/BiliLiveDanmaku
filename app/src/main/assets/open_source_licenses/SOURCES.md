# License text sources

The project MIT license applies only to project-owned code. Third-party texts below are preserved verbatim and retain their upstream terms and attribution. This directory is packaged as application assets; it is a scoped notice set, not a complete resolved-dependency or distribution audit.

| File | Upstream source | SHA-256 |
| --- | --- | --- |
| [Apache-2.0.txt](Apache-2.0.txt) | [Exact source](https://www.apache.org/licenses/LICENSE-2.0.txt) | `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` |
| [Brotli-MIT.txt](Brotli-MIT.txt) | [Exact source](https://raw.githubusercontent.com/google/brotli/4363f2d74b8af198f78b08e10cd0b3ead48af590/LICENSE) | `3d180008e36922a4e8daec11c34c7af264fed5962d07924aea928c38e8663c94` |
| [Kotlin-Boost-1.0.txt](Kotlin-Boost-1.0.txt) | [Exact source](https://raw.githubusercontent.com/JetBrains/kotlin/v1.9.22/license/third_party/boost_LICENSE.txt) | `8d8291caf1cee26d23acf3eb67c9f9a2d58f1c681b16a4fbe8cbfb9e3c0b5a9b` |
| [Kotlinx-Coroutines-NOTICE.txt](Kotlinx-Coroutines-NOTICE.txt) | [Exact source](https://raw.githubusercontent.com/Kotlin/kotlinx.coroutines/1.7.3/license/NOTICE.txt) | `e2d729c0548af3d0de8deddde6a525d10d2026961727b23795346484ef46a137` |
| [Kotlinx-Serialization-NOTICE.txt](Kotlinx-Serialization-NOTICE.txt) | [Exact source](https://raw.githubusercontent.com/Kotlin/kotlinx.serialization/v1.6.2/license/NOTICE.txt) | `ec77bba5c852830abfdefcdefbf4a22577ff4df79ec323a898d5100ca1d6f8d6` |
| [MPL-2.0.txt](MPL-2.0.txt) | [Exact source](https://www.mozilla.org/media/MPL/2.0/index.f75d2927d3c1.txt) | `3f3d9e0024b1921b067d6f7f88deb4a60cbe7a78e76c64e3f1d7fc3b779b9d04` |
| [OkHttp-Public-Suffix-NOTICE.txt](OkHttp-Public-Suffix-NOTICE.txt) | [Exact source](https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp/src/main/resources/okhttp3/internal/publicsuffix/NOTICE) | `8a9c58fbded5ea3474315e4a9824ca8b1486098a1a03e897e0b19419d5e1876a` |
| [PROJECT-MIT.txt](PROJECT-MIT.txt) | Project root LICENSE (Copyright (c) 2026 xinhen2117) | `2d643817fe1272422b8f4641b4243ed6d968dee8be9a4c3f2d7e7ce211f5b3a3` |
| [Protobuf-3.10.0-BSD.txt](Protobuf-3.10.0-BSD.txt) | [Exact source](https://raw.githubusercontent.com/protocolbuffers/protobuf/v3.10.0/LICENSE) | `6e5e117324afd944dcf67f36cf329843bc1a92229a8cd9bb573d7a83130fea7d` |
| [ZXing-NOTICE.txt](ZXing-NOTICE.txt) | [Exact source](https://raw.githubusercontent.com/zxing/zxing/zxing-3.5.3/NOTICE) | `4db4b60aace29ec6c5c4f13ca9cac1930d3c0c54bcc67dceead0c10771120e66` |

## Scope notes

- Apache-2.0 covers the main AndroidX/Compose, OkHttp/Okio, ZXing, Kotlin and kotlinx components. This does not override differently licensed embedded material.
- Brotli-MIT.txt retains the Brotli Authors copyright, not the project copyright. Its source commit matches all 15 Java files in dec-0.1.2-sources.jar.
- ZXing-NOTICE.txt is the upstream project-wide notice, including Barcode4J and JCommander. Only ZXing core is declared by this app; preserving the complete notice does not claim every ZXing module is included.
- Kotlinx-Coroutines-NOTICE.txt and Kotlinx-Serialization-NOTICE.txt retain the corresponding upstream Apache notices.
- Kotlin MathJVM.kt includes inverse-hyperbolic implementations derived from Boost special math functions: Copyright Eric Ford & Hubert Holin 2001. See the [Kotlin v1.9.22 license inventory](https://github.com/JetBrains/kotlin/blob/v1.9.22/license/README.md). Boost-1.0 has an object-code exception; its full text is retained here conservatively.
- The same Kotlin inventory attributes stdlib collection code to GWT, (C) 2007-08 Google Inc., and unsigned utilities to Guava's UnsignedLongs, (C) 2011 The Guava Authors, both under Apache-2.0.
- Protobuf-3.10.0-BSD.txt applies to the protobuf implementation shaded into androidx.datastore.preferences.protobuf by datastore-preferences-core:1.0.0, not to all AndroidX code.
- OkHttp-Public-Suffix-NOTICE.txt identifies publicsuffixes.gz as MPL-2.0 data derived from the Public Suffix List. Source: https://github.com/publicsuffix/list ; OkHttp version: https://github.com/square/okhttp/tree/parent-4.12.0 . For redistribution, verify availability of the source form corresponding to the actual bundled data; a live list URL is not a verified exact-version match.
- Compiler and test dependencies are documented separately in the root THIRD_PARTY_NOTICES.md, not represented as all belonging to the application runtime.
