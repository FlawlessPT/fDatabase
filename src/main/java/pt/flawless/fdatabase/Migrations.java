package pt.flawless.fdatabase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses migration resource paths ({@code V<number>__<description>.sql}) into {@link Migration}s. */
public final class Migrations {

    private static final Pattern FILE_NAME = Pattern.compile("V(\\d+)__.+\\.sql");

    private Migrations() {
    }

    /**
     * Extracts the version from each file name (last {@code /} segment).
     *
     * @throws IllegalArgumentException if a name does not match the pattern, a version does not
     *         fit in an int, or versions are not strictly increasing in list order
     */
    public static List<Migration> parse(List<String> paths) {
        List<Migration> result = new ArrayList<>();
        int previous = Integer.MIN_VALUE;
        for (String path : paths) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            Matcher matcher = FILE_NAME.matcher(name);
            if (!matcher.matches()) {
                throw new IllegalArgumentException(
                        "Invalid migration file name (expected V<number>__<description>.sql): " + path);
            }
            int version;
            try {
                version = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Migration version is too large: " + path, e);
            }
            if (!result.isEmpty() && version <= previous) {
                throw new IllegalArgumentException("Migration versions are not strictly increasing at: "
                        + path + " (version " + version + " after " + previous + ")");
            }
            previous = version;
            result.add(new Migration(version, path));
        }
        return Collections.unmodifiableList(result);
    }
}
