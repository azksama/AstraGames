# Wolf native data and archive readers

The read-only metadata layouts in `Formats.kt`, the metadata protection routines in `MetadataCrypt.kt`, the Wolf carrier/XOR/RNG/AES/ChaCha archive routines in `Archive.kt`, `ArchiveKey.kt`, and `ArchiveChaCha.kt`, and the individual-resource decoder in `WolfXResource.kt` are adapted from:

- WolfTL, Copyright (c) 2024, 2026 Sinflower, commit `bfc38fc2735ab4f7f7ddbec8d57b7fef65f0712f`, https://github.com/Sinflower/WolfTL. Adapted files include `WolfTL/WolfRPG/{FileCoder,CommonEvents,GameDat,Database}.hpp` and `WolfTL/WolfCrypt/{WolfCrypt,WolfCryptUtils,WolfAes,WolfDataDecrypt,WolfRng,WolfSha512}.hpp`.
- UberWolf, Copyright (c) 2023, 2024, 2025, 2026 Sinflower, commit `f4191375d289171b1f29dbc5ca320133b2b4b96a`, https://github.com/Sinflower/UberWolf. Adapted files: `UberWolfLib/WolfPro.cpp`, `UberWolfLib/WolfCrypt/{WolfCrypt,WolfCryptTypes,WolfCryptUtils,WolfAes,WolfDataDecrypt,WolfRng,WolfSha512,WolfDxArcKey,WolfChaCha20}.hpp`, and `UberWolfLib/WolfX/detail/{GeneratorDetail,CrackDetail,DataManipDetail,UtilsDetail,ValidateDetail}.hpp` (WolfX files Copyright (c) 2025 Sinflower). Their file-level MIT notices apply separately from the DX Library sources bundled upstream.
- wolf-rpg-formats, Copyright (c) 2024 djytw, commit `5c70e643693e08f99bf299a2ca598f7eaa02c542`, https://github.com/djytw/wolf-rpg-formats

MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

The LZ4 block decoder is independently implemented from the raw block specification: https://github.com/lz4/lz4/blob/v1.10.0/doc/lz4_Block_format.md .

The DX5/DX6/DX8 archive layouts, DX LZ token format, Huffman header/tree format, and archive filename key derivation were reconstructed from `3rdParty/DXLib/DXArchive{,Ver5,Ver6}.{h,cpp}` and `Huffman.{h,cpp}` in the pinned UberWolf tree. The Kotlin readers and codecs are independent implementations. DX Library is attributed to Takumi Yamada and is not covered by UberWolf's MIT license.

DX Library Copyright (C) 2001-2026 Takumi Yamada.

DX Library terms: https://dxlib.xsrv.jp/dxlicense.html (consulted 2026-10-10). Those terms permit royalty-free commercial and noncommercial use, disclaim liability and an obligation to correct defects, and require DX Library attribution when redistributing its source or library files. The Kotlin archive implementation does not include DX Library binaries or its optional JPEG/PNG/audio/physics/Live2D libraries.

This module contains decoders for the explicitly supported Wolf archive, historical metadata, and Pro T/U/V/W metadata profiles with the established empty-secret key chain, plus established Pro 1000/1010/1020 and CC2/200 external carrier profiles and the fixed ChaCha profile 100. The WOLFX individual-resource decoder accepts an explicit key and any referenced variable values, verifies the native five check bytes, and otherwise refuses the resource; it performs no key search. It includes no original Wolf RPG engine executable or proprietary sample/game assets. Unknown protection keys and future Pro markers are not inferred or silently bypassed. Supported formats and verification boundaries are documented in `ARCHIVE_FORMATS.md` and `docs/wolf-native/WOLFX_RESOURCES.md`.
