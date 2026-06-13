package com.firedoge.kineticassembly.minecraft.assembly;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;

import com.firedoge.kineticassembly.KineticAssembly;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

class AssemblyStorageFile implements AutoCloseable {
    static final String FILE_EXTENSION = ".assem";
    static final String SINGLE_FILE_EXTENSION = ".assem";
    private static final int HEADER_BYTES = 4096;
    private static final int DEFAULT_SECTOR_BYTES = 4096;
    private static final int EXTERNAL_MASK = 16;
    private static final ByteBuffer PADDING_BUFFER = ByteBuffer.allocateDirect(1);

    private final BitSet usedSectors = new BitSet();
    private final BitSet usedIndices = new BitSet();
    private final int sectorBytes;
    private final Path path;
    private final Path externalFileDir;
    private final FileChannel file;
    private final ByteBuffer header;
    private final IntBuffer sectorSpans;

    AssemblyStorageFile(Path path, Path externalFileDir) throws IOException {
        this(path, externalFileDir, DEFAULT_SECTOR_BYTES);
    }

    AssemblyStorageFile(Path path, Path externalFileDir, int sectorBytes) throws IOException {
        this.path = path;
        this.externalFileDir = externalFileDir;
        this.sectorBytes = sectorBytes;

        Files.createDirectories(path.getParent());
        Files.createDirectories(externalFileDir);
        file = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.DSYNC
        );

        int totalIndexCount = HEADER_BYTES / Integer.BYTES;
        header = ByteBuffer.allocateDirect(HEADER_BYTES);
        sectorSpans = header.asIntBuffer();
        sectorSpans.limit(totalIndexCount);
        usedSectors.set(0, HEADER_BYTES / sectorBytes, true);

        long existingSize = Files.size(path);
        int headerBytesRead = file.read(header, 0L);
        if (headerBytesRead == -1) {
            return;
        }
        if (headerBytesRead != HEADER_BYTES) {
            KineticAssembly.LOGGER.error("Assembly storage file {} has truncated header: {}", path, headerBytesRead);
        }

