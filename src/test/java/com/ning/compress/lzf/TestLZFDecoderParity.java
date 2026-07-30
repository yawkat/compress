package com.ning.compress.lzf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import com.ning.compress.BaseForTests;
import com.ning.compress.lzf.util.ChunkDecoderFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests that verify that the "safe" and "optimal" {@link ChunkDecoder} implementations
 * agree on malformed input. They are separate hand-optimized implementations of the same
 * format, with validation duplicated in both, so they can drift apart -- and have: see
 * "#85: Handle malformed LZF back references".
 *<p>
 * For every input both decoders have to either fail with {@link LZFException}, or succeed
 * with identical output. Anything else -- one succeeding where the other fails, differing
 * output, or an exception other than {@code LZFException} for content (as opposed to
 * argument) problems -- is a failure.
 */
public class TestLZFDecoderParity extends BaseForTests
{
    /**
     * Declared uncompressed lengths to try; includes 0, the boundaries of the
     * short (3 - 8 bytes) and long (9 - 264 bytes) back-reference run lengths
     */
    private final static int[] DECLARED_LENGTHS = new int[] { 0, 1, 3, 8, 9, 16, 40, 264, 300 };

    /**
     * Offsets of the chunk within the output buffer: 0, and a non-zero one so that
     * back-references pointing before the chunk (but still within the buffer) are covered
     */
    private final static int[] CHUNK_OFFSETS = new int[] { 0, 5 };

    @Test
    public void testEveryControlByte() {
        byte[][] tails = new byte[][] {
                {}, { 0x00 }, { 0x41 }, { (byte) 0xFF }, { 0x00, 0x00 }, { 0x01, 0x02, 0x03 }
        };
        for (int ctrl = 0; ctrl < 256; ++ctrl) {
            for (byte[] tail : tails) {
                byte[] payload = new byte[1 + tail.length];
                payload[0] = (byte) ctrl;
                System.arraycopy(tail, 0, payload, 1, tail.length);
                for (int declaredLen : DECLARED_LENGTHS) {
                    for (int chunkOffset : CHUNK_OFFSETS) {
                        _assertSameOutcome(payload, chunkOffset, declaredLen);
                    }
                }
            }
        }
    }

    @Test
    public void testRandomPayloads() {
        Random rnd = new Random(1234); // fixed seed: has to stay reproducible
        for (int i = 0; i < 3000; ++i) {
            byte[] payload = new byte[rnd.nextInt(24)];
            rnd.nextBytes(payload);
            _assertSameOutcome(payload, CHUNK_OFFSETS[i & 1], DECLARED_LENGTHS[i % DECLARED_LENGTHS.length]);
        }
    }

    @Test
    public void testTruncatedAndMutatedValidData() throws IOException {
        byte[] orig = "the quick brown fox jumps over the lazy dog, the quick brown fox"
                .getBytes(StandardCharsets.UTF_8);
        // Compressed payload of a single chunk, without the 7 byte header
        byte[] payload = Arrays.copyOfRange(compress(orig), 7, compress(orig).length);

        // Truncated at every possible point ...
        for (int cut = 0; cut <= payload.length; ++cut) {
            byte[] truncated = Arrays.copyOf(payload, cut);
            for (int chunkOffset : CHUNK_OFFSETS) {
                _assertSameOutcome(truncated, chunkOffset, orig.length);
            }
        }
        // ... and with every single byte mutated, to hit control bytes, lengths and offsets
        for (int i = 0; i < payload.length; ++i) {
            for (int mask : new int[] { 0x01, 0x20, 0x80, 0xE0 }) {
                byte[] mutated = payload.clone();
                mutated[i] = (byte) (mutated[i] ^ mask);
                _assertSameOutcome(mutated, 0, orig.length);
            }
        }
    }

    /*
    ///////////////////////////////////////////////////////////////////////
    // Second-level test methods
    ///////////////////////////////////////////////////////////////////////
     */

    // Note: `decodeChunk(byte[], int, int, byte[], int, int)` does not use decoder state,
    // so single instances can be reused for all cases
    private final ChunkDecoder _safe = ChunkDecoderFactory.safeInstance();
    private final ChunkDecoder _optimal = ChunkDecoderFactory.optimalInstance();

    private void _assertSameOutcome(byte[] payload, int chunkOffset, int declaredLen)
    {
        byte[] safeOutput = new byte[chunkOffset + declaredLen];
        byte[] optimalOutput = new byte[chunkOffset + declaredLen];
        // Prefill, so that content of an earlier chunk (or stale buffer content) is
        // distinguishable from output actually produced for this chunk
        Arrays.fill(safeOutput, (byte) 'P');
        Arrays.fill(optimalOutput, (byte) 'P');

        LZFException safeFailure = _decode(_safe, payload, chunkOffset, declaredLen, safeOutput);
        LZFException optimalFailure = _decode(_optimal, payload, chunkOffset, declaredLen, optimalOutput);

        if ((safeFailure == null) != (optimalFailure == null)) {
            fail("Decoders disagree for "+_describe(payload, chunkOffset, declaredLen)
                    +": safe "+_outcome(safeFailure)+", optimal "+_outcome(optimalFailure));
        }
        if (safeFailure == null) {
            assertArrayEquals(safeOutput, optimalOutput,
                    "Decoders produced different output for "+_describe(payload, chunkOffset, declaredLen));
        }
    }

    /**
     * @return `LZFException` thrown by the decoder, if any; `null` if decoding succeeded.
     *   Fails the test for any other exception: arguments passed are valid, so content
     *   problems have to be reported as `LZFException`
     */
    private LZFException _decode(ChunkDecoder decoder, byte[] payload, int chunkOffset, int declaredLen,
            byte[] output)
    {
        try {
            decoder.decodeChunk(payload, 0, payload.length, output, chunkOffset, chunkOffset + declaredLen);
            return null;
        } catch (LZFException e) {
            return e;
        } catch (RuntimeException e) {
            fail(decoder.getClass().getSimpleName()+" threw "+e.getClass().getName()
                    +" instead of LZFException for "+_describe(payload, chunkOffset, declaredLen), e);
            return null; // never gets here
        }
    }

    private String _outcome(LZFException e) {
        return (e == null) ? "succeeded" : "failed ("+e.getMessage()+")";
    }

    private String _describe(byte[] payload, int chunkOffset, int declaredLen) {
        StringBuilder sb = new StringBuilder("payload 0x");
        for (byte b : payload) {
            sb.append(String.format("%02x", b));
        }
        return sb.append(", chunk offset ").append(chunkOffset)
                .append(", declared uncompressed length ").append(declaredLen).toString();
    }
}
