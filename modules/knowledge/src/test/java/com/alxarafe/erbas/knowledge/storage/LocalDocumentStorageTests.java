package com.alxarafe.erbas.knowledge.storage;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.alxarafe.erbas.knowledge.storage.application.*;
import com.alxarafe.erbas.knowledge.storage.infrastructure.LocalDocumentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.alxarafe.erbas.knowledge.storage.application.StorageFailure.Kind.*;

class LocalDocumentStorageTests {
    private static final StorageContext CONTEXT = new StorageContext("fixture.owner", UUID.randomUUID());
    private static final int PARALLEL_WRITERS = 8;
    private static final int WAIT_SECONDS = 10;
    private static final int STREAM_SIZE = 2 * 1024 * 1024;
    @TempDir Path root;

    private LocalDocumentStorage storage() { return new LocalDocumentStorage(root); }
    private StoredObject write(byte[] value) { return storage().write(CONTEXT, new ByteArrayInputStream(value)); }
    private byte[] read(StoredObject object) {
        var out = new ByteArrayOutputStream();
        storage().read(CONTEXT, object, out);
        return out.toByteArray();
    }
    private Path file(StoredObject object) throws IOException {
        try (var files = Files.list(root)) {
            return files.filter(path -> path.getFileName().toString().endsWith(object.id() + LocalDocumentStorage.OBJECT_SUFFIX))
                    .findFirst().orElseThrow();
        }
    }
    private static void failure(StorageFailure.Kind kind, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(StorageFailure.class, value -> {
            assertThat(value.kind()).isEqualTo(kind);
            assertThat(value.getCause()).isNull();
        });
    }

    @Test void persistentObjectsSurviveAdapterRecreationAndNeverOverwrite() throws Exception {
        byte[] first = {1, 2, 3};
        var a = write(first);
        var b = write(first);
        assertThat(a.id()).isNotEqualTo(b.id());
        assertThat(a.sha256()).isEqualTo(b.sha256());
        assertThat(read(a)).isEqualTo(first);
        assertThat(read(b)).isEqualTo(first);
        try (var files = Files.list(root)) { assertThat(files.toList()).hasSize(2); }
    }

    @Test void streamsWithoutClosingCallerResources() {
        var source = new InputStream() {
            int remaining = STREAM_SIZE;
            boolean closed;
            public int read() { return remaining-- > 0 ? 42 : -1; }
            public void close() { closed = true; }
        };
        var object = storage().write(CONTEXT, source);
        var target = new OutputStream() {
            long count;
            boolean closed;
            public void write(int value) { if (value != 42) throw new AssertionError("Unexpected fixture byte"); count++; }
            public void close() { closed = true; }
        };
        storage().read(CONTEXT, object, target);
        assertThat(target.count).isEqualTo(STREAM_SIZE);
        assertThat(object.size()).isEqualTo(STREAM_SIZE);
        assertThat(source.closed).isFalse();
        assertThat(target.closed).isFalse();
    }

    @Test void emptyObjectsAreValid() { assertThat(read(write(new byte[0]))).isEmpty(); }

    @Test void differentModulesAndScopesCannotReadAnExistingObject() {
        var object = write(new byte[]{1});
        var target = new ByteArrayOutputStream();
        failure(NOT_FOUND, () -> storage().read(new StorageContext("fixture.other", CONTEXT.scope()), object, target));
        failure(NOT_FOUND, () -> storage().read(new StorageContext(CONTEXT.module(), UUID.randomUUID()), object, target));
        assertThat(target.size()).isZero();
    }

    @Test void contextStringsAreHashedRatherThanInterpretedAsPaths() throws Exception {
        var context = new StorageContext("../../outside/../module", UUID.randomUUID());
        var object = storage().write(context, new ByteArrayInputStream(new byte[]{1}));
        try (var files = Files.list(root)) {
            assertThat(files.toList()).singleElement().satisfies(path -> assertThat(path.getParent()).isEqualTo(root));
        }
        failure(NOT_FOUND, () -> storage().read(CONTEXT, object, new ByteArrayOutputStream()));
    }

