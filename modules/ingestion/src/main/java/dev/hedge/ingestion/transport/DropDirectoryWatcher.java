package dev.hedge.ingestion.transport;

import dev.hedge.ingestion.IngestionPipeline;
import dev.hedge.ingestion.IngestionPipeline.Outcome;
import dev.hedge.ingestion.source.Artifact;
import dev.hedge.ingestion.source.IngestionException;
import dev.hedge.ingestion.source.ObservationSource;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Watches a directory that providers deliver into, with the semantics a real SFTP drop has.
 *
 * <p>This is a watched directory rather than an SFTP server on purpose, and worth being precise
 * about: running OpenSSH locally would exercise OpenSSH, not this code. What actually has to be
 * right is everything below — and all of it is identical whether the bytes arrived over SFTP, a
 * mounted share or a copy command.
 *
 * <ul>
 *   <li><strong>Done markers.</strong> A file is only read once {@code name.done} appears beside
 *       it. Without that the watcher reads files that are still being written and sees a portfolio
 *       that stops halfway down.
 *   <li><strong>Claim by atomic move.</strong> A file is moved into a working directory before
 *       being opened, so two sweeps — or two instances — cannot process the same delivery twice.
 *   <li><strong>Oldest first.</strong> Deliveries are processed in the order they landed, because
 *       an incremental feed applied out of order produces the wrong position.
 *   <li><strong>Archive, never delete.</strong> The file survives processing, so a parser fixed
 *       next month can be run against the original bytes.
 * </ul>
 */
public final class DropDirectoryWatcher {

    private static final String DONE_SUFFIX = ".done";
    private static final String WORKING = ".processing";

    private final Path dropDirectory;
    private final Path archiveDirectory;
    private final Map<String, ObservationSource> sourcesByProvider;
    private final IngestionPipeline pipeline;

    public DropDirectoryWatcher(
            Path dropDirectory,
            Path archiveDirectory,
            Map<String, ObservationSource> sourcesByProvider,
            IngestionPipeline pipeline) {
        this.dropDirectory = Objects.requireNonNull(dropDirectory, "a drop directory is required");
        this.archiveDirectory = Objects.requireNonNull(archiveDirectory, "an archive directory is required");
        this.sourcesByProvider = Map.copyOf(Objects.requireNonNull(sourcesByProvider, "sources are required"));
        this.pipeline = Objects.requireNonNull(pipeline, "a pipeline is required");
    }

    /** What one delivery turned into, and which file it was. */
    public record Swept(String fileName, Outcome outcome) {}

    /**
     * One pass over the drop directory. Returns what happened rather than logging it, so the
     * behaviour can be tested without a scheduler, a thread or a clock.
     */
    public List<Swept> sweep() {
        List<Swept> swept = new ArrayList<>();
        Path working = dropDirectory.resolve(WORKING);
        try {
            Files.createDirectories(working);
            Files.createDirectories(archiveDirectory);
        } catch (IOException failure) {
            throw new IngestionException("could not prepare the drop directories", failure);
        }

        for (Path delivered : readyFiles()) {
            Optional<ObservationSource> source = sourceFor(delivered.getFileName().toString());
            if (source.isEmpty()) {
                // Not an error to shout about: an unknown file may simply belong to someone else.
                continue;
            }
            Path claimed = claim(delivered, working);
            if (claimed == null) {
                continue; // another sweep took it first
            }
            Artifact artifact = readArtifact(claimed, delivered.getFileName().toString());
            Outcome outcome = pipeline.accept(source.orElseThrow(), artifact);
            archive(claimed, delivered);
            swept.add(new Swept(artifact.name(), outcome));
        }
        return swept;
    }

    /** Files whose done marker has appeared, oldest delivery first. */
    private List<Path> readyFiles() {
        try (Stream<Path> entries = Files.list(dropDirectory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().endsWith(DONE_SUFFIX))
                    .filter(this::hasDoneMarker)
                    .sorted(Comparator.comparing(this::lastModified)
                            .thenComparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException failure) {
            throw new IngestionException("could not list " + dropDirectory, failure);
        }
    }

    private boolean hasDoneMarker(Path file) {
        return Files.exists(file.resolveSibling(file.getFileName() + DONE_SUFFIX));
    }

    private long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException failure) {
            return Long.MAX_VALUE;
        }
    }

    /** Moving the file is the claim. A move that fails means someone else already claimed it. */
    private Path claim(Path delivered, Path working) {
        Path target = working.resolve(delivered.getFileName());
        try {
            try {
                return Files.move(delivered, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                return Files.move(delivered, target);
            }
        } catch (IOException failure) {
            return null;
        }
    }

    private Artifact readArtifact(Path claimed, String originalName) {
        try {
            return new Artifact(originalName, Files.readAllBytes(claimed));
        } catch (IOException failure) {
            throw new IngestionException("could not read " + claimed, failure);
        }
    }

    private void archive(Path claimed, Path delivered) {
        try {
            Files.move(claimed, archiveDirectory.resolve(claimed.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(delivered.resolveSibling(delivered.getFileName() + DONE_SUFFIX));
        } catch (IOException failure) {
            throw new IngestionException("could not archive " + claimed, failure);
        }
    }

    /** Longest matching provider id wins, so CUSTODIAN_A and CUSTODIAN_AB cannot be confused. */
    private Optional<ObservationSource> sourceFor(String fileName) {
        return sourcesByProvider.entrySet().stream()
                .filter(entry -> fileName.startsWith(entry.getKey()))
                .max(Comparator.comparingInt(entry -> entry.getKey().length()))
                .map(Map.Entry::getValue);
    }
}
