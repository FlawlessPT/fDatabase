package pt.flawless.fdatabase;

/** A versioned migration script located at {@code path} (a classpath resource path). */
public record Migration(int version, String path) {
}