    @Test void tamperingAndTruncationAreDetected() throws Exception {
        var object = write(new byte[]{1, 2, 3});
        Files.write(file(object), new byte[]{3, 2, 1});
        failure(INTEGRITY, () -> read(object));
        Files.write(file(object), new byte[]{1});
        failure(INTEGRITY, () -> read(object));
    }

    @Test void missingObjectsAndWrongDigestFailExplicitly() throws Exception {
        var object = write(new byte[]{1});
        var wrong = new StoredObject(object.id(), "0".repeat(64), object.size());
        failure(INTEGRITY, () -> read(wrong));
        Files.delete(file(object));
        failure(NOT_FOUND, () -> read(object));
    }

    @Test void symlinkObjectNeverDisclosesItsTarget() throws Exception {
        var object = write(new byte[]{1});
        Path name = file(object);
        Path outside = Files.createTempFile(root.getParent(), "knowledge-outside-", ".fixture");
        try {
            Files.write(outside, new byte[]{1});
            Files.delete(name);
            Files.createSymbolicLink(name, outside);
            var target = new ByteArrayOutputStream();
            failure(INTEGRITY, () -> storage().read(CONTEXT, object, target));
            assertThat(target.size()).isZero();
        } finally { Files.delete(outside); }
    }

    @Test void rejectsRootAndAncestorSymlinks() throws Exception {
        Path real = Files.createDirectory(root.resolve("real"));
        Path child = Files.createDirectory(real.resolve("child"));
        Path link = Files.createSymbolicLink(root.resolve("link"), real);
        failure(UNAVAILABLE, () -> new LocalDocumentStorage(link).write(CONTEXT, new ByteArrayInputStream(new byte[0])));
        failure(UNAVAILABLE, () -> new LocalDocumentStorage(link.resolve(child.getFileName()))
                .write(CONTEXT, new ByteArrayInputStream(new byte[0])));
        assertThatIllegalArgumentException().isThrownBy(() -> new LocalDocumentStorage(Path.of("relative")));
        assertThatIllegalArgumentException().isThrownBy(() -> new LocalDocumentStorage(root.resolve("../escape")));
    }

    @Test void missingRootIsUnavailableRatherThanCreatedInATemporaryLocation() {
        failure(UNAVAILABLE, () -> new LocalDocumentStorage(root.resolve("absent"))
                .write(CONTEXT, new ByteArrayInputStream(new byte[0])));
    }

    @Test void interruptedWriteNeverPublishesAnObject() throws Exception {
        var source = new InputStream() {
            int reads;
            public int read() throws IOException { if (reads++ == 0) return 1; throw new IOException("fixture"); }
            public int read(byte[] bytes, int offset, int count) throws IOException {
                int value = read(); bytes[offset] = (byte) value; return 1;
            }
        };
        failure(WRITE, () -> storage().write(CONTEXT, source));
        try (var files = Files.list(root)) { assertThat(files.toList()).isEmpty(); }
    }

    @Test void destinationFailureIsExplicitAndDoesNotMutateTheObject() {
        var object = write(new byte[]{1});
        failure(READ, () -> storage().read(CONTEXT, object, new OutputStream() {
            public void write(int value) throws IOException { throw new IOException("fixture"); }
        }));
        assertThat(read(object)).containsExactly((byte) 1);
    }

    @Test void parallelWritesUseIndependentImmutableReferences() throws Exception {
        try (var executor = Executors.newFixedThreadPool(PARALLEL_WRITERS)) {
            var tasks = new ArrayList<java.util.concurrent.Future<StoredObject>>();
            for (int index = 0; index < PARALLEL_WRITERS; index++) tasks.add(executor.submit(() -> write(new byte[]{1})));
            var ids = new HashSet<UUID>();
            for (var task : tasks) {
                var object = task.get(WAIT_SECONDS, TimeUnit.SECONDS);
                ids.add(object.id());
                assertThat(read(object)).containsExactly((byte) 1);
            }
            assertThat(ids).hasSize(PARALLEL_WRITERS);
        }
    }
}