        for (int spanIndex = 0; spanIndex < totalIndexCount; spanIndex++) {
            int span = sectorSpans.get(spanIndex);
            usedIndices.set(spanIndex, span != 0);
            if (span == 0) {
                continue;
            }

            int spanStart = spanStart(span);
            int spanLength = spanLength(span);
            if (((long) spanStart) * sectorBytes > existingSize) {
                KineticAssembly.LOGGER.warn(
                        "Assembly storage file {} has out-of-bounds span at index {}: start={}, length={}, size={}",
                        path,
                        spanIndex,
                        spanStart,
                        spanLength,
                        existingSize
                );
            }
            if (spanStart < 0 || spanLength <= 0) {
                KineticAssembly.LOGGER.warn("Assembly storage file {} has invalid span at index {}", path, spanIndex);
                continue;
            }
            for (int sector = spanStart; sector < spanStart + spanLength; sector++) {
                if (usedSectors.get(sector)) {
                    KineticAssembly.LOGGER.warn("Assembly storage file {} has overlapping span at index {}", path, spanIndex);
                }
            }
            usedSectors.set(spanStart, spanStart + spanLength, true);
        }
    }

    int findFreeIndex() {
        return usedIndices.nextClearBit(0);
    }

    int totalIndexCapacity() {
        return HEADER_BYTES / Integer.BYTES;
    }

    CompoundTag read(int index) throws IOException {
        DataInputStream input = assemblyDataInputStream(index);
        if (input == null) {
            return null;
        }
        try (input) {
            return NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
        }
    }

    void write(int index, CompoundTag tag) throws IOException {
        if (tag == null) {
            clear(index);
            return;
        }
        try (DataOutputStream output = new DataOutputStream(new SectorSpanDataBuffer(index))) {
            NbtIo.writeCompressed(tag.copy(), output);
        }
    }

    void flush() throws IOException {
        file.force(true);
    }

    @Override
    public void close() throws IOException {
        try {
            padOrTruncateToFullSector();
        } finally {
            try {
                file.force(true);
            } finally {
                file.close();
            }
        }
    }

    private DataInputStream assemblyDataInputStream(int index) throws IOException {
        int span = sectorSpans.get(index);
        int start = spanStart(span);
        int length = spanLength(span);
        if (start == 0) {
            return null;
        }
        if (length <= 0 || start + length > usedSectors.length()) {
            KineticAssembly.LOGGER.error("Assembly storage file {} has invalid span at index {}", path, index);
            return null;
        }

        ByteBuffer byteBuffer = ByteBuffer.allocate(length * sectorBytes);
        file.read(byteBuffer, (long) start * sectorBytes);
        byteBuffer.flip();
        if (byteBuffer.remaining() < Integer.BYTES + 1) {
            KineticAssembly.LOGGER.error("Assembly storage file {} has truncated sector header at index {}", path, index);
            return null;
        }

        int declaredBytes = byteBuffer.getInt();
        byte dataType = byteBuffer.get();
        if (declaredBytes == 0) {
            KineticAssembly.LOGGER.warn("Assembly storage file {} has zero-sized data at index {}", path, index);
            return null;
        }

        int payloadBytes = declaredBytes - 1;
        if ((dataType & EXTERNAL_MASK) != 0) {
            if (payloadBytes != 0) {
                KineticAssembly.LOGGER.warn("Assembly storage file {} has mixed internal/external data at index {}", path, index);
            }
            return new DataInputStream(externalAssemblyInputStream(index));
        }
        if (payloadBytes > byteBuffer.remaining()) {
            KineticAssembly.LOGGER.error(
                    "Assembly storage file {} has truncated payload at index {}: expected={}, remaining={}",
                    path,
                    index,
                    payloadBytes,
                    byteBuffer.remaining()
            );
            return null;
        }
        if (payloadBytes < 0) {
            KineticAssembly.LOGGER.error("Assembly storage file {} has negative payload size at index {}", path, index);
            return null;
        }

        return new DataInputStream(new ByteArrayInputStream(byteBuffer.array(), byteBuffer.position(), payloadBytes));
    }

    private InputStream externalAssemblyInputStream(int index) throws IOException {
        Path external = externalFilePath(index);
        if (Files.isRegularFile(external)) {
            return Files.newInputStream(external);
        }
        throw new IOException("External assembly path " + external + " is not a file");
    }

    private void write(int index, ByteBuffer byteBuffer) throws IOException {
        int oldSpan = sectorSpans.get(index);
        int oldStart = spanStart(oldSpan);
        int oldLength = spanLength(oldSpan);
        int sectorsNeeded = sizeToSectors(byteBuffer.remaining());
        boolean external = false;
        Path temporaryExternalFile = null;

        if (sectorsNeeded > 255) {
            external = true;
            sectorsNeeded = 1;
        }

        int writeStart = allocateSpace(sectorsNeeded);
        if (external) {
            temporaryExternalFile = writeExternalFile(byteBuffer);
            file.write(externalStub(), (long) writeStart * sectorBytes);
        } else {
            file.write(byteBuffer, (long) writeStart * sectorBytes);
        }

        sectorSpans.put(index, packSpan(writeStart, sectorsNeeded));
        usedIndices.set(index, true);
        writeHeader();

        if (external) {
            Files.move(temporaryExternalFile, externalFilePath(index), StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(externalFilePath(index));
        }

        if (oldStart != 0) {
            usedSectors.clear(oldStart, oldStart + oldLength);
        }
    }

    private Path writeExternalFile(ByteBuffer byteBuffer) throws IOException {
        Path tempFile = Files.createTempFile(externalFileDir, "tmp", null);
        try (FileChannel channel = FileChannel.open(tempFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            byteBuffer.position(5);
            channel.write(byteBuffer);
        }
        return tempFile;
    }

    private void clear(int index) throws IOException {
        int span = sectorSpans.get(index);
        if (span == 0) {
            return;
        }
        sectorSpans.put(index, 0);
        usedIndices.clear(index);
        int start = spanStart(span);
        usedSectors.clear(start, start + spanLength(span));
        Files.deleteIfExists(externalFilePath(index));
        writeHeader();
    }

    private int allocateSpace(int sectorsNeeded) {
        int cursor = 0;
        while (true) {
            int start = usedSectors.nextClearBit(cursor);
            int nextUsed = usedSectors.nextSetBit(start);
            if (nextUsed == -1 || nextUsed - start >= sectorsNeeded) {
                usedSectors.set(start, start + sectorsNeeded);
                return start;
            }
            cursor = nextUsed;
        }
    }

    private int sizeToSectors(int sizeBytes) {
        return (sizeBytes + sectorBytes - 1) / sectorBytes;
    }

    private void writeHeader() throws IOException {
        header.position(0);
        file.write(header, 0L);
    }

    private ByteBuffer externalStub() {
        ByteBuffer byteBuffer = ByteBuffer.allocate(5);
        byteBuffer.putInt(1);
        byteBuffer.put((byte) EXTERNAL_MASK);
        byteBuffer.flip();
        return byteBuffer;
    }

    private Path externalFilePath(int index) {
        return externalFileDir.resolve(index + SINGLE_FILE_EXTENSION);
    }

    private int spanStart(int span) {
        return span >> 8 & 0xFFFFFF;
    }

    private int spanLength(int span) {
        return span & 0xFF;
    }

    private int packSpan(int start, int length) {
        if (start < 0 || length <= 0 || length > 255) {
            throw new IllegalArgumentException("Invalid span: start=" + start + ", length=" + length);
        }
        return (start << 8) | length;
    }

    private void padOrTruncateToFullSector() throws IOException {
        int desiredSize = usedSectors.length() * sectorBytes;
        int currentSize = (int) file.size();
        if (currentSize > desiredSize) {
            file.truncate(desiredSize);
            return;
        }
        if (currentSize < desiredSize) {
            ByteBuffer padding = PADDING_BUFFER.duplicate();
            padding.position(0);
            file.write(padding, desiredSize - 1L);
        }
    }

    private final class SectorSpanDataBuffer extends ByteArrayOutputStream {
        private final int index;

        private SectorSpanDataBuffer(int index) {
            super(sectorBytes);
            write(0);
            write(0);
            write(0);
            write(0);
            write(0);
            this.index = index;
        }

        @Override
        public void close() throws IOException {
            ByteBuffer byteBuffer = ByteBuffer.wrap(buf, 0, count);
            byteBuffer.putInt(0, count - Integer.BYTES);
            AssemblyStorageFile.this.write(index, byteBuffer);
        }
    }
}
