package com.alxarafe.erbas.knowledge.storage.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import com.alxarafe.erbas.knowledge.storage.application.*;
import com.alxarafe.erbas.knowledge.storage.domain.ContentDigest;
import static com.alxarafe.erbas.knowledge.storage.application.StorageFailure.Kind.*;

/** Linux/Docker local adapter; directory handles prevent symlink and ancestor replacement races. */
public final class LocalDocumentStorage implements DocumentStorage {
    public static final String DEFAULT_ROOT = "/var/lib/erbas/knowledge";
    public static final String OBJECT_SUFFIX = ".blob";
    public static final String TEMP_SUFFIX = ".pending";
    private static final int COPY_BUFFER_SIZE = 16 * 1024;
    private static final String PRIVATE_PERMISSIONS = "rw-------";
    private static final char CONTEXT_SEPARATOR = '\0';
    private final Path root;

    public LocalDocumentStorage(Path root) {
        if (!root.isAbsolute() || !root.equals(root.normalize()) || root.getParent() == null)
            throw new IllegalArgumentException("Storage root must be an absolute normalized directory");
        this.root = root;
    }

    @Override public StoredObject write(StorageContext context, InputStream source) {
        UUID id = UUID.randomUUID();
        Path name = name(context, id);
        Path pending = Path.of(name + TEMP_SUFFIX);
        try (var directory = directory()) {
            boolean reserved = false;
            boolean temporary = false;
            try {
                var digest = ContentDigest.newDigest();
                long size = 0;
                try (var channel = directory.newByteChannel(pending,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(PRIVATE_PERMISSIONS)))) {
                    temporary = true;
                    if (!(channel instanceof FileChannel file)) throw new StorageFailure(UNAVAILABLE);
                    byte[] buffer = new byte[COPY_BUFFER_SIZE];
                    int count;
                    while ((count = source.read(buffer)) != -1) {
                        digest.update(buffer, 0, count);
                        size = Math.addExact(size, count);
                        var bytes = ByteBuffer.wrap(buffer, 0, count);
                        while (bytes.hasRemaining()) file.write(bytes);
                    }
                    file.force(true);
                }
                // Exclusive reservation: never replace an object created by another operation.
                try (var ignored = directory.newByteChannel(name,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(PRIVATE_PERMISSIONS)))) {
                    reserved = true;
                }
                // SecureDirectoryStream.move has ATOMIC_MOVE semantics; unsupported filesystems fail closed.
                directory.move(pending, directory, name);
                temporary = false;
                try (var channel = directory.newByteChannel(Path.of("."), Set.of(StandardOpenOption.READ))) {
                    if (!(channel instanceof FileChannel file)) throw new StorageFailure(UNAVAILABLE);
                    file.force(true);
                }
                return new StoredObject(id, ContentDigest.hex(digest.digest()), size);
            } catch (IOException | ArithmeticException failure) {
                throw new StorageFailure(WRITE);
            } catch (UnsupportedOperationException | SecurityException unavailable) {
                throw new StorageFailure(UNAVAILABLE);
            } finally {
                // Unpublished objects may survive process death; offline inventory handles those too.
                if (temporary) delete(directory, pending);
                // Do not remove published objects on failure: commit outcome may be unknown to a caller.
                if (temporary && reserved) delete(directory, name);
            }
        } catch (IOException | UnsupportedOperationException | SecurityException failure) { throw new StorageFailure(UNAVAILABLE); }
    }

    @Override public void read(StorageContext context, StoredObject object, OutputStream target) {
        try (var directory = directory()) {
            Path name = name(context, object.id());
            try {
                var attributes = directory.getFileAttributeView(name, BasicFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS).readAttributes();
                if (!attributes.isRegularFile() || attributes.size() != object.size()) throw new StorageFailure(INTEGRITY);
                var digest = ContentDigest.newDigest();
                long size = 0;
                try (var channel = directory.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                        var source = Channels.newInputStream(channel)) {
                    byte[] buffer = new byte[COPY_BUFFER_SIZE];
                    int count;
                    while ((count = source.read(buffer)) != -1) {
                        size = Math.addExact(size, count);
                        if (size > object.size()) throw new StorageFailure(INTEGRITY);
                        digest.update(buffer, 0, count);
                        target.write(buffer, 0, count);
                    }
                }
                if (size != object.size() || !object.sha256().equals(ContentDigest.hex(digest.digest())))
                    throw new StorageFailure(INTEGRITY);
            } catch (NoSuchFileException missing) { throw new StorageFailure(NOT_FOUND); }
            catch (IOException failure) { throw new StorageFailure(READ); }
        } catch (IOException | UnsupportedOperationException | SecurityException failure) { throw new StorageFailure(UNAVAILABLE); }
    }

    /** Physical names are an adapter detail, derived only from trusted context and UUID values. */
    private static Path name(StorageContext context, UUID id) {
        String partition = ContentDigest.sha256((context.module() + CONTEXT_SEPARATOR + context.scope()).getBytes(StandardCharsets.UTF_8));
        return Path.of(partition + "-" + id + OBJECT_SUFFIX);
    }

    private SecureDirectoryStream<Path> directory() throws IOException {
        var initial = Files.newDirectoryStream(root.getRoot());
        if (!(initial instanceof SecureDirectoryStream<Path> current)) {
            initial.close();
            throw new StorageFailure(UNAVAILABLE);
        }
        try {
            for (Path component : root) {
                var parent = current;
                current = parent.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                parent.close();
            }
            return current;
        } catch (IOException | RuntimeException failure) {
            current.close();
            throw failure;
        }
    }

    private static void delete(SecureDirectoryStream<Path> directory, Path name) {
        try { directory.deleteFile(name); }
        catch (IOException cleanupFailure) { /* Offline inventory also covers failed temporary cleanup. */ }
    }
}
