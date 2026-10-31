package org.traceform.agentos;

/**
 * Generate, validate, and feed the failure back once. Pure helper so the retry
 * policy can be tested without a model.
 */
final class StructuredRepair {
    interface Generator {
        String generate(String prompt) throws Exception;
    }

    interface Validator {
        /** Throws with a message that names the concrete problem. */
        void validate(String text) throws Exception;
    }

    static final class Result {
        final String text;
        final int attempts;
        final String lastFailure;

        Result(String text, int attempts, String lastFailure) {
            this.text = text;
            this.attempts = attempts;
            this.lastFailure = lastFailure;
        }
    }

    private StructuredRepair() {}

    static Result generate(Generator generator, Validator validator, String prompt, int attempts) throws Exception {
        String lastFailure = null;
        String text = null;
        for (int attempt = 0; attempt < Math.max(1, attempts); attempt++) {
            String attemptPrompt = prompt;
            if (lastFailure != null && !lastFailure.isEmpty()) {
                attemptPrompt = prompt + "\nThe previous attempt was rejected because: "
                    + lastFailure + "\nFix exactly that problem and keep everything else the same.";
            }
            text = generator.generate(attemptPrompt);
            try {
                validator.validate(text);
                return new Result(text, attempt + 1, null);
            } catch (Exception invalid) {
                lastFailure = invalid.getMessage() == null ? invalid.toString() : invalid.getMessage();
            }
        }
        throw new IllegalStateException("Gave up after " + Math.max(1, attempts)
            + " attempt(s): " + lastFailure);
    }
}
