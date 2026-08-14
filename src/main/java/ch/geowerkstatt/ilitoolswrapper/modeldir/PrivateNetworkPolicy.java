package ch.geowerkstatt.ilitoolswrapper.modeldir;

/**
 * Decides whether a model repository URL may resolve into a non-public address range.
 */
public enum PrivateNetworkPolicy {
    /** Reject URLs that resolve into a non-public address range. */
    BLOCK,
    /** Accept every resolvable host, intended for test and development environments. */
    ALLOW;

    private static final String ALLOW_PRIVATE_NETWORKS_ENV = "MODELDIR_ALLOW_PRIVATE_NETWORKS";

    /**
     * Reads the policy from the environment variable {@code MODELDIR_ALLOW_PRIVATE_NETWORKS}.
     *
     * @return {@link #ALLOW} if the variable is set to {@code true}, {@link #BLOCK} otherwise
     */
    public static PrivateNetworkPolicy fromEnvironment() {
        return "true".equalsIgnoreCase(System.getenv(ALLOW_PRIVATE_NETWORKS_ENV)) ? ALLOW : BLOCK;
    }
}
