package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the file-level half of deleting a downloaded model (issue #53). The manager entry
 * points need a Context for `filesDir`, but the part that can actually get this wrong —
 * *which* files go — is plain java.io and testable here.
 *
 * The rule being pinned: a model and its download sidecars are one unit. Removing the blob
 * while leaving a `.part` and `.etag` behind would leave resume state pointing at a file that
 * no longer exists, which is exactly the class of bug [ModelDownloader] was written to prevent.
 */
class ModelDownloaderDeleteTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun write(name: String, bytes: Int): File =
        tmp.newFile(name).apply { writeBytes(ByteArray(bytes)) }

    @Test fun `deletes the model and every sidecar it left behind`() {
        val model = write("ggml-base.bin", 1000)
        val part = write("ggml-base.bin.part", 300)
        val etag = write("ggml-base.bin.etag", 20)
        val sha = write("ggml-base.bin.sha256", 64)

        val freed = ModelDownloader.deleteWithSidecars(model)

        assertFalse("model", model.exists())
        assertFalse("part", part.exists())
        assertFalse("etag", etag.exists())
        assertFalse("sha256", sha.exists())
        assertEquals(1000L + 300 + 20 + 64, freed)
    }

    @Test fun `reports only the bytes that were actually there`() {
        val model = write("gemma-3-270m-qat-Q4_0.gguf", 4096)
        assertEquals(4096L, ModelDownloader.deleteWithSidecars(model))
    }

    @Test fun `deleting something that was never downloaded is a no-op, not a failure`() {
        val absent = File(tmp.root, "never-fetched.gguf")
        assertEquals(0L, ModelDownloader.deleteWithSidecars(absent))
    }

    @Test fun `an interrupted download leaves only a part file, and that goes too`() {
        // The user got half of it, then hit delete. Nothing should survive to be resumed from.
        val model = File(tmp.root, "Qwen3-0.6B-Q8_0.gguf")
        val part = write("Qwen3-0.6B-Q8_0.gguf.part", 5000)
        val etag = write("Qwen3-0.6B-Q8_0.gguf.etag", 18)

        assertEquals(5018L, ModelDownloader.deleteWithSidecars(model))

        assertFalse(part.exists())
        assertFalse(etag.exists())
    }

    @Test fun `never touches another model's files`() {
        // Names share a directory and a prefix family; deletion must key off the exact name.
        val target = write("ggml-tiny.bin", 100)
        val neighbour = write("ggml-tiny-q8_0.bin", 100)
        val neighbourPart = write("ggml-tiny-q8_0.bin.part", 50)
        val other = write("ggml-base.bin", 100)

        ModelDownloader.deleteWithSidecars(target)

        assertFalse(target.exists())
        assertTrue("a longer name that starts the same must survive", neighbour.exists())
        assertTrue(neighbourPart.exists())
        assertTrue(other.exists())
    }

    @Test fun `a Parakeet-style bundle goes file by file, and the directory survives`() {
        // ParakeetModelManager deletes its four files by name rather than wiping the dir.
        val dir = tmp.newFolder("parakeet")
        val files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
            .map { File(dir, it).apply { writeBytes(ByteArray(256)) } }

        val freed = files.sumOf { ModelDownloader.deleteWithSidecars(it) }

        assertEquals(4L * 256, freed)
        assertTrue("the bundle directory itself stays", dir.isDirectory)
        files.forEach { assertFalse(it.name, it.exists()) }
    }
}
