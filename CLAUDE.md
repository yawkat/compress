# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

compress-lzf is a Java library for encoding and decoding data in LZF format. It implements the LZF compression algorithm, optimized for speed with modest compression. The format is 100% compatible with the original C liblzf library.

**Key characteristics:**
- Pure Java implementation with no external dependencies
- Requires JDK 8+
- Supports both "safe" (JDK-only) and "unsafe" (sun.misc.Unsafe) implementations for performance
- Block-oriented compression (max 64KB chunks) enabling parallel processing
- Module name: `com.ning.compress.lzf` (JPMS support since 1.1)

## Build and Development Commands

### Building
```bash
./mvnw clean install         # Build and install to local Maven repo
./mvnw verify                # Build and run tests
./mvnw package               # Build JAR without installing
```

### Testing
```bash
./mvnw test                  # Run all tests
./mvnw test -Dtest=TestClassName # Run specific test class
./mvnw test -Dtest=TestClassName#methodName  # Run specific test method
```

### Code Quality
```bash
./mvnw javadoc:javadoc  # Generate Javadocs (output: target/site/apidocs/)
```

### Manual Performance Testing
The repository includes manual performance test scripts:
```bash
./run-comp-perf              # Run compression performance test
./run-uncomp-perf            # Run decompression performance test
./run-skip                   # Run skip performance test
```

## Code Architecture

### Core Package Structure

**Main packages:**
- `com.ning.compress` - Base interfaces and utilities (BufferRecycler, DataHandler, Uncompressor)
- `com.ning.compress.lzf` - Main LZF API and streaming classes
- `com.ning.compress.lzf.impl` - Implementation details (Vanilla and Unsafe variants)
- `com.ning.compress.lzf.parallel` - Parallel compression support
- `com.ning.compress.lzf.util` - Utilities and factory classes
- `com.ning.compress.gzip` - GZIP-related utilities

### Key Design Patterns

**Two-Tier Implementation Strategy:**
The library provides both "safe" and "optimal" (typically unsafe) implementations:
- **Safe**: Uses only standard JDK APIs, works on all platforms
- **Optimal**: May use `sun.misc.Unsafe` for performance (4-5% faster encoding, 10-15% faster decoding)

Access via factory methods:
- `ChunkEncoderFactory.safeInstance()` vs `ChunkEncoderFactory.optimalInstance()`
- `ChunkDecoderFactory.safeInstance()` vs `ChunkDecoderFactory.optimalInstance()`

**Chunk-Based Processing:**
Data is processed in chunks (max 64KB = `LZFChunk.MAX_CHUNK_LEN`):
1. Input split into chunks
2. Each chunk compressed independently (can be parallelized)
3. Chunks combined into output stream/array
4. Uncompressible chunks stored as-is with different header

**Buffer Recycling:**
`BufferRecycler` class enables buffer reuse to reduce GC pressure. Encoders/decoders implement `Closeable` to return buffers to the pool.

### Main Entry Points

**Block API (byte arrays):**
- `LZFEncoder.encode(byte[])` - Compress data
- `LZFDecoder.decode(byte[])` - Decompress data
- Both have `safe*` variants for guaranteed JDK-only implementations

**Streaming API:**
- `LZFOutputStream` - Compress while writing
- `LZFInputStream` - Decompress while reading
- `LZFCompressingInputStream` - Wrap input stream with compression

**Parallel Processing:**
- `PLZFOutputStream` - Parallel compression using thread pool

**Low-level API:**
- `ChunkEncoder` - Encodes individual chunks (abstract class)
- `ChunkDecoder` - Decodes individual chunks (abstract class)
- Implementations: `VanillaChunkEncoder`, `UnsafeChunkEncoder`, etc. in `impl` package

### Implementation Hierarchy

**Encoders:**
```
ChunkEncoder (abstract)
├── VanillaChunkEncoder (safe, pure Java)
└── UnsafeChunkEncoder (uses sun.misc.Unsafe)
    ├── UnsafeChunkEncoderLE (little-endian optimized)
    └── UnsafeChunkEncoderBE (big-endian optimized)
```

**Decoders:**
```
ChunkDecoder (abstract)
├── VanillaChunkDecoder (safe, pure Java)
└── UnsafeChunkDecoder (uses sun.misc.Unsafe)
```

### Important Constraints

1. **Chunk Size**: Maximum uncompressed chunk is 64KB (`LZFChunk.MAX_CHUNK_LEN = 65535`)
2. **Hash Window**: Compression uses 8KB back-reference window (`MAX_OFF = 8192`)
3. **Thread Safety**: `ChunkEncoder` and `ChunkDecoder` instances are NOT thread-safe (stateful)
4. **Buffer Management**: Always call `.close()` on encoders/decoders to return buffers to recycler

### Format Compatibility

This implementation uses the **original LZF format** (compatible with C liblzf), which differs from some other Java adaptations (like H2 database's variant) in block identifiers, though the internal compression structure is identical.

## Module System (JPMS)

The project uses Moditect plugin to add `module-info.class` for Java 9+ compatibility while maintaining Java 8 build compatibility. Module definition is in `src/moditect/module-info.java`.

**Module requires:**
- `java.xml` (transitive)
- `jdk.unsupported` (for sun.misc.Unsafe access)

**Module exports:**
- All public packages except `com.ning.compress.lzf.impl` (though currently exported for compatibility)
