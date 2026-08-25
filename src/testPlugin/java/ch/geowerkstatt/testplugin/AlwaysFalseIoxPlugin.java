package ch.geowerkstatt.testplugin;

import ch.ehi.basics.settings.Settings;
import ch.interlis.ili2c.metamodel.TransferDescription;
import ch.interlis.iom.IomObject;
import ch.interlis.iox.IoxValidationConfig;
import ch.interlis.iox_j.logging.LogEventFactory;
import ch.interlis.iox_j.validator.InterlisFunction;
import ch.interlis.iox_j.validator.ObjectPool;
import ch.interlis.iox_j.validator.Value;

/**
 * The smallest ilivalidator plugin that makes a difference to a validation result: a function that always
 * returns false.
 *
 * <p>A constraint calling it fails when this plugin is loaded and is silently skipped when it is not, so the
 * success flag of a validation inverts with the presence of the plugin. That inversion is what proves that
 * {@code --plugins} took effect, which nothing else does: a validation whose plugin is missing reports success,
 * and the tool logs a plugin folder on every run whether or not one was passed.
 *
 * <p>The plugin is built here rather than pulled from a release of a real function library, so the tests stay
 * independent of that library's evolution and CI needs no external artifact.
 *
 * <p>The class name has to end in {@code IoxPlugin}. That is how the tool discovers a plugin class, documented
 * in {@code docs/ilivalidator.html} of the distribution ("der Name der Java-Klasse muss mit IoxPlugin enden")
 * and mirrored by the real function libraries, whose classes are all named {@code ...IoxPlugin}. A class with
 * another name is silently ignored, which surfaces as a constraint that is skipped rather than as an error.
 */
public final class AlwaysFalseIoxPlugin implements InterlisFunction {
    /**
     * Takes no state; the function result does not depend on the validation context.
     *
     * @param td the transfer description of the validation
     * @param settings the validator settings
     * @param validationConfig the validation configuration
     * @param objectPool the object pool of the validation
     * @param logEventFactory the log event factory of the validation
     */
    @Override
    public void init(TransferDescription td, Settings settings, IoxValidationConfig validationConfig, ObjectPool objectPool, LogEventFactory logEventFactory) {
        // Nothing to set up.
    }

    /**
     * Always evaluates to false, whatever it is called with.
     *
     * @param validationKind the kind of validation being run
     * @param usageScope the scope the function is called from
     * @param mainObj the object the constraint is evaluated on
     * @param actualArguments the arguments the constraint passed
     * @return the boolean value false
     */
    @Override
    public Value evaluate(String validationKind, String usageScope, IomObject mainObj, Value[] actualArguments) {
        return new Value(false);
    }

    /**
     * The qualified INTERLIS name the model declares this function under.
     *
     * @return the qualified name of the function
     */
    @Override
    public String getQualifiedIliName() {
        return "TestFunctions.AlwaysFalse";
    }
}
