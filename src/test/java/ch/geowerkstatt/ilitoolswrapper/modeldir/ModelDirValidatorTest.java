package ch.geowerkstatt.ilitoolswrapper.modeldir;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public final class ModelDirValidatorTest {
    private static final Set<String> ILIVALIDATOR_PLACEHOLDERS = Set.of("%ITF_DIR");
    private static final Set<String> ILI2GPKG_PLACEHOLDERS = Set.of("%XTF_DIR", "%ILI_FROM_DB");

    @Test
    void emptyListJoinsToEmptyString() {
        assertEquals("", ilivalidator().validateAndJoin(List.of()));
    }

    @Test
    void entriesAreJoinedWithSemicolonInListOrder() {
        List<String> modelDirs = List.of("%ITF_DIR", "https://models.interlis.ch/", "http://models.geo.admin.ch/");

        assertEquals("%ITF_DIR;https://models.interlis.ch/;http://models.geo.admin.ch/", ilivalidator().validateAndJoin(modelDirs));
    }

    @Test
    void placeholderOfOtherToolIsRejected() {
        assertRejected(ilivalidator(), "%XTF_DIR");
        assertRejected(ili2gpkg(), "%ITF_DIR");
    }

    @Test
    void ili2gpkgAcceptsItsOwnPlaceholders() {
        assertEquals("%ILI_FROM_DB;%XTF_DIR", ili2gpkg().validateAndJoin(List.of("%ILI_FROM_DB", "%XTF_DIR")));
    }

    @Test
    void unknownPlaceholderIsRejected() {
        assertRejected(ilivalidator(), "%JAR_DIR/ilimodels");
        assertRejected(ilivalidator(), "%UNKNOWN_DIR");
    }

    @Test
    void placeholderWithSubpathIsAccepted() {
        assertEquals("%ITF_DIR/repository;%ITF_DIR/models", ilivalidator().validateAndJoin(List.of("%ITF_DIR/repository", "%ITF_DIR/models")));
        assertEquals("%XTF_DIR/repository/sub", ili2gpkg().validateAndJoin(List.of("%XTF_DIR/repository/sub")));
    }

    @Test
    void subpathLeavingTheDirectoryIsRejected() {
        assertRejected(ilivalidator(), "%ITF_DIR/../other-session");
        assertRejected(ilivalidator(), "%ITF_DIR/repository/../../escape");
        assertRejected(ilivalidator(), "%ITF_DIR/./models");
    }

    @Test
    void subpathWithEmptySegmentIsRejected() {
        assertRejected(ilivalidator(), "%ITF_DIR/");
        assertRejected(ilivalidator(), "%ITF_DIR//models");
        assertRejected(ilivalidator(), "%ITF_DIR/models/");
    }

    @Test
    void subpathWithBackslashIsRejected() {
        assertRejected(ilivalidator(), "%ITF_DIR/models\\sub");
    }

    @Test
    void subpathOnOtherToolsPlaceholderIsRejected() {
        assertRejected(ilivalidator(), "%XTF_DIR/models");
        assertRejected(ili2gpkg(), "%ITF_DIR/models");
    }

    @Test
    void entryWithSemicolonIsRejected() {
        assertRejected(ilivalidator(), "%ITF_DIR;https://models.interlis.ch/");
    }

    @Test
    void localPathIsRejected() {
        assertRejected(ilivalidator(), "/etc/models");
        assertRejected(ilivalidator(), "C:\\models");
        assertRejected(ilivalidator(), "models");
        assertRejected(ilivalidator(), "../processing");
    }

    @Test
    void nonHttpSchemeIsRejected() {
        assertRejected(ilivalidator(), "file:///etc/");
        assertRejected(ilivalidator(), "ftp://models.interlis.ch/");
    }

    @Test
    void blankEntryIsRejected() {
        assertRejected(ilivalidator(), "");
        assertRejected(ilivalidator(), "   ");
    }

    @Test
    void urlWithUserinfoIsRejectedWithoutRepeatingTheCredentials() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ilivalidator().validateAndJoin(List.of("https://user:secret@models.interlis.ch/")));

        String message = Objects.requireNonNull(exception.getMessage(), "Rejection must carry a message.");
        assertFalse(message.contains("secret"), "The rejection must not repeat the credentials, it is logged and returned to the caller: " + message);
        assertTrue(message.contains("models.interlis.ch"), "The rejection should name the host, but was: " + message);
    }

    @Test
    void schemeComparisonIsCaseInsensitive() {
        assertEquals("HTTPS://models.interlis.ch/", ilivalidator().validateAndJoin(List.of("HTTPS://models.interlis.ch/")));
    }

    @Test
    void privateAddressesAreRejectedWhenBlocked() {
        ModelDirValidator validator = blockingPrivateNetworks();

        assertRejected(validator, "http://127.0.0.1/");
        assertRejected(validator, "http://10.0.0.1/");
        assertRejected(validator, "http://172.16.0.1/");
        assertRejected(validator, "http://192.168.1.1/");
        assertRejected(validator, "http://169.254.169.254/latest/meta-data/");
        assertRejected(validator, "http://100.64.0.1/");
        assertRejected(validator, "http://0.0.0.0/");
        assertRejected(validator, "http://[::1]/");
        assertRejected(validator, "http://[fc00::1]/");
    }

    @Test
    void hostnameResolvingToPrivateAddressIsRejectedWhenBlocked() {
        assertRejected(blockingPrivateNetworks(), "http://localhost:8080/repository/");
    }

    @Test
    void privateAddressIsAcceptedWhenAllowed() {
        assertEquals("http://localhost:8080/repository/", ilivalidator().validateAndJoin(List.of("http://localhost:8080/repository/")));
    }

    @Test
    void placeholderIsNotAddressCheckedWhenBlocked() {
        assertEquals("%ITF_DIR", blockingPrivateNetworks().validateAndJoin(List.of("%ITF_DIR")));
    }

    @Test
    void metaConfigAcceptsIlidataReferenceAndEmptyValue() {
        assertDoesNotThrow(() -> ModelDirValidator.validateMetaConfig("ilidata:DEFAULT"));
        assertDoesNotThrow(() -> ModelDirValidator.validateMetaConfig("ilidata:ch.geow.profile-1"));
        assertDoesNotThrow(() -> ModelDirValidator.validateMetaConfig(""));
    }

    @Test
    void metaConfigRejectsFileFormAndMalformedReferences() {
        assertMetaConfigRejected("profile.toml");
        assertMetaConfigRejected("/repositories/profile.toml");
        assertMetaConfigRejected("ilidata:");
        assertMetaConfigRejected("ilidata:profile with space");
        assertMetaConfigRejected("ilidata:a;ilidata:b");
        assertMetaConfigRejected("ILIDATA:DEFAULT");
    }

    private static ModelDirValidator ilivalidator() {
        return new ModelDirValidator(ILIVALIDATOR_PLACEHOLDERS, PrivateNetworkPolicy.ALLOW);
    }

    private static ModelDirValidator ili2gpkg() {
        return new ModelDirValidator(ILI2GPKG_PLACEHOLDERS, PrivateNetworkPolicy.ALLOW);
    }

    private static ModelDirValidator blockingPrivateNetworks() {
        return new ModelDirValidator(ILIVALIDATOR_PLACEHOLDERS, PrivateNetworkPolicy.BLOCK);
    }

    private static void assertRejected(ModelDirValidator validator, String entry) {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validateAndJoin(List.of(entry)),
                "Entry should have been rejected: " + entry);
        String message = Objects.requireNonNull(exception.getMessage(), "Rejection must carry a message.");
        assertTrue(message.contains(entry), "Message should name the rejected entry, but was: " + message);
    }

    private static void assertMetaConfigRejected(String metaConfig) {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> ModelDirValidator.validateMetaConfig(metaConfig),
                "Meta config should have been rejected: " + metaConfig);
        String message = Objects.requireNonNull(exception.getMessage(), "Rejection must carry a message.");
        assertTrue(message.contains("ilidata:"), "Message should state the expected form, but was: " + message);
    }
}
