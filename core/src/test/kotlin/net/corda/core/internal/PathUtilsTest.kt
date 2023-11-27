package net.corda.core.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Path

class PathUtilsTest {
    @TempDir
    private lateinit var tempFolder: Path

    @Test
	fun `deleteRecursively - non-existent path`() {
        val path = tempFolder / "non-existent"
        path.deleteRecursively()
        assertThat(path).doesNotExist()
    }

    @Test
	fun `deleteRecursively - file`() {
        val file = (tempFolder / "file").createFile()
        file.deleteRecursively()
        assertThat(file).doesNotExist()
    }

    @Test
	fun `deleteRecursively - empty folder`() {
        val emptyDir = (tempFolder / "empty").createDirectories()
        emptyDir.deleteRecursively()
        assertThat(emptyDir).doesNotExist()
    }

    @Test
	fun `deleteRecursively - dir with single file`() {
        val dir = (tempFolder / "dir").createDirectories()
        (dir / "file").createFile()
        dir.deleteRecursively()
        assertThat(dir).doesNotExist()
    }

    @Test
	fun `deleteRecursively - nested single file`() {
        val dir = (tempFolder / "dir").createDirectories()
        val dir2 = (dir / "dir2").createDirectories()
        (dir2 / "file").createFile()
        dir.deleteRecursively()
        assertThat(dir).doesNotExist()
    }

    @Test
	fun `deleteRecursively - complex`() {
        val dir = (tempFolder / "dir").createDirectories()
        (dir / "file1").createFile()
        val dir2 = (dir / "dir2").createDirectories()
        (dir2 / "file2").createFile()
        (dir2 / "file3").createFile()
        (dir2 / "dir3").createDirectories()
        dir.deleteRecursively()
        assertThat(dir).doesNotExist()
    }

    @Test
	fun `copyToDirectory - copy into zip directory`() {
        val source: Path = tempFolder.newFile("source.txt").let {
            it.writeText("Example Text")
            it.toPath()
        }
        val target = tempFolder / "target.zip"
        FileSystems.newFileSystem(URI.create("jar:${target.toUri()}"), mapOf("create" to "true")).use { fs ->
            val dir = fs.getPath("dir").createDirectories()
            val result = source.copyToDirectory(dir)
            assertThat(result)
                .isRegularFile()
                .hasParent(dir)
                .hasSameContentAs(source)
        }
    }
}