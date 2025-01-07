package dev.hedge.ingestion.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * The bytes a provider delivered, exactly as delivered, plus their fingerprint.
 *
 * <p>The fingerprint is how the same file arriving twice is recognised and refused. Not the file
 * name: custodians reuse names, retransmit after failures, and occasionally send yesterday's file
 * with today's name.
 */
public final class Artifact {

    private final String name;
    private final byte[] content;
    private final String sha256;

    public Artifact(String name, byte[] content) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("an artifact needs a name");
        }
        Objects.requireNonNull(content, "an artifact needs content");
        this.name = name.trim();
        this.content = content.clone();
        this.sha256 = digestOf(this.content);
    }

    public static Artifact of(String name, String text) {
        return new Artifact(name, text.getBytes(StandardCharsets.UTF_8));
    }

    public String name() {
        return name;
    }

    public byte[] content() {
        return content.clone();
    }

    public String text() {
        return new String(content, StandardCharsets.UTF_8);
    }

    public String sha256() {
        return sha256;
    }

    public int sizeInBytes() {
        return content.length;
    }

    private static String digestOf(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IngestionException("SHA-256 is required by the Java platform", impossible);
        }
    }

    @Override
    public String toString() {
        return name + " (" + content.length + " bytes, sha256=" + sha256.substring(0, 12) + "...)";
    }
}
