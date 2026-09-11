package dev.aperture.ai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.aperture.marketdata.QuoteSource;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The structural guarantee that the LLM cannot trade.
 *
 * <p>This test <em>is</em> the control. The analyst is handed {@link AnalystToolkit}, so the model
 * can do exactly what that class can do and nothing else - a far stronger property than a system
 * prompt asking it not to trade, because a prompt can be argued with and a missing method cannot.
 *
 * <p>It fails the build if anyone adds a public method whose name merely <em>suggests</em>
 * mutation. That is deliberately over-strict: the cost of a false positive is renaming a method,
 * and the cost of a false negative is an LLM with a way to move money.
 */
class AnalystToolkitIsReadOnlyTest {

    /** Verbs that have no business on a type handed to a language model. */
    private static final List<String> MUTATING_PREFIXES = List.of(
            "place", "submit", "send", "cancel", "amend", "replace", "modify", "update",
            "delete", "remove", "set", "write", "save", "store", "persist", "create",
            "buy", "sell", "trade", "execute", "transfer", "withdraw", "deposit", "close",
            "open", "liquidate", "reset", "clear", "drop", "apply", "declare", "record");

    @Test
    @DisplayName("AnalystToolkit exposes no method that could mutate anything")
    void toolkitHasNoMutatingMethods() {
        assertNoMutatingMethods(AnalystToolkit.class);
    }

    @Test
    @DisplayName("QuoteSource, which the toolkit reads through, is also read-only")
    void quoteSourceIsReadOnly() {
        // Checked separately because a read-only façade over a mutable interface is not read-only.
        assertNoMutatingMethods(QuoteSource.class);
    }

    private static void assertNoMutatingMethods(Class<?> type) {
        List<String> offenders = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .filter(AnalystToolkitIsReadOnlyTest::looksMutating)
                .sorted()
                .toList();

        assertThat(offenders)
                .describedAs("%s is handed to the LLM and must expose no mutating method. "
                        + "If one of these is genuinely read-only, rename it; do not weaken "
                        + "this test.", type.getSimpleName())
                .isEmpty();
    }

    private static boolean looksMutating(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        return MUTATING_PREFIXES.stream().anyMatch(lower::startsWith);
    }
}
