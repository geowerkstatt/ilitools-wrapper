package ch.geowerkstatt.ilitoolswrapper.modeldir;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validates the model repository options of a request before they are handed to an INTERLIS tool.
 *
 * <p>The entries are passed through to the tool option {@code --modeldir} unchanged, so only allowed forms may
 * enter: {@code http(s)} URLs and the placeholders of the tool the validator was created for, optionally followed
 * by a relative subpath below the directory the placeholder expands to (for example the subfolders the wrapper
 * materializes received files into). A local path would give the caller access to server side directories such as
 * the session directory of another request, and an entry containing the join character {@code ;} would expand into
 * several entries.
 */
public final class ModelDirValidator {
    private static final String ENTRY_SEPARATOR = ";";
    private static final String ILIDATA_PREFIX = "ilidata:";

    private final Set<String> allowedPlaceholders;
    private final PrivateNetworkPolicy privateNetworkPolicy;

    /**
     * Creates a validator for a single tool.
     *
     * @param allowedPlaceholders the tool placeholders accepted as entries, for example {@code %ITF_DIR}
     * @param privateNetworkPolicy whether URLs that resolve into non-public address ranges are accepted
     */
    public ModelDirValidator(Set<String> allowedPlaceholders, PrivateNetworkPolicy privateNetworkPolicy) {
        this.allowedPlaceholders = Set.copyOf(allowedPlaceholders);
        this.privateNetworkPolicy = privateNetworkPolicy;
    }

    /**
     * Validates every entry and joins them into the value of the tool option {@code --modeldir}.
     *
     * @param modelDirs the requested model repositories, in the order the tool should search them
     * @return the joined value, or an empty string if no repository was requested
     * @throws IllegalArgumentException if an entry is neither an allowed placeholder nor an acceptable {@code http(s)} URL
     */
    public String validateAndJoin(List<String> modelDirs) {
        for (String modelDir : modelDirs) {
            validateEntry(modelDir);
        }
        return String.join(ENTRY_SEPARATOR, modelDirs);
    }

    /**
     * Validates the meta configuration reference of a request. Only the repository indexed form is supported, a
     * file path would bypass the curation through the repository index.
     *
     * @param metaConfig the reference to validate, empty if no meta configuration was requested
     * @throws IllegalArgumentException if the reference is not of the form {@code ilidata:<DatasetId>}
     */
    public static void validateMetaConfig(String metaConfig) {
        if (metaConfig.isEmpty()) {
            return;
        }

        String datasetId = metaConfig.startsWith(ILIDATA_PREFIX) ? metaConfig.substring(ILIDATA_PREFIX.length()) : "";
        boolean valid = !datasetId.isEmpty()
                && !datasetId.contains(ENTRY_SEPARATOR)
                && datasetId.chars().noneMatch(Character::isWhitespace);
        if (!valid) {
            throw new IllegalArgumentException("Meta config must have the form \"ilidata:<DatasetId>\" but was \"" + metaConfig + "\".");
        }
    }

    private void validateEntry(String modelDir) {
        if (modelDir.isBlank()) {
            throw new IllegalArgumentException("Model dir entry must not be blank but was \"" + modelDir + "\".");
        }
        if (modelDir.contains(ENTRY_SEPARATOR)) {
            throw new IllegalArgumentException("Model dir entry \"" + modelDir + "\" must not contain \";\", send one entry per repository.");
        }

        if (modelDir.startsWith("%")) {
            validatePlaceholder(modelDir);
        } else {
            validateUrl(modelDir);
        }
    }

    private void validatePlaceholder(String modelDir) {
        int subpathIndex = modelDir.indexOf('/');
        String placeholder = subpathIndex < 0 ? modelDir : modelDir.substring(0, subpathIndex);
        if (!allowedPlaceholders.contains(placeholder)) {
            throw new IllegalArgumentException("Model dir entry \"" + modelDir + "\" is not an allowed placeholder, expected one of " + allowedPlaceholders + ".");
        }
        if (subpathIndex >= 0) {
            validatePlaceholderSubpath(modelDir, modelDir.substring(subpathIndex + 1));
        }
    }

    // A placeholder expands to a directory of the session, so a subpath stays inside the session exactly when no
    // segment leaves it. This is what makes the wrapper subfolders (models, repository) addressable as own entries.
    private static void validatePlaceholderSubpath(String modelDir, String subpath) {
        boolean valid = !subpath.isEmpty() && Arrays.stream(subpath.split("/", -1))
                .allMatch(segment -> !segment.isEmpty() && !segment.equals(".") && !segment.equals("..") && !segment.contains("\\"));
        if (!valid) {
            throw new IllegalArgumentException("Model dir entry \"" + modelDir
                    + "\" must address a subfolder with a relative path: no empty segments, no \".\" or \"..\" and no backslashes.");
        }
    }

    private void validateUrl(String modelDir) {
        URI uri;
        try {
            uri = new URI(modelDir);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Model dir entry \"" + modelDir + "\" is not a valid URL.", e);
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Model dir entry \"" + modelDir + "\" must be an http(s) URL or an allowed placeholder.");
        }

        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Model dir entry \"" + modelDir + "\" does not name a host.");
        }
        if (uri.getUserInfo() != null) {
            // Names the host instead of the entry: repeating the entry would carry the credentials into the log
            // and into the status message, which is what this rule exists to prevent.
            throw new IllegalArgumentException("Model dir entry for host \"" + host + "\" must not carry credentials, they would end up in tool arguments and logs.");
        }

        if (privateNetworkPolicy == PrivateNetworkPolicy.BLOCK) {
            requirePublicAddresses(modelDir, host);
        }
    }

    private static void requirePublicAddresses(String modelDir, String host) {
        String hostName = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(hostName);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Model dir entry \"" + modelDir + "\" cannot be resolved, so it cannot be confirmed to be public.", e);
        }

        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new IllegalArgumentException("Model dir entry \"" + modelDir + "\" resolves to the non-public address " + address.getHostAddress()
                        + ", set MODELDIR_ALLOW_PRIVATE_NETWORKS=true to allow this.");
            }
        }
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (address.isLoopbackAddress()
                || address.isAnyLocalAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            // Carrier grade NAT range 100.64.0.0/10, which isSiteLocalAddress does not cover.
            return !((bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 0x40);
        }
        // IPv6 unique local addresses fc00::/7, which isSiteLocalAddress does not cover either.
        return (bytes[0] & 0xFE) != 0xFC;
    }
}
